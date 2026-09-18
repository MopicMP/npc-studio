package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The vanilla server, from Mojang.
 *
 * Two documents. A manifest listing every version there has ever been with a
 * link to its own file, and that file, which among much else holds the address
 * and the checksum of the server jar. So installing is: find the version, read
 * its file, download what it points at, check it is what it said.
 *
 * Old versions have no server download at all — the manifest goes back to 2009,
 * and a dedicated server was not published for the first year of it. That is
 * reported as itself rather than as a failure to download, because it is not a
 * fault and no amount of retrying changes it.
 */
public final class MojangCore implements CoreProvider {

	/** One line of the manifest. */
	public record Version(String id, String type, String url) {
	}

	/** What the version's own file says about its server jar. */
	public record ServerJar(String url, String sha1, long size) {
	}

	private final String manifest;
	private final Downloads downloads;
	private List<Version> listed;

	public MojangCore(String manifest, Downloads downloads) {
		this.manifest = manifest;
		this.downloads = downloads;
	}

	static List<Version> parseManifest(String json) {
		List<Version> versions = new ArrayList<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonArray array = root.getAsJsonArray("versions");
		if (array == null) return versions;
		for (int at = 0; at < array.size(); at++) {
			JsonObject version = array.get(at).getAsJsonObject();
			versions.add(new Version(
				version.get("id").getAsString(),
				version.has("type") ? version.get("type").getAsString() : "",
				version.has("url") ? version.get("url").getAsString() : ""));
		}
		return versions;
	}

	static Optional<ServerJar> parseServerJar(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonObject downloads = root.getAsJsonObject("downloads");
		if (downloads == null || !downloads.has("server")) return Optional.empty();
		JsonObject server = downloads.getAsJsonObject("server");
		return Optional.of(new ServerJar(
			server.get("url").getAsString(),
			server.has("sha1") ? server.get("sha1").getAsString() : null,
			server.has("size") ? server.get("size").getAsLong() : -1));
	}

	private List<Version> all() throws IOException {
		if (listed == null) listed = parseManifest(downloads.text(URI.create(manifest)));
		return listed;
	}

	/**
	 * Released versions, newest first.
	 *
	 * Snapshots are left out here rather than filtered in the screen: the list is
	 * what somebody chooses a server from, and four thousand entries in which one
	 * in six is a version that existed for a week is not a list, it is a search
	 * problem. The manifest is already newest first, so nothing is sorted.
	 */
	@Override
	public List<String> versions() throws IOException {
		List<String> released = new ArrayList<>();
		for (Version version : all()) {
			if (version.type().equals("release")) released.add(version.id());
		}
		return released;
	}

	@Override
	public String install(Path directory, String gameVersion, Downloads.Progress progress)
		throws IOException {
		Version version = all().stream()
			.filter(candidate -> candidate.id().equals(gameVersion))
			.findFirst()
			.orElseThrow(() -> new IOException("Mojang lists no version called " + gameVersion));

		ServerJar jar = parseServerJar(downloads.text(URI.create(version.url())))
			.orElseThrow(() -> new IOException(
				"Minecraft " + gameVersion + " has no server to download."
					+ " A dedicated server was not published for it."));

		String name = "server.jar";
		downloads.file(URI.create(jar.url()), directory.resolve(name),
			jar.sha1() == null ? null : Downloads.Hash.sha1(jar.sha1()), progress);
		return name;
	}
}
