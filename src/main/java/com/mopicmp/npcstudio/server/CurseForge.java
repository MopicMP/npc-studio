package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The other catalogue, which will not talk to you without a key of your own.
 *
 * Everything awkward about this class comes from that one fact and from what it
 * implies. CurseForge's terms allow an application to use their API with a key
 * <em>issued to whoever is running it</em>; a key baked into a mod and shipped to
 * everybody is exactly what they revoke, and rightly — it is one account's quota
 * and one account's name on every request the world makes. So there is no key in
 * this repository, there is a field where somebody pastes their own, and until
 * they do this source is listed and refuses to search.
 *
 * The second consequence is smaller and surprises people: <b>a file here may have
 * no address.</b> An author can turn off third-party downloads, and then the API
 * answers with the file, its size, its checksum and a null {@code downloadUrl}.
 * That is not a fault to retry — it is a decision, and the only honest thing to
 * do is say so and point at the project page.
 *
 * Third: it cannot answer the question Modrinth answers. There is no
 * "does this run on a server" field, so nothing is filtered out on that ground
 * and every row says {@code unknown} rather than pretending to know.
 *
 * The numbers below — 432 for Minecraft, 6 for mods and 5 for plugins, 4 for
 * Fabric — are CurseForge's own and are written out where they are used rather
 * than hidden behind names, because the only way to check one is to read their
 * documentation and a name would make that harder rather than easier.
 */
public final class CurseForge extends Catalogue {

	/** Minecraft. Their gameId, not ours. */
	private static final int GAME = 432;

	/** Their two shelves that matter here: mods and Bukkit plugins. */
	private static final int MODS = 6;
	private static final int PLUGINS = 5;

	private final String key;

	public CurseForge(String api, Downloads downloads, String key) {
		super(api, downloads);
		this.key = key == null ? "" : key.trim();
	}

	@Override
	public String id() {
		return "curseforge";
	}

	/**
	 * The key, and nowhere else.
	 *
	 * It is put together at the moment of asking and never kept in a field of its
	 * own beyond the one it arrived in, never logged, and never sent by
	 * {@link Downloads} to an address that redirects — see the note there about
	 * why a request carrying a secret is not followed.
	 */
	private Map<String, String> headers() {
		return Map.of("x-api-key", key, "Accept", "application/json");
	}

	// ------------------------------------------------------------------ addresses

	/** Which shelf a loader belongs to: Bukkit plugins, or mods. */
	static int classFor(String loader) {
		return switch (loader) {
			case "paper", "purpur", "bukkit", "spigot" -> PLUGINS;
			case Catalogue.DATAPACK -> DATA_PACKS;
			default -> MODS;
		};
	}

	/** Their number for the data packs shelf of Minecraft. */
	private static final int DATA_PACKS = 6945;

	/** Their loader numbering. Zero means "do not narrow by loader". */
	static int loaderNumber(String loader) {
		return switch (loader) {
			case "forge" -> 1;
			case "fabric" -> 4;
			case "quilt" -> 5;
			case "neoforge" -> 6;
			default -> 0;
		};
	}

	/**
	 * Our order named as their sortField.
	 *
	 * Two of ours land on the same one of theirs: they sort by when a project was
	 * last touched and have nothing for when it first appeared, so "newest" and
	 * "updated" are the same question here. Saying so is better than offering a
	 * word that quietly does something else.
	 */
	static int sortField(String order) {
		return switch (order == null ? "" : order) {
			case "downloads" -> 6;
			case "follows" -> 2;
			case "newest", "updated" -> 3;
			default -> 0;
		};
	}

	static String searchUrl(String api, String query, String loader, String gameVersion,
			int offset, int limit, String order) {
		StringBuilder url = new StringBuilder(api + "/mods/search?gameId=" + GAME
			+ "&classId=" + classFor(loader)
			+ "&index=" + offset + "&pageSize=" + limit + "&sortOrder=desc");
		int sort = sortField(order);
		if (sort > 0) url.append("&sortField=").append(sort);
		if (!query.isBlank()) {
			url.append("&searchFilter=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
		}
		if (!gameVersion.isBlank()) {
			url.append("&gameVersion=").append(
				URLEncoder.encode(gameVersion, StandardCharsets.UTF_8));
		}
		int number = loaderNumber(loader);
		if (number > 0) url.append("&modLoaderType=").append(number);
		return url.toString();
	}

	static String filesUrl(String api, String project, String loader, String gameVersion) {
		StringBuilder url = new StringBuilder(api + "/mods/" + encodeId(project)
			+ "/files?index=0&pageSize=50");
		if (!gameVersion.isBlank()) {
			url.append("&gameVersion=").append(
				URLEncoder.encode(gameVersion, StandardCharsets.UTF_8));
		}
		int number = loaderNumber(loader);
		if (number > 0) url.append("&modLoaderType=").append(number);
		return url.toString();
	}

	/**
	 * A project id, which is a number, kept a number.
	 *
	 * It comes back out of their own answer and goes into a path, and a value from
	 * outside that becomes part of an address is exactly the shape the security
	 * notes are about — so it is checked to be digits rather than escaped.
	 */
	static String encodeId(String project) {
		String trimmed = project == null ? "" : project.trim();
		if (!trimmed.matches("[0-9]{1,12}")) {
			throw new IllegalArgumentException("not a CurseForge project id: " + project);
		}
		return trimmed;
	}

	// ------------------------------------------------------------------ reading

	static Page parseSearch(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<Found> found = new ArrayList<>();
		JsonArray data = array(root, "data");
		int total = 0;
		int offset = 0;
		int returned = 0;
		JsonObject pagination = object(root, "pagination");
		if (pagination != null) {
			total = number(pagination, "totalCount");
			offset = number(pagination, "index");
			returned = number(pagination, "resultCount");
		}
		if (data == null) return new Page(found, total, offset, returned);
		for (int at = 0; at < data.size(); at++) {
			if (!data.get(at).isJsonObject()) continue;
			JsonObject mod = data.get(at).getAsJsonObject();
			// A project with no picture is written "logo": null rather than left
			// out, and that difference is what threw a class cast where the list of
			// plugins should have been. Every read here goes through the guards.
			String logo = string(object(mod, "logo"), "url");

			// The loaders are not stated for the project; they are stated per file,
			// and the search answer carries an index of the latest few. Gathering
			// them from there is the only place they exist in this reply.
			List<String> loaders = new ArrayList<>();
			JsonArray indexes = array(mod, "latestFilesIndexes");
			if (indexes != null) {
				for (int each = 0; each < indexes.size(); each++) {
					if (!indexes.get(each).isJsonObject()) continue;
					JsonObject index = indexes.get(each).getAsJsonObject();
					String named = loaderName(number(index, "modLoader"));
					if (!named.isEmpty() && !loaders.contains(named)) loaders.add(named);
				}
			}
			found.add(new Found(
				String.valueOf(number(mod, "id")),
				string(mod, "slug"),
				string(mod, "name"),
				string(mod, "summary"),
				count(mod),
				// They do not say, so neither do we. Guessing here would either hide
				// things that work or offer things that break the server.
				"unknown",
				"unknown",
				loaders,
				logo));
		}
		if (returned == 0) returned = data.size();
		return new Page(found, total, offset, returned);
	}

	private static String loaderName(int number) {
		return switch (number) {
			case 1 -> "forge";
			case 4 -> "fabric";
			case 5 -> "quilt";
			case 6 -> "neoforge";
			default -> "";
		};
	}

	static List<Release> parseFiles(String json) {
		List<Release> releases = new ArrayList<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonArray data = array(root, "data");
		if (data == null) return releases;
		for (int at = 0; at < data.size(); at++) {
			if (!data.get(at).isJsonObject()) continue;
			JsonObject file = data.get(at).getAsJsonObject();

			// Their hash list names its algorithm with a number: one is SHA-1, two
			// is MD5. Only the first is worth having — MD5 says a file arrived
			// whole and nothing about it being the file that was published.
			String sha1 = null;
			JsonArray hashes = array(file, "hashes");
			if (hashes != null) {
				for (int each = 0; each < hashes.size(); each++) {
					if (!hashes.get(each).isJsonObject()) continue;
					JsonObject hash = hashes.get(each).getAsJsonObject();
					if (number(hash, "algo") == 1) sha1 = string(hash, "value");
				}
			}
			String name = string(file, "fileName");
			if (name.isBlank()) name = string(file, "displayName");
			// Their list mixes game versions and loader names in one array; the
			// versions are the ones that look like versions.
			List<String> versions = new ArrayList<>();
			JsonArray tags = array(file, "gameVersions");
			if (tags != null) {
				for (int each = 0; each < tags.size(); each++) {
					if (!tags.get(each).isJsonPrimitive()) continue;
					String tag = tags.get(each).getAsString();
					if (!tag.isEmpty() && Character.isDigit(tag.charAt(0))) versions.add(tag);
				}
			}
			releases.add(new Release(
				String.valueOf(number(file, "id")),
				string(file, "displayName"),
				string(file, "displayName"),
				switch (number(file, "releaseType")) {
					case 2 -> "beta";
					case 3 -> "alpha";
					default -> "release";
				},
				name,
				string(file, "downloadUrl"),
				sha1,
				file.has("fileLength") && !file.get("fileLength").isJsonNull()
					? file.get("fileLength").getAsLong() : -1,
				"",
				versions));
		}
		return releases;
	}

	private static long count(JsonObject mod) {
		JsonElement value = mod.get("downloadCount");
		// Written as a floating point number in their answers, which
		// getAsLong on a whole double handles and getAsInt would truncate oddly.
		return value == null || !value.isJsonPrimitive() ? 0 : (long) value.getAsDouble();
	}

	private static int number(JsonObject object, String key) {
		JsonElement value = object == null ? null : object.get(key);
		return value == null || !value.isJsonPrimitive() ? 0 : value.getAsInt();
	}

	/**
	 * The project page, which here is two requests and not one.
	 *
	 * Their description is a separate endpoint answering a string of HTML rather
	 * than a field on the project — so a page needs the project, the description
	 * and the files. A description that will not come is not a reason to show
	 * nothing: the pictures and the versions are still worth having, and an empty
	 * paragraph says less than a wrong error message.
	 */
	static Details parseProject(String json, String describedHtml, List<Release> releases) {
		JsonObject data = object(JsonParser.parseString(json).getAsJsonObject(), "data");
		if (data == null) {
			return new Details("", "", "", describedHtml, "", 0, List.of(), List.of(),
				Sides.UNKNOWN, List.of(), releases, "");
		}
		List<Shot> gallery = new ArrayList<>();
		JsonArray shots = array(data, "screenshots");
		if (shots != null) {
			for (int at = 0; at < shots.size(); at++) {
				if (!shots.get(at).isJsonObject()) continue;
				JsonObject shot = shots.get(at).getAsJsonObject();
				String url = string(shot, "url");
				if (!url.isBlank()) gallery.add(new Shot(url, string(shot, "title")));
			}
		}
		List<String> authors = new ArrayList<>();
		JsonArray named = array(data, "authors");
		if (named != null) {
			for (int at = 0; at < named.size(); at++) {
				if (!named.get(at).isJsonObject()) continue;
				String name = string(named.get(at).getAsJsonObject(), "name");
				if (!name.isBlank()) authors.add(name);
			}
		}
		// The versions and the loaders are stated per file here rather than on the
		// project, so both are gathered from the index of the latest few — which
		// is also all a person wants to know: what it is current for.
		List<String> versions = new ArrayList<>();
		List<String> loaders = new ArrayList<>();
		JsonArray indexes = array(data, "latestFilesIndexes");
		if (indexes != null) {
			for (int at = 0; at < indexes.size(); at++) {
				if (!indexes.get(at).isJsonObject()) continue;
				JsonObject index = indexes.get(at).getAsJsonObject();
				String version = string(index, "gameVersion");
				if (!version.isBlank() && !versions.contains(version)) versions.add(version);
				String loader = loaderName(number(index, "modLoader"));
				if (!loader.isEmpty() && !loaders.contains(loader)) loaders.add(loader);
			}
		}
		return new Details(
			String.valueOf(number(data, "id")),
			string(data, "name"),
			string(data, "summary"),
			describedHtml,
			String.join(", ", authors),
			count(data),
			versions,
			loaders,
			// They have no such field, and a guess here would either hide things
			// that work or offer things that break the server.
			Sides.UNKNOWN,
			gallery,
			releases,
			string(object(data, "links"), "websiteUrl"));
	}

	// ------------------------------------------------------------------ asking

	/**
	 * Ask, and turn the two answers that mean "your key" into words about the key.
	 *
	 * A refusal that arrives as "answered 403" is technically complete and no use
	 * at all: the person reading it pasted something a minute ago and needs to
	 * know whether that is what is wrong. Every other status is passed through as
	 * it came, because inventing a friendly explanation for a fault we have not
	 * identified is how a message ends up pointing at the wrong thing.
	 */
	private String ask(URI uri) throws IOException {
		try {
			return downloads.text(uri, headers());
		} catch (IOException refused) {
			String said = refused.getMessage() == null ? "" : refused.getMessage();
			if (said.contains("answered 401") || said.contains("answered 403")) {
				throw new IOException("CurseForge would not accept the key — it answered"
					+ " \"not allowed\". Check that it was pasted whole, that it is a key for"
					+ " their API from console.curseforge.com rather than a password for the"
					+ " site, and that it has not been revoked. \"Change\" beside the key"
					+ " takes another one.", refused);
			}
			if (said.contains("answered 404")) {
				throw new IOException("CurseForge has nothing at that address: " + uri
					+ " — their API may have moved, which is a change to the catalogue"
					+ " rather than to your key.", refused);
			}
			throw refused;
		}
	}

	@Override
	public Page search(String query, String loader, String gameVersion, int offset, String order)
		throws IOException {
		return parseSearch(
			ask(URI.create(searchUrl(api, query, loader, gameVersion, offset, 20, order))));
	}

	/**
	 * The files for this server, asked twice when the narrow question comes back
	 * empty.
	 *
	 * Their loader filter reads a numeric field on each file, and plenty of files
	 * do not carry it — the loader is written into the list of game versions
	 * instead, as the word "Fabric" beside "1.21.4". Asking with
	 * {@code modLoaderType} then answers nothing at all for a project that plainly
	 * has a Fabric build, and the person is told "nothing published for this
	 * version and loader", which is false and unarguable.
	 *
	 * So an empty answer is asked again without the loader and sorted out here.
	 * Two requests only in the case that was previously a dead end.
	 */
	@Override
	public List<Release> releases(String project, String loader, String gameVersion)
		throws IOException {
		List<Release> narrow = parseFiles(ask(URI.create(
			filesUrl(api, project, loader, gameVersion))));
		if (!narrow.isEmpty() || loaderNumber(loader) == 0) return narrow;

		String json = ask(URI.create(filesUrl(api, project, "", gameVersion)));
		return keepFor(parseFilesWithVersions(json), loader);
	}

	/**
	 * Keep the files whose own list of game versions names this loader.
	 *
	 * A file that names no loader at all is kept too: on the plugins shelf, and on
	 * older mods, that list holds only Minecraft versions, and dropping those
	 * would turn one wrong answer into another.
	 */
	static List<Release> keepFor(List<Tagged> tagged, String loader) {
		List<Release> kept = new ArrayList<>();
		for (Tagged each : tagged) {
			boolean namesALoader = false;
			boolean namesOurs = false;
			for (String tag : each.versions()) {
				String lower = tag.toLowerCase(Locale.ROOT);
				if (LOADERS.contains(lower)) {
					namesALoader = true;
					if (lower.equals(loader)) namesOurs = true;
				}
			}
			if (namesOurs || !namesALoader) kept.add(each.release());
		}
		return kept;
	}

	/** The words in a file's version list that are loaders rather than versions. */
	private static final List<String> LOADERS =
		List.of("forge", "fabric", "quilt", "neoforge");

	/** A file with the words it was tagged with, which is where the loader hides. */
	record Tagged(Release release, List<String> versions) {
	}

	static List<Tagged> parseFilesWithVersions(String json) {
		List<Tagged> tagged = new ArrayList<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonArray data = array(root, "data");
		List<Release> releases = parseFiles(json);
		if (data == null) return tagged;
		int at = 0;
		for (int index = 0; index < data.size() && at < releases.size(); index++) {
			if (!data.get(index).isJsonObject()) continue;
			List<String> versions = new ArrayList<>();
			JsonArray listed = array(data.get(index).getAsJsonObject(), "gameVersions");
			if (listed != null) {
				for (int each = 0; each < listed.size(); each++) {
					if (listed.get(each).isJsonPrimitive()) {
						versions.add(listed.get(each).getAsString());
					}
				}
			}
			tagged.add(new Tagged(releases.get(at++), versions));
		}
		return tagged;
	}

	@Override
	public String iconUrl(String project) throws IOException {
		String json = ask(URI.create(api + "/mods/" + encodeId(project)));
		JsonObject data = object(JsonParser.parseString(json).getAsJsonObject(), "data");
		return string(object(data, "logo"), "url");
	}

	@Override
	public Details details(String project, String loader, String gameVersion) throws IOException {
		String id = encodeId(project);
		// Every version for this loader, not only the ones for this game version —
		// see the note on the same line in Modrinth.
		List<Release> releases = releases(project, loader, "");
		String described = "";
		try {
			String json = ask(URI.create(api + "/mods/" + id + "/description"));
			described = string(JsonParser.parseString(json).getAsJsonObject(), "data");
		} catch (IOException | RuntimeException without) {
			// A page with pictures and versions and no words is still a page.
		}
		return parseProject(ask(URI.create(api + "/mods/" + id)), described, releases);
	}

	/**
	 * The same download as anywhere else, or the reason there is not one.
	 *
	 * A missing address here is a decision by the project's author, not a fault,
	 * and the message says what to do instead. Retrying it forever, which is what
	 * a generic "download failed" invites, would never work.
	 */
	@Override
	public String install(ManagedServer server, Release release, Downloads.Progress progress)
		throws IOException {
		if (release.url() == null || release.url().isBlank()) {
			throw new IOException("CurseForge will not hand this file to another program:"
				+ " its author has third-party downloads turned off. It has to come from the"
				+ " project page, and then be added with the button for a file on disk.");
		}
		return super.install(server, release, progress);
	}
}
