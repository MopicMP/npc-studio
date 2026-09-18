package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A folder of mods that arrived as one file.
 *
 * Nobody assembles a server one jar at a time. A set comes as a set — a zip a
 * friend sent, or a Modrinth pack, which is a zip with a list in it — and the
 * alternative this replaces is opening two folders and dragging.
 *
 * <b>Everything here is reading somebody else's archive, so everything here is
 * bounded.</b> An archive is a file format designed to be small and to become
 * large, and each of these bounds exists because the absence of it is a known way
 * to fill a disk or walk out of a folder:
 *
 * <ul>
 * <li>a name inside an archive never becomes a path. Only its last segment is
 * used, and the result is checked to still be inside the addons folder — an entry
 * called {@code ../../server.properties} is a real thing that real archives
 * contain;</li>
 * <li>a count of entries, a size per file and a size for the lot. A zip that
 * states four kilobytes and unpacks to forty gigabytes is not a fault to survive,
 * it is a thing to refuse;</li>
 * <li>a pack's list of downloads is fetched through {@link Downloads}, so the
 * same rule about which hosts may be reached applies to it as to everything else.
 * A pack that names some other server is a pack that gets no files, not a pack
 * that redirects this mod somewhere.</li>
 * </ul>
 */
public final class Packs {

	private Packs() {
	}

	/** What came of unpacking one: what went in, and what did not and why. */
	public record Result(List<String> added, List<String> skipped) {

		public boolean nothing() {
			return added.isEmpty();
		}
	}

	/** Beyond this an archive is not a set of mods, it is something else. */
	private static final int MOST_ENTRIES = 4000;

	/** One jar this large is already unusual; ten of them is a mistake. */
	private static final long MOST_PER_JAR = 256L * 1024 * 1024;

	private static final long MOST_TOTAL = 1024L * 1024 * 1024;

	/** The list a Modrinth pack carries, naming files to fetch rather than holding them. */
	private static final String INDEX = "modrinth.index.json";

	/** Whether this looks like something to unpack rather than to install. */
	public static boolean isArchive(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".zip") || name.endsWith(".mrpack");
	}

	/**
	 * Put everything an archive holds onto a server.
	 *
	 * @param downloads may be null, in which case a pack's listed files are
	 *                  reported as skipped instead of fetched
	 */
	public static Result install(ManagedServer server, Path archive, Downloads downloads,
			Downloads.Progress progress) throws IOException {
		Path folder = Addons.folder(server);
		Files.createDirectories(folder);
		Path safe = folder.toAbsolutePath().normalize();

		List<String> added = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		long total = 0;

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			int seen = 0;
			String index = null;

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++seen > MOST_ENTRIES) {
					throw new IOException("That archive holds more than " + MOST_ENTRIES
						+ " files, which is not a set of mods");
				}
				if (entry.isDirectory()) continue;
				String name = entry.getName().replace('\\', '/');

				if (name.equalsIgnoreCase(INDEX) || name.toLowerCase(Locale.ROOT)
					.endsWith("/" + INDEX)) {
					try (InputStream in = zip.getInputStream(entry)) {
						index = new String(in.readNBytes(4 * 1024 * 1024),
							StandardCharsets.UTF_8);
					}
					continue;
				}
				if (!wanted(name)) continue;

				long size = entry.getSize();
				if (size > MOST_PER_JAR) {
					skipped.add(last(name) + " — too large");
					continue;
				}
				String file = last(name);
				Path target = folder.resolve(file).normalize();
				// The name came from inside somebody else's archive, so it is
				// checked after being resolved rather than trusted before.
				if (!target.toAbsolutePath().normalize().startsWith(safe)) {
					skipped.add(name + " — that name points outside the folder");
					continue;
				}
				Path partial = target.resolveSibling(file + ".part");
				long written;
				try (InputStream in = zip.getInputStream(entry);
					 OutputStream out = Files.newOutputStream(partial)) {
					written = copy(in, out, MOST_PER_JAR);
				}
				total += written;
				if (total > MOST_TOTAL) {
					Files.deleteIfExists(partial);
					throw new IOException("That archive unpacks to more than "
						+ MOST_TOTAL / (1024 * 1024) + " MB");
				}
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
				added.add(file);
				progress.at(total, -1);
			}

			if (index != null) {
				fromIndex(index, folder, safe, downloads, added, skipped, progress);
			}
		} catch (java.util.zip.ZipException notAZip) {
			throw new IOException("That file is not an archive this can read: "
				+ notAZip.getMessage(), notAZip);
		}
		return new Result(List.copyOf(added), List.copyOf(skipped));
	}

	/**
	 * Which entries of an archive are mods.
	 *
	 * A jar at the top, or a jar in a folder called {@code mods} however deep —
	 * which covers a zip somebody made by hand and a pack's {@code overrides}
	 * alike. A jar anywhere else is left where it is: a shader or a library
	 * hiding in a pack is not something to put in a server's mods folder because
	 * it happens to end in the right four letters.
	 */
	static boolean wanted(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (!lower.endsWith(".jar")) return false;
		if (!lower.contains("/")) return true;
		return lower.contains("/mods/") || lower.startsWith("mods/");
	}

	private static String last(String name) {
		int slash = name.lastIndexOf('/');
		return slash < 0 ? name : name.substring(slash + 1);
	}

	private static long copy(InputStream in, OutputStream out, long most) throws IOException {
		byte[] chunk = new byte[64 * 1024];
		long done = 0;
		int read;
		while ((read = in.read(chunk)) > 0) {
			done += read;
			if (done > most) throw new IOException("A file in that archive is too large");
			out.write(chunk, 0, read);
		}
		return done;
	}

	/**
	 * A Modrinth pack's list, which names files instead of holding them.
	 *
	 * The addresses are theirs and the checksums are published beside them, so
	 * each download is verified exactly as one from the browser is. A file the
	 * pack marks as being for the client only is left out — it is the same
	 * judgement the browser makes, made here because a pack is a browser's worth
	 * of installs at once.
	 */
	static void fromIndex(String json, Path folder, Path safe, Downloads downloads,
			List<String> added, List<String> skipped, Downloads.Progress progress) {
		JsonArray files;
		try {
			files = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("files");
		} catch (RuntimeException unreadable) {
			skipped.add(INDEX + " — could not be read");
			return;
		}
		if (files == null) return;

		for (int at = 0; at < files.size() && at < MOST_ENTRIES; at++) {
			if (!files.get(at).isJsonObject()) continue;
			JsonObject file = files.get(at).getAsJsonObject();
			String path = text(file, "path").replace('\\', '/');
			if (!wanted(path)) continue;

			JsonObject env = file.getAsJsonObject("env");
			if (env != null && text(env, "server").equals("unsupported")) {
				skipped.add(last(path) + " — the pack says it is for the client");
				continue;
			}
			if (downloads == null) {
				skipped.add(last(path) + " — nothing to fetch it with");
				continue;
			}

			JsonArray addresses = file.getAsJsonArray("downloads");
			if (addresses == null || addresses.isEmpty()) {
				skipped.add(last(path) + " — the pack gives no address for it");
				continue;
			}
			String sha1 = null;
			JsonObject hashes = file.getAsJsonObject("hashes");
			if (hashes != null && hashes.has("sha1")) sha1 = hashes.get("sha1").getAsString();

			String name = last(path);
			Path target = folder.resolve(name).normalize();
			if (!target.toAbsolutePath().normalize().startsWith(safe)) {
				skipped.add(path + " — that name points outside the folder");
				continue;
			}

			boolean got = false;
			String why = "";
			for (int each = 0; each < addresses.size() && !got; each++) {
				String url = addresses.get(each).getAsString();
				try {
					downloads.file(URI.create(url), target,
						sha1 == null ? null : Downloads.Hash.sha1(sha1), progress);
					got = true;
				} catch (IOException | RuntimeException refused) {
					why = refused.getMessage() == null ? refused.toString() : refused.getMessage();
				}
			}
			if (got) added.add(name);
			else skipped.add(name + " — " + why);
		}
	}

	private static String text(JsonObject object, String key) {
		var value = object == null ? null : object.get(key);
		return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
	}
}
