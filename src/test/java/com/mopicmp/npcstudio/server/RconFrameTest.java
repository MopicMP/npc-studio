package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bytes of the command protocol, without a socket in sight.
 *
 * Everything checked here is a thing that would otherwise be found out by a
 * session that goes wrong later and for no visible reason: the length measured
 * from the wrong place, the wrong byte order, a request too long for the buffer
 * the server reads it into. Framing is arithmetic, so it is checked as
 * arithmetic.
 */
class RconFrameTest {

	@Test
	@DisplayName("A packet is its length, two integers, the text, and two zeroes")
	void shape() {
		byte[] bytes = new RconFrame(7, RconFrame.TYPE_COMMAND, "list").encode();

		// 4 for the length field, then the length it states.
		assertEquals(4 + RconFrame.OVERHEAD + 4, bytes.length);
		assertEquals(RconFrame.OVERHEAD + 4, RconFrame.length(bytes));

		// Little-endian, which is the single easiest thing to get wrong here.
		assertArrayEquals(new byte[] {14, 0, 0, 0}, Arrays.copyOfRange(bytes, 0, 4));
		assertArrayEquals(new byte[] {7, 0, 0, 0}, Arrays.copyOfRange(bytes, 4, 8));
		assertArrayEquals(new byte[] {2, 0, 0, 0}, Arrays.copyOfRange(bytes, 8, 12));
		assertArrayEquals("list".getBytes(StandardCharsets.UTF_8),
			Arrays.copyOfRange(bytes, 12, 16));
		assertArrayEquals(new byte[] {0, 0}, Arrays.copyOfRange(bytes, 16, 18));
	}

	@Test
	@DisplayName("What was encoded is what comes back")
	void roundTrip() {
		RconFrame sent = new RconFrame(1234, RconFrame.TYPE_RESPONSE, "There are 3 players");
		byte[] bytes = sent.encode();
		RconFrame read = RconFrame.decode(Arrays.copyOfRange(bytes, 4, bytes.length));
		assertEquals(sent, read);
	}

	@Test
	@DisplayName("Text that is not ASCII survives, because a message of the day rarely is")
	void unicode() {
		String russian = "Привет, мир";
		byte[] bytes = new RconFrame(1, RconFrame.TYPE_COMMAND, russian).encode();
		assertEquals(russian, RconFrame.decode(Arrays.copyOfRange(bytes, 4, bytes.length)).body());
	}

	@Test
	@DisplayName("The body ends at the first zero, not at the end of the packet")
	void padding() {
		byte[] payload = new byte[16];
		payload[0] = 5;
		payload[4] = 2;
		System.arraycopy("hi".getBytes(StandardCharsets.UTF_8), 0, payload, 8, 2);
		// Bytes 10 onwards are left as zeroes: padding a server may or may not send.
		RconFrame read = RconFrame.decode(payload);
		assertEquals(5, read.id());
		assertEquals(2, read.type());
		assertEquals("hi", read.body());
	}

	@Test
	@DisplayName("A request longer than the server's buffer is refused rather than sent")
	void tooLong() {
		String huge = "say " + "x".repeat(RconFrame.MAX_REQUEST);
		assertThrows(IllegalArgumentException.class,
			() -> new RconFrame(1, RconFrame.TYPE_COMMAND, huge).encode());

		// And the largest one that does fit is sent without complaint, so the
		// limit is the buffer's and not a rounding of it.
		int room = RconFrame.MAX_REQUEST - 4 - RconFrame.OVERHEAD;
		assertEquals(RconFrame.MAX_REQUEST,
			new RconFrame(1, RconFrame.TYPE_COMMAND, "x".repeat(room)).encode().length);
	}

	@Test
	@DisplayName("A packet with no room for its header is not read as an empty one")
	void truncated() {
		assertThrows(IllegalArgumentException.class, () -> RconFrame.decode(new byte[4]));
	}

	@Test
	@DisplayName("A password does not reach a log through toString")
	void quiet() {
		String text = new RconFrame(1, RconFrame.TYPE_AUTH, "hunter2").toString();
		assertEquals(false, text.contains("hunter2"));
	}
}
