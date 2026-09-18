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
 * Which configuration files a server has, and whose they are.
 *
 * <b>The hard part is not reading them, it is knowing what belongs to what.</b>
 * A Bukkit plugin keeps its own folder, {@code plugins/<name from plugin.yml>},
 * and that is close to a guarantee. A Fabric mod drops a file into
 * {@code config/} named after itself by convention and by nothing stronger —
 * {@code lithium.properties} for a mod whose id is {@code lithium}, but also
 * {@code ferritecore.mixin.properties}, and plenty that agree with neither.
 *
 * <p>So the matching is by prefix, and what does not match is not hidden: it goes
 * under "the rest", where somebody can find it. A file shown in the wrong group
 * is worse than a file shown in no group, because the wrong group is a claim.
 *
 * <p>The server's own {@code server.properties} is deliberately not here. It has
 * its own tab, with names and explanations written for it, and offering it twice
 * in two different editors is offering two answers to one question.
 */
public final class Configs {

	private Configs() {
	}

	/**
	 * One file that can be opened.
	 *
	 * @param name  what to call it in the list, which is its path under the server
	 * @param owner the addon it belongs to, or blank when nothing claims it
	 */
	public record Entry(String name, Path path, String owner, long size) {
	}

	/** Files under one heading: a plugin, a mod, the core itself, or the rest. */
	public record Group(String name, String id, List<Entry> files) {
	}

	/** Neither a hundred plugins nor a mod with a thousand files is a list. */
	private static final int MOST_FILES = 400;

	/** Deep enough for {@code plugins/X/subfolder/thing.yml} and no deeper. */
	private static final int DEEP = 3;

	/**
	 * Every configuration file on this server, grouped by what owns it.
	 *
	 * The core's own files come first — {@code bukkit.yml}, {@code spigot.yml},
	 * {@code config/paper-*.yml} — because they are the ones somebody edits on the
	 * first day, and because they are the ones that are always there.
	 */
	public static List<Group> of(ManagedServer server, List<Addon> addons) {
		Path root = server.path();
		List<Entry> all = new ArrayList<>();
		gather(root.resolve("plugins"), root, all);
		gather(root.resolve("config"), root, all);
		for (String name : List.of("bukkit.yml", "spigot.yml", "commands.yml", "permissions.yml",
				"help.yml", "wepif.yml", "paper.yml", "pufferfish.yml", "purpur.yml")) {
			Path file = root.resolve(name);
			if (Files.isRegularFile(file)) all.add(entry(root, file));
		}

		// Owners in the order they beat one another: a plugin's own folder is
		// certain, a mod's file name is a convention.
		Map<String, List<Entry>> byOwner = new LinkedHashMap<>();
		List<Entry> core = new ArrayList<>();
		List<Entry> rest = new ArrayList<>();
		for (Entry each : all) {
			String owner = ownerOf(each, addons);
			if (owner.isEmpty()) {
				(coreFile(each) ? core : rest).add(each);
			} else {
				byOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(each);
			}
		}

		List<Group> groups = new ArrayList<>();
		if (!core.isEmpty()) groups.add(new Group("", "core", List.copyOf(core)));
		for (Addon addon : addons) {
			List<Entry> mine = byOwner.remove(addon.file());
			// Listed even with nothing under it, and this is the important part.
			// A plugin writes its folder the first time it runs, so a plugin
			// installed a minute ago has no settings on disk at all — and a list
			// that leaves it out entirely looks exactly like a list that has lost
			// it. Named, with nothing under it, is the true picture and the one
			// that says what to do about it.
			if (addon.kind() != Addon.Kind.DATAPACK) {
				groups.add(new Group(addon.label(), addon.file(),
					mine == null ? List.of() : List.copyOf(mine)));
			} else if (mine != null) {
				groups.add(new Group(addon.label(), addon.file(), List.copyOf(mine)));
			}
		}
		for (var left : byOwner.entrySet()) {
			groups.add(new Group(left.getKey(), left.getKey(), List.copyOf(left.getValue())));
		}
		if (!rest.isEmpty()) groups.add(new Group("", "rest", List.copyOf(rest)));
		return List.copyOf(groups);
	}

	/** Whether this is the core's own rather than something's that was added. */
	private static boolean coreFile(Entry entry) {
		String name = entry.name();
		return !name.startsWith("plugins/") && !name.startsWith("config/")
			|| name.startsWith("config/paper-");
	}

	/**
	 * Which addon a file belongs to.
	 *
	 * A plugin folder wins outright — {@code plugins/Chunky/config.yml} is
	 * Chunky's, whatever else is called what. Under {@code config/} it is the file
	 * name against the mod's id, longest id first so that a mod called
	 * {@code sodium-extra} is not handed {@code sodium}'s file.
	 */
	private static String ownerOf(Entry entry, List<Addon> addons) {
		String name = entry.name();
		if (name.startsWith("plugins/")) {
			int slash = name.indexOf('/', "plugins/".length());
			if (slash < 0) return "";
			String folder = name.substring("plugins/".length(), slash);
			for (Addon addon : addons) {
				if (addon.kind() != Addon.Kind.PLUGIN) continue;
				if (addon.name().equalsIgnoreCase(folder) || addon.id().equalsIgnoreCase(folder)) {
					return addon.file();
				}
			}
			return folder;
		}
		if (!name.startsWith("config/")) return "";
		String bare = name.substring("config/".length()).toLowerCase(Locale.ROOT);
		int dot = bare.indexOf('.');
		String stem = dot < 0 ? bare : bare.substring(0, dot);
		String best = "";
		int longest = 0;
		for (Addon addon : addons) {
			String id = addon.id().toLowerCase(Locale.ROOT);
			if (id.isBlank() || id.length() <= longest) continue;
			if (stem.equals(id) || stem.startsWith(id + "-") || stem.startsWith(id + "_")) {
				best = addon.file();
				longest = id.length();
			}
		}
		return best;
	}

	/**
	 * Every file worth offering under this folder, and one bad folder does not
	 * cost the rest.
	 *
	 * Walked with a visitor rather than with a stream, and that is the whole point
	 * of the change: a stream walk throws the moment it meets a folder it cannot
	 * list, and the throw comes out in the middle of the iteration — so everything
	 * after it was lost. A plugin's folder is a good place to meet one: some keep a
	 * lock, a cache or a database beside their settings. The symptom is a plugin
	 * whose configs are simply not in the list, with nothing said anywhere.
	 */
	private static void gather(Path folder, Path root, List<Entry> into) {
		if (!Files.isDirectory(folder)) return;
		List<Path> found = new ArrayList<>();
		try {
			Files.walkFileTree(folder, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
				DEEP, new java.nio.file.SimpleFileVisitor<Path>() {
					@Override
					public java.nio.file.FileVisitResult visitFile(Path file,
							java.nio.file.attribute.BasicFileAttributes attributes) {
						if (found.size() >= MOST_FILES) {
							return java.nio.file.FileVisitResult.TERMINATE;
						}
						if (attributes.isRegularFile()
							&& editable(file.getFileName().toString())) {
							found.add(file);
						}
						return java.nio.file.FileVisitResult.CONTINUE;
					}

					@Override
					public java.nio.file.FileVisitResult visitFileFailed(Path file,
							IOException problem) {
						// One file or folder we may not read. Everything beside it is
						// still readable, and it is the everything that matters.
						return java.nio.file.FileVisitResult.CONTINUE;
					}

					@Override
					public java.nio.file.FileVisitResult postVisitDirectory(Path directory,
							IOException problem) {
						return java.nio.file.FileVisitResult.CONTINUE;
					}
				});
		} catch (IOException | RuntimeException unreadable) {
			// The top folder itself. Whatever was gathered before it is kept.
		}
		found.sort(java.util.Comparator.naturalOrder());
		for (Path file : found) {
			if (into.size() >= MOST_FILES) return;
			into.add(entry(root, file));
		}
	}

	private static Entry entry(Path root, Path file) {
		long size;
		try {
			size = Files.size(file);
		} catch (IOException gone) {
			size = 0;
		}
		return new Entry(root.relativize(file).toString().replace('\\', '/'), file, "", size);
	}

	/**
	 * Which files are worth offering.
	 *
	 * Configuration and not data. A plugin's folder holds both, and the difference
	 * matters: {@code config.yml} is a thing to edit, {@code playerdata.yml} with
	 * forty thousand entries is a thing to leave alone.
	 */
	private static boolean editable(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".yml") || lower.endsWith(".yaml")
			|| lower.endsWith(".properties") || lower.endsWith(".conf")
			|| lower.endsWith(".toml") || lower.endsWith(".json") || lower.endsWith(".json5");
	}

	/**
	 * How a change to this file reaches the running server.
	 *
	 * Said before the change rather than after it, because "it saved and nothing
	 * happened" is the single most common way a settings screen wastes somebody's
	 * afternoon. There is no catalogue of per-plugin reload commands yet — that is
	 * a later stage — so this says only what is certainly true.
	 */
	public enum Reach {
		/** The server is not running; it will read the file when it starts. */
		NOT_RUNNING,
		/** It is running, and this file is read once at start. */
		NEEDS_RESTART
	}

	public static Reach reach(boolean running) {
		return running ? Reach.NEEDS_RESTART : Reach.NOT_RUNNING;
	}
}
