package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The world types this particular server knows, mods included.
 *
 * {@code level-type} takes the id of a world preset, and the presets are data:
 * the game ships seven and a mod ships however many it likes, as files inside its
 * own jar. So the list of types a server can make is a fact about that server's
 * mods folder, and a fixed list of the vanilla seven is a list that is wrong on
 * every modded server there is — which is exactly what somebody found after
 * installing Biomes O' Plenty and looking for it.
 *
 * <p>They are read from where the server will read them: every jar in
 * {@code mods} and {@code plugins}, and every datapack in the world. A preset is
 * a file at {@code data/<namespace>/worldgen/world_preset/<name>.json}, and the
 * path is the whole of what we need — nothing inside the file is parsed, because
 * the id is the only thing that goes into {@code server.properties} and the
 * server is the one that has to understand the rest.
 *
 * <p>This looks at jars from the internet. So: names only, no reading of entry
 * contents, a cap on how many entries are walked, and a jar that will not open is
 * skipped rather than failing the list.
 */
public final class WorldTypes {

	private WorldTypes() {
	}

	/** The seven the game itself ships, in the order the window offers them. */
	public static final List<String> VANILLA = List.of(
		"minecraft:normal", "minecraft:flat", "minecraft:large_biomes",
		"minecraft:amplified", "minecraft:single_biome_surface",
		"minecraft:debug_all_block_states");

	/** Where in a jar a preset lives. */
	private static final String INSIDE = "worldgen/world_preset/";

	/** How many entries of one jar are worth walking before calling it enough. */
	private static final int MOST_ENTRIES = 60000;

	/** How many jars are worth opening. A mods folder, not a mirror of one. */
	private static final int MOST_JARS = 500;

	/**
	 * Every type this server could be told to use, the vanilla ones first.
	 *
	 * Reads folders and jars, so never on the thread that draws.
	 */
	public static List<String> of(ManagedServer server) {
		Set<String> found = new LinkedHashSet<>(VANILLA);
		int opened = 0;
		for (String folder : List.of("mods", "plugins")) {
			opened += fromJars(server.path().resolve(folder), found, MOST_JARS - opened);
		}
		fromDatapacks(server.path().resolve(Worlds.currentName(server)).resolve("datapacks"),
			found, MOST_JARS - opened);
		return List.copyOf(found);
	}

	private static int fromJars(Path folder, Set<String> found, int allowance) {
		if (!Files.isDirectory(folder) || allowance <= 0) return 0;
		int opened = 0;
		try (var files = Files.list(folder)) {
			for (Path each : files.filter(Files::isRegularFile).toList()) {
				String name = each.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				if (!name.endsWith(".jar")) continue;
				if (++opened > allowance) break;
				inside(each, found);
			}
		} catch (IOException unreadable) {
			// A folder that will not list is a folder with no presets in it, as far
			// as a list of world types is concerned.
		}
		return opened;
	}

	/**
	 * Datapacks, which are a zip or a folder and are read as both.
	 *
	 * A datapack in the world is where a preset most often comes from on a server
	 * with no mods at all — including, before long, from us.
	 */
	private static void fromDatapacks(Path folder, Set<String> found, int allowance) {
		if (!Files.isDirectory(folder) || allowance <= 0) return;
		int opened = 0;
		try (var packs = Files.list(folder)) {
			for (Path each : packs.toList()) {
				if (++opened > allowance) break;
				if (Files.isRegularFile(each)
					&& each.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
						.endsWith(".zip")) {
					inside(each, found);
				} else if (Files.isDirectory(each)) {
					unpacked(each.resolve("data"), found);
				}
			}
		} catch (IOException unreadable) {
			// As above.
		}
	}

	private static void unpacked(Path data, Set<String> found) {
		if (!Files.isDirectory(data)) return;
		try (var spaces = Files.list(data)) {
			for (Path space : spaces.filter(Files::isDirectory).toList()) {
				Path presets = space.resolve("worldgen").resolve("world_preset");
				if (!Files.isDirectory(presets)) continue;
				try (var files = Files.list(presets)) {
					for (Path each : files.filter(Files::isRegularFile).toList()) {
						String id = id(space.getFileName().toString(),
							each.getFileName().toString());
						if (id != null) found.add(id);
					}
				}
			}
		} catch (IOException unreadable) {
			// As above.
		}
	}

	private static void inside(Path jar, Set<String> found) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			int seen = 0;
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				if (++seen > MOST_ENTRIES) break;
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				String id = fromPath(entry.getName());
				if (id != null) found.add(id);
			}
		} catch (IOException | RuntimeException unreadable) {
			// A jar that will not open is somebody else's problem to report: the
			// server will say so far more clearly than a list of world types can.
		}
	}

	/** The id in {@code data/<ns>/worldgen/world_preset/<name>.json}, or null. */
	static String fromPath(String path) {
		String each = path.replace('\\', '/');
		if (!each.startsWith("data/")) return null;
		int space = each.indexOf('/', "data/".length());
		if (space < 0) return null;
		String namespace = each.substring("data/".length(), space);
		String rest = each.substring(space + 1);
		if (!rest.startsWith(INSIDE)) return null;
		return id(namespace, rest.substring(INSIDE.length()));
	}

	private static String id(String namespace, String file) {
		if (!file.endsWith(".json")) return null;
		String name = file.substring(0, file.length() - ".json".length());
		// Nested folders under world_preset are legal and rare; the id keeps the
		// slashes, which is what the server expects to be given.
		if (!namespace.matches("[a-z0-9_.-]+") || !name.matches("[a-z0-9_./-]+")) return null;
		return namespace + ":" + name;
	}

	/** The ones that are not the game's own, for a window that wants to say so. */
	public static List<String> added(List<String> all) {
		List<String> added = new ArrayList<>(all);
		added.removeAll(VANILLA);
		return List.copyOf(added);
	}
}
