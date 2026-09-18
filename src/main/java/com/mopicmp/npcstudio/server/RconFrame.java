package com.mopicmp.npcstudio.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One RCON packet, as bytes and back.
 *
 * Framing is kept apart from the socket on purpose. Everything that can be got
 * wrong here — byte order, where the length is measured from, the two
 * terminators, the request that is too long to send — is arithmetic on a byte
 * array, and arithmetic can be tested without a server, a port or a timeout.
 * {@link Rcon} is then only the part that talks.
 *
 * The shape, little-endian throughout:
 *
 * <pre>
 * int32 length   how many bytes follow this field
 * int32 id       ours, echoed back
 * int32 type     what this packet is
 * byte[] body    text, terminated by a zero
 * byte  0        a second terminator nobody has ever needed
 * </pre>
 *
 * So {@code length} is ten more than the body: two integers, and two zeroes.
 */
public record RconFrame(int id, int type, String body) {

	/** A reply carrying command output. Also what an end marker comes back as. */
	public static final int TYPE_RESPONSE = 0;

	/** Sent: run this command. Received: the answer to a login attempt. */
	public static final int TYPE_COMMAND = 2;

	/** Sent: here is the password. */
	public static final int TYPE_AUTH = 3;

	/** The id a server puts in a login reply when the password was wrong. */
	public static final int AUTH_FAILED = -1;

	/** Everything in a packet that is not body: two integers and two zeroes. */
	static final int OVERHEAD = 10;

	/**
	 * The largest request we will send, length field included.
	 *
	 * Not a guess about what is polite: the vanilla server reads a request into
	 * one fixed buffer of this size and does not wait for the rest. A longer
	 * command is therefore not truncated — it is misframed, and the next packet
	 * is read starting from the middle of this one, so the session goes wrong
	 * some time after the mistake and for no visible reason. Refusing loudly
	 * here is the only version of this that can be understood from a log.
	 */
	public static final int MAX_REQUEST = 1460;

	/**
	 * The largest reply we will accept as one packet.
	 *
	 * A server splits long output into packets of about four kilobytes, so this
	 * is already generous. It exists because a length arrives over a socket, and
	 * a length that arrives over a socket decides how much memory we allocate —
	 * see the rule in {@code docs/security.md} about everything from the network
	 * having a bound.
	 */
	public static final int MAX_REPLY = 8192;

	public byte[] encode() {
		byte[] text = body.getBytes(StandardCharsets.UTF_8);
		int length = OVERHEAD + text.length;
		if (4 + length > MAX_REQUEST) {
			throw new IllegalArgumentException(
				"RCON request of " + (4 + length) + " bytes is longer than the "
					+ MAX_REQUEST + " the server reads in one go");
		}
		return ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN)
			.putInt(length)
			.putInt(id)
			.putInt(type)
			.put(text)
			.put((byte) 0)
			.put((byte) 0)
			.array();
	}

	/** The length field on its own, from the first four bytes off the wire. */
	public static int length(byte[] header) {
		return ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}

	/**
	 * A packet from the bytes that followed its length field.
	 *
	 * The body ends at the first zero rather than at the end of the payload:
	 * some servers pad, and a trailing zero read as text turns every comparison
	 * against a known answer into a puzzle.
	 */
	public static RconFrame decode(byte[] payload) {
		if (payload.length < 8) {
			throw new IllegalArgumentException(
				"RCON packet of " + payload.length + " bytes has no room for its header");
		}
		ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
		int id = buffer.getInt();
		int type = buffer.getInt();
		int end = 8;
		while (end < payload.length && payload[end] != 0) end++;
		String body = new String(payload, 8, end - 8, StandardCharsets.UTF_8);
		return new RconFrame(id, type, body);
	}

	/** Never let a password reach a log through a careless {@code toString}. */
	@Override
	public String toString() {
		return "RconFrame[id=" + id + ", type=" + type + ", body=" + body.length() + " chars]";
	}
}
