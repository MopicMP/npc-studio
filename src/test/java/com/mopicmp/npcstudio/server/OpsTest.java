package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two identities one name can have, and the operator entry that matches
 * neither.
 */
class OpsTest {

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	@Test
	@DisplayName("A server that does not check accounts calls people by the game's own recipe")
	void offlineIdIsTheGamesOwn() {
		// Checked against the game itself rather than against a number written
		// from memory — the number written from memory was wrong, and a wrong one
		// here is an operator entry that silently does not match.
		assertEquals(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("Notch"),
			Ops.offline("Notch"));
		assertEquals(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("mopicmp"),
			Ops.offline("mopicmp"));
		assertNotEquals(Ops.offline("Notch"), Ops.offline("notch"));
	}

	@Test
	@DisplayName("Which uuid to write depends on the setting, not on the name")
	void whichIdToUse() {
		UUID mine = UUID.fromString("11111111-2222-3333-4444-555555555555");

		assertEquals(mine, Ops.idFor("Steve", true, mine));
		assertEquals(Ops.offline("Steve"), Ops.idFor("Steve", false, mine));
		// Not signed in and the server checking anyway: there is no right answer,
		// and the offline one is at least the one that will match if the check is
		// turned off — which is what the window offers to do.
		assertEquals(Ops.offline("Steve"), Ops.idFor("Steve", true, null));
	}

	@Test
	@DisplayName("Adding somebody replaces the line that did not work")
	void addReplaces(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		UUID online = UUID.fromString("11111111-2222-3333-4444-555555555555");
		Files.writeString(Ops.file(server), "[{\"uuid\":\"" + online
			+ "\",\"name\":\"Steve\",\"level\":4}]");

		Ops.add(server, Ops.offline("Steve"), "Steve", 4);

		assertEquals(1, Ops.read(server).size(), Ops.read(server).toString());
		assertEquals(Ops.offline("Steve"), Ops.read(server).getFirst().id());
		assertTrue(Ops.has(server, "Steve", Ops.offline("Steve")));
	}

	@Test
	@DisplayName("Somebody else's list is not lost, and a broken line is not fatal")
	void keepsTheRest(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Files.writeString(Ops.file(server), "[{\"uuid\":\"not-a-uuid\",\"name\":\"Broken\"},"
			+ "{\"uuid\":\"11111111-2222-3333-4444-555555555555\",\"name\":\"Ann\",\"level\":3}]");

		Ops.add(server, Ops.offline("Steve"), "Steve", 4);

		assertEquals(2, Ops.read(server).size());
		assertTrue(Ops.read(server).stream().anyMatch(each -> each.name().equals("Ann")));
		assertEquals(3, Ops.read(server).stream()
			.filter(each -> each.name().equals("Ann")).findFirst().orElseThrow().level());
	}
}
