package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What LuckPerms knows, read out of its own backup file.
 *
 * <b>Why a backup file and not the database.</b> LuckPerms keeps its data in H2
 * by default — {@code plugins/LuckPerms/luckperms-h2-v2.mv.db} — and reading that
 * would mean shipping a database driver and betting on their schema version
 * staying put. It also keeps it in MySQL, MariaDB, PostgreSQL, SQLite, YAML, JSON
 * and HOCON, depending on one line of their config, so "read the storage" is not
 * one job but eight, and seven of them are somebody else's server.
 *
 * <p>There is one thing every one of those can be asked for: {@code lp export}
 * writes a gzipped JSON file into the plugin's own folder. It is the format their
 * own {@code lp import} reads back, which is what makes it worth building on — a
 * format a plugin has to keep reading is a format that does not move under you.
 *
 * <p>Everything here was read off LuckPerms-Bukkit-5.5.71 rather than remembered:
 * the file shape from {@code Exporter}, the node shape from
 * {@code NodeJsonSerializer}, and the key prefixes below from the node types
 * themselves. Guessing at somebody else's format is how a reader works on the one
 * server it was written against and on nothing else.
 */
public final class LuckPermsExport {

	private LuckPermsExport() {
	}

	/**
	 * The key prefixes LuckPerms writes, taken from its own node classes.
	 *
	 * A group's name, weight, prefix and parents are not fields in the file; they
	 * are permissions with reserved keys, which is why they are pulled out here
	 * rather than shown among the rest. Somebody looking at a list of what a rank
	 * can do does not want {@code weight.100} in it.
	 */
	private static final String WEIGHT = "weight.";
	private static final String DISPLAY = "displayname.";
	private static final String PREFIX = "prefix.";
	private static final String SUFFIX = "suffix.";
	private static final String PARENT = "group.";
	private static final String META = "meta.";

	/**
	 * The node's own word for what it is, which the file carries beside the key.
	 *
	 * Found by exporting a real server rather than by reading the serializer: every
	 * node comes with {@code "type": "inheritance" | "permission" | "prefix" |
	 * "suffix" | "weight" | "display_name" | "meta"}. That is worth using, because
	 * a key prefix is a guess about a string and this is the plugin saying so. The
	 * prefixes stay as the fallback for a file that does not carry it.
	 */
	private static final String INHERITANCE_TYPE = "inheritance";
	private static final String PERMISSION_TYPE = "permission";
	private static final String PREFIX_TYPE = "prefix";
	private static final String SUFFIX_TYPE = "suffix";
	private static final String WEIGHT_TYPE = "weight";
	private static final String DISPLAY_TYPE = "display_name";
	private static final String META_TYPE = "meta";

	/** One permission, and whether it is a grant or a denial. */
	public record Permission(String node, boolean granted, long until) {

		/** Whether this one runs out, and when. Zero means it does not. */
		public boolean temporary() {
			return until > 0;
		}
	}

	/**
	 * One privilege: a group, as LuckPerms keeps it.
	 *
	 * The weight is what decides which of two prefixes a player wears and which
	 * rank wins an argument between them, and it is the closest thing LuckPerms
	 * has to the operator levels this replaces — with the difference that there
	 * can be forty of them and each has a name.
	 */
	public record Group(String name, String display, int weight, String prefix, String suffix,
			List<String> parents, List<Permission> permissions) {

		/** What to call it: the name somebody chose to show, or the id itself. */
		public String title() {
			return display.isBlank() ? name : display;
		}
	}

	/**
	 * A ladder of groups in order, which LuckPerms calls a track.
	 *
	 * This is what "levels of access" actually is on a server that has grown up:
	 * not a number from one to four but a named ladder — default, vip, moderator,
	 * admin — with one command to move somebody up it and one to move them down.
	 */
	public record Track(String name, List<String> groups) {
	}

	/** Somebody LuckPerms has a record of, and the groups they are in. */
	public record Member(String uuid, String name, String primary, List<String> groups) {
	}

	/** The whole file, in the three parts it comes in. */
	public record Data(List<Group> groups, List<Track> tracks, List<Member> members) {

		public static Data empty() {
			return new Data(List.of(), List.of(), List.of());
		}

		public Group group(String name) {
			for (Group each : groups) {
				if (each.name().equalsIgnoreCase(name)) return each;
			}
			return null;
		}
	}

	/** Read the gzipped file LuckPerms wrote. */
	public static Data read(Path file) throws IOException {
		try (var stream = new GZIPInputStream(Files.newInputStream(file));
				var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return of(JsonParser.parseReader(reader));
		} catch (RuntimeException notJson) {
			throw new IOException("That is not a LuckPerms export: " + notJson.getMessage(),
				notJson);
		}
	}

	/** The same, from text — which is what the tests hold and what a bad file is. */
	public static Data of(String json) throws IOException {
		try {
			return of(JsonParser.parseString(json));
		} catch (RuntimeException notJson) {
			throw new IOException("That is not a LuckPerms export: " + notJson.getMessage(),
				notJson);
		}
	}

	private static Data of(JsonElement parsed) {
		if (parsed == null || !parsed.isJsonObject()) return Data.empty();
		JsonObject root = parsed.getAsJsonObject();
		return new Data(groups(object(root, "groups")), tracks(object(root, "tracks")),
			members(object(root, "users")));
	}

	private static List<Group> groups(JsonObject holding) {
		List<Group> found = new ArrayList<>();
		if (holding == null) return List.copyOf(found);
		for (var entry : holding.entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			found.add(group(entry.getKey(), entry.getValue().getAsJsonObject()));
		}
		// Heaviest first, because that is the order a server administrator holds
		// them in their head: the rank that wins is the one at the top.
		found.sort((one, two) -> two.weight() != one.weight()
			? Integer.compare(two.weight(), one.weight())
			: one.name().compareToIgnoreCase(two.name()));
		return List.copyOf(found);
	}

	private static Group group(String name, JsonObject holding) {
		String display = "";
		int weight = 0;
		String prefix = "";
		int prefixAt = Integer.MIN_VALUE;
		String suffix = "";
		int suffixAt = Integer.MIN_VALUE;
		List<String> parents = new ArrayList<>();
		List<Permission> permissions = new ArrayList<>();

		for (JsonObject node : array(holding, "nodes")) {
			String key = text(node, "key");
			if (key.isBlank()) continue;
			boolean value = !node.has("value") || bool(node, "value");
			long until = node.has("expiry") ? number(node, "expiry") : 0;
			String kind = kindOf(node, key);

			switch (kind) {
				case WEIGHT_TYPE -> weight = whole(after(key, WEIGHT), weight);
				case DISPLAY_TYPE -> display = after(key, DISPLAY);
				case INHERITANCE_TYPE -> parents.add(after(key, PARENT));
				case PREFIX_TYPE -> {
					// prefix.<priority>.<text>, and the text may hold dots of its
					// own, so only the first one after the priority separates.
					String[] split = chatMeta(after(key, PREFIX));
					int priority = whole(split[0], 0);
					if (priority > prefixAt) {
						prefixAt = priority;
						prefix = split[1];
					}
				}
				case SUFFIX_TYPE -> {
					String[] split = chatMeta(after(key, SUFFIX));
					int priority = whole(split[0], 0);
					if (priority > suffixAt) {
						suffixAt = priority;
						suffix = split[1];
					}
				}
				case META_TYPE -> {
					// Somebody else's plugin keeps its settings in here. Not this
					// window's to show and certainly not this window's to rewrite.
				}
				default -> permissions.add(new Permission(key, value, until));
			}
		}
		permissions.sort((one, two) -> one.node().compareToIgnoreCase(two.node()));
		return new Group(name, display, weight, prefix, suffix,
			List.copyOf(parents), List.copyOf(permissions));
	}

	/**
	 * What kind of node this is: what it says it is, or what its key looks like.
	 *
	 * The word comes first because the plugin wrote it. The prefixes are the
	 * fallback for a file without one — an older export, or a hand-written stand-in
	 * — and they are also the reason a permission node that happens to begin with
	 * "weight." is not mistaken for a weight when the word is there to say so.
	 */
	private static String kindOf(JsonObject node, String key) {
		String said = text(node, "type");
		if (!said.isBlank()) return said;
		if (key.startsWith(WEIGHT)) return WEIGHT_TYPE;
		if (key.startsWith(DISPLAY)) return DISPLAY_TYPE;
		if (key.startsWith(PARENT)) return INHERITANCE_TYPE;
		if (key.startsWith(PREFIX)) return PREFIX_TYPE;
		if (key.startsWith(SUFFIX)) return SUFFIX_TYPE;
		if (key.startsWith(META)) return META_TYPE;
		return PERMISSION_TYPE;
	}

	/** What follows a reserved prefix, or the whole key when it is not there. */
	private static String after(String key, String prefix) {
		return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
	}

	private static String[] chatMeta(String rest) {
		int dot = rest.indexOf('.');
		if (dot < 0) return new String[] {"0", rest};
		return new String[] {rest.substring(0, dot), rest.substring(dot + 1)};
	}

	/**
	 * The ladders, whose shape is not the one it looks like it should be.
	 *
	 * A track in the file is <em>not</em> a name against a list of groups; it is a
	 * name against an object with a {@code groups} array inside it. Read off a real
	 * export — a reader written from the obvious guess found no tracks at all and
	 * said so by showing none, which is the worst way for this to go wrong. The
	 * bare array is still taken, in case some version wrote one.
	 */
	private static List<Track> tracks(JsonObject holding) {
		List<Track> found = new ArrayList<>();
		if (holding == null) return List.copyOf(found);
		for (var entry : holding.entrySet()) {
			JsonElement value = entry.getValue();
			JsonArray rows = value.isJsonArray() ? value.getAsJsonArray()
				: value.isJsonObject() && value.getAsJsonObject().has("groups")
					&& value.getAsJsonObject().get("groups").isJsonArray()
					? value.getAsJsonObject().getAsJsonArray("groups") : null;
			if (rows == null) continue;
			List<String> rungs = new ArrayList<>();
			for (JsonElement each : rows) {
				if (each.isJsonPrimitive()) rungs.add(each.getAsString());
			}
			found.add(new Track(entry.getKey(), List.copyOf(rungs)));
		}
		found.sort((one, two) -> one.name().compareToIgnoreCase(two.name()));
		return List.copyOf(found);
	}

	private static List<Member> members(JsonObject holding) {
		List<Member> found = new ArrayList<>();
		if (holding == null) return List.copyOf(found);
		for (var entry : holding.entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			JsonObject one = entry.getValue().getAsJsonObject();
			List<String> groups = new ArrayList<>();
			for (JsonObject node : array(one, "nodes")) {
				String key = text(node, "key");
				// A denial of a group is not membership of it, and writing it down
				// as one would show somebody as a moderator because they were
				// pointedly told they are not.
				if (key.startsWith(PARENT) && (!node.has("value") || bool(node, "value"))) {
					groups.add(key.substring(PARENT.length()));
				}
			}
			found.add(new Member(entry.getKey().toLowerCase(Locale.ROOT),
				text(one, "username"), text(one, "primaryGroup"), List.copyOf(groups)));
		}
		return List.copyOf(found);
	}

	/** Which groups each person is in, by uuid, for looking one person up. */
	public static Map<String, Member> byId(Data data) {
		Map<String, Member> found = new LinkedHashMap<>();
		for (Member each : data.members()) found.put(each.uuid(), each);
		return found;
	}

	// ------------------------------------------------------------------ reading

	private static JsonObject object(JsonObject holding, String key) {
		if (holding == null || !holding.has(key)) return null;
		JsonElement found = holding.get(key);
		return found.isJsonObject() ? found.getAsJsonObject() : null;
	}

	private static List<JsonObject> array(JsonObject holding, String key) {
		List<JsonObject> found = new ArrayList<>();
		if (holding == null || !holding.has(key) || !holding.get(key).isJsonArray()) {
			return found;
		}
		JsonArray all = holding.getAsJsonArray(key);
		for (JsonElement each : all) {
			if (each.isJsonObject()) found.add(each.getAsJsonObject());
		}
		return found;
	}

	private static String text(JsonObject holding, String key) {
		try {
			return holding.has(key) && holding.get(key).isJsonPrimitive()
				? holding.get(key).getAsString() : "";
		} catch (RuntimeException wrongKind) {
			return "";
		}
	}

	private static boolean bool(JsonObject holding, String key) {
		try {
			return holding.get(key).getAsBoolean();
		} catch (RuntimeException wrongKind) {
			return true;
		}
	}

	private static long number(JsonObject holding, String key) {
		try {
			return holding.get(key).getAsLong();
		} catch (RuntimeException wrongKind) {
			return 0;
		}
	}

	private static int whole(String said, int fallback) {
		try {
			return Integer.parseInt(said.trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}
}
