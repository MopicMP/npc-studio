package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A place plugins and mods can be looked for, whichever place it is.
 *
 * This exists because there is now more than one, and the second one taught what
 * the first hid: almost nothing about browsing is particular to a site. A row is
 * a picture, a name, a summary and a download count. A release is a file, an
 * address and a checksum. Installing one is "fetch that address into the addons
 * folder, check the checksum, remember which project it came from" — the same
 * sentence for every catalogue there will ever be.
 *
 * What <em>is</em> particular is the shape of the answers and how you are allowed
 * to ask, and that is all the subclasses are: an address builder and a reader.
 * Modrinth answers with {@code hits} and needs nothing to ask; CurseForge answers
 * with {@code data} and needs a key belonging to the person asking.
 *
 * The records live here rather than in one of the two on purpose. They were in
 * {@code Modrinth} first, and every other class then spoke about a row of a list
 * by naming one particular website — which reads as though there could never be
 * another, until there is.
 */
public abstract class Catalogue {

	/**
	 * One page of results, and how many there are altogether.
	 *
	 * The total is what makes scrolling work: without it the list either stops at
	 * the first twenty or asks for a twenty-first page that does not exist every
	 * time somebody reaches the bottom.
	 */
	public record Page(List<Found> hits, int total, int offset, int returned) {

		public Page(List<Found> hits, int total, int offset) {
			this(hits, total, offset, hits.size());
		}

		public boolean more() {
			return offset + returned < total;
		}

		/**
		 * Where the next page starts.
		 *
		 * Counted in what the catalogue sent, not in what survived the filtering.
		 * Asking for "everything after the twelve I kept" when it sent twenty
		 * fetches eight of them a second time — which is how Fabric API came to
		 * be in the list ten times.
		 */
		public int next() {
			return offset + returned;
		}
	}

	/** One project as a search returns it. */
	public record Found(String id, String slug, String title, String summary,
			long downloads, String serverSide, String clientSide, List<String> loaders,
			String icon) {

		/**
		 * Whether it is known not to run on a server at all.
		 *
		 * Known, not guessed: only Modrinth states this, and a catalogue that says
		 * nothing gets {@code unknown} rather than a decision made on its behalf.
		 */
		public boolean serverless() {
			return serverSide.equals("unsupported");
		}
	}

	/** One published file of one project. */
	public record Release(String id, String name, String number, String type,
			String filename, String url, String sha1, long size, String changelog,
			List<String> gameVersions) {

		public Release(String id, String name, String number, String type,
				String filename, String url, String sha1, long size) {
			this(id, name, number, type, filename, url, sha1, size, "", List.of());
		}

		public Release(String id, String name, String number, String type,
				String filename, String url, String sha1, long size, String changelog) {
			this(id, name, number, type, filename, url, sha1, size, changelog, List.of());
		}

		public boolean finished() {
			return type.equals("release");
		}

		/**
		 * Whether this file is for that version of the game.
		 *
		 * Asked rather than filtered away, because the whole list is shown: a
		 * version list cut down to what fits is a list that cannot answer "is
		 * there one for my version yet", which is the question people open it
		 * with. A file that says nothing about versions is treated as fitting —
		 * there is no honest way to rule it out.
		 */
		public boolean fits(String gameVersion) {
			return gameVersions.isEmpty() || gameVersions.contains(gameVersion);
		}
	}

	/**
	 * A whole project page, for somebody deciding rather than searching.
	 *
	 * The list gives a name, a line and a number, which is enough to recognise
	 * something already known and not nearly enough to choose between two things
	 * never heard of. That is what this is for, and it is why the pictures are in
	 * it: half of what a mod does is only sayable in a screenshot.
	 *
	 * @param body       the description as the site wrote it — see {@link Markup}
	 * @param author     whoever published it, which is a thing people look for
	 * @param sides      whether it runs on a server, a client or both
	 * @param gallery    its pictures, in the order the site put them
	 * @param page       its own page on the web, for the button that leaves
	 */
	public record Details(String id, String title, String summary, String body, String author,
			long downloads, List<String> gameVersions, List<String> loaders, Sides sides,
			List<Shot> gallery, List<Release> releases, String page) {
	}

	/**
	 * Which end of the game something is for.
	 *
	 * Kept as the catalogue's own three words — {@code required}, {@code optional},
	 * {@code unsupported} — plus {@code unknown} for the catalogue that does not
	 * say. Turning "does not say" into "yes" is how a browser for a server offers
	 * a rendering mod, so it stays a fourth answer rather than a default.
	 */
	public record Sides(String server, String client) {

		public static final Sides UNKNOWN = new Sides("unknown", "unknown");

		public boolean known() {
			return !server.equals("unknown") || !client.equals("unknown");
		}

		/** Whether it will do anything at all on a server. */
		public boolean serverless() {
			return server.equals("unsupported");
		}
	}

	/**
	 * One picture from a project's gallery, and what its author said about it.
	 *
	 * The caption is kept because it is written by somebody explaining their own
	 * screenshot — "Sodium fixes many graphical issues with smooth lighting while
	 * underwater" is the sentence that makes the picture mean something, and
	 * throwing it away leaves a pretty rectangle.
	 */
	public record Shot(String url, String caption) {
	}

	/**
	 * Everything a project page needs, in one call.
	 *
	 * One method rather than four because it is two or three requests either way
	 * and they all have to arrive before anything can be drawn — a page that
	 * appears in pieces over four seconds is a page that is read four times.
	 */
	public abstract Details details(String project, String loader, String gameVersion)
		throws IOException;

	/**
	 * Which project a file on disk came from, asked by its checksum.
	 *
	 * The exact question, answered exactly. A jar somebody installed by hand
	 * carries no note of where it came from, and matching by name is guesswork —
	 * "Chunky" is a file called Chunky-Bukkit-1.5.3.jar from a project called
	 * chunky, and none of those three strings is another. A checksum is the file
	 * itself, so a match is not a guess.
	 *
	 * Answers an empty string when this catalogue cannot be asked. CurseForge can,
	 * but by a fingerprint of its own devising rather than by a standard digest,
	 * so it is not the same question and is not answered here.
	 */
	public String projectByHash(String sha1) throws IOException {
		return "";
	}

	/**
	 * A description reduced to its words, for anywhere that cannot draw more.
	 *
	 * The interesting version of this lives in {@link Markup}, which keeps the
	 * headings, the emphasis, the links and the code blocks. This is that, then
	 * flattened — kept as one call because a caption or a log line wants a string
	 * and nothing else.
	 */
	public static String plain(String markup) {
		return Markup.plain(markup);
	}

	/**
	 * The orders a catalogue is asked to sort by.
	 *
	 * One list for all of them, translated into each site's own numbering by the
	 * subclass. Kept in the order they are worth offering: what matches, then what
	 * everybody uses, then what is being followed, then what is new and what has
	 * just been touched.
	 */
	public static final List<String> ORDERS =
		List.of("relevance", "downloads", "follows", "newest", "updated");

	protected final String api;
	protected final Downloads downloads;

	protected Catalogue(String api, Downloads downloads) {
		this.api = api.endsWith("/") ? api.substring(0, api.length() - 1) : api;
		this.downloads = downloads;
	}

	// ------------------------------------------------------------------ reading json

	/**
	 * A field that may be missing, and may be there and be null.
	 *
	 * Those are not the same thing in somebody else's JSON, and Gson's own
	 * accessors treat them as the same right up until they do not: asking for an
	 * absent object gives null, and asking for one whose value is {@code null}
	 * throws a class cast, because {@code JsonNull} is not {@code JsonObject}.
	 *
	 * <b>This cost a screenful of stack trace where a list of plugins should have
	 * been.</b> CurseForge writes {@code "logo": null} for a project without a
	 * picture, and the sample this was written against simply left the field out —
	 * so the code was right about the shape it had seen and wrong about the shape
	 * that exists. Every reader here goes through these three now, and none of
	 * them can throw.
	 */
	protected static JsonObject object(JsonObject from, String key) {
		JsonElement value = from == null ? null : from.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	protected static JsonArray array(JsonObject from, String key) {
		JsonElement value = from == null ? null : from.get(key);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	protected static String string(JsonObject from, String key) {
		JsonElement value = from == null ? null : from.get(key);
		return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
	}

	/** Which entry in the catalogue of sources this is. */
	public abstract String id();

	public abstract Page search(String query, String loader, String gameVersion, int offset,
		String order) throws IOException;

	public abstract List<Release> releases(String project, String loader, String gameVersion)
		throws IOException;

	/**
	 * Where one project keeps its picture.
	 *
	 * Asked separately because the installed list has only a jar and a note of
	 * which project it came from — a plugin carries no picture inside itself, so
	 * the catalogue is the only place one exists.
	 */
	public abstract String iconUrl(String project) throws IOException;

	/** A project's picture, for a list that is otherwise forty lines of text. */
	public byte[] icon(String url) throws IOException {
		return downloads.bytes(URI.create(url), 1024 * 1024);
	}

	/** The newest finished release, or the newest of anything when none is. */
	public static Release best(List<Release> releases) throws IOException {
		for (Release release : releases) {
			if (release.finished()) return release;
		}
		if (!releases.isEmpty()) return releases.getFirst();
		throw new IOException("nothing published for this version and loader");
	}

	/**
	 * Which loader a core wants, as the catalogues spell it.
	 *
	 * Vanilla answers with nothing, and that is not an oversight: a vanilla
	 * server takes neither plugins nor mods, and a browser offered on one would
	 * be a shop with nothing on the shelves that fits.
	 */
	/** The word this mod uses for the shelf that is not mods and not plugins. */
	public static final String DATAPACK = "datapack";

	public static String loaderFor(String core) {
		return switch (core) {
			case "paper" -> "paper";
			case "purpur" -> "purpur";
			case "fabric" -> "fabric";
			case "quilt" -> "quilt";
			case "neoforge" -> "neoforge";
			case "forge" -> "forge";
			default -> "";
		};
	}

	/**
	 * Download one into a server's folder.
	 *
	 * The address comes from the release rather than being built: it points at a
	 * different host from the API, and at a path nobody could guess. The checksum
	 * published beside it is checked before the file gets its name.
	 */
	public String install(ManagedServer server, Release release, Downloads.Progress progress)
			throws IOException {
		return install(server, release, null, progress);
	}

	/** The same, into a folder of the caller's choosing — a world's, for a datapack. */
	public String install(ManagedServer server, Release release, Path into,
			Downloads.Progress progress) throws IOException {
		if (release.url() == null || release.url().isBlank()) {
			throw new IOException("that file has no address to download from");
		}
		// A datapack does not belong in the mods folder; it belongs to a world.
		// Which world is the caller's business — this only knows where it was told
		// to put things.
		Path folder = into == null ? Addons.folder(server) : into;
		Files.createDirectories(folder);
		// The name comes from someone else's catalogue and must not become a path
		// of its own — see the rule in docs/security.md.
		String name = Path.of(release.filename()).getFileName().toString();
		String lower = name.toLowerCase(Locale.ROOT);
		boolean pack = into != null;
		if (name.isBlank() || !(pack ? lower.endsWith(".zip") : lower.endsWith(".jar"))) {
			throw new IOException(pack
				? "that file is not a datapack: " + release.filename()
				: "that file is not a jar: " + release.filename());
		}
		downloads.file(URI.create(release.url()), folder.resolve(name),
			release.sha1() == null ? null : Downloads.Hash.sha1(release.sha1()), progress);
		return name;
	}

	/**
	 * The one for a source, or nothing when it cannot be used yet.
	 *
	 * The key is passed in rather than read here: this is code that builds
	 * addresses, and a class that quietly reaches for somebody's credentials is a
	 * class nobody can reason about.
	 */
	public static java.util.Optional<Catalogue> of(CoreCatalog.Source source, Downloads downloads,
			String key) {
		if (source == null || !source.usable()) return java.util.Optional.empty();
		if (source.needsKey() && (key == null || key.isBlank())) return java.util.Optional.empty();
		return switch (source.id()) {
			case "modrinth" -> java.util.Optional.of(new Modrinth(source.api(), downloads));
			case "curseforge" -> java.util.Optional.of(
				new CurseForge(source.api(), downloads, key));
			default -> java.util.Optional.empty();
		};
	}
}
