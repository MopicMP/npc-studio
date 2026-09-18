package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/**
 * A Fabric server.
 *
 * Three version numbers rather than one — the game, the loader, and the
 * installer — and the person creating a server has an opinion about exactly one
 * of them. So the other two are the newest stable of each, chosen here, and the
 * screen asks only which Minecraft.
 *
 * What arrives is a launcher, not a server: on its first start it fetches the
 * vanilla server and the loader's libraries itself. That is why
 * {@link #fetchesOnFirstStart()} is true, and it is worth saying in the
 * interface — install on the train, start at home, and the failure mentions
 * neither the network nor Fabric.
 */
public final class FabricCore implements CoreProvider {

	/** A line from any of Fabric's three lists: they share this shape. */
	public record Release(String version, boolean stable) {
	}

	private final String meta;
	private final Downloads downloads;

	public FabricCore(String meta, Downloads downloads) {
		this.meta = meta.endsWith("/") ? meta.substring(0, meta.length() - 1) : meta;
		this.downloads = downloads;
	}

	static List<Release> parseReleases(String json) {
		List<Release> releases = new ArrayList<>();
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		for (int at = 0; at < array.size(); at++) {
			var entry = array.get(at).getAsJsonObject();
			releases.add(new Release(
				entry.get("version").getAsString(),
				entry.has("stable") && entry.get("stable").getAsBoolean()));
		}
		return releases;
	}

	/** The newest stable one, or the newest of any kind if none is marked stable. */
	static String newestStable(List<Release> releases) throws IOException {
		for (Release release : releases) {
			if (release.stable()) return release.version();
		}
		if (!releases.isEmpty()) return releases.getFirst().version();
		throw new IOException("Fabric offered an empty version list");
	}

	@Override
	public List<String> versions() throws IOException {
		List<String> stable = new ArrayList<>();
		for (Release release : parseReleases(downloads.text(URI.create(meta + "/versions/game")))) {
			if (release.stable()) stable.add(release.version());
		}
		return stable;
	}

	/**
	 * The address of the ready-made server launcher.
	 *
	 * Built here rather than kept in the catalogue because it is a shape, not an
	 * address: four values in a fixed order. The host it hangs off is the
	 * catalogue's.
	 */
	String serverJarUrl(String gameVersion, String loader, String installer) {
		return meta + "/versions/loader/" + gameVersion + "/" + loader + "/" + installer
			+ "/server/jar";
	}

	@Override
	public String install(Path directory, String gameVersion, Downloads.Progress progress)
		throws IOException {
		String loader = newestStable(
			parseReleases(downloads.text(URI.create(meta + "/versions/loader"))));
		String installer = newestStable(
			parseReleases(downloads.text(URI.create(meta + "/versions/installer"))));

		String name = "fabric-server-launch.jar";
		// No checksum is published for this one. Said out loud rather than left as
		// a null nobody notices: it is served over HTTPS from a host on the list,
		// which is the whole of the assurance here.
		downloads.file(URI.create(serverJarUrl(gameVersion, loader, installer)),
			directory.resolve(name), null, progress);
		return name;
	}

	@Override
	public boolean fetchesOnFirstStart() {
		return true;
	}
}
