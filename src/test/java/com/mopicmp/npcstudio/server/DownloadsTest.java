package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fetching, against a server on this machine.
 *
 * The rules being checked are the ones in {@code docs/security.md}, and each of
 * them is here because a mod has hurt somebody by not having it: a fetch that
 * goes wherever it is told, a download whose size is decided by the download,
 * and bytes taken on trust because they arrived from the right address.
 *
 * The last one is the least obvious and the most useful. A checksum does not
 * only catch a truncated download — it is what makes "we installed the server
 * that was published" a different sentence from "we installed whatever answered
 * that address today".
 */
class DownloadsTest {

	private HttpServer server;
	private byte[] payload;
	private String sha256;

	@TempDir
	Path folder;

	@BeforeEach
	void serve() throws IOException {
		payload = "not really a server jar, but it hashes like one".repeat(200)
			.getBytes(StandardCharsets.UTF_8);
		sha256 = hex("SHA-256", payload);

		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/good", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		});
		server.createContext("/text", exchange -> {
			byte[] body = "{\"versions\": [\"1.21.1\"]}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/missing", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static String hex(String algorithm, byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
		} catch (Exception impossible) {
			throw new AssertionError(impossible);
		}
	}

	private URI at(String path) {
		return URI.create("http://localhost:" + server.getAddress().getPort() + path);
	}

	private static Downloads allowing(String... hosts) {
		return new Downloads(Set.of(hosts));
	}

	@Test
	@DisplayName("A file with a matching checksum lands where it was asked for")
	void downloaded() throws IOException {
		Path target = folder.resolve("server.jar");
		allowing("localhost").file(at("/good"), target,
			Downloads.Hash.sha256(sha256), Downloads.Progress.IGNORED);

		assertArrayEquals(payload, Files.readAllBytes(target));
		assertFalse(Files.exists(folder.resolve("server.jar.part")), "a part file was left behind");
	}

	@Test
	@DisplayName("Progress is reported, and ends at the size of the file")
	void progress() throws IOException {
		long[] last = {0, 0};
		allowing("localhost").file(at("/good"), folder.resolve("server.jar"),
			null, (done, total) -> {
				last[0] = done;
				last[1] = total;
			});
		assertEquals(payload.length, last[0]);
		assertEquals(payload.length, last[1]);
	}

	@Test
	@DisplayName("Bytes that do not match the published checksum are not kept")
	void wrongHash() {
		Path target = folder.resolve("server.jar");
		IOException thrown = assertThrows(IOException.class, () ->
			allowing("localhost").file(at("/good"), target,
				Downloads.Hash.sha256("0".repeat(64)), Downloads.Progress.IGNORED));

		assertTrue(thrown.getMessage().contains("not what was published"), thrown.getMessage());
		// Nothing is left that could be mistaken for a working server.
		assertFalse(Files.exists(target));
		assertFalse(Files.exists(folder.resolve("server.jar.part")));
	}

	@Test
	@DisplayName("A checksum in the other case is still the same checksum")
	void caseOfHex() throws IOException {
		allowing("localhost").file(at("/good"), folder.resolve("server.jar"),
			Downloads.Hash.sha256(sha256.toUpperCase(java.util.Locale.ROOT)),
			Downloads.Progress.IGNORED);
		assertTrue(Files.exists(folder.resolve("server.jar")));
	}

	@Test
	@DisplayName("An address that is not on the list is refused before anything is sent")
	void notAllowed() {
		Downloads downloads = allowing("api.papermc.io");
		IOException thrown = assertThrows(IOException.class, () ->
			downloads.file(at("/good"), folder.resolve("server.jar"),
				null, Downloads.Progress.IGNORED));
		assertTrue(thrown.getMessage().contains("not one this mod is allowed to reach"),
			thrown.getMessage());
		assertFalse(Files.exists(folder.resolve("server.jar")));
	}

	@Test
	@DisplayName("Plain HTTP is refused anywhere but this machine")
	void plainHttp() {
		Downloads downloads = allowing("localhost", "example.org");
		assertTrue(downloads.permitted(at("/good")), "loopback over plain HTTP is the exception");
		assertFalse(downloads.permitted(URI.create("http://example.org/x")));
		assertTrue(downloads.permitted(URI.create("https://example.org/x")));
	}

	@Test
	@DisplayName("An address with no host at all is refused rather than crashed on")
	void oddAddresses() {
		Downloads downloads = allowing("localhost");
		assertFalse(downloads.permitted(URI.create("file:///C:/Windows/System32/config/SAM")));
		assertFalse(downloads.permitted(URI.create("/relative/path")));
	}

	@Test
	@DisplayName("An answer that is not 200 is an error, not an empty file")
	void missing() {
		assertThrows(IOException.class, () ->
			allowing("localhost").file(at("/missing"), folder.resolve("server.jar"),
				null, Downloads.Progress.IGNORED));
		assertFalse(Files.exists(folder.resolve("server.jar")));
	}

	@Test
	@DisplayName("A short document comes back as text")
	void text() throws IOException {
		assertEquals("{\"versions\": [\"1.21.1\"]}", allowing("localhost").text(at("/text")));
	}

	@Test
	@DisplayName("A missing document is an error rather than an empty string")
	void textMissing() {
		assertThrows(IOException.class, () -> allowing("localhost").text(at("/missing")));
	}
}
