package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reader, against a file LuckPerms actually wrote.
 *
 * <b>The fixture is not made up.</b> {@code luckperms-export.json} is the output
 * of {@code lp export} on a real Paper server running LuckPerms 5.5.71, with the
 * groups, weights, prefixes, inheritance, a denial, a temporary node and a track
 * put there by LuckPerms' own commands. A reader tested against a file this test
 * invented would prove that the reader agrees with the test — see the two things
 * that run found and reading their source did not:
 *
 * <ul>
 * <li>a track is a name against an <em>object</em> with a {@code groups} array in
 * it, not a name against an array — the obvious reading finds no tracks at all
 * and reports that as "this server has none";
 * <li>every node carries a {@code type} saying what it is, which is better than
 * inferring the same thing from the shape of the key.
 * </ul>
 */
class LuckPermsExportTest {

	private static String real() throws IOException {
		try (InputStream stream = LuckPermsExportTest.class.getResourceAsStream(
				"/luckperms-export.json")) {
			assertNotNull(stream, "the exported fixture is missing from test resources");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("Groups come back with their names, weights, prefixes and parents")
	void groups() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of(real());

		// Heaviest first, which is the order they are argued about in.
		assertEquals(List.of("admin", "moderator", "vip", "default"),
			data.groups().stream().map(LuckPermsExport.Group::name).toList());

		LuckPermsExport.Group admin = data.group("admin");
		assertEquals(100, admin.weight());
		assertEquals("&c[Admin]", admin.prefix());
		assertEquals(List.of("moderator"), admin.parents());
		// Weight, prefix and inheritance are nodes in the file. None of them belongs
		// in a list of what the rank can do.
		assertEquals(List.of("minecraft.command.op"),
			admin.permissions().stream().map(LuckPermsExport.Permission::node).toList());
	}

	@Test
	@DisplayName("A display name is shown instead of the id, in whatever alphabet it is in")
	void displayName() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of(real());
		LuckPermsExport.Group moderator = data.group("moderator");
		// Typed in Russian, sent as a command, stored, exported and read back — the
		// whole way round, because a rank named in the wrong alphabet comes back as
		// question marks and nobody finds out until it is on somebody's screen.
		assertEquals("Модератор", moderator.display());
		assertEquals("Модератор", moderator.title());
		// And a group with no display name is called by its own name.
		assertEquals("default", data.group("default").title());
	}

	@Test
	@DisplayName("A denial is not a grant, and a temporary node says when it ends")
	void grantsAndDenials() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of(real());

		LuckPermsExport.Permission denied = one(data.group("moderator"),
			"worldguard.region.bypass.*");
		assertFalse(denied.granted());
		assertFalse(denied.temporary());

		LuckPermsExport.Permission allowed = one(data.group("moderator"), "essentials.kick");
		assertTrue(allowed.granted());

		// Set with settemp 7d, so it carries an expiry — in seconds, which is what
		// LuckPerms writes and not what a Java programmer reaches for by habit.
		LuckPermsExport.Permission temporary = one(data.group("vip"), "essentials.fly");
		assertTrue(temporary.temporary());
		assertTrue(temporary.until() > 1_700_000_000L && temporary.until() < 4_000_000_000L,
			"an expiry in seconds, got " + temporary.until());
	}

	@Test
	@DisplayName("A track is a ladder in order, read out of the shape the file really has")
	void tracks() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of(real());
		assertEquals(1, data.tracks().size());
		LuckPermsExport.Track staff = data.tracks().getFirst();
		assertEquals("staff", staff.name());
		// In order. The order is the whole point of a track: it is what "promote"
		// and "demote" mean.
		assertEquals(List.of("default", "vip", "moderator", "admin"), staff.groups());
	}

	@Test
	@DisplayName("The bare array a hand-written file might use is read as well")
	void tracksWrittenTheOtherWay() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of(
			"{\"tracks\":{\"staff\":[\"default\",\"admin\"]}}");
		assertEquals(List.of("default", "admin"), data.tracks().getFirst().groups());
	}

	@Test
	@DisplayName("The type on a node is believed before the shape of its key")
	void typeWins() throws IOException {
		// A permission that begins with one of the reserved prefixes. Read by the
		// key alone it would vanish out of the list and be taken for a weight; the
		// file says what it is, so it stays where it belongs.
		LuckPermsExport.Data data = LuckPermsExport.of("{\"groups\":{\"g\":{\"nodes\":["
			+ "{\"type\":\"permission\",\"key\":\"weight.plugin.use\",\"value\":true},"
			+ "{\"type\":\"weight\",\"key\":\"weight.5\",\"value\":true}]}}}");
		LuckPermsExport.Group group = data.group("g");
		assertEquals(5, group.weight());
		assertEquals(List.of("weight.plugin.use"),
			group.permissions().stream().map(LuckPermsExport.Permission::node).toList());
	}

	@Test
	@DisplayName("Without a type, the key prefixes still say what a node is")
	void withoutAType() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of("{\"groups\":{\"g\":{\"nodes\":["
			+ "{\"key\":\"weight.7\",\"value\":true},"
			+ "{\"key\":\"group.default\",\"value\":true},"
			+ "{\"key\":\"prefix.50.&a[G] \",\"value\":true},"
			+ "{\"key\":\"meta.somebody-elses.setting\",\"value\":true},"
			+ "{\"key\":\"plugin.use\",\"value\":true}]}}}");
		LuckPermsExport.Group group = data.group("g");
		assertEquals(7, group.weight());
		assertEquals(List.of("default"), group.parents());
		assertEquals("&a[G] ", group.prefix());
		// Another plugin's settings live under meta. Not ours to show and certainly
		// not ours to offer to delete.
		assertEquals(List.of("plugin.use"),
			group.permissions().stream().map(LuckPermsExport.Permission::node).toList());
	}

	@Test
	@DisplayName("The louder of two prefixes is the one worn")
	void prefixPriority() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of("{\"groups\":{\"g\":{\"nodes\":["
			+ "{\"type\":\"prefix\",\"key\":\"prefix.10.quiet\",\"value\":true},"
			+ "{\"type\":\"prefix\",\"key\":\"prefix.90.loud\",\"value\":true}]}}}");
		assertEquals("loud", data.group("g").prefix());
	}

	@Test
	@DisplayName("A prefix with dots in it keeps them")
	void prefixWithDots() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of("{\"groups\":{\"g\":{\"nodes\":["
			+ "{\"type\":\"prefix\",\"key\":\"prefix.5.[a.b.c] \",\"value\":true}]}}}");
		assertEquals("[a.b.c] ", data.group("g").prefix());
	}

	@Test
	@DisplayName("Somebody's groups are read, and a group they were denied is not one")
	void members() throws IOException {
		LuckPermsExport.Data data = LuckPermsExport.of("{\"users\":{"
			+ "\"2e7f81fe-4dd6-3981-b817-d323711367be\":{\"username\":\"Alice\","
			+ "\"primaryGroup\":\"admin\",\"nodes\":["
			+ "{\"type\":\"inheritance\",\"key\":\"group.admin\",\"value\":true},"
			+ "{\"type\":\"inheritance\",\"key\":\"group.banned\",\"value\":false}]}}}");
		LuckPermsExport.Member alice = data.members().getFirst();
		assertEquals("Alice", alice.name());
		assertEquals("admin", alice.primary());
		// Being pointedly told you are not in a group is not being in it.
		assertEquals(List.of("admin"), alice.groups());
		assertNotNull(LuckPermsExport.byId(data).get("2e7f81fe-4dd6-3981-b817-d323711367be"));
	}

	@Test
	@DisplayName("A gzipped file is read the way LuckPerms writes one")
	void gzipped(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("npc-studio-view.json.gz");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream zipped = new GZIPOutputStream(bytes)) {
			zipped.write(real().getBytes(StandardCharsets.UTF_8));
		}
		Files.write(file, bytes.toByteArray());
		assertEquals(4, LuckPermsExport.read(file).groups().size());
	}

	@Test
	@DisplayName("Something that is not an export is refused rather than half read")
	void nonsense(@TempDir Path folder) throws IOException {
		assertThrows(IOException.class, () -> LuckPermsExport.of("not json at all"));
		// An empty object is a server with nothing set up, which is not an error.
		assertTrue(LuckPermsExport.of("{}").groups().isEmpty());
		assertNull(LuckPermsExport.of("{}").group("admin"));

		Path file = folder.resolve("plain.json.gz");
		Files.writeString(file, "{\"groups\":{}}");
		assertThrows(IOException.class, () -> LuckPermsExport.read(file));
	}

	@Test
	@DisplayName("Which backend a server has is decided by a folder that exists")
	void backend(@TempDir Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "paper";
		server.directory = folder.toString();
		Files.createDirectories(folder);

		// A jar dropped in and never run is a plan, not a permission system.
		Files.createDirectories(folder.resolve("plugins"));
		Files.writeString(folder.resolve("plugins").resolve("LuckPerms.jar"), "not really a jar");
		assertEquals(Privileges.Kind.OPERATORS, Privileges.kindOf(server));

		Files.createDirectories(folder.resolve("plugins").resolve("LuckPerms"));
		assertEquals(Privileges.Kind.LUCK_PERMS, Privileges.kindOf(server));
		assertEquals(folder.resolve("plugins").resolve("LuckPerms")
			.resolve("npc-studio-view.json.gz"), Privileges.exportFile(server));
	}

	@Test
	@DisplayName("On Fabric the same plugin keeps its folder somewhere else")
	void backendBesideTheWorld(@TempDir Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "fabric";
		server.directory = folder.toString();
		Files.createDirectories(folder.resolve("LuckPerms"));
		assertEquals(Privileges.Kind.LUCK_PERMS, Privileges.kindOf(server));
		assertEquals(folder.resolve("LuckPerms"), Privileges.folder(server));
	}

	@Test
	@DisplayName("Names and nodes that would become a different command are refused")
	void whatMayBeSent() {
		assertTrue(Privileges.validGroup("moderator"));
		assertTrue(Privileges.validGroup("co-owner_2.0"));
		// A space makes one command argument into two, and the second lands where
		// the value goes.
		assertFalse(Privileges.validGroup("head moderator"));
		assertFalse(Privileges.validGroup(""));
		assertFalse(Privileges.validGroup("группа"));

		assertTrue(Privileges.validNode("worldguard.region.bypass.*"));
		assertFalse(Privileges.validNode("essentials.tp true"));
		assertFalse(Privileges.validNode(""));
		assertEquals("moderator", Privileges.tidy("  Moderator "));
	}

	private static LuckPermsExport.Permission one(LuckPermsExport.Group group, String node) {
		for (LuckPermsExport.Permission each : group.permissions()) {
			if (each.node().equals(node)) return each;
		}
		throw new AssertionError(group.name() + " has no " + node + ", only "
			+ group.permissions());
	}
}
