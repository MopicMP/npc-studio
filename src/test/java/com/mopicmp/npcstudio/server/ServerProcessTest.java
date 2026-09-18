package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of starting a server that can be checked without starting one.
 *
 * Two of them are worth this much care. The command line, because "it will not
 * start" is answered in seconds by somebody who can read the exact line and in
 * an evening by somebody who cannot. And finding the process again, because the
 * server is deliberately outlived by the game session that started it, so the
 * only handle we have on it later is a number the operating system reuses.
 */
class ServerProcessTest {

	private static ManagedServer server(Path folder) {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.directory = folder.toString();
		server.jar = "server.jar";
		server.memoryMb = 2048;
		server.port = 25565;
		server.rconPort = 25999;
		return server;
	}

	@Test
	@DisplayName("The command line is java, the heap, the jar, and nogui")
	void commandLine() {
		ManagedServer server = server(Path.of("C:", "servers", "test"));
		server.jvmArgs = List.of("-XX:+UseG1GC");

		List<String> line = ServerProcess.commandLine(server, Path.of("java"));

		assertEquals("java", line.get(0));
		assertEquals("-Xms2048M", line.get(1));
		assertEquals("-Xmx2048M", line.get(2));
		assertEquals("-jar", line.get(line.indexOf("-jar")));
		assertEquals(Path.of("C:", "servers", "test", "server.jar").toString(),
			line.get(line.indexOf("-jar") + 1));
		assertEquals("nogui", line.getLast());
	}

	@Test
	@DisplayName("The jar is named absolutely, because the process runs inside its folder")
	void absoluteJar() {
		// Found by running one: a relative directory produced "-jar
		// build/servers/x/server.jar" for a process whose working directory was
		// already build/servers/x, so it looked for the jar inside itself. The
		// server's own message is "Unable to access jarfile", which says nothing
		// about two paths having been combined.
		ManagedServer server = server(Path.of("build", "servers", "relative"));
		List<String> line = ServerProcess.commandLine(server, Path.of("java"));
		String jar = line.get(line.indexOf("-jar") + 1);
		assertTrue(Path.of(jar).isAbsolute(), "the jar path is relative: " + jar);
		assertTrue(jar.endsWith("server.jar"), jar);
	}

	@Test
	@DisplayName("A heap smaller than the floor is raised to it, at both ends")
	void smallHeap() {
		ManagedServer server = server(Path.of("."));
		server.memoryMb = 128;
		List<String> line = ServerProcess.commandLine(server, Path.of("java"));
		assertEquals("-Xms512M", line.get(1));
		assertEquals("-Xmx512M", line.get(2));
	}

	@Test
	@DisplayName("The child is told to write its output in UTF-8")
	void encoding() {
		// Found by reading one. A JVM whose output is redirected to a file writes
		// it in the operating system's encoding, and this reads that file as UTF-8
		// — so on a Russian Windows every letter of Fabric's translated start-up
		// lines arrived as one replacement character. The whole first screenful of
		// the console was diamonds.
		List<String> line = ServerProcess.commandLine(server(Path.of(".")), Path.of("java"));
		assertTrue(line.contains("-Dstdout.encoding=UTF-8"), line.toString());
		assertTrue(line.contains("-Dstderr.encoding=UTF-8"), line.toString());
		assertTrue(line.contains("-Dfile.encoding=UTF-8"), line.toString());
		// Before the jar, or the server would take them for its own arguments.
		assertTrue(line.indexOf("-Dstdout.encoding=UTF-8") < line.indexOf("-jar"), line.toString());
	}

	@Test
	@DisplayName("Nothing is passed to the collector, and that is the setting")
	void noTuning() {
		// This is a guard on a finding, not on a style. Five collector flags were
		// added here once — a pause target, a periodic collection, and free-ratio
		// bounds that make G1 resize the heap constantly — and they turned a
		// server that carried three people into one that threw its only player
		// off. Anything added back belongs beside a measurement on a running
		// server.
		List<String> line = ServerProcess.commandLine(server(Path.of(".")), Path.of("java"));
		for (String argument : line) {
			assertTrue(!argument.startsWith("-XX:"),
				"a collector flag came back without a measurement: " + argument);
		}
		assertEquals("-Xms2048M", line.get(1));
		assertEquals("-Xmx2048M", line.get(2));
	}

	@Test
	@DisplayName("A server with no properties yet gets a command channel and a password")
	void firstStart() {
		PropertiesFile properties = PropertiesFile.empty();
		ManagedServer server = server(Path.of("."));
		server.rconPassword = "";

		ServerProcess.applyRcon(properties, server);

		assertEquals("true", properties.get("enable-rcon"));
		assertEquals("25999", properties.get("rcon.port"));
		assertEquals("25565", properties.get("server-port"));
		assertEquals(24, server.rconPassword.length());
		assertEquals(server.rconPassword, properties.get("rcon.password"));

		// Generated, not a constant with a clever name.
		ManagedServer second = server(Path.of("."));
		second.rconPassword = "";
		ServerProcess.applyRcon(PropertiesFile.empty(), second);
		assertNotEquals(server.rconPassword, second.rconPassword);
	}

	@Test
	@DisplayName("A password already in the file is adopted, never replaced")
	void adopted() {
		// Somebody who set their own has other tools pointed at this server, and
		// overwriting it would break every one of them without saying so.
		PropertiesFile properties = PropertiesFile.parse("""
			#Minecraft server properties
			enable-rcon=false
			rcon.password=theirs
			rcon.port=25580
			motd=A Minecraft Server
			""");
		ManagedServer server = server(Path.of("."));
		server.rconPassword = "ours";

		ServerProcess.applyRcon(properties, server);

		assertEquals("theirs", server.rconPassword);
		assertEquals(25580, server.rconPort);
		// Only the switch is ours to change: without it there is no channel at all.
		assertEquals("true", properties.get("enable-rcon"));
		assertTrue(properties.text().contains("#Minecraft server properties"));
		assertEquals("A Minecraft Server", properties.get("motd"));
	}

	@Test
	@DisplayName("A process id with nothing behind it is not a running server")
	void nothingThere() {
		ManagedServer server = server(Path.of("."));
		server.pid = 0;
		assertTrue(ServerProcess.attach(server).isEmpty());

		// An id that is almost certainly not in use, and certainly did not start
		// when we say ours did.
		server.pid = 999_999_999;
		server.pidStarted = System.currentTimeMillis();
		assertTrue(ServerProcess.attach(server).isEmpty());
	}

	@Test
	@DisplayName("A reused process id is not mistaken for the server that had it")
	void reusedId() {
		// The stand-in is this very test: it is alive, it is java, and its start
		// time is known — which is every check attach makes.
		ProcessHandle self = ProcessHandle.current();
		assumeTrue(self.info().startInstant().isPresent(),
			"this system does not report process start times");
		long started = self.info().startInstant().map(Instant::toEpochMilli).orElseThrow();

		ManagedServer server = server(Path.of("."));
		server.pid = self.pid();
		server.pidStarted = started;
		assertTrue(ServerProcess.attach(server).isPresent(),
			"a live process with the right start time should be found");

		// The same id, remembered from a process that started a minute earlier.
		// Without the start time this would report somebody else's program as the
		// server, and offer to stop it.
		server.pidStarted = started - 60_000;
		assertTrue(ServerProcess.attach(server).isEmpty());
	}

	@Test
	@DisplayName("Forgetting a process clears both halves of its identity")
	void forget() {
		ManagedServer server = server(Path.of("."));
		server.pid = 4242;
		server.pidStarted = System.currentTimeMillis();
		ServerProcess.forget(server);
		assertEquals(0, server.pid);
		assertEquals(0, server.pidStarted);
	}
}
