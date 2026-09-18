package com.mopicmp.npcstudio.server;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Asking a Minecraft server whether it is up, the way the game itself asks.
 *
 * <b>Why this exists at all.</b> Whether a server is running was decided by
 * looking at the process we started: its identifier, and the moment it started,
 * kept beside it. That is a good answer to "is our process alive" and a poor
 * answer to the question people actually ask, which is "is the server up" — the
 * two come apart in every one of these cases:
 *
 * <ul>
 * <li>the game was closed and opened again, and the identifier no longer matches
 * what the operating system will tell us about that process;</li>
 * <li>the server was started by something that is not this mod — a batch file, a
 * panel, a previous version of this mod;</li>
 * <li>the process is alive but the server inside it has died in a way that leaves
 * the process standing.</li>
 * </ul>
 *
 * <p>The server list in the game answers this correctly for anything with an
 * address, and it does it with the protocol below: connect, say hello, ask for
 * the status, read one JSON document. No plugin on the far side, no agreement
 * with anybody — every Minecraft server since 1.7 answers this, including ones
 * this mod has never seen before. So this is what "up" means here.
 *
 * <p>The shape is the protocol's own: <b>varint</b> lengths and ids, a handshake
 * naming the next state as {@code 1} (status), then an empty status request. The
 * numbers are read out of what the game sends rather than from documentation, and
 * {@code ServerRun} pings a real server it has just started.
 */
public final class Ping {

	private Ping() {
	}

	/**
	 * What a server said about itself.
	 *
	 * @param version what the server calls its version, which is not always a
	 *                number: proxies put their own name here
	 * @param online  how many are on it, or -1 when it did not say
	 */
	public record Status(String version, int online, int most, String motd) {
	}

	/** Long enough for a machine that is busy, short enough not to hold a frame. */
	private static final int CONNECT_MS = 400;
	private static final int READ_MS = 1200;

	/** A status document is a few kilobytes; this is room for a strange one. */
	private static final int MOST_ANSWER = 512 * 1024;

	/**
	 * Whether something is serving Minecraft at this address.
	 *
	 * Never throws. Every failure here — refused, timed out, answered with
	 * nonsense — means the same thing to whoever asked, which is "no".
	 */
	public static boolean answers(String host, int port) {
		return status(host, port) != null;
	}

	/** What the server says about itself, or null when it says nothing. */
	public static Status status(String host, int port) {
		if (port <= 0 || port > 65535) return null;
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), CONNECT_MS);
			socket.setSoTimeout(READ_MS);
			OutputStream out = socket.getOutputStream();

			// The handshake. The protocol version is deliberately -1: it means
			// "asking, not joining", and every server answers a status request
			// whatever number is in here — which matters, because this mod is used
			// against servers older and newer than the game it is running in.
			ByteArrayOutputStream hello = new ByteArrayOutputStream();
			DataOutputStream body = new DataOutputStream(hello);
			writeVarInt(body, 0x00);
			writeVarInt(body, -1);
			writeString(body, host);
			body.writeShort(port);
			writeVarInt(body, 1);
			writePacket(out, hello.toByteArray());

			// And the request itself, which is one empty packet.
			ByteArrayOutputStream ask = new ByteArrayOutputStream();
			writeVarInt(new DataOutputStream(ask), 0x00);
			writePacket(out, ask.toByteArray());
			out.flush();

			DataInputStream in = new DataInputStream(socket.getInputStream());
			int length = readVarInt(in);
			if (length <= 0 || length > MOST_ANSWER) return null;
			int id = readVarInt(in);
			if (id != 0x00) return null;
			String json = readString(in);
			return json.isBlank() ? null : read(json);
		} catch (IOException | RuntimeException noAnswer) {
			return null;
		}
	}

	/**
	 * The parts of the answer worth keeping.
	 *
	 * Read by hand rather than through a JSON binding, because the interesting
	 * field — the description — is three different shapes in the wild: a string, a
	 * chat object with {@code text}, or a tree of them. Everything here treats a
	 * shape it does not know as "said nothing", which is true and harmless.
	 */
	static Status read(String json) {
		try {
			com.google.gson.JsonObject root =
				com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			String version = "";
			if (root.has("version") && root.get("version").isJsonObject()) {
				com.google.gson.JsonObject said = root.getAsJsonObject("version");
				if (said.has("name")) version = said.get("name").getAsString();
			}
			int online = -1;
			int most = -1;
			if (root.has("players") && root.get("players").isJsonObject()) {
				com.google.gson.JsonObject players = root.getAsJsonObject("players");
				if (players.has("online")) online = players.get("online").getAsInt();
				if (players.has("max")) most = players.get("max").getAsInt();
			}
			return new Status(version, online, most, motdOf(root.get("description")));
		} catch (RuntimeException notWhatWeExpected) {
			// It answered something, on the right port, in the right frames. That is
			// still a running server, and the only honest thing to lose is the text.
			return new Status("", -1, -1, "");
		}
	}

	private static String motdOf(com.google.gson.JsonElement description) {
		if (description == null) return "";
		if (description.isJsonPrimitive()) return description.getAsString();
		if (description.isJsonObject()) {
			com.google.gson.JsonObject said = description.getAsJsonObject();
			StringBuilder out = new StringBuilder();
			if (said.has("text")) out.append(said.get("text").getAsString());
			if (said.has("extra") && said.get("extra").isJsonArray()) {
				for (var part : said.getAsJsonArray("extra")) out.append(motdOf(part));
			}
			return out.toString();
		}
		return "";
	}

	// ------------------------------------------------------------------ framing

	private static void writePacket(OutputStream out, byte[] body) throws IOException {
		ByteArrayOutputStream framed = new ByteArrayOutputStream();
		DataOutputStream head = new DataOutputStream(framed);
		writeVarInt(head, body.length);
		head.write(body);
		out.write(framed.toByteArray());
	}

	static void writeVarInt(DataOutputStream out, int value) throws IOException {
		int rest = value;
		while (true) {
			if ((rest & ~0x7F) == 0) {
				out.writeByte(rest);
				return;
			}
			out.writeByte((rest & 0x7F) | 0x80);
			rest >>>= 7;
		}
	}

	static int readVarInt(InputStream in) throws IOException {
		int value = 0;
		int shift = 0;
		while (true) {
			int read = in.read();
			if (read < 0) throw new IOException("The server stopped answering mid-number");
			value |= (read & 0x7F) << shift;
			if ((read & 0x80) == 0) return value;
			shift += 7;
			// Five groups of seven bits is the whole of a thirty-two bit number; more
			// than that is a stream that is not this protocol, or is not sane.
			if (shift >= 35) throw new IOException("That is not a length");
		}
	}

	private static void writeString(DataOutputStream out, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = readVarInt(in);
		if (length < 0 || length > MOST_ANSWER) throw new IOException("That answer is too long");
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
