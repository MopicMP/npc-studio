package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Fetching things from other people's servers, under rules.
 *
 * Three of them, and each is in {@code docs/security.md} because a mod has hurt
 * somebody by not having it.
 *
 * <b>Only hosts on a list.</b> A mod that fetches from an address it was handed
 * is a mod that can be pointed at anything, including the inside of the user's
 * own network. The list is data rather than code so that a moved endpoint is a
 * catalogue update instead of a release — and the check is repeated on the
 * address a redirect landed on, because otherwise the list only guards the first
 * hop.
 *
 * <b>A size, always.</b> A length that arrives over a socket decides how much of
 * the disk gets used; the answer to "how big is this" cannot come from the same
 * place as the file.
 *
 * <b>A hash where one is published.</b> Mojang and Paper both state a checksum
 * beside the download. Checking it costs one pass over bytes we are already
 * reading, and it is the difference between "we downloaded a server" and "we
 * downloaded what was at that address today".
 */
public final class Downloads {

	/** Whoever is watching a long download; called with bytes so far and total, or -1. */
	public interface Progress {
		void at(long done, long total);

		Progress IGNORED = (done, total) -> {
		};
	}

	/** A published checksum: the algorithm named as Java names it, and the hex. */
	public record Hash(String algorithm, String hex) {
		public static Hash sha1(String hex) {
			return new Hash("SHA-1", hex);
		}

		public static Hash sha256(String hex) {
			return new Hash("SHA-256", hex);
		}
	}

	/** A server jar is tens of megabytes. This is room to spare, not a target. */
	public static final long MAX_DOWNLOAD = 512L * 1024 * 1024;

	/** Version lists are a few hundred kilobytes at most. */
	private static final long MAX_TEXT = 8L * 1024 * 1024;

	private final Set<String> allowed;
	private final HttpClient client;

	public Downloads(Set<String> allowedHosts) {
		this.allowed = Set.copyOf(allowedHosts);
		this.client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	}

	/**
	 * Whether we are allowed to go there at all.
	 *
	 * Plain HTTP is refused except to this machine. Everything this fetches comes
	 * from a public API that speaks HTTPS, and the exception exists for things
	 * that cannot be anything else — a tunnel agent on the loopback address, and
	 * the tests.
	 */
	public boolean permitted(URI uri) {
		String host = uri.getHost();
		if (host == null) return false;
		host = host.toLowerCase(Locale.ROOT);
		boolean here = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !(scheme.equals("http") && here)) return false;
		return allowed.contains(host);
	}

	private void check(URI uri, String what) throws IOException {
		if (!permitted(uri)) {
			throw new IOException("Refusing to " + what + " " + uri
				+ ": that address is not one this mod is allowed to reach");
		}
	}

	/** A short document — a version list, a catalogue — as text. */
	public String text(URI uri) throws IOException {
		return text(uri, java.util.Map.of());
	}

	/**
	 * The same, carrying headers that are nobody else's business.
	 *
	 * <b>These requests do not follow redirects, and that is the point.</b> A
	 * header holding somebody's API key is sent again to wherever a redirect
	 * points, so "follow redirects" and "carry a secret" are two things that must
	 * not be true at once: an API that answered 302 to another host — because it
	 * was mistyped in the catalogue, because it moved, because somebody controls
	 * a DNS entry — would be handed the key. An API endpoint has no reason to
	 * redirect, so refusing costs nothing real.
	 */
	public String text(URI uri, java.util.Map<String, String> headers) throws IOException {
		check(uri, "fetch");
		boolean secret = !headers.isEmpty();
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", agent());
			headers.forEach(builder::header);
			HttpResponse<InputStream> response = (secret ? direct() : client)
				.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
			check(response.uri(), "follow a redirect to");
			if (secret && response.statusCode() / 100 == 3) {
				response.body().close();
				throw new IOException(uri + " answered " + response.statusCode()
					+ " and sent us elsewhere. A request carrying a key is not followed"
					+ " anywhere it did not start.");
			}
			if (response.statusCode() != 200) {
				response.body().close();
				throw new IOException(uri + " answered " + response.statusCode());
			}
			try (InputStream in = response.body()) {
				return new String(in.readNBytes((int) MAX_TEXT), StandardCharsets.UTF_8);
			}
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while fetching " + uri, stopped);
		}
	}

	private HttpClient direct;

	private synchronized HttpClient direct() {
		if (direct == null) {
			direct = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(15))
				.build();
		}
		return direct;
	}

	/**
	 * A small thing straight into memory — a picture, not a jar.
	 *
	 * Separate from {@link #file} because it has no name on disk and no published
	 * checksum, and because the caller wants bytes rather than a path. The bound
	 * is the caller's and small on purpose: this is used from a list that may ask
	 * for twenty of them.
	 */
	public byte[] bytes(URI uri, int most) throws IOException {
		check(uri, "fetch");
		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", agent())
				.GET()
				.build();
			HttpResponse<InputStream> response =
				client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			check(response.uri(), "follow a redirect to");
			if (response.statusCode() != 200) {
				response.body().close();
				throw new IOException(uri + " answered " + response.statusCode());
			}
			try (InputStream in = response.body()) {
				return in.readNBytes(most);
			}
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while fetching " + uri, stopped);
		}
	}

	/**
	 * A file, verified, written beside its place and moved in.
	 *
	 * Nothing lands at the target path until the whole thing has arrived and its
	 * hash has matched. A half-downloaded server jar that is named like a whole
	 * one is a server that will not start and a reason nobody can see.
	 */
	public void file(URI uri, Path target, Hash expected, Progress progress) throws IOException {
		check(uri, "download from");
		Path parent = target.getParent();
		if (parent != null) Files.createDirectories(parent);
		Path partial = target.resolveSibling(target.getFileName() + ".part");

		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofMinutes(30))
				.header("User-Agent", agent())
				.GET()
				.build();
			HttpResponse<InputStream> response =
				client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			check(response.uri(), "follow a redirect to");
			if (response.statusCode() != 200) {
				response.body().close();
				throw new IOException(uri + " answered " + response.statusCode());
			}
			long total = response.headers().firstValueAsLong("content-length").orElse(-1);
			if (total > MAX_DOWNLOAD) {
				response.body().close();
				throw new IOException("That download states " + total
					+ " bytes, which is past the " + MAX_DOWNLOAD + " this will accept");
			}

			MessageDigest digest = expected == null ? null : digest(expected.algorithm());
			long done = 0;
			try (InputStream in = response.body();
				 OutputStream out = Files.newOutputStream(partial)) {
				byte[] chunk = new byte[64 * 1024];
				int read;
				while ((read = in.read(chunk)) > 0) {
					done += read;
					if (done > MAX_DOWNLOAD) {
						throw new IOException("That download passed " + MAX_DOWNLOAD
							+ " bytes, whatever its headers said");
					}
					out.write(chunk, 0, read);
					if (digest != null) digest.update(chunk, 0, read);
					progress.at(done, total);
				}
			}

			if (digest != null) {
				String got = HexFormat.of().formatHex(digest.digest());
				if (!got.equalsIgnoreCase(expected.hex())) {
					throw new IOException("What arrived from " + uri + " is not what was published:"
						+ " expected " + expected.algorithm() + " " + expected.hex() + ", got " + got);
				}
			}
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while downloading " + uri, stopped);
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	private static MessageDigest digest(String algorithm) throws IOException {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException unknown) {
			throw new IOException("No such digest as " + algorithm, unknown);
		}
	}

	/**
	 * Who we say we are.
	 *
	 * Named rather than blank because these are somebody else's free APIs, and a
	 * client they can identify is one they can talk to about a problem instead of
	 * blocking.
	 */
	private static String agent() {
		return com.mopicmp.npcstudio.NpcStudio.MOD_ID + " (Minecraft mod; server manager)";
	}
}
