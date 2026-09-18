package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Finding plugins and mods without leaving the game.
 *
 * Modrinth first, because its API is open, needs no key, and answers the three
 * questions that matter here in one go: does this work with our version, does it
 * work with our loader, and <b>does it run on a server at all</b>. The last one
 * is the reason to use a catalogue rather than a search engine — a browser full
 * of client mods is a browser that installs things which stop the server
 * starting. It is also the one thing the other catalogue cannot answer.
 *
 * The shapes below were read off the live API rather than from documentation:
 * search answers with {@code hits}, each carrying {@code project_id},
 * {@code title}, {@code server_side} and the loaders in {@code categories}; the
 * version listing answers with a plain array whose files carry a URL and a
 * checksum. There is a task, {@code gradlew checkBrowse}, that asks the real
 * service whether that is still true.
 */
public final class Modrinth extends Catalogue {

	public Modrinth(String api, Downloads downloads) {
		super(api, downloads);
	}

	@Override
	public String id() {
		return "modrinth";
	}

	// ------------------------------------------------------------------ addresses

	private static String facets(String loader, String gameVersion) {
		List<String> parts = new ArrayList<>();
		// A datapack is a project type here, not a loader — asking for both would
		// be asking for datapacks that are also written for Fabric, of which there
		// are none.
		if (DATAPACK.equals(loader)) {
			if (!gameVersion.isBlank()) parts.add("[\"versions:" + gameVersion + "\"]");
			parts.add("[\"project_type:datapack\"]");
			return "[" + String.join(",", parts) + "]";
		}
		if (!loader.isBlank()) parts.add("[\"categories:" + loader + "\"]");
		if (!gameVersion.isBlank()) parts.add("[\"versions:" + gameVersion + "\"]");
		// The project type, and it is not the same one for both shelves.
		//
		// It has to be asked for at all: "Essential Sodium" is a modpack, its
		// published file is a .mrpack rather than a jar, and installing it answered
		// "that file is not a jar" — true, about a thing that was never installable
		// here. A browser for a server that lists resource packs, shaders and
		// modpacks is a browser where a third of the rows cannot be pressed.
		//
		// It was {@code mod} for everything, on the belief that this catalogue kept
		// plugins under that type and told them apart by the loader. That is wrong,
		// and the size of the mistake was measured against the live API rather than
		// argued about: with {@code project_type:mod} the whole Paper shelf is 96
		// projects, and after the client-only ones are dropped a person sees about
		// forty. With {@code project_type:plugin} it is 3405 for this game version —
		// which is the number they can also read on the website.
		parts.add(PLUGINS.contains(loader) ? "[\"project_type:plugin\"]" : "[\"project_type:mod\"]");
		return "[" + String.join(",", parts) + "]";
	}

	/**
	 * The loaders whose things this catalogue files as plugins rather than mods.
	 *
	 * Server platforms and proxies both: the counts say so, and by a long way —
	 * velocity has 858 plugins against 30 projects that call themselves mods for
	 * it, sponge 126 against 24.
	 */
	private static final java.util.Set<String> PLUGINS = java.util.Set.of(
		"paper", "spigot", "bukkit", "purpur", "folia",
		"velocity", "waterfall", "bungeecord", "sponge");

	static String searchUrl(String api, String query, String loader, String gameVersion,
			int offset, int limit, String order) {
		StringBuilder url = new StringBuilder(api + "/search?limit=" + limit + "&offset=" + offset);
		if (order != null && ORDERS.contains(order)) url.append("&index=").append(order);
		if (!query.isBlank()) {
			url.append("&query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
		}
		String facets = facets(loader, gameVersion);
		if (!facets.equals("[]")) {
			url.append("&facets=").append(URLEncoder.encode(facets, StandardCharsets.UTF_8));
		}
		return url.toString();
	}

	static String versionsUrl(String api, String id, String loader, String gameVersion) {
		StringBuilder url = new StringBuilder(api + "/project/" + id + "/version");
		String join = "?";
		if (!loader.isBlank()) {
			url.append(join).append("loaders=").append(URLEncoder.encode(
				"[\"" + loader + "\"]", StandardCharsets.UTF_8));
			join = "&";
		}
		if (!gameVersion.isBlank()) {
			url.append(join).append("game_versions=").append(URLEncoder.encode(
				"[\"" + gameVersion + "\"]", StandardCharsets.UTF_8));
		}
		return url.toString();
	}

	// ------------------------------------------------------------------ reading

	static Page parseSearch(String json) {
		List<Found> found = new ArrayList<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		int total = root.has("total_hits") ? root.get("total_hits").getAsInt() : 0;
		int offset = root.has("offset") ? root.get("offset").getAsInt() : 0;
		JsonArray hits = array(root, "hits");
		if (hits == null) return new Page(found, total, offset, 0);
		for (int at = 0; at < hits.size(); at++) {
			JsonObject hit = hits.get(at).getAsJsonObject();
			List<String> categories = new ArrayList<>();
			JsonArray listed = array(hit, "categories");
			if (listed != null) {
				for (int each = 0; each < listed.size(); each++) {
					categories.add(listed.get(each).getAsString());
				}
			}
			found.add(new Found(
				string(hit, "project_id"),
				string(hit, "slug"),
				string(hit, "title"),
				string(hit, "description"),
				hit.has("downloads") ? hit.get("downloads").getAsLong() : 0,
				hit.has("server_side") ? string(hit, "server_side") : "unknown",
				hit.has("client_side") ? string(hit, "client_side") : "unknown",
				categories,
				string(hit, "icon_url")));
		}
		return new Page(found, total, offset, hits.size());
	}

	static List<Release> parseVersions(String json) {
		List<Release> releases = new ArrayList<>();
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		for (int at = 0; at < array.size(); at++) {
			JsonObject version = array.get(at).getAsJsonObject();
			JsonArray files = array(version, "files");
			if (files == null || files.isEmpty()) continue;

			// The primary file, and only that: a version can also carry sources
			// and a javadoc, and neither belongs in a server folder.
			JsonObject file = null;
			for (int each = 0; each < files.size(); each++) {
				JsonObject candidate = files.get(each).getAsJsonObject();
				if (candidate.has("primary") && candidate.get("primary").getAsBoolean()) {
					file = candidate;
					break;
				}
			}
			if (file == null) file = files.get(0).getAsJsonObject();

			String sha1 = null;
			JsonObject hashes = object(file, "hashes");
			if (hashes != null && !string(hashes, "sha1").isEmpty()) {
				sha1 = string(hashes, "sha1");
			}

			releases.add(new Release(
				string(version, "id"),
				string(version, "name"),
				string(version, "version_number"),
				version.has("version_type") ? string(version, "version_type") : "release",
				string(file, "filename"),
				string(file, "url"),
				sha1,
				file.has("size") ? file.get("size").getAsLong() : -1,
				// Their version listing carries the notes with the file, which is
				// the whole of a changelog and costs no extra request.
				string(version, "changelog"),
				strings(array(version, "game_versions"))));
		}
		return releases;
	}

	/**
	 * A project page: what it is, what it looks like, and what it has published.
	 *
	 * The description arrives as Markdown with HTML mixed into it, and the
	 * pictures are listed separately from it — which is convenient, because the
	 * ones inside the text are lost by {@link Catalogue#plain} and the ones in the
	 * gallery are the ones the author chose to show.
	 */
	static Details parseProject(String json, List<Release> releases) {
		return parseProject(json, releases, "");
	}

	static Details parseProject(String json, List<Release> releases, String author) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<Shot> gallery = new ArrayList<>();
		JsonArray pictures = array(root, "gallery");
		if (pictures != null) {
			for (int at = 0; at < pictures.size(); at++) {
				if (!pictures.get(at).isJsonObject()) continue;
				JsonObject shot = pictures.get(at).getAsJsonObject();
				// The full-size one where there is one. What they call "url" is a
				// thumbnail three hundred and fifty pixels wide, which is narrower
				// than the panel it would be drawn in — a screenshot enlarged past
				// its own size is a screenshot nobody can read.
				String url = string(shot, "raw_url");
				if (url.isBlank()) url = string(shot, "url");
				if (!url.isBlank()) gallery.add(new Shot(url, string(shot, "title")));
			}
		}
		String slug = string(root, "slug");
		return new Details(
			string(root, "id"),
			string(root, "title"),
			string(root, "description"),
			// Kept as it was written. The markup is read where it is drawn, so that
			// nothing here has to know what a screen can do with a heading.
			string(root, "body"),
			author,
			root.has("downloads") ? root.get("downloads").getAsLong() : 0,
			strings(array(root, "game_versions")),
			strings(array(root, "loaders")),
			new Sides(
				root.has("server_side") ? string(root, "server_side") : "unknown",
				root.has("client_side") ? string(root, "client_side") : "unknown"),
			gallery,
			releases,
			slug.isBlank() ? "" : "https://modrinth.com/project/" + slug);
	}

	private static List<String> strings(JsonArray array) {
		List<String> found = new ArrayList<>();
		if (array == null) return found;
		for (int at = 0; at < array.size(); at++) {
			if (array.get(at).isJsonPrimitive()) found.add(array.get(at).getAsString());
		}
		return found;
	}

	/**
	 * Whoever publishes it, which is a thing people look for and a thing this API
	 * keeps somewhere else.
	 *
	 * A project carries a team id and not a name, so the name is one more request.
	 * It is made separately and allowed to fail: a page without an author's name
	 * is worse than one with it and much better than none at all.
	 */
	static String parseMembers(String json) {
		JsonArray members = JsonParser.parseString(json).getAsJsonArray();
		String owner = "";
		String first = "";
		for (int at = 0; at < members.size(); at++) {
			if (!members.get(at).isJsonObject()) continue;
			JsonObject member = members.get(at).getAsJsonObject();
			JsonObject user = object(member, "user");
			String name = string(user, "username");
			if (name.isBlank()) continue;
			if (first.isBlank()) first = name;
			if (string(member, "role").equalsIgnoreCase("owner") && owner.isBlank()) owner = name;
		}
		return owner.isBlank() ? first : owner;
	}

	// ------------------------------------------------------------------ asking

	/**
	 * Search, with the things that cannot run on a server left out.
	 *
	 * Filtered here rather than marked in the list: this is a browser for a
	 * server, and an entry that cannot be installed is an entry that will be
	 * pressed.
	 */
	@Override
	public Page search(String query, String loader, String gameVersion, int offset, String order)
		throws IOException {
		String json = downloads.text(URI.create(
			searchUrl(api, query, loader, gameVersion, offset, 20, order)));
		Page page = parseSearch(json);
		List<Found> kept = new ArrayList<>();
		for (Found found : page.hits()) {
			if (!found.serverless()) kept.add(found);
		}
		// The total and the number sent stay the catalogue's own, not the count
		// after filtering: they are what decide where the next page begins and
		// whether there is one, and both are true whether or not this page had
		// client mods taken out of it.
		return new Page(kept, page.total(), offset, page.hits().size());
	}

	@Override
	public String iconUrl(String project) throws IOException {
		String json = downloads.text(URI.create(api + "/project/" + project));
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		return string(root, "icon_url");
	}

	@Override
	public Details details(String project, String loader, String gameVersion) throws IOException {
		// Every version for this loader, not only the ones for this server's game
		// version. A list cut to what fits cannot answer "is there one for my
		// version yet", which is the question the tab is opened with — so the
		// whole list is shown and each row says whether it fits.
		List<Release> releases = releases(project, loader, "");
		String author = "";
		try {
			author = parseMembers(downloads.text(URI.create(api + "/project/" + project
				+ "/members")));
		} catch (IOException | RuntimeException without) {
			// A page without a name on it is worse than one with it, and much
			// better than no page.
		}
		return parseProject(downloads.text(URI.create(api + "/project/" + project)),
			releases, author);
	}

	/**
	 * Which project a file belongs to, by its SHA-1.
	 *
	 * One request, one exact answer. This is what makes a jar somebody installed
	 * by hand openable: the file is the question, so there is no matching by name
	 * and nothing to be wrong about. A file the catalogue has never seen answers
	 * 404, which is not an error worth reporting — it means "this came from
	 * somewhere else", and that is a true and ordinary thing.
	 */
	@Override
	public String projectByHash(String sha1) throws IOException {
		if (sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) return "";
		try {
			String json = downloads.text(URI.create(
				api + "/version_file/" + sha1.toLowerCase(java.util.Locale.ROOT)
				+ "?algorithm=sha1"));
			return string(JsonParser.parseString(json).getAsJsonObject(), "project_id");
		} catch (IOException unknown) {
			return "";
		}
	}

	@Override
	public List<Release> releases(String id, String loader, String gameVersion) throws IOException {
		return parseVersions(downloads.text(URI.create(versionsUrl(api, id, loader, gameVersion))));
	}
}
