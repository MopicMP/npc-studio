package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A Paper server.
 *
 * Two things about Paper that are not true of the other cores.
 *
 * A release is a game version <em>and</em> a build number, and the builds of one
 * version are not equally finished: they are marked {@code STABLE} once they are
 * considered ready and something else before that. So the newest build is the
 * wrong default and the newest ready one is the right one — somebody who wants
 * an experimental build knows they do.
 *
 * And their version list is grouped: an object of version groups, each holding
 * its own versions, both newest first. It also lists release candidates and
 * pre-releases, which are not what somebody creating their first server means by
 * a version.
 *
 * <b>The address here is the second one this code has used.</b> The previous API
 * was retired and now answers 410 to everything, which is worth remembering for
 * what it says about the split between data and code: the host moving would have
 * been a catalogue update, but the shape of every answer changed with it, and
 * that could only be a new class. Addresses being data buys less than it looks
 * like it does.
 */
public final class PaperCore implements CoreProvider {

	/** One build: its number, how finished it is, and the file it produced. */
	public record Build(int number, String channel, String file, String url, String sha256) {
		public boolean ready() {
			return channel.equalsIgnoreCase("STABLE");
		}
	}

	/** A plain released version: digits and dots, nothing else. */
	private static final Pattern RELEASE = Pattern.compile("\\d+(\\.\\d+)*");

	private final String api;
	private final Downloads downloads;

	public PaperCore(String api, Downloads downloads) {
		this.api = api.endsWith("/") ? api.substring(0, api.length() - 1) : api;
		this.downloads = downloads;
	}

	/**
	 * The versions worth offering, newest first.
	 *
	 * The document is an object of groups rather than a list, and both it and
	 * each group are already in the order we want, so nothing is sorted — the
	 * order comes from the file, which is the only place it is correct. Release
	 * candidates and pre-releases are dropped: they are versions of Minecraft
	 * that existed for a fortnight.
	 */
	static List<String> parseVersions(String json) {
		List<String> versions = new ArrayList<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonObject groups = root.getAsJsonObject("versions");
		if (groups == null) return versions;
		for (String group : groups.keySet()) {
			JsonElement listed = groups.get(group);
			if (!listed.isJsonArray()) continue;
			JsonArray array = listed.getAsJsonArray();
			for (int at = 0; at < array.size(); at++) {
				String version = array.get(at).getAsString();
				if (RELEASE.matcher(version).matches()) versions.add(version);
			}
		}
		return versions;
	}

	/** Every build of one version. The document is a plain array, newest first. */
	static List<Build> parseBuilds(String json) {
		List<Build> builds = new ArrayList<>();
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		for (int at = 0; at < array.size(); at++) {
			JsonObject build = array.get(at).getAsJsonObject();
			JsonObject downloads = build.getAsJsonObject("downloads");
			if (downloads == null) continue;
			// The key names what the file is for: there is a server download and
			// there can be others.
			JsonObject server = downloads.getAsJsonObject("server:default");
			if (server == null) continue;
			String sha256 = null;
			JsonObject checksums = server.getAsJsonObject("checksums");
			if (checksums != null && checksums.has("sha256")) {
				sha256 = checksums.get("sha256").getAsString();
			}
			builds.add(new Build(
				build.get("id").getAsInt(),
				build.has("channel") ? build.get("channel").getAsString() : "",
				server.get("name").getAsString(),
				server.has("url") ? server.get("url").getAsString() : "",
				sha256));
		}
		return builds;
	}

	/** The newest finished build, or the newest of any kind if none is finished. */
	static Build best(List<Build> builds) throws IOException {
		Build newestReady = null;
		Build newest = null;
		for (Build build : builds) {
			if (newest == null || build.number() > newest.number()) newest = build;
			if (build.ready() && (newestReady == null || build.number() > newestReady.number())) {
				newestReady = build;
			}
		}
		if (newestReady != null) return newestReady;
		if (newest != null) return newest;
		throw new IOException("Paper lists no builds for that version");
	}

	@Override
	public List<String> versions() throws IOException {
		return parseVersions(downloads.text(URI.create(api + "/projects/paper")));
	}

	@Override
	public String install(Path directory, String gameVersion, Downloads.Progress progress)
		throws IOException {
		String listed = downloads.text(
			URI.create(api + "/projects/paper/versions/" + gameVersion + "/builds"));
		Build build = best(parseBuilds(listed));
		if (build.url().isBlank()) {
			throw new IOException("Paper build " + build.number() + " of " + gameVersion
				+ " lists no address to download from");
		}

		// Kept under its published name rather than renamed to something tidy: the
		// name carries the version and the build, and that is the first thing
		// anybody looking at a server folder wants to know.
		String name = build.file();
		downloads.file(URI.create(build.url()), directory.resolve(name),
			build.sha256() == null ? null : Downloads.Hash.sha256(build.sha256()), progress);
		return name;
	}

	@Override
	public boolean fetchesOnFirstStart() {
		return true;
	}
}
