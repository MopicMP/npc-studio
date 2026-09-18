package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;

/**
 * Which cores can be installed, and from where.
 *
 * The awkward fact this is shaped around: the cores do not agree on anything.
 * Vanilla is one jar. Fabric is a launcher that fetches the rest on its first
 * run. Paper patches a vanilla jar it downloads itself. Forge runs an installer
 * that writes argument files. So "install a core" cannot be one URL template,
 * and pretending it can is how a launcher ends up with a special case per core
 * buried in a method called {@code download}.
 *
 * What is data and what is code is therefore split on purpose: <b>addresses are
 * data, shapes are code</b>. A moved endpoint or a new mirror is a catalogue
 * update; a core with a genuinely new installation procedure is a new provider
 * class, and there is no way round that.
 *
 * The catalogue ships in the jar and may be replaced by a newer copy from the
 * site. Both, rather than either: only in the jar means every change to somebody
 * else's API needs a release, and only from the site means the mod does not work
 * when the site is down or does not exist yet.
 */
public final class CoreCatalog {

	/** One installable core. */
	public record Core(String id, String name, String provider, String accepts, String endpoint) {

		/** Whether this core takes plugins, mods, or only datapacks. */
		public boolean takesPlugins() {
			return accepts.equals("plugins");
		}

		public boolean takesMods() {
			return accepts.equals("mods");
		}
	}

	/**
	 * Somewhere plugins and mods can be looked for.
	 *
	 * @param api      where to ask, or blank when this one cannot be used yet
	 * @param needsKey whether it wants a key belonging to whoever is running this
	 * @param keys     the page where that person gets one, for the button that opens it
	 * @param colour   the site's own colour, so the button reads as the place it goes
	 * @param kinds    which shelves it actually has: {@code mods}, {@code plugins}, or both
	 */
	public record Source(String id, String name, int colour, String api, boolean needsKey,
			String keys, List<String> kinds) {

		public boolean usable() {
			return !api.isBlank();
		}

		/**
		 * Whether this place has that kind of thing at all.
		 *
		 * Data rather than a check on the id, because it is a fact about a site and
		 * facts about sites belong in the catalogue. It is also not decoration: a
		 * source offered for a shelf it does not stock is a search that always
		 * comes back empty, and an empty answer reads as "there is nothing like
		 * that" rather than "you are asking in the wrong shop".
		 */
		public boolean has(String kind) {
			return kinds.isEmpty() || kinds.contains(kind);
		}

		public boolean bothKinds() {
			return has("mods") && has("plugins");
		}
	}

	private static final String BUNDLED = "/assets/npc_studio/servers/cores.json";

	private final List<Core> cores;
	private final Set<String> allowed;
	private final Set<String> pictures;
	private final String browse;
	private final List<Source> sources;

	private CoreCatalog(List<Core> cores, Set<String> allowed, Set<String> pictures,
			String browse, List<Source> sources) {
		this.cores = List.copyOf(cores);
		this.allowed = Set.copyOf(allowed);
		this.pictures = Set.copyOf(pictures);
		this.browse = browse;
		this.sources = List.copyOf(sources);
	}

	/**
	 * Hosts a picture may come from, which is a longer list than the other one.
	 *
	 * Two lists because the two risks are not the same size: a jar is code and is
	 * run, a picture is bytes that go through a decoder and are drawn. So the list
	 * a jar may come from stays as short as it can be, and this one may hold the
	 * places mod authors actually put their screenshots — which, for half of
	 * Modrinth, is GitHub.
	 *
	 * It is still a list, and that matters: a description is written by a stranger,
	 * and fetching whatever address it names hands this player's address to
	 * whoever wrote it. A picture is the oldest way there is to do that.
	 */
	public Set<String> pictureHosts() {
		Set<String> both = new LinkedHashSet<>(allowed);
		both.addAll(pictures);
		return Set.copyOf(both);
	}

	/** A fetcher that may also reach the picture hosts. */
	public Downloads forPictures() {
		return new Downloads(pictureHosts());
	}

	public List<Source> sources() {
		return sources;
	}

	public Optional<Source> source(String id) {
		return sources.stream().filter(each -> each.id().equals(id)).findFirst();
	}

	/** Where plugins and mods are looked for. Data, like everything else here. */
	public String browse() {
		return browse;
	}

	public List<Core> cores() {
		return cores;
	}

	public Optional<Core> byId(String id) {
		return cores.stream().filter(core -> core.id().equals(id)).findFirst();
	}

	/** Every host this catalogue's cores need, and nothing else. */
	public Set<String> allowedHosts() {
		return allowed;
	}

	public Downloads downloads() {
		return new Downloads(allowed);
	}

	public static CoreCatalog parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<Core> cores = new ArrayList<>();
		JsonArray listed = root.getAsJsonArray("cores");
		if (listed != null) {
			for (int at = 0; at < listed.size(); at++) {
				JsonObject core = listed.get(at).getAsJsonObject();
				cores.add(new Core(
					string(core, "id"),
					string(core, "name"),
					string(core, "provider"),
					string(core, "accepts"),
					string(core, "endpoint")));
			}
		}
		Set<String> allowed = new LinkedHashSet<>();
		JsonArray hosts = root.getAsJsonArray("allowed");
		if (hosts != null) {
			for (int at = 0; at < hosts.size(); at++) {
				allowed.add(hosts.get(at).getAsString().toLowerCase(java.util.Locale.ROOT));
			}
		}
		Set<String> pictures = new LinkedHashSet<>();
		JsonArray art = root.getAsJsonArray("pictures");
		if (art != null) {
			for (int at = 0; at < art.size(); at++) {
				pictures.add(art.get(at).getAsString().toLowerCase(java.util.Locale.ROOT));
			}
		}
		List<Source> sources = new ArrayList<>();
		JsonArray listedSources = root.getAsJsonArray("sources");
		if (listedSources != null) {
			for (int at = 0; at < listedSources.size(); at++) {
				JsonObject source = listedSources.get(at).getAsJsonObject();
				int colour = 0xFFFFFFFF;
				try {
					colour = 0xFF000000 | Integer.parseInt(string(source, "colour"), 16);
				} catch (NumberFormatException notAColour) {
					// A catalogue with a typo in a colour still lists the site.
				}
				List<String> kinds = new ArrayList<>();
				JsonArray shelves = source.getAsJsonArray("kinds");
				if (shelves != null) {
					for (int each = 0; each < shelves.size(); each++) {
						kinds.add(shelves.get(each).getAsString());
					}
				}
				sources.add(new Source(
					string(source, "id"),
					string(source, "name"),
					colour,
					string(source, "api"),
					source.has("needsKey") && source.get("needsKey").getAsBoolean(),
					string(source, "keys"),
					List.copyOf(kinds)));
			}
		}
		return new CoreCatalog(cores, allowed, pictures, string(root, "browse"), sources);
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
	}

	/** The copy inside the jar. Always present, and the answer when nothing else is. */
	public static CoreCatalog bundled() {
		try (InputStream in = CoreCatalog.class.getResourceAsStream(BUNDLED)) {
			if (in == null) throw new IOException("no " + BUNDLED + " in the jar");
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (Exception missing) {
			// A mod that cannot read its own resource is broken, but it is not a
			// reason to stop the game: everything else still works, and the server
			// screen can say that no cores are offered.
			NpcStudio.LOGGER.error("Could not read the bundled core catalogue: {}",
				missing.toString());
			return new CoreCatalog(List.of(), Set.of(), Set.of(), "", List.of());
		}
	}

	/**
	 * The updated copy if there is a usable one, and the bundled copy otherwise.
	 *
	 * A downloaded catalogue that will not parse is ignored rather than fatal —
	 * the fallback is a file we shipped and know is good, so the worst case is
	 * being out of date rather than being stopped.
	 */
	public static CoreCatalog load(Path updated) {
		if (updated != null && Files.isRegularFile(updated)) {
			try {
				return parse(Files.readString(updated, StandardCharsets.UTF_8));
			} catch (Exception broken) {
				NpcStudio.LOGGER.warn("Ignoring the updated core catalogue at {}: {}",
					updated, broken.toString());
			}
		}
		return bundled();
	}
}
