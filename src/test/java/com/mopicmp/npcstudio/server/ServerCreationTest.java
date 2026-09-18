package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Creating a server, with the download replaced by a stand-in.
 *
 * What creation has to get right has nothing to do with where a jar came from:
 * the order of the steps, the agreement, a port already taken, and — the one
 * worth the most — leaving <b>nothing</b> behind when a step fails. A folder
 * with a half-install in it is worse than no folder, because the name is then
 * taken and the reason is invisible.
 */
class ServerCreationTest {

	@TempDir
	Path folder;

	private static final CoreCatalog.Core CORE =
		new CoreCatalog.Core("vanilla", "Vanilla", "mojang", "datapacks", "https://example.invalid");

	/** A core provider that writes a file instead of fetching one. */
	private static final class Stub implements CoreProvider {
		private final boolean fail;
		int installs;

		Stub(boolean fail) {
			this.fail = fail;
		}

		@Override
		public List<String> versions() {
			return List.of("1.21.1");
		}

		@Override
		public String install(Path directory, String gameVersion, Downloads.Progress progress)
			throws IOException {
			installs++;
			// Half a download, then the failure — which is exactly the shape of a
			// real one going wrong.
			Files.writeString(directory.resolve("server.jar"), "jar", StandardCharsets.UTF_8);
			if (fail) throw new IOException("the download failed");
			return "server.jar";
		}
	}

	private ServerCreation.Request request(int port) {
		return ServerCreation.Request.of("Test Server", "vanilla", "1.21.1").agreed().on(port);
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	@Test
	@DisplayName("A created server has its jar, its agreement, its settings and its entry")
	void created() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");

		ManagedServer server = ServerCreation.create(store, base, CORE, new Stub(false),
			request(freePort()), Downloads.Progress.IGNORED);

		assertEquals("test-server", server.id);
		assertEquals("Test Server", server.name);
		assertEquals("vanilla", server.core);
		assertEquals("1.21.1", server.gameVersion);
		assertEquals("server.jar", server.jar);

		Path directory = base.resolve("test-server");
		assertTrue(Files.isRegularFile(directory.resolve("server.jar")));
		assertTrue(Eula.accepted(directory));

		PropertiesFile properties = PropertiesFile.read(directory.resolve("server.properties"));
		assertEquals("Test Server", properties.get("motd"));
		assertEquals(String.valueOf(server.port), properties.get("server-port"));
		assertEquals("true", properties.get("online-mode"));
		assertEquals("world", properties.get("level-name"));

		// And it survives the game closing.
		assertTrue(new ServerStore(folder.resolve("servers.json")).byId("test-server").isPresent());
	}

	@Test
	@DisplayName("Nothing is created for somebody who has not agreed to the terms")
	void withoutAgreeing() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");
		Stub stub = new Stub(false);

		assertThrows(IllegalStateException.class, () -> ServerCreation.create(store, base, CORE,
			stub, ServerCreation.Request.of("Test Server", "vanilla", "1.21.1"),
			Downloads.Progress.IGNORED));

		// Not merely "no entry in the list": nothing was downloaded and no folder
		// was made, because the check happens before any of it.
		assertEquals(0, stub.installs);
		assertFalse(Files.exists(base.resolve("test-server")));
		assertEquals(0, store.all().size());
	}

	@Test
	@DisplayName("A failed install leaves no folder, no entry and no taken name")
	void failedInstall() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");
		int port = freePort();

		assertThrows(IOException.class, () -> ServerCreation.create(store, base, CORE,
			new Stub(true), request(port), Downloads.Progress.IGNORED));

		assertFalse(Files.exists(base.resolve("test-server")),
			"a half-installed server folder was left behind");
		assertEquals(0, store.all().size());

		// And the name is free, so the second attempt is not called "test-server-2".
		ManagedServer server = ServerCreation.create(store, base, CORE, new Stub(false),
			request(port), Downloads.Progress.IGNORED);
		assertEquals("test-server", server.id);
	}

	@Test
	@DisplayName("A port already in use is reported rather than quietly moved")
	void portTaken() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");
		try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			IOException thrown = assertThrows(IOException.class, () ->
				ServerCreation.create(store, base, CORE, new Stub(false),
					request(held.getLocalPort()), Downloads.Progress.IGNORED));
			assertTrue(thrown.getMessage().contains("already in use"), thrown.getMessage());
		}
		assertFalse(Files.exists(base.resolve("test-server")));
	}

	@Test
	@DisplayName("Two servers of the same name get two folders")
	void sameName() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");

		ServerCreation.create(store, base, CORE, new Stub(false),
			request(freePort()), Downloads.Progress.IGNORED);
		ManagedServer second = ServerCreation.create(store, base, CORE, new Stub(false),
			request(freePort()), Downloads.Progress.IGNORED);

		assertEquals("test-server-2", second.id);
		assertTrue(Files.isDirectory(base.resolve("test-server")));
		assertTrue(Files.isDirectory(base.resolve("test-server-2")));
	}

	@Test
	@DisplayName("A folder that already has something in it is not built over")
	void occupied() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");
		Path directory = base.resolve("test-server");
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("world-of-somebody-else.txt"), "keep me",
			StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> ServerCreation.create(store, base, CORE,
			new Stub(false), request(freePort()), Downloads.Progress.IGNORED));

		assertTrue(Files.exists(directory.resolve("world-of-somebody-else.txt")),
			"somebody else's files were removed");
	}

	@Test
	@DisplayName("A list that could not be read does not silently swallow a new server")
	void unreadableList() throws IOException {
		Path list = folder.resolve("servers.json");
		Files.writeString(list, "{ broken", StandardCharsets.UTF_8);
		ServerStore store = new ServerStore(list);
		Stub stub = new Stub(false);

		assertThrows(IOException.class, () -> ServerCreation.create(store, folder.resolve("servers"),
			CORE, stub, request(freePort()), Downloads.Progress.IGNORED));
		assertEquals(0, stub.installs, "the core was downloaded for a server that could not be kept");
	}

	@Test
	@DisplayName("The agreement is written only where a server was actually made")
	void agreementIsPerServer() throws IOException {
		ServerStore store = new ServerStore(folder.resolve("servers.json"));
		Path base = folder.resolve("servers");
		ServerCreation.create(store, base, CORE, new Stub(false),
			request(freePort()), Downloads.Progress.IGNORED);

		assertTrue(Eula.accepted(base.resolve("test-server")));
		assertFalse(Eula.accepted(base));
		assertFalse(Files.exists(base.resolve("eula.txt")));
	}
}
