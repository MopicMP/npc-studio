package com.mopicmp.npcstudio.server;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The command channel to a running server.
 *
 * Why this exists at all, given that we start the local server ourselves and
 * could have written to its standard input: because the process is deliberately
 * detached, so that closing the game does not stop the server. A detached
 * process has no pipe to us — the moment the game exits, standard input is gone
 * and anything still writing to standard output is writing into a broken pipe.
 * RCON is a socket, so it can be dropped and picked up again, by this session or
 * the next one, and the same code then works against a server on somebody else's
 * machine.
 *
 * What this channel cannot do is files. It runs commands and reads their output,
 * which covers players, bans, gamerules and saving, and covers nothing about
 * configuration. That is why the plan has a second transport, and why this class
 * does not pretend to be the whole of one.
 *
 * <b>This protocol has no encryption of any kind.</b> The password crosses the
 * wire as it was typed. On the loopback address that is unremarkable; across the
 * internet it is the server handed to whoever is listening, which is why the
 * plan says a remote RCON belongs inside an SSH tunnel and nowhere else.
 */
public final class Rcon implements AutoCloseable {

	/** Long enough for a slow first reply, short enough to notice a wedged server. */
	private static final int READ_TIMEOUT_MS = 10_000;

	private static final int CONNECT_TIMEOUT_MS = 5_000;

	/** A ceiling on one command's assembled output, so a flood cannot fill the heap. */
	private static final int MAX_OUTPUT = 4 * 1024 * 1024;

	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
	private final AtomicInteger nextId = new AtomicInteger(1);

	private Rcon(Socket socket) throws IOException {
		this.socket = socket;
		this.in = new DataInputStream(socket.getInputStream());
		this.out = socket.getOutputStream();
	}

	/** Thrown when the server answered the login with a refusal. */
	public static final class AuthFailed extends IOException {
		public AuthFailed(String message) {
			super(message);
		}
	}

	/**
	 * Connect and log in, or throw.
	 *
	 * A returned instance is always authenticated: there is no state where the
	 * caller holds an open, unusable connection and has to remember to check.
	 */
	public static Rcon connect(String host, int port, String password) throws IOException {
		Socket socket = new Socket();
		socket.setTcpNoDelay(true);
		try {
			socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(READ_TIMEOUT_MS);
			Rcon rcon = new Rcon(socket);
			rcon.login(password);
			return rcon;
		} catch (IOException failed) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// The connection attempt is the failure worth reporting.
			}
			throw failed;
		}
	}

	private void login(String password) throws IOException {
		int id = nextId.getAndIncrement();
		write(new RconFrame(id, RconFrame.TYPE_AUTH, password));

		// Some servers send an empty response packet before the login answer, so
		// the answer is the first packet of the login type rather than simply the
		// first packet.
		while (true) {
			RconFrame reply = read();
			if (reply.type() != RconFrame.TYPE_COMMAND) continue;
			if (reply.id() == RconFrame.AUTH_FAILED) {
				throw new AuthFailed("The server refused the RCON password");
			}
			return;
		}
	}

	/**
	 * Where a server cuts a long answer, and therefore how we know more is coming.
	 *
	 * Measured against a real server rather than assumed: {@code help} came back
	 * as one packet of exactly 4096 characters and then one of 1548. A body of
	 * exactly this length means another packet follows; anything shorter is the
	 * end of the answer.
	 */
	private static final int SPLIT = 4096;

	/** How long to wait for a continuation that the split rule says should come. */
	private static final int CONTINUATION_TIMEOUT_MS = 2_000;

	/**
	 * Run one command and return everything the server said about it.
	 *
	 * <b>One packet is sent, and nothing else.</b> The usual trick for finding
	 * the end of a long answer is to send a second, empty packet after the
	 * command and wait for its echo. Against a real server that is fatal: it
	 * reads one packet per read, checks that the length field accounts for
	 * exactly what arrived, and closes the connection when it does not — and two
	 * packets written back to back routinely arrive together. The failure is a
	 * dropped connection during an ordinary command, with nothing in the server's
	 * log about it.
	 *
	 * So the end of an answer is found by its shape instead. Everything but the
	 * last packet of a split answer is exactly {@link #SPLIT} characters, which
	 * makes the common case — one short answer — cost no waiting at all.
	 */
	public synchronized String command(String command) throws IOException {
		int id = nextId.getAndIncrement();
		write(new RconFrame(id, RconFrame.TYPE_COMMAND, command));

		StringBuilder output = new StringBuilder();
		boolean expectMore = true;
		while (expectMore) {
			RconFrame reply;
			try {
				reply = output.isEmpty() ? read() : readSoon();
			} catch (SocketTimeoutException quiet) {
				// An answer of exactly 4096 characters looks like a split one and is
				// not, so this is a legitimate ending rather than a fault. It is
				// also what a server that has stopped answering looks like, and
				// whatever arrived is worth more than an exception.
				return output.toString();
			}
			if (reply.id() != id) continue;
			if (output.length() + reply.body().length() > MAX_OUTPUT) {
				throw new IOException("RCON output for '" + command + "' passed "
					+ MAX_OUTPUT + " characters and was cut off");
			}
			output.append(reply.body());
			expectMore = reply.body().length() == SPLIT;
		}
		return output.toString();
	}

	/** A continuation read: short, because the answer is already in hand. */
	private RconFrame readSoon() throws IOException {
		int was = socket.getSoTimeout();
		socket.setSoTimeout(CONTINUATION_TIMEOUT_MS);
		try {
			return read();
		} finally {
			socket.setSoTimeout(was);
		}
	}

	public boolean isOpen() {
		return !socket.isClosed() && socket.isConnected();
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException ignored) {
			// Closing is the end of this object's usefulness either way.
		}
	}

	private void write(RconFrame frame) throws IOException {
		out.write(frame.encode());
		out.flush();
	}

	private RconFrame read() throws IOException {
		byte[] header = new byte[4];
		in.readFully(header);
		int length = RconFrame.length(header);
		if (length < 8 || length > RconFrame.MAX_REPLY) {
			// A length outside what the protocol can mean is a stream that has lost
			// its place; reading on would allocate whatever the next four bytes
			// happen to say.
			throw new IOException("RCON packet claims a length of " + length);
		}
		byte[] payload = new byte[length];
		in.readFully(payload);
		return RconFrame.decode(payload);
	}
}
