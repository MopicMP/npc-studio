package com.mopicmp.npcstudio.client.server;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings of a server, and what shape each one is.
 *
 * Nearly the whole of {@code server.properties}, grouped by what somebody is
 * thinking about rather than by the alphabetical order the file happens to be
 * written in. The first version of this offered ten keys, which is a demo of a
 * settings screen rather than a settings screen: the file is what people open
 * this mod to avoid opening, and half of it is not enough to stop.
 *
 * Four things are deliberately absent. The message of the day, the port, the
 * world's folder name and everything about the command channel — the first two
 * belong to the server's identity and live in the column on the left, the third
 * belongs to worlds, and the last is ours. A password shown in a settings list
 * is a password shown to whoever is standing behind you.
 *
 * Nothing here knows how to draw. The ranges are here because a view distance of
 * nine hundred is a server that will not start, and finding that out from a
 * crash is a much longer road than finding it out from a field that refuses it.
 */
public record ServerSettings(String group, String key, String label, Kind kind,
		List<String> choices, String fallback, int min, int max) {

	public enum Kind {
		/** Free text. */
		TEXT,
		/** A whole number, between {@link #min} and {@link #max}. */
		NUMBER,
		/** One of a few words, chosen from a list. */
		CHOICE,
		/** True or false. A choice of two, and it reads better as one. */
		SWITCH
	}

	private static final String ACCESS = "npc_studio.server.group.access";
	private static final String GAME = "npc_studio.server.group.game";
	private static final String SPEED = "npc_studio.server.group.speed";
	private static final String PACK = "npc_studio.server.group.pack";
	private static final String OTHER = "npc_studio.server.group.other";

	private static ServerSettings text(String group, String key, String fallback) {
		return new ServerSettings(group, key, label(key), Kind.TEXT, List.of(), fallback, 0, 0);
	}

	private static ServerSettings number(String group, String key, String fallback,
			int min, int max) {
		return new ServerSettings(group, key, label(key), Kind.NUMBER, List.of(), fallback,
			min, max);
	}

	private static ServerSettings choice(String group, String key, String fallback,
			String... choices) {
		return new ServerSettings(group, key, label(key), Kind.CHOICE, List.of(choices),
			fallback, 0, 0);
	}

	private static ServerSettings switched(String group, String key, String fallback) {
		return new ServerSettings(group, key, label(key), Kind.SWITCH, List.of("true", "false"),
			fallback, 0, 0);
	}

	/** The translation key of a setting, worked out from the key it edits. */
	private static String label(String key) {
		return "npc_studio.server.key." + key.replace('.', '_').replace('-', '_');
	}

	public static final List<ServerSettings> ALL = List.of(
		number(ACCESS, "max-players", "20", 1, 1000),
		switched(ACCESS, "white-list", "false"),
		switched(ACCESS, "enforce-whitelist", "false"),
		switched(ACCESS, "online-mode", "true"),
		switched(ACCESS, "enforce-secure-profile", "true"),
		switched(ACCESS, "prevent-proxy-connections", "false"),
		switched(ACCESS, "hide-online-players", "false"),
		switched(ACCESS, "accepts-transfers", "false"),
		number(ACCESS, "player-idle-timeout", "0", 0, 1440),
		choice(ACCESS, "op-permission-level", "4", "1", "2", "3", "4"),

		choice(GAME, "difficulty", "easy", "peaceful", "easy", "normal", "hard"),
		choice(GAME, "gamemode", "survival", "survival", "creative", "adventure", "spectator"),
		switched(GAME, "force-gamemode", "false"),
		switched(GAME, "hardcore", "false"),
		switched(GAME, "pvp", "true"),
		switched(GAME, "spawn-monsters", "true"),
		switched(GAME, "allow-flight", "false"),
		switched(GAME, "allow-nether", "true"),
		switched(GAME, "enable-command-block", "false"),
		number(GAME, "spawn-protection", "16", 0, 1000),
		choice(GAME, "function-permission-level", "2", "1", "2", "3", "4"),

		// The seed, the world type, structures and the world's size are not here:
		// they belong to a world, and worlds have a tab of their own coming. A
		// setting shown in two places is a setting that disagrees with itself.

		// The four that decide whether a server on a small machine is playable.
		number(SPEED, "view-distance", "10", 2, 32),
		number(SPEED, "simulation-distance", "10", 2, 32),
		number(SPEED, "entity-broadcast-range-percentage", "100", 10, 1000),
		number(SPEED, "network-compression-threshold", "256", -1, 1000000),
		number(SPEED, "max-tick-time", "60000", 0, 1000000),
		number(SPEED, "pause-when-empty-seconds", "60", 0, 100000),
		switched(SPEED, "sync-chunk-writes", "true"),
		switched(SPEED, "use-native-transport", "true"),

		text(PACK, "resource-pack", ""),
		text(PACK, "resource-pack-sha1", ""),
		text(PACK, "resource-pack-prompt", ""),
		switched(PACK, "require-resource-pack", "false"),

		switched(OTHER, "enable-status", "true"),
		switched(OTHER, "enable-query", "false"),
		number(OTHER, "query.port", "25565", 1, 65535),
		switched(OTHER, "broadcast-console-to-ops", "true"),
		switched(OTHER, "broadcast-rcon-to-ops", "true"),
		switched(OTHER, "log-ips", "true"),
		switched(OTHER, "enable-jmx-monitoring", "false")
	);

	/** The group for lines this core put in the file that nobody here knows about. */
	public static final String EXTRA = "npc_studio.server.group.extra";

	/**
	 * Settings for keys found in a particular server's own file.
	 *
	 * {@code server.properties} is the same file on vanilla, on Fabric and on
	 * Paper — that is why the list looks identical on all three, and it is not a
	 * mistake. What differs between those cores lives in files of its own:
	 * {@code bukkit.yml}, {@code spigot.yml}, {@code paper-global.yml}. But a core
	 * may add a line here, and one this window does not know about is one it hides
	 * — so anything else in the file is offered as plain text, under its own name.
	 * Text and not a choice, because the only thing known about it is that it is
	 * there.
	 */
	public static List<ServerSettings> own(List<String> keys) {
		List<ServerSettings> made = new ArrayList<>();
		java.util.Collections.sort(keys);
		for (String key : keys) {
			made.add(new ServerSettings(EXTRA, key, key, Kind.TEXT, List.of(), "", 0, 0));
		}
		return List.copyOf(made);
	}

	/** The groups in the order they are shown. */
	public static List<String> groups() {
		List<String> groups = new ArrayList<>();
		for (ServerSettings setting : ALL) {
			if (!groups.contains(setting.group())) groups.add(setting.group());
		}
		return groups;
	}

	public static List<ServerSettings> of(String group) {
		return ALL.stream().filter(setting -> setting.group().equals(group)).toList();
	}

	/** Whether a value may be written. */
	public boolean allows(String value) {
		return switch (kind) {
			case NUMBER -> {
				try {
					int number = Integer.parseInt(value.trim());
					yield number >= min && number <= max;
				} catch (NumberFormatException notANumber) {
					yield false;
				}
			}
			case CHOICE, SWITCH -> choices.contains(value);
			// A seed or a resource pack address is somebody's own string; the only
			// limit is one that stops a file being filled with a held-down key.
			case TEXT -> value.length() <= 256;
		};
	}
}
