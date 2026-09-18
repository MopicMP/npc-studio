package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asking a server whether it is up.
 *
 * <b>What a test can prove here, and what it cannot.</b> The far side of this is
 * somebody else's program, so a socket in this file that answers the way I think
 * a server answers proves only that I am consistent with myself. What it does
 * prove, and what these tests are for, is the framing: that a length is written
 * as a varint, that the handshake says state 1, that a reply arriving in pieces
 * is still read whole, and that nothing here throws when the far side is rude.
 *
 * The other half is a live check: {@code ServerRun} starts a real server and
 * pings it, and that is the one that proves the protocol.
 */
class PingTest {

	/** A socket that answers one status request the way a server does. */
	private static final class Pretend implements AutoCloseable {
		final ServerSocket socket;
		volatile String saw = "";
		volatile int nextState = -1;

		Pretend(String answer, boolean dribble) throws IOException {
			socket = new ServerSocket(0);
			Thread thread = new Thread(() -> {
				try (Socket client = socket.accept()) {
					DataInputStream in = new DataInputStream(client.getInputStream());
					// The handshake, read the way a server reads it.
					Ping.readVarInt(in);
					Ping.readVarInt(in);
					Ping.readVarInt(in);
					int hostLength = Ping.readVarInt(in);
					byte[] host = new byte[hostLength];
					in.readFully(host);
					saw = new String(host, StandardCharsets.UTF_8);
					in.readShort();
					nextState = Ping.readVarInt(in);
					// And the empty request behind it.
					Ping.readVarInt(in);
					Ping.readVarInt(in);

					byte[] body = body(answer);
					ByteArrayOutputStream framed = new ByteArrayOutputStream();
					DataOutputStream head = new DataOutputStream(framed);
					Ping.writeVarInt(head, body.length);
					head.write(body);
					byte[] whole = framed.toByteArray();
					if (!dribble) {
						client.getOutputStream().write(whole);
					} else {
						// A reply that arrives in two pieces, which is what a socket
						// does whenever the answer is larger than a packet.
						client.getOutputStream().write(whole, 0, 3);
						client.getOutputStream().flush();
						Thread.sleep(60);
						client.getOutputStream().write(whole, 3, whole.length - 3);
					}
					client.getOutputStream().flush();
					Thread.sleep(50);
				} catch (IOException | InterruptedException stopped) {
					// The test is over; there is nothing to report to.
				}
			});
			thread.setDaemon(true);
			thread.start();
		}

		private static byte[] body(String answer) throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			DataOutputStream data = new DataOutputStream(out);
			Ping.writeVarInt(data, 0x00);
			byte[] text = answer.getBytes(StandardCharsets.UTF_8);
			Ping.writeVarInt(data, text.length);
			data.write(text);
			return out.toByteArray();
		}

		int port() {
			return socket.getLocalPort();
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}

	private static final String ANSWER = """
		{"version":{"name":"Paper 26.2","protocol":800},
		 "players":{"max":20,"online":3},
		 "description":{"text":"A ","extra":[{"text":"server"}]}}""";

	@Test
	@DisplayName("A server that answers is up, and says what it is")
	void answers() throws IOException {
		try (Pretend server = new Pretend(ANSWER, false)) {
			Ping.Status status = Ping.status("127.0.0.1", server.port());
			assertNotNull(status);
			assertEquals("Paper 26.2", status.version());
			assertEquals(3, status.online());
			assertEquals(20, status.most());
			assertEquals("A server", status.motd());
			// State 1 is "status"; state 2 would be a join, and a mod that watches a
			// server by joining it would show a player who is not there.
			assertEquals(1, server.nextState);
		}
	}

	@Test
	@DisplayName("An answer arriving in pieces is read whole")
	void dribbled() throws IOException {
		try (Pretend server = new Pretend(ANSWER, true)) {
			Ping.Status status = Ping.status("127.0.0.1", server.port());
			assertNotNull(status);
			assertEquals(3, status.online());
		}
	}

	@Test
	@DisplayName("Nothing listening is a no, not an exception")
	void nothingThere() throws IOException {
		int free;
		try (ServerSocket socket = new ServerSocket(0)) {
			free = socket.getLocalPort();
		}
		assertFalse(Ping.answers("127.0.0.1", free));
		assertNull(Ping.status("127.0.0.1", 0));
		assertNull(Ping.status("127.0.0.1", 70000));
	}

	@Test
	@DisplayName("Something on the port that is not a server is a no")
	void notAServer() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			Thread thread = new Thread(() -> {
				try (Socket client = socket.accept()) {
					client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n"
						.getBytes(StandardCharsets.UTF_8));
					client.getOutputStream().flush();
				} catch (IOException stopped) {
					// Nothing to report to.
				}
			});
			thread.setDaemon(true);
			thread.start();
			// It may be read as a length and then fail, or fail at once. Either way
			// the answer is no and nothing is thrown at the caller.
			assertNull(Ping.status("127.0.0.1", socket.getLocalPort()));
		}
	}

	@Test
	@DisplayName("A description in any of its three shapes is read, or left blank")
	void descriptions() {
		assertEquals("plain", Ping.read("{\"description\":\"plain\"}").motd());
		assertEquals("a b", Ping.read(
			"{\"description\":{\"text\":\"a \",\"extra\":[{\"text\":\"b\"}]}}").motd());
		assertEquals("", Ping.read("{\"description\":[1,2,3]}").motd());
		// A document with nothing familiar in it is still a server that answered.
		Ping.Status odd = Ping.read("{}");
		assertNotNull(odd);
		assertEquals(-1, odd.online());
		assertTrue(odd.version().isEmpty());
	}
}
