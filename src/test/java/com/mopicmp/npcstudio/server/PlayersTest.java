package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everyone a server knows, and where each fact about them comes from.
 *
 * The tests are about provenance rather than about presentation: a name comes
 * from the server's own cache, the time played from the statistics the server
 * writes, "operator" from {@code ops.json}. Nothing here is inferred and nothing
 * is scored — see the note on {@link Players} for why that is the whole point.
 */
class PlayersTest {

	private static final String ONE = "2e7f81fe-4dd6-3981-b817-d323711367be";
	private static final String TWO = "be5d3ae9-cfb8-3e0e-818b-c883ef6066be";

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "vanilla";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	/** A world laid out the way 26.2 lays one out. */
	private static void world(Path root, String uuid, long playTicks) throws IOException {
		write(root.resolve("world").resolve("players").resolve("data").resolve(uuid + ".dat"),
			"not really nbt");
		// The server keeps the previous copy beside it, and it is the same person.
		write(root.resolve("world").resolve("players").resolve("data").resolve(uuid + ".dat_old"),
			"older");
		write(root.resolve("world").resolve("players").resolve("stats").resolve(uuid + ".json"),
			"{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + playTicks
				+ ",\"minecraft:leave_game\":3}},\"DataVersion\":4903}");
	}

	@Test
	@DisplayName("Players are found where 26.2 keeps them, with the time they have played")
	void readsTheNewLayout(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		world(root, ONE, 24_000);
		write(root.resolve("usercache.json"),
			"[{\"uuid\":\"" + ONE + "\",\"name\":\"Player861\"}]");

		List<Players.Player> found = Players.of(server, "world", Set.of());
		assertEquals(1, found.size(), found.toString());
		Players.Player one = found.getFirst();
		assertEquals("Player861", one.name());
		// 24000 ticks is twenty minutes, and minutes is what a person reads.
		assertEquals(20, one.minutes());
		assertTrue(one.everPlayed());
		assertFalse(one.op());
		assertFalse(one.banned());
	}

	@Test
	@DisplayName("An older server keeps them somewhere else, and that is looked for too")
	void readsTheOldLayout(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		write(root.resolve("world").resolve("playerdata").resolve(ONE + ".dat"), "nbt");
		write(root.resolve("world").resolve("stats").resolve(ONE + ".json"),
			"{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1200}}}");

		List<Players.Player> found = Players.of(server, "world", Set.of());
		assertEquals(1, found.size());
		assertEquals(1, found.getFirst().minutes());
		assertNotNull(Players.dataFolder(server, "world"));
	}

	@Test
	@DisplayName("Operators, bans and the whitelist are read from the server's own lists")
	void marks(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		world(root, ONE, 0);
		world(root, TWO, 0);
		write(root.resolve("usercache.json"), "[{\"uuid\":\"" + ONE + "\",\"name\":\"Alice\"},"
			+ "{\"uuid\":\"" + TWO + "\",\"name\":\"Bob\"}]");
		write(root.resolve("ops.json"), "[{\"uuid\":\"" + ONE + "\",\"name\":\"Alice\","
			+ "\"level\":4,\"bypassesPlayerLimit\":false}]");
		write(root.resolve("banned-players.json"), "[{\"uuid\":\"" + TWO + "\",\"name\":\"Bob\","
			+ "\"reason\":\"griefing\",\"source\":\"Alice\",\"expires\":\"forever\"}]");
		write(root.resolve("whitelist.json"), "[{\"uuid\":\"" + ONE + "\",\"name\":\"Alice\"}]");

		List<Players.Player> found = Players.of(server, "world", Set.of("alice"));
		Players.Player alice = one(found, "Alice");
		assertTrue(alice.op());
		assertEquals(4, alice.level());
		assertTrue(alice.whitelisted());
		// Online is matched on the name, because that is all the command channel
		// gives back for the people on a server.
		assertTrue(alice.online());

		Players.Player bob = one(found, "Bob");
		assertTrue(bob.banned());
		assertEquals("griefing", bob.banReason());
		assertFalse(bob.online());
	}

	@Test
	@DisplayName("Somebody whitelisted who has never joined is still shown")
	void neverPlayed(@TempDir Path folder) throws IOException {
		// The person an administrator is most likely to be looking for: added to
		// the list a minute ago, with no file anywhere in the world yet.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		write(root.resolve("whitelist.json"),
			"[{\"uuid\":\"" + TWO + "\",\"name\":\"Newcomer\"}]");
		write(root.resolve("usercache.json"),
			"[{\"uuid\":\"" + TWO + "\",\"name\":\"Newcomer\"}]");

		Players.Player only = Players.of(server, "world", Set.of()).getFirst();
		assertEquals("Newcomer", only.name());
		assertTrue(only.whitelisted());
		assertFalse(only.everPlayed());
		assertEquals(-1, only.minutes());
	}

	@Test
	@DisplayName("A whitelisted name is read from the whitelist, cache or no cache")
	void nameFromTheWhitelist(@TempDir Path folder) throws IOException {
		// The ordinary case, and the one that was broken: whitelisting somebody
		// before their first visit. Nothing has put them in usercache.json — that
		// file is written when a player connects — so the whitelist entry is the
		// only place their name exists. It was being read for the uuid and thrown
		// away, and the tab showed the person as a row of hexadecimal.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		write(root.resolve("whitelist.json"),
			"[{\"uuid\":\"" + TWO + "\",\"name\":\"MopicMP\"}]");

		Players.Player only = Players.of(server, "world", Set.of()).getFirst();
		assertEquals("MopicMP", only.name());
		assertEquals("MopicMP", only.label());
		assertTrue(only.whitelisted());

		// And the cache still wins where it has an answer: it is what the server
		// itself last saw, so it is the newer of the two after a name change.
		write(root.resolve("usercache.json"),
			"[{\"uuid\":\"" + TWO + "\",\"name\":\"Renamed\"}]");
		assertEquals("Renamed", Players.of(server, "world", Set.of()).getFirst().name());
	}

	@Test
	@DisplayName("The whitelist and the ban list are written back as the server writes them")
	void writesLists(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		write(root.resolve("whitelist.json"), "[]");

		Players.whitelist(server, "Newcomer", "", true);
		List<Players.Player> found = Players.of(server, "world", Set.of());
		assertEquals(1, found.size());
		assertTrue(found.getFirst().whitelisted());
		// With no uuid to hand, the offline one is written — which is the uuid this
		// name has on a server that does not check accounts, and the only one that
		// can be worked out without asking anybody.
		assertEquals(Ops.offline("Newcomer").toString(), found.getFirst().uuid());

		// Added twice is once: the second entry replaces the first rather than
		// giving the server two rows for one person.
		Players.whitelist(server, "Newcomer", "", true);
		assertEquals(1, Players.of(server, "world", Set.of()).size());

		Players.whitelist(server, "Newcomer", "", false);
		assertTrue(Players.of(server, "world", Set.of()).isEmpty());

		Players.ban(server, "Rude", "", "spam", true);
		Players.Ban ban = Players.bans(server).getFirst();
		assertEquals("Rude", ban.name());
		assertEquals("spam", ban.reason());
		assertEquals("forever", ban.expires());
		Players.ban(server, "Rude", "", "", false);
		assertTrue(Players.bans(server).isEmpty());
	}

	@Test
	@DisplayName("An address ban is its own list, and a bad address never reaches it")
	void ipBans(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		write(root.resolve("banned-ips.json"), "[]");

		Players.banIp(server, "203.0.113.9", "proxy", true);
		Players.IpBan ban = Players.ipBans(server).getFirst();
		assertEquals("203.0.113.9", ban.ip());
		assertEquals("proxy", ban.reason());

		// The same address twice is one entry, and pardoning takes it out.
		Players.banIp(server, "203.0.113.9", "again", true);
		assertEquals(1, Players.ipBans(server).size());
		Players.banIp(server, "203.0.113.9", "", false);
		assertTrue(Players.ipBans(server).isEmpty());

		// Nonsense is refused before it is written: the server reads this file at
		// start, and a line in it that is not an address is a server that will not
		// start, complaining about a list nobody remembers editing.
		assertThrows(IOException.class, () -> Players.banIp(server, "not an address", "", true));
		assertThrows(IOException.class, () -> Players.banIp(server, "999.1.1.1", "", true));
		assertTrue(Players.validIp("127.0.0.1"));
		assertTrue(Players.validIp("::1"));
		assertFalse(Players.validIp("1.2.3"));
	}

	private static Players.Player one(List<Players.Player> found, String name) {
		for (Players.Player each : found) {
			if (each.name().equals(name)) return each;
		}
		assertNotNull(null, "nobody called " + name + " among " + found);
		return null;
	}
}
