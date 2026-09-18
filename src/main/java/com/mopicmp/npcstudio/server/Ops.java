package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Who is an operator, and the trap in the middle of it.
 *
 * {@code ops.json} keeps a uuid beside every name, and the server matches on the
 * uuid. Which uuid a player has depends on a setting that has nothing to do with
 * this file: with {@code online-mode=true} it is the one Mojang keeps, and with
 * {@code online-mode=false} the server makes one up from the name — the md5 of
 * {@code OfflinePlayer:<name>}, the same way every server has since 2012.
 *
 * <p>So {@code /op somebody} typed into a console <em>before</em> that somebody has
 * ever joined an offline-mode server writes the online uuid into the file, the
 * player joins with the offline one, and the two do not match. The name in the
 * file is right, the entry is there, and the player is not an operator. That is
 * not a bug in anybody's server; it is two identities for one name, and it is
 * worth writing this class to get right rather than leaving people to find out.
 */
public final class Ops {

	private Ops() {
	}

	/** One line of the file. */
	public record Op(UUID id, String name, int level, boolean bypassesPlayerLimit) {
	}

	public static Path file(ManagedServer server) {
		return server.path().resolve("ops.json");
	}

	/**
	 * The uuid a name will have on this server, which depends on how it checks.
	 *
	 * @param signedIn the uuid this game is signed in with, or null when it is not
	 */
	public static UUID idFor(String name, boolean checksAccounts, UUID signedIn) {
		if (checksAccounts && signedIn != null) return signedIn;
		return offline(name);
	}

	/** What a server with the check turned off will call somebody by this name. */
	public static UUID offline(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	public static List<Op> read(ManagedServer server) {
		Path where = file(server);
		if (!Files.isRegularFile(where)) return List.of();
		try {
			var parsed = JsonParser.parseString(Files.readString(where));
			if (!parsed.isJsonArray()) return List.of();
			List<Op> ops = new ArrayList<>();
			for (var each : parsed.getAsJsonArray()) {
				JsonObject one = each.getAsJsonObject();
				try {
					ops.add(new Op(UUID.fromString(one.get("uuid").getAsString()),
						one.get("name").getAsString(),
						one.has("level") ? one.get("level").getAsInt() : 4,
						one.has("bypassesPlayerLimit")
							&& one.get("bypassesPlayerLimit").getAsBoolean()));
				} catch (RuntimeException wrong) {
					// One line nobody can read is one line skipped; the rest of the
					// file is somebody's list of operators and is not ours to drop.
				}
			}
			return List.copyOf(ops);
		} catch (IOException | RuntimeException unreadable) {
			return List.of();
		}
	}

	/**
	 * Put somebody in the file, replacing whatever was there under that name.
	 *
	 * By name <em>and</em> by uuid, because the whole point is that the same name
	 * can carry two of them: leaving the old line in place is leaving the entry
	 * that did not work beside the one that does.
	 */
	public static void add(ManagedServer server, UUID id, String name, int level)
			throws IOException {
		List<Op> ops = new ArrayList<>(read(server));
		ops.removeIf(each -> each.name().equalsIgnoreCase(name) || each.id().equals(id));
		ops.add(new Op(id, name, level, false));
		write(server, ops);
	}

	public static void write(ManagedServer server, List<Op> ops) throws IOException {
		JsonArray out = new JsonArray();
		for (Op each : ops) {
			JsonObject one = new JsonObject();
			one.addProperty("uuid", each.id().toString());
			one.addProperty("name", each.name());
			one.addProperty("level", each.level());
			one.addProperty("bypassesPlayerLimit", each.bypassesPlayerLimit());
			out.add(one);
		}
		Files.createDirectories(server.path());
		Files.writeString(file(server), out.toString() + "\n", StandardCharsets.UTF_8);
	}

	/** Whether this name already has an operator's line that will actually match. */
	public static boolean has(ManagedServer server, String name, UUID id) {
		for (Op each : read(server)) {
			if (each.id().equals(id)) return true;
		}
		return false;
	}
}
