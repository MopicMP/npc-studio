package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The saves a server has, and which one it is playing.
 *
 * Two words that get confused and must not be: a <b>world</b> is a whole save,
 * and a <b>dimension</b> is one part of one. Getting this wrong is not a naming
 * quibble, it changes what the screen shows — because the two cores this manages
 * disagree about where the parts live.
 *
 * <ul>
 * <li>Vanilla and Fabric keep everything in one folder: the overworld at the top,
 * {@code DIM-1} for the nether, {@code DIM1} for the end, and
 * {@code dimensions/<mod>/<name>} for anything a mod added;</li>
 * <li>Bukkit, Spigot and Paper split them into folders beside each other:
 * {@code world}, {@code world_nether}, {@code world_the_end}. Three folders, one
 * world — and listing them as three saves would be telling somebody they have
 * three worlds when they have one.</li>
 * </ul>
 *
 * What makes a folder a save is {@code level.dat} and nothing else. Not its name,
 * because people rename them; not being listed anywhere, because a folder copied
 * in by hand is a real save the moment it is there.
 *
 * Nothing here writes. Changing which world is played is one line in
 * {@code server.properties} and a restart — see the note on {@link #currentName}
 * — and pretending otherwise would be pretending a world can be swapped under a
 * running server.
 */
public final class Worlds {

	private Worlds() {
	}

	/**
	 * One save.
	 *
	 * @param folders    every folder it occupies: one on Fabric, up to three on Paper
	 * @param dimensions what is inside it, however the core arranges them
	 * @param size       what it takes on disk, all folders together
	 * @param played     when anything in it was last written
	 */
	public record World(String name, Path folder, List<Path> folders,
			List<Dimension> dimensions, long size, long played) {

		public boolean is(String other) {
			return name.equalsIgnoreCase(other);
		}
	}

	/**
	 * One part of a save, wherever the core happens to keep it.
	 *
	 * The name is the dimension's own id — {@code minecraft:the_nether},
	 * {@code mymod:moon} — and not the folder it happens to sit in. Folders are
	 * where a core decided to put a thing; the id is what the thing is, and it is
	 * the same id whether the chunks are in {@code DIM-1}, in {@code world_nether}
	 * or under {@code dimensions/minecraft/the_nether}. Two of those turning up in
	 * one save is one dimension found twice, not two dimensions — which is exactly
	 * what a list showed when it named folders instead.
	 */
	public record Dimension(String name, Path folder, long size) {

		/** Whether this is the one every world has and no world can be without. */
		public boolean overworld() {
			return OVERWORLD.equals(name);
		}
	}

	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";
	public static final String END = "minecraft:the_end";

	/**
	 * The folders that hold a dimension's chunks, and only those.
	 *
	 * The unit that can be swapped for another. Everything else in a save —
	 * {@code level.dat}, the players, the maps, the scoreboard — belongs to the
	 * world rather than to one of its dimensions, and replacing a nether must not
	 * touch any of it.
	 */
	private static final List<String> CHUNKS = List.of("region", "entities", "poi");

	/** How far down a world folder this will look before calling it enough. */
	private static final int DEPTH = 6;

	/** The name of the world the server will load on its next start. */
	public static String currentName(ManagedServer server) {
		try {
			if (Files.isRegularFile(server.propertiesPath())) {
				String named = PropertiesFile.read(server.propertiesPath())
					.getOr("level-name", "world").trim();
				if (!named.isEmpty()) return named;
			}
		} catch (IOException unreadable) {
			// A server whose properties cannot be read still has folders on disk,
			// and showing them with none marked is better than showing none.
		}
		return "world";
	}

	/**
	 * Every save in a server's folder, largest first.
	 *
	 * Ordered by size rather than by name because that is the question a list of
	 * saves is scrolled with — which of these is the big one — and because the
	 * current world is marked rather than sorted to the top: moving a row when it
	 * becomes current makes the list jump under the press that made it current.
	 */
	public static List<World> found(ManagedServer server) {
		Path root = server.path();
		if (!Files.isDirectory(root)) return List.of();

		// Everything with a level.dat, by folder name.
		Map<String, Path> saves = new LinkedHashMap<>();
		try (var children = Files.list(root)) {
			children.filter(Files::isDirectory)
				.filter(each -> Files.isRegularFile(each.resolve("level.dat")))
				.forEach(each -> saves.put(each.getFileName().toString(), each));
		} catch (IOException unreadable) {
			return List.of();
		}

		// The Paper arrangement: a folder whose name is another folder's name with
		// a suffix is a part of that one, not a save of its own.
		List<World> worlds = new ArrayList<>();
		java.util.Set<String> taken = new java.util.HashSet<>();
		for (var entry : saves.entrySet()) {
			String name = entry.getKey();
			if (taken.contains(name)) continue;
			if (parentOf(name, saves.keySet()) != null) continue;

			List<Path> folders = new ArrayList<>();
			List<Dimension> dimensions = new ArrayList<>();
			folders.add(entry.getValue());
			dimensions.addAll(inside(entry.getValue()));

			for (String suffix : SUFFIXES) {
				Path beside = saves.get(name + suffix);
				if (beside == null) continue;
				taken.add(name + suffix);
				folders.add(beside);
				dimensions.add(new Dimension(suffix.equals("_nether") ? NETHER : END,
					beside, size(beside)));
			}
			dimensions = once(dimensions);

			long size = 0;
			long played = 0;
			for (Path folder : folders) {
				size += size(folder);
				played = Math.max(played, newest(folder));
			}
			worlds.add(new World(name, entry.getValue(), List.copyOf(folders),
				List.copyOf(dimensions), size, played));
		}
		// The one being played first, then the big ones. Sorting by size alone was
		// the earlier rule, on the grounds that moving a row when it becomes
		// current makes the list jump under the press that made it current — true,
		// and it lost the argument: the world somebody is looking for in a list of
		// worlds is almost always the one their server is running.
		String playing = currentName(server);
		worlds.sort(java.util.Comparator.comparing((World each) -> !each.is(playing))
			.thenComparing(java.util.Comparator.comparingLong(World::size).reversed()));
		return List.copyOf(worlds);
	}

	/** The suffixes Bukkit and its descendants put on the other two dimensions. */
	private static final List<String> SUFFIXES = List.of("_nether", "_the_end");

	private static String parentOf(String name, java.util.Set<String> saves) {
		for (String suffix : SUFFIXES) {
			if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
				String parent = name.substring(0, name.length() - suffix.length());
				if (saves.contains(parent)) return parent;
			}
		}
		return null;
	}

	/**
	 * The dimensions kept inside one folder, as vanilla and Fabric keep them.
	 *
	 * The two numbered ones have been called {@code DIM-1} and {@code DIM1} since
	 * before there was a word for a dimension, and anything a mod adds lives under
	 * {@code dimensions/<namespace>/<name>}. Both are read, and neither is
	 * assumed: a save with neither is an overworld and nothing else, which is the
	 * ordinary case for a fresh server.
	 *
	 * The overworld comes first when it is where vanilla puts it — in the world
	 * folder itself — and is left out when it is not. It was added unconditionally
	 * for one round, on the reasoning that every world has one; a save whose
	 * chunks all live under {@code dimensions/} then showed «the overworld, 0 B»
	 * above the real {@code minecraft:overworld} it already had. A row that says a
	 * dimension is empty when it is not is worse than a row that is missing, and
	 * the world folder having no {@code region} in it is the whole evidence
	 * available: the overworld is elsewhere.
	 */
	static List<Dimension> inside(Path folder) {
		List<Dimension> found = new ArrayList<>();
		if (Files.isDirectory(folder.resolve("region"))) {
			found.add(new Dimension(OVERWORLD, folder, chunkSize(folder)));
		}
		if (Files.isDirectory(folder.resolve("DIM-1"))) {
			found.add(new Dimension(NETHER, folder.resolve("DIM-1"),
				size(folder.resolve("DIM-1"))));
		}
		if (Files.isDirectory(folder.resolve("DIM1"))) {
			found.add(new Dimension(END, folder.resolve("DIM1"),
				size(folder.resolve("DIM1"))));
		}
		Path added = folder.resolve("dimensions");
		if (Files.isDirectory(added)) {
			try (var spaces = Files.list(added)) {
				for (Path space : spaces.filter(Files::isDirectory).toList()) {
					try (var names = Files.list(space)) {
						for (Path one : names.filter(Files::isDirectory).toList()) {
							found.add(new Dimension(
								space.getFileName() + ":" + one.getFileName(), one, size(one)));
						}
					}
				}
			} catch (IOException unreadable) {
				// A dimension folder that cannot be listed is left out rather than
				// turned into a failure: the save is still a save.
			}
		}
		return found;
	}

	/**
	 * One entry per dimension, keeping the first place it was found.
	 *
	 * A save can hold the same dimension twice over — the nether in {@code DIM-1}
	 * because that is where it has always been, and again under
	 * {@code dimensions/minecraft/the_nether} because a mod moved to the newer
	 * arrangement. One of them is where the chunks are and the other is a leftover,
	 * and showing both invites somebody to replace the empty one and wonder why
	 * nothing changed. The first found wins, which is the vanilla place.
	 */
	private static List<Dimension> once(List<Dimension> all) {
		Map<String, Dimension> byName = new LinkedHashMap<>();
		for (Dimension each : all) byName.putIfAbsent(each.name(), each);
		return List.copyOf(byName.values());
	}

	/**
	 * What the chunks in a folder take, which is not what the folder takes.
	 *
	 * Asked of the overworld, because the overworld is the world folder: counting
	 * the whole thing would count the nether, the players and the maps as part of
	 * the overworld, and then the numbers in the list would not add up to the one
	 * above them.
	 */
	private static long chunkSize(Path folder) {
		long total = 0;
		for (String each : CHUNKS) total += size(folder.resolve(each));
		return total;
	}

	/**
	 * Where a dimension's chunks actually sit, which is a folder deeper on Paper.
	 *
	 * Vanilla writes them in the dimension folder itself; Bukkit and its
	 * descendants give the nether a save folder of its own and then put the chunks
	 * in {@code DIM-1} inside it, which is the same layout one level down. Both are
	 * looked for rather than worked out from the core, because a folder somebody
	 * copied in from elsewhere does not know which core made it.
	 */
	public static Path chunks(Path dimension) {
		if (Files.isDirectory(dimension.resolve("region"))) return dimension;
		for (String numbered : List.of("DIM-1", "DIM1")) {
			Path deeper = dimension.resolve(numbered);
			if (Files.isDirectory(deeper.resolve("region"))) return deeper;
		}
		return dimension;
	}

	/**
	 * Put somebody else's chunks in place of a dimension's own.
	 *
	 * Only the chunks: {@code region}, {@code entities} and {@code poi}, plus the
	 * dimension's own {@code data} when the target is not the world folder. What
	 * stays is everything that belongs to the world rather than to this part of it
	 * — {@code level.dat}, the players, the maps — because a world whose nether
	 * was replaced is the same world, with a different nether. Swapping the whole
	 * folder would take the seed and the spawn with it, and the overworld could not
	 * be done that way at all.
	 *
	 * The source has to hold a {@code region} folder. That is not a formality: a
	 * folder without one is not a dimension, and copying it would leave a dimension
	 * that loads and is empty — which looks like our doing, and is.
	 */
	public static void replace(Dimension dimension, Path source) throws IOException {
		if (source == null || !Files.isDirectory(source)) {
			throw new IOException("There is no folder there.");
		}
		if (!Files.isDirectory(dimension.folder())) {
			// The list was read a while ago and the folder has gone since. Creating
			// it again would leave a dimension nothing owns.
			throw new IOException("That dimension is no longer there.");
		}
		if (!Files.isDirectory(source.resolve("region"))) {
			throw new IOException("That folder holds no chunks. A dimension is a folder"
				+ " with a 'region' folder in it — the world folder itself for an"
				+ " overworld, DIM-1 for a nether, DIM1 for an end, or one of the"
				+ " folders under 'dimensions' for anything a mod added.");
		}

		Path target = chunks(dimension.folder()).toAbsolutePath().normalize();
		Path from = source.toAbsolutePath().normalize();
		if (target.equals(from) || from.startsWith(target) || target.startsWith(from)) {
			throw new IOException("That folder is the one being replaced, or holds it."
				+ " Copying it onto itself would delete it halfway through.");
		}

		boolean ownFolder = !Files.isRegularFile(target.resolve("level.dat"));
		List<String> parts = new ArrayList<>(CHUNKS);
		// A dimension's data folder is raids and nothing else; a world's holds the
		// maps and the scoreboard, and that is not this dimension's to replace.
		if (ownFolder) parts.add("data");

		Files.createDirectories(target);
		for (String part : parts) {
			deleteTree(target.resolve(part));
			Path each = from.resolve(part);
			if (Files.isDirectory(each)) copyTree(each, target.resolve(part));
		}
	}

	/**
	 * Throw a dimension's chunks away so the server makes it again.
	 *
	 * There is no way to make a dimension the way a world is made — a dimension is
	 * not a thing anybody creates, it is a thing the server generates the first
	 * time somebody walks into it. So making one again is deleting the chunks and
	 * letting that happen: same seed, same generator, same mods, a fresh nether.
	 *
	 * The same three folders as a replacement, for the same reason: the seed, the
	 * spawn and the players are the world's, not this dimension's. Deleting the
	 * whole folder would take them, and the overworld could not be done at all.
	 */
	public static void reset(Dimension dimension) throws IOException {
		if (!Files.isDirectory(dimension.folder())) {
			throw new IOException("That dimension is no longer there.");
		}
		Path target = chunks(dimension.folder()).toAbsolutePath().normalize();
		boolean ownFolder = !Files.isRegularFile(target.resolve("level.dat"));
		List<String> parts = new ArrayList<>(CHUNKS);
		if (ownFolder) parts.add("data");
		for (String part : parts) deleteTree(target.resolve(part));
	}

	/**
	 * Delete a whole save, every folder of it.
	 *
	 * All of them, because on Paper a world is three folders and deleting one of
	 * them leaves a nether with no world attached — which the list would then show
	 * as a save of its own, and which the server would recreate around on its next
	 * start. Either the world goes or it stays.
	 *
	 * Nothing here checks whether it is the world being played: that is a decision,
	 * and decisions belong in front of the person making them, not in the method
	 * that carries them out.
	 */
	public static void drop(ManagedServer server, World world) throws IOException {
		Path root = server.path().toAbsolutePath().normalize();
		for (Path folder : world.folders()) {
			Path each = folder.toAbsolutePath().normalize();
			// The same rule as everywhere a name from outside becomes a path: it
			// came from a folder listing, and it still gets checked.
			if (!each.startsWith(root) || each.equals(root)) {
				throw new IOException("That folder is not inside the server: " + each);
			}
			deleteTree(each);
		}
	}

	private static void deleteTree(Path folder) throws IOException {
		if (!Files.exists(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
		try (var walk = Files.walk(folder)) {
			for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(each);
			}
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (var walk = Files.walk(from)) {
			for (Path each : walk.toList()) {
				Path landing = to.resolve(from.relativize(each).toString());
				if (Files.isDirectory(each, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
					Files.createDirectories(landing);
				} else if (Files.isRegularFile(each, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
					Files.createDirectories(landing.getParent());
					Files.copy(each, landing,
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				// Anything else — a link, a device, whatever a strange archiver left
				// behind — is left where it is rather than followed.
			}
		}
	}

	/** What a folder takes on disk, following nothing it should not. */
	public static long size(Path folder) {
		if (!Files.isDirectory(folder)) return 0;
		long[] total = {0};
		try (var walk = Files.walk(folder, DEPTH)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				try {
					total[0] += Files.size(file);
				} catch (IOException gone) {
					// A file that vanished between the listing and the question is
					// a running server writing, which is normal here.
				}
			});
		} catch (IOException unreadable) {
			return total[0];
		}
		return total[0];
	}

	private static long newest(Path folder) {
		long[] latest = {0};
		try (var walk = Files.walk(folder, 2)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				try {
					latest[0] = Math.max(latest[0], Files.getLastModifiedTime(file).toMillis());
				} catch (IOException gone) {
					// See above.
				}
			});
		} catch (IOException unreadable) {
			return latest[0];
		}
		return latest[0];
	}

	/**
	 * Whether a name may be a world folder at all.
	 *
	 * Checked because it goes into {@code server.properties} and then into a path
	 * the server resolves — a value from a text field that becomes a folder name
	 * is the shape the security notes are about. Letters, digits, dash, underscore
	 * and dot, and never a dot on its own or a name starting with one.
	 */
	public static boolean validName(String name) {
		if (name == null) return false;
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > 64) return false;
		if (trimmed.startsWith(".")) return false;
		return trimmed.matches("[A-Za-z0-9._\\-]+");
	}
}
