package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Everyone a server knows about, gathered from the files it keeps.
 *
 * <b>Every column here can be pointed at a file.</b> That is the rule this whole
 * tab is built on, and it is a decision against the obvious alternative: a score
 * out of ten saying how trustworthy somebody is. A number nobody can check is a
 * number that gets people banned for nothing, and it cannot be argued with —
 * there is nothing to argue with. So: how long they have played, when they were
 * last here, whether they are an operator, banned, on the whitelist. Each of
 * those is a fact with a file behind it, and "problem players" or "regulars" are
 * what you get by sorting on them, not a verdict this mod hands down.
 *
 * <p>Where the files are, read off a real 26.2 server rather than remembered: a
 * world keeps {@code players/data/<uuid>.dat}, {@code players/stats/<uuid>.json}
 * and {@code players/advancements/<uuid>.json}. Older servers, and Bukkit ones,
 * keep the same things at {@code playerdata/} and {@code stats/} beside the world
 * — both are looked for, because this mod manages servers it did not create.
 *
 * <p>Names come from {@code usercache.json}, which is the server's own record of
 * who a uuid is. On a server that does not check accounts there is nothing else:
 * the uuid is made from the name itself, and asking Mojang about it would be
 * asking about somebody who may not exist.
 */
public final class Players {

	private Players() {
	}

	/**
	 * One person the server has met.
	 *
	 * @param played  how long they have played, in ticks, or -1 when unknown
	 * @param seen    when their file was last written, or 0 when there is no file
	 * @param level   an operator's level, 0 when they are not one
	 */
	public record Player(String uuid, String name, long seen, long played, boolean op, int level,
			boolean banned, String banReason, boolean whitelisted, boolean online) {

		/** What to show them as: their name if it is known, the uuid if it is not. */
		public String label() {
			return name.isBlank() ? uuid : name;
		}

		/** Played time in minutes, which is the unit a person thinks in. */
		public long minutes() {
			return played < 0 ? -1 : played / 20 / 60;
		}

		public boolean everPlayed() {
			return seen > 0;
		}
	}

	/** A thousand people is a server nobody is administering from a game window. */
	private static final int MOST = 5000;

	/**
	 * Everyone: those with a file in the world, and those only named in a list.
	 *
	 * The second half matters. Somebody added to the whitelist who has not joined
	 * yet has no file anywhere in the world, and a list that showed only players
	 * with files would leave whoever is waiting to be let in invisible — which is
	 * precisely the person an administrator is looking for.
	 */
	public static List<Player> of(ManagedServer server, String world, Set<String> onlineNames) {
		Map<String, String> names = usercache(server);
		Map<String, Op> ops = opsById(server);
		Map<String, Ban> bans = bansById(server);
		Map<String, String> whitelist = whitelistById(server, names);
		Set<String> online = lower(onlineNames);

		Map<String, Player> found = new LinkedHashMap<>();
		Path data = dataFolder(server, world);
		Path stats = statsFolder(server, world);
		if (data != null) {
			try (var files = Files.list(data)) {
				for (Path file : files.sorted().toList()) {
					String name = file.getFileName().toString();
					// The server keeps the one before last as well; it is the same
					// person and would be a second row for them.
					if (!name.endsWith(".dat")) continue;
					String id = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
					if (!looksLikeId(id)) continue;
					if (found.size() >= MOST) break;
					long seen = 0;
					try {
						seen = Files.getLastModifiedTime(file).toMillis();
					} catch (IOException gone) {
						continue;
					}
					found.put(id, make(id, names, ops, bans, whitelist, online,
						seen, playTicks(stats, id)));
				}
			} catch (IOException unreadable) {
				// A world folder that will not list. Everybody named in the lists
				// below is still shown, which is better than an empty tab.
			}
		}
		// And everybody who is only in a list: ops, bans, the whitelist, the cache.
		for (String id : namedElsewhere(names, ops, bans, whitelist)) {
			if (found.containsKey(id) || found.size() >= MOST) continue;
			found.put(id, make(id, names, ops, bans, whitelist, online, 0, -1));
		}
		return List.copyOf(found.values());
	}

	private static Player make(String id, Map<String, String> names, Map<String, Op> ops,
			Map<String, Ban> bans, Map<String, String> whitelist, Set<String> online,
			long seen, long played) {
		String name = names.getOrDefault(id, "");
		Op op = ops.get(id);
		if (name.isBlank() && op != null) name = op.name();
		Ban ban = bans.get(id);
		if (name.isBlank() && ban != null) name = ban.name();
		// And the whitelist, which carries a name of its own.
		//
		// This one was missing, and it was the list where it matters most: the
		// cache only holds people who have joined, so somebody whitelisted before
		// their first visit — the ordinary case, the whole reason to whitelist by
		// name — had no name anywhere this looked, and was shown as their uuid. The
		// name was sitting in whitelist.json the entire time.
		if (name.isBlank()) name = whitelist.getOrDefault(id, "");
		return new Player(id, name, seen, played, op != null, op == null ? 0 : op.level(),
			ban != null, ban == null ? "" : ban.reason(), whitelist.containsKey(id),
			!name.isBlank() && online.contains(name.toLowerCase(Locale.ROOT)));
	}

	private static List<String> namedElsewhere(Map<String, String> names, Map<String, Op> ops,
			Map<String, Ban> bans, Map<String, String> whitelist) {
		List<String> all = new ArrayList<>();
		all.addAll(ops.keySet());
		all.addAll(bans.keySet());
		all.addAll(whitelist.keySet());
		all.addAll(names.keySet());
		return all;
	}

	private static Set<String> lower(Set<String> names) {
		Set<String> out = new java.util.HashSet<>();
		for (String each : names) out.add(each.toLowerCase(Locale.ROOT));
		return out;
	}

	private static boolean looksLikeId(String id) {
		return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	// ------------------------------------------------------------------ folders

	/**
	 * Where the player files are, which moved in 26.2.
	 *
	 * They used to be {@code <world>/playerdata}; they are now
	 * {@code <world>/players/data}. Read off a real server of each — this is not a
	 * detail to remember wrongly, because looking in the wrong place gives an empty
	 * tab and no error at all.
	 */
	public static Path dataFolder(ManagedServer server, String world) {
		return firstFolder(server.path().resolve(world), "players/data", "playerdata");
	}

	public static Path statsFolder(ManagedServer server, String world) {
		return firstFolder(server.path().resolve(world), "players/stats", "stats");
	}

	private static Path firstFolder(Path world, String... names) {
		for (String name : names) {
			Path here = world;
			for (String part : name.split("/")) here = here.resolve(part);
			if (Files.isDirectory(here)) return here;
		}
		return null;
	}

	/**
	 * How long somebody has played, in ticks.
	 *
	 * {@code stats.minecraft:custom.minecraft:play_time}, which the server writes
	 * when they leave. Ticks rather than anything friendlier, because that is what
	 * is in the file and the conversion belongs where it is shown.
	 */
	private static long playTicks(Path stats, String id) {
		if (stats == null) return -1;
		Path file = stats.resolve(id + ".json");
		if (!Files.isRegularFile(file)) return -1;
		try {
			JsonObject root = JsonParser.parseString(
				Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject all = object(root, "stats");
			if (all == null) return -1;
			JsonObject custom = object(all, "minecraft:custom");
			if (custom == null || !custom.has("minecraft:play_time")) return -1;
			return custom.get("minecraft:play_time").getAsLong();
		} catch (IOException | RuntimeException unreadable) {
			return -1;
		}
	}

	// ------------------------------------------------------------------ the lists

	/** The server's own record of which name a uuid belongs to. */
	public static Map<String, String> usercache(ManagedServer server) {
		Map<String, String> names = new LinkedHashMap<>();
		for (JsonObject each : array(server.path().resolve("usercache.json"))) {
			String id = string(each, "uuid").toLowerCase(Locale.ROOT);
			String name = string(each, "name");
			if (!id.isBlank() && !name.isBlank()) names.putIfAbsent(id, name);
		}
		return names;
	}

	private static Map<String, Op> opsById(ManagedServer server) {
		Map<String, Op> ops = new LinkedHashMap<>();
		for (Ops.Op op : Ops.read(server)) {
			ops.put(op.id().toString().toLowerCase(Locale.ROOT),
				new Op(op.name(), op.level()));
		}
		return ops;
	}

	private record Op(String name, int level) {
	}

	/** One ban as the server writes it. The reason is what a kicked player sees. */
	public record Ban(String id, String name, String reason, String source, String expires) {
	}

	public static List<Ban> bans(ManagedServer server) {
		List<Ban> found = new ArrayList<>();
		for (JsonObject each : array(server.path().resolve("banned-players.json"))) {
			found.add(new Ban(string(each, "uuid").toLowerCase(Locale.ROOT), string(each, "name"),
				string(each, "reason"), string(each, "source"), string(each, "expires")));
		}
		return List.copyOf(found);
	}

	private static Map<String, Ban> bansById(ManagedServer server) {
		Map<String, Ban> bans = new LinkedHashMap<>();
		for (Ban ban : bans(server)) {
			if (!ban.id().isBlank()) bans.put(ban.id(), ban);
		}
		return bans;
	}

	/**
	 * Who is on the whitelist, by uuid.
	 *
	 * An entry may have been written by hand with only a name in it, so a name is
	 * matched against the cache before it is given up on. The name is kept as well
	 * as the uuid, because for most of the people on this list it is the only name
	 * the server has anywhere: they have not joined yet, so nothing has put them in
	 * the cache.
	 */
	private static Map<String, String> whitelistById(ManagedServer server,
			Map<String, String> names) {
		Map<String, String> found = new LinkedHashMap<>();
		Map<String, String> byName = new LinkedHashMap<>();
		names.forEach((id, name) -> byName.put(name.toLowerCase(Locale.ROOT), id));
		for (JsonObject each : array(server.path().resolve("whitelist.json"))) {
			String id = string(each, "uuid").toLowerCase(Locale.ROOT);
			String name = string(each, "name");
			if (id.isBlank()) {
				String named = byName.get(name.toLowerCase(Locale.ROOT));
				if (named != null) found.put(named, name);
			} else {
				found.put(id, name);
			}
		}
		return found;
	}

	/**
	 * A ban on an address rather than on a person.
	 *
	 * A separate list in the server and a separate thing in kind: it keeps out
	 * whoever is behind that address, including people who have done nothing —
	 * a household, a school, a whole mobile network share addresses. Worth showing
	 * plainly for exactly that reason, since it is the list that catches strangers.
	 */
	public record IpBan(String ip, String reason, String source, String created, String expires) {
	}

	public static List<IpBan> ipBans(ManagedServer server) {
		List<IpBan> found = new ArrayList<>();
		for (JsonObject each : array(server.path().resolve("banned-ips.json"))) {
			String ip = string(each, "ip");
			if (ip.isBlank()) continue;
			found.add(new IpBan(ip, string(each, "reason"), string(each, "source"),
				string(each, "created"), string(each, "expires")));
		}
		return List.copyOf(found);
	}

	/** Ban or pardon an address by editing the file. Stopped servers only. */
	public static void banIp(ManagedServer server, String ip, String reason, boolean on)
			throws IOException {
		String address = ip.strip();
		if (on && !validIp(address)) {
			throw new IOException("That is not an address: " + ip);
		}
		Path file = server.path().resolve("banned-ips.json");
		List<JsonObject> entries = new ArrayList<>(array(file));
		entries.removeIf(each -> string(each, "ip").equalsIgnoreCase(address));
		if (on) {
			JsonObject added = new JsonObject();
			added.addProperty("ip", address);
			added.addProperty("created", stamp());
			added.addProperty("source", "NPC Studio");
			added.addProperty("expires", "forever");
			added.addProperty("reason", reason == null || reason.isBlank()
				? "Banned by an operator." : reason);
			entries.add(added);
		}
		write(file, entries);
	}

	/**
	 * Whether that is an address at all.
	 *
	 * Checked before it is written, not after: this file is read by the server at
	 * start, and a line in it that is not an address is a server that refuses to
	 * start with a stack trace about a list nobody remembers editing.
	 */
	public static boolean validIp(String ip) {
		if (ip.isBlank() || ip.length() > 45) return false;
		// Four numbers under 256, or something with colons in it that is not
		// obviously nonsense — which is as much as can be said about the shape of
		// an IPv6 address without writing a parser for one.
		if (ip.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
			for (String part : ip.split("\\.")) {
				if (Integer.parseInt(part) > 255) return false;
			}
			return true;
		}
		return ip.matches("[0-9a-fA-F:]{2,45}") && ip.contains(":");
	}

	public static List<String> whitelistNames(ManagedServer server) {
		List<String> found = new ArrayList<>();
		for (JsonObject each : array(server.path().resolve("whitelist.json"))) {
			String name = string(each, "name");
			if (!name.isBlank()) found.add(name);
		}
		return List.copyOf(found);
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Put somebody on the whitelist, or take them off, by editing the file.
	 *
	 * <b>Only while the server is stopped.</b> A running server holds these lists
	 * in memory and writes them out when they change; a file edited underneath it
	 * is a file it will overwrite without reading. When it is up, the same thing is
	 * done with a command, which is the server's own way of being asked.
	 */
	public static void whitelist(ManagedServer server, String name, String uuid, boolean on)
			throws IOException {
		Path file = server.path().resolve("whitelist.json");
		List<JsonObject> entries = new ArrayList<>(array(file));
		entries.removeIf(each -> string(each, "name").equalsIgnoreCase(name)
			|| !uuid.isBlank() && string(each, "uuid").equalsIgnoreCase(uuid));
		if (on) {
			JsonObject added = new JsonObject();
			added.addProperty("uuid", uuid.isBlank()
				? Ops.offline(name).toString() : uuid);
			added.addProperty("name", name);
			entries.add(added);
		}
		write(file, entries);
	}

	/** Ban or pardon by editing the file. Stopped servers only — see whitelist. */
	public static void ban(ManagedServer server, String name, String uuid, String reason,
			boolean on) throws IOException {
		Path file = server.path().resolve("banned-players.json");
		List<JsonObject> entries = new ArrayList<>(array(file));
		entries.removeIf(each -> string(each, "name").equalsIgnoreCase(name)
			|| !uuid.isBlank() && string(each, "uuid").equalsIgnoreCase(uuid));
		if (on) {
			JsonObject added = new JsonObject();
			added.addProperty("uuid", uuid.isBlank() ? Ops.offline(name).toString() : uuid);
			added.addProperty("name", name);
			added.addProperty("created", stamp());
			added.addProperty("source", "NPC Studio");
			added.addProperty("expires", "forever");
			added.addProperty("reason", reason == null || reason.isBlank()
				? "Banned by an operator." : reason);
			entries.add(added);
		}
		write(file, entries);
	}

	private static String stamp() {
		return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
			.format(java.time.ZonedDateTime.now());
	}

	private static void write(Path file, List<JsonObject> entries) throws IOException {
		JsonArray out = new JsonArray();
		for (JsonObject each : entries) out.add(each);
		Path part = file.resolveSibling(file.getFileName() + ".npc_part");
		Files.writeString(part, out.toString(), StandardCharsets.UTF_8);
		Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	// ------------------------------------------------------------------ reading

	private static List<JsonObject> array(Path file) {
		if (!Files.isRegularFile(file)) return List.of();
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!root.isJsonArray()) return List.of();
			List<JsonObject> found = new ArrayList<>();
			for (JsonElement each : root.getAsJsonArray()) {
				if (each.isJsonObject()) found.add(each.getAsJsonObject());
			}
			return found;
		} catch (IOException | RuntimeException unreadable) {
			return List.of();
		}
	}

	private static JsonObject object(JsonObject root, String name) {
		return root.has(name) && root.get(name).isJsonObject() ? root.getAsJsonObject(name) : null;
	}

	private static String string(JsonObject root, String name) {
		return root.has(name) && root.get(name).isJsonPrimitive()
			? root.get(name).getAsString() : "";
	}
}
