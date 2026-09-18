package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The command channel against a server that behaves the way a real one does.
 *
 * <b>The fake here was rewritten after a live run, and the difference matters.</b>
 * It first mirrored the protocol as documented — an unknown packet type gets a
 * polite "Unknown request" reply — and the client was built on that: it sent an
 * empty marker packet after each command and waited for the echo to know a long
 * answer had ended. Against a real server that closes the connection. Vanilla
 * reads one packet per read and checks that the length field accounts for
 * exactly what arrived; two packets written back to back routinely arrive
 * together, and then it simply hangs up.
 *
 * So this fake now does three things because a real server was watched doing
 * them: it hangs up on a packet type it does not know, it cuts a long answer at
 * exactly 4096 characters, and it says nothing at all after the last piece. The
 * client has to find the end of an answer from its shape, and a test that sends
 * a second packet would fail here the way it failed there.
 */
class RconTest {

	/** A server that speaks the protocol, for one connection at a time. */
	private static final class Fake implements AutoCloseable {

		private final ServerSocket socket;
		private final String password;
		private final Thread thread;

		Fake(String password) throws IOException {
			this.password = password;
			this.socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
			this.thread = new Thread(this::serve, "fake-rcon");
			this.thread.setDaemon(true);
			this.thread.start();
		}

		int port() {
			return socket.getLocalPort();
		}

		private void serve() {
			while (!socket.isClosed()) {
				try (Socket client = socket.accept()) {
					converse(client);
				} catch (IOException done) {
					return;
				}
			}
		}

		private void converse(Socket client) throws IOException {
			DataInputStream in = new DataInputStream(client.getInputStream());
			OutputStream out = client.getOutputStream();
			while (true) {
				byte[] header = new byte[4];
				try {
					in.readFully(header);
				} catch (IOException gone) {
					return;
				}
				byte[] payload = new byte[RconFrame.length(header)];
				in.readFully(payload);
				RconFrame frame = RconFrame.decode(payload);

				switch (frame.type()) {
					case RconFrame.TYPE_AUTH -> {
						boolean right = frame.body().equals(password);
						send(out, new RconFrame(right ? frame.id() : RconFrame.AUTH_FAILED,
							RconFrame.TYPE_COMMAND, ""));
					}
					case RconFrame.TYPE_COMMAND -> {
						String answer = answer(frame.body());
						// Cut at four thousand and ninety-six, then nothing: no
						// marker, no terminator, no length announced anywhere.
						if (answer.isEmpty()) {
							send(out, new RconFrame(frame.id(), RconFrame.TYPE_RESPONSE, ""));
						}
						for (int at = 0; at < answer.length(); at += 4096) {
							send(out, new RconFrame(frame.id(), RconFrame.TYPE_RESPONSE,
								answer.substring(at, Math.min(answer.length(), at + 4096))));
						}
					}
					// What a real one does with a packet type it does not know, and
					// the reason this client sends exactly one packet per command.
					default -> {
						return;
					}
				}
			}
		}

		private static String answer(String command) {
			return switch (command) {
				case "list" -> "There are 0 of a max of 20 players online:";
				case "long" -> "x".repeat(10_000);
				// Exactly one piece, and no shorter piece after it: the case where
				// the shape of a split answer lies.
				case "exactly" -> "x".repeat(4096);
				case "quiet" -> "";
				case "say привет" -> "Said привет";
				default -> "Unknown command";
			};
		}

		private static void send(OutputStream out, RconFrame frame) throws IOException {
			// Not frame.encode(): a reply may legitimately be longer than a request
			// is allowed to be, and that limit belongs to what we send.
			byte[] body = frame.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			java.nio.ByteBuffer buffer =
				java.nio.ByteBuffer.allocate(4 + RconFrame.OVERHEAD + body.length)
					.order(java.nio.ByteOrder.LITTLE_ENDIAN);
			buffer.putInt(RconFrame.OVERHEAD + body.length);
			buffer.putInt(frame.id());
			buffer.putInt(frame.type());
			buffer.put(body).put((byte) 0).put((byte) 0);
			out.write(buffer.array());
			out.flush();
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}

	@Test
	@DisplayName("A command comes back with the server's answer")
	void command() throws IOException {
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			assertEquals("There are 0 of a max of 20 players online:", rcon.command("list"));
			assertTrue(rcon.isOpen());
		}
	}

	@Test
	@DisplayName("The wrong password fails at connect, not at the first command")
	void wrongPassword() throws IOException {
		try (Fake fake = new Fake("secret")) {
			assertThrows(Rcon.AuthFailed.class,
				() -> Rcon.connect("127.0.0.1", fake.port(), "guess"));
		}
	}

	@Test
	@DisplayName("An answer of several packets is assembled whole")
	void longAnswer() throws IOException {
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			String answer = rcon.command("long");
			assertEquals(10_000, answer.length());
			assertEquals("x".repeat(10_000), answer);
		}
	}

	@Test
	@DisplayName("A command with no output is an empty answer, not a wait")
	void noOutput() throws IOException {
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			assertEquals("", rcon.command("quiet"));
		}
	}

	@Test
	@DisplayName("Commands keep their place when several are sent down one connection")
	void inOrder() throws IOException {
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			assertEquals(10_000, rcon.command("long").length());
			assertEquals("There are 0 of a max of 20 players online:", rcon.command("list"));
			assertEquals("Unknown command", rcon.command("nonsense"));
		}
	}

	@Test
	@DisplayName("Text that is not ASCII crosses the wire unharmed")
	void unicode() throws IOException {
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			assertEquals("Said привет", rcon.command("say привет"));
		}
	}

	@Test
	@DisplayName("A server that is not there is an error, not a hang")
	void notThere() throws IOException {
		int port;
		try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = free.getLocalPort();
		}
		assertThrows(IOException.class, () -> Rcon.connect("127.0.0.1", port, "secret"));
	}

	@Test
	@DisplayName("A reply longer than the protocol allows is refused before it is allocated")
	void absurdLength() throws IOException {
		try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread liar = new Thread(() -> {
				try (Socket client = listener.accept()) {
					byte[] header = new byte[4];
					new DataInputStream(client.getInputStream()).readFully(header);
					// A length field of two gigabytes, which is the whole point of
					// bounding it: believing this allocates two gigabytes.
					client.getOutputStream().write(new byte[] {0, 0, 0, 0x7f});
					client.getOutputStream().flush();
					Thread.sleep(200);
				} catch (Exception done) {
					// The client hanging up is the expected end of this.
				}
			});
			liar.setDaemon(true);
			liar.start();
			IOException thrown = assertThrows(IOException.class,
				() -> Rcon.connect("127.0.0.1", listener.getLocalPort(), "secret"));
			assertTrue(thrown.getMessage().contains("length"),
				"expected a complaint about the length, got: " + thrown.getMessage());
		}
	}

	@Test
	@DisplayName("Nothing but the command is sent, because anything else hangs up the server")
	void onlyTheCommand() throws IOException {
		// The fake closes on a packet type it does not know, exactly as a real
		// server does. A client that sent a marker after the command would find
		// the connection gone here — which is precisely how this was found, on a
		// running server, after the tests were green.
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			assertEquals("There are 0 of a max of 20 players online:", rcon.command("list"));
			assertEquals("There are 0 of a max of 20 players online:", rcon.command("list"));
			assertTrue(rcon.isOpen(), "the connection did not survive two commands");
		}
	}

	@Test
	@DisplayName("An answer of exactly one full piece ends, rather than waiting for ever")
	void exactlyOnePiece() throws IOException {
		// 4096 characters look like the first piece of a longer answer and are
		// not. This must end, and it must end with everything that was sent.
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			long began = System.currentTimeMillis();
			assertEquals(4096, rcon.command("exactly").length());
			assertTrue(System.currentTimeMillis() - began < 8_000,
				"waiting for a continuation that was never coming took too long");

			// And the connection is still usable afterwards.
			assertEquals("Unknown command", rcon.command("whatever"));
		}
	}

	@Test
	@DisplayName("Frames are read one at a time even when several arrive together")
	void backToBack() throws IOException {
		// Three commands in a row on one connection is the case where a client that
		// reads by buffer rather than by frame loses its place.
		try (Fake fake = new Fake("secret");
			 Rcon rcon = Rcon.connect("127.0.0.1", fake.port(), "secret")) {
			for (int at = 0; at < 5; at++) {
				assertEquals("Unknown command", rcon.command("whatever" + at));
			}
		}
	}

	@Test
	@DisplayName("A password is not what a frame prints about itself")
	void password() {
		assertTrue(Arrays.stream(new RconFrame(1, RconFrame.TYPE_AUTH, "s3cr3t")
			.toString().split(" ")).noneMatch(word -> word.contains("s3cr3t")));
	}
}
