package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The list that is the only record of where a server lives.
 *
 * Two things are checked here that are not about storage at all. That a folder
 * name built from something a person typed cannot climb out of its folder —
 * the rule in {@code docs/security.md} about a name never becoming a path. And
 * that an unreadable file is left alone rather than replaced: preferences can be
 * rebuilt from defaults, and this cannot, because the password to a running
 * server is in it.
 */
class ServerStoreTest {

	@TempDir
	Path folder;

	private ManagedServer made(ServerStore store, String name) {
		ManagedServer server = new ManagedServer();
		server.id = store.idFor(name);
		server.name = name;
		server.directory = folder.resolve(server.id).toString();
		server.rconPassword = ManagedServer.newSecret();
		return server;
	}

	@Test
	@DisplayName("A saved list comes back as it went in")
	void roundTrip() {
		Path file = folder.resolve("servers.json");
		ServerStore store = new ServerStore(file);
		ManagedServer server = made(store, "Test Server");
		server.core = "fabric";
		server.gameVersion = "26.2";
		server.memoryMb = 3072;
		server.rconPort = 25580;
		store.add(server);
		store.save();

		ServerStore again = new ServerStore(file);
		assertEquals(1, again.all().size());
		ManagedServer read = again.byId("test-server").orElseThrow();
		assertEquals("Test Server", read.name);
		assertEquals("fabric", read.core);
		assertEquals(3072, read.memoryMb);
		assertEquals(25580, read.rconPort);
		assertEquals(server.rconPassword, read.rconPassword);
		assertEquals(java.util.List.of("nogui"), read.gameArgs);
	}

	@Test
	@DisplayName("A name becomes a folder name that cannot leave its folder")
	void names() {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		assertEquals("test-server", store.idFor("Test Server"));
		assertEquals("survival-2026", store.idFor("Survival 2026"));

		// The shapes that are the whole reason this is built rather than filtered.
		assertEquals("etc-passwd", store.idFor("../../etc/passwd"));
		assertEquals("c-windows", store.idFor("C:\\Windows"));
		assertEquals("server", store.idFor(".."));
		assertEquals("server", store.idFor(""));

		// Windows will not create a folder called "con", whatever it is for.
		assertEquals("con-server", store.idFor("Con"));
		assertEquals("com1-server", store.idFor("COM1"));

		// Nothing in a result can be anything but a letter, a digit or a dash.
		for (String odd : new String[] {"../..", "a\u0000b", "CON", "  ", "мой сервер"}) {
			String id = store.idFor(odd);
			assertTrue(id.matches("[a-z0-9-]+"), "not a safe folder name: " + id);
			assertFalse(id.startsWith("-") || id.endsWith("-"), "stray dash: " + id);
		}
	}

	@Test
	@DisplayName("Two servers with the same name get different folders")
	void unique() {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		store.add(made(store, "Survival"));
		assertEquals("survival-2", store.idFor("Survival"));
		store.add(made(store, "Survival"));
		assertEquals("survival-3", store.idFor("Survival"));

		assertThrows(IllegalArgumentException.class, () -> {
			ManagedServer clash = new ManagedServer();
			clash.id = "survival";
			store.add(clash);
		});
	}

	@Test
	@DisplayName("An unreadable list is reported and left exactly as it was")
	void unreadable() throws IOException {
		Path file = folder.resolve("servers.json");
		String damaged = "{\"servers\": [ this is not json";
		Files.writeString(file, damaged, StandardCharsets.UTF_8);

		ServerStore store = new ServerStore(file);
		assertTrue(store.isUnreadable());
		assertEquals(0, store.all().size());

		store.save();
		assertEquals(damaged, Files.readString(file),
			"the damaged file was overwritten, and with it the only record of a server");
	}

	@Test
	@DisplayName("A missing list is an empty one, not a failure")
	void missing() {
		ServerStore store = new ServerStore(folder.resolve("nothing-here.json"));
		assertFalse(store.isUnreadable());
		assertEquals(0, store.all().size());
	}

	@Test
	@DisplayName("Removing a server takes it out of the file too")
	void removed() {
		Path file = folder.resolve("servers.json");
		ServerStore store = new ServerStore(file);
		store.add(made(store, "One"));
		store.add(made(store, "Two"));
		store.save();

		store.remove("one");
		store.save();

		ServerStore again = new ServerStore(file);
		assertEquals(1, again.all().size());
		assertTrue(again.byId("two").isPresent());
	}

	@Test
	@DisplayName("A generated password is long and not the same one twice")
	void secrets() {
		String first = ManagedServer.newSecret();
		String second = ManagedServer.newSecret();
		assertEquals(24, first.length());
		assertFalse(first.equals(second));
		assertTrue(first.matches("[A-Za-z0-9]+"));
	}
}
