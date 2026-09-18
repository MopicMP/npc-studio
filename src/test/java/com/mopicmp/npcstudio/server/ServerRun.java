package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Puts a real server up, talks to it, and takes it down again.
 *
 * The one thing the tests cannot do. {@code RconTest} proves the client agrees
 * with the protocol as we understood it; only this proves the understanding.
 * The same goes for the rest of the first stage — a process that outlives the
 * session that started it, a console read from a file, a shutdown that saves.
 *
 * Deliberately three commands rather than one, because the interesting claim is
 * about <b>outliving</b>:
 *
 * <pre>
 * gradlew checkServer -Pmode=start -Peula=accepted   puts it up, then exits
 * gradlew checkServer -Pmode=attach                  a new JVM finds it running
 * gradlew checkServer -Pmode=stop                    asks it to stop, and waits
 * </pre>
 *
 * Between the first and the second this whole process ends. If the server were a
 * child in the way that matters — dying with its parent, reachable only down a
 * pipe — the second command would find nothing.
 *
 * <b>The agreement is a flag, not a default.</b> Nothing here writes
 * {@code eula.txt} unless a person typed {@code -Peula=accepted}, because that
 * is the whole content of the file: somebody saying they agreed.
 */
public final class ServerRun {

	private static final Path BASE = Path.of("build", "live-check");
	private static final Path LIST = BASE.resolve("servers.json");
	private static final String ID = "live-check";

	public static void main(String[] arguments) throws Exception {
		String mode = System.getProperty("mode", "start");
		switch (mode) {
			case "start" -> start();
			case "attach" -> attach();
			case "stop" -> stop();
			case "probe" -> probe();
			case "clean" -> clean();
			default -> {
				System.out.println("mode must be start, attach, stop or clean");
				System.exit(1);
			}
		}
	}

	private static ManagedServer only(ServerStore store) {
		Optional<ManagedServer> found = store.byId(ID);
		if (found.isEmpty()) {
			System.out.println("No server has been created yet. Run with -Pmode=start first.");
			System.exit(1);
		}
		return found.get();
	}

	private static void start() throws Exception {
		if (!"accepted".equals(System.getProperty("eula", ""))) {
			System.out.println("This creates and runs a Minecraft server, which requires"
				+ " agreeing to the Minecraft EULA:");
			System.out.println("  " + Eula.URL);
			System.out.println("Run again with -Peula=accepted if you agree.");
			System.exit(1);
		}

		Files.createDirectories(BASE);
		ServerStore store = new ServerStore(LIST);
		if (store.byId(ID).isPresent()) {
			System.out.println("There is already a live-check server. Use -Pmode=stop"
				+ " and then -Pmode=clean.");
			System.exit(1);
		}

		CoreCatalog catalog = CoreCatalog.bundled();
		CoreCatalog.Core core = catalog.byId("vanilla").orElseThrow();
		CoreProvider provider = CoreProvider.of(core, catalog.downloads()).orElseThrow();
		String version = provider.versions().getFirst();
		int port = freePort();

		System.out.println("Creating a vanilla " + version + " server on port " + port);
		long[] shown = {-1};
		ManagedServer server = ServerCreation.create(store, BASE, core, provider,
			ServerCreation.Request.of("Live Check", "vanilla", version)
				.agreed().on(port).withMemory(1024),
			(done, total) -> {
				long percent = total > 0 ? done * 100 / total : -1;
				if (percent >= 0 && percent / 25 != shown[0] / 25) {
					shown[0] = percent;
					System.out.println("  " + percent + "%");
				}
			});
		System.out.println("  " + server.jar + " in " + server.directory);

		System.out.println("Starting it");
		ServerProcess.start(server);
		store.save();
		System.out.println("  process " + server.pid + ", command port " + server.rconPort);

		// Read the console the way the panel will: out of the file, not down a pipe.
		LogTail tail = LogTail.whole(server.consolePath());
		long until = System.currentTimeMillis() + 300_000;
		boolean up = false;
		while (System.currentTimeMillis() < until && !up) {
			for (String line : tail.poll()) {
				System.out.println("  | " + line);
				if (line.contains("For help, type") || line.contains("Done (")) up = true;
			}
			if (!up) {
				if (!ServerProcess.running(server)) {
					System.out.println("The server stopped before it finished starting.");
					System.exit(1);
				}
				Thread.sleep(500);
			}
		}
		if (!up) {
			System.out.println("The server did not finish starting within five minutes.");
			System.exit(1);
		}

		System.out.println("Asking it something over the command channel");
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			System.out.println("  list -> " + rcon.command("list"));
			System.out.println("  seed -> " + rcon.command("seed"));
			// Long output, to exercise the part that arrives in several packets.
			String help = rcon.command("help");
			System.out.println("  help -> " + help.length() + " characters, "
				+ help.lines().count() + " lines");
		}

		System.out.println();
		System.out.println("Left running on purpose. This process is about to end;"
			+ " the server should not.");
		System.out.println("Now run: gradlew checkServer -Pmode=attach");
	}

	private static void attach() throws Exception {
		ServerStore store = new ServerStore(LIST);
		ManagedServer server = only(store);

		System.out.println("A new JVM, looking for process " + server.pid);
		Optional<ProcessHandle> handle = ServerProcess.attach(server);
		if (handle.isEmpty()) {
			System.out.println("  not found — it did not outlive the session that started it");
			System.exit(1);
		}
		System.out.println("  found, alive since " + handle.get().info().startInstant().orElse(null));

		// The console picks up where somebody would be looking, not from the top.
		LogTail tail = LogTail.recent(server.consolePath());
		List<String> recent = tail.poll();
		System.out.println("  console: " + recent.size() + " recent lines");
		if (!recent.isEmpty()) System.out.println("  | " + recent.getLast());

		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			System.out.println("  list -> " + rcon.command("list"));
		}

		// And the guard against a reused id, on a process that really is running.
		long remembered = server.pidStarted;
		server.pidStarted = remembered - 60_000;
		boolean fooled = ServerProcess.attach(server).isPresent();
		server.pidStarted = remembered;
		System.out.println("  with the wrong start time: "
			+ (fooled ? "still found, which is wrong" : "not found, which is right"));
		if (fooled) System.exit(1);

		System.out.println();
		System.out.println("It outlived us. Now run: gradlew checkServer -Pmode=stop");
	}

	private static void stop() throws Exception {
		ServerStore store = new ServerStore(LIST);
		ManagedServer server = only(store);
		if (!ServerProcess.running(server)) {
			System.out.println("It is not running.");
			return;
		}

		System.out.println("Asking process " + server.pid + " to stop, and waiting for the save");
		long began = System.currentTimeMillis();
		boolean stopped = ServerProcess.stop(server, 120);
		System.out.println("  " + (stopped ? "stopped" : "still up")
			+ " after " + (System.currentTimeMillis() - began) + " ms");
		store.save();

		LogTail tail = LogTail.recent(server.consolePath());
		for (String line : tail.poll()) {
			if (line.contains("Saving") || line.contains("Stopping") || line.contains("ThreadedAnvilChunkStorage")) {
				System.out.println("  | " + line);
			}
		}
		// The world is there and has been written, which is what a polite shutdown
		// is for.
		Path level = server.path().resolve("world").resolve("level.dat");
		System.out.println("  world/level.dat: "
			+ (Files.exists(level) ? Files.size(level) + " bytes" : "missing"));
		if (!stopped) System.exit(1);
	}

	/**
	 * Asks the running server, in raw packets, what it does with each approach.
	 *
	 * Written because a live run failed and the reason had to be found out rather
	 * than reasoned about. Three questions: does a command on its own work, does
	 * an unknown packet type get an answer, and does sending a second packet
	 * straight after a command survive.
	 */
	private static void probe() throws Exception {
		ServerStore store = new ServerStore(LIST);
		ManagedServer server = only(store);

		System.out.println("1. a command on its own");
		converse(server, out -> {
			out.write(new RconFrame(10, RconFrame.TYPE_COMMAND, "list").encode());
			out.flush();
		});

		System.out.println("2. a command, then an unknown packet type, in two writes");
		converse(server, out -> {
			out.write(new RconFrame(20, RconFrame.TYPE_COMMAND, "list").encode());
			out.flush();
			out.write(new RconFrame(21, RconFrame.TYPE_RESPONSE, "").encode());
			out.flush();
		});

		System.out.println("3. long output, to see how it is split");
		converse(server, out -> {
			out.write(new RconFrame(30, RconFrame.TYPE_COMMAND, "help").encode());
			out.flush();
		});
	}

	private interface Ask {
		void write(java.io.OutputStream out) throws IOException;
	}

	private static void converse(ManagedServer server, Ask ask) {
		try (java.net.Socket socket = new java.net.Socket()) {
			socket.setTcpNoDelay(true);
			socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.rconPort), 5000);
			socket.setSoTimeout(3000);
			java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
			java.io.OutputStream out = socket.getOutputStream();

			out.write(new RconFrame(1, RconFrame.TYPE_AUTH, server.rconPassword).encode());
			out.flush();
			System.out.println("   login: " + read(in));

			ask.write(out);
			for (int packet = 0; packet < 6; packet++) {
				String reply = read(in);
				System.out.println("   " + reply);
				if (reply.startsWith("closed") || reply.startsWith("nothing")) break;
			}
		} catch (IOException failed) {
			System.out.println("   " + failed);
		}
	}

	private static String read(java.io.DataInputStream in) {
		try {
			byte[] header = new byte[4];
			in.readFully(header);
			byte[] payload = new byte[RconFrame.length(header)];
			in.readFully(payload);
			RconFrame frame = RconFrame.decode(payload);
			String body = frame.body();
			return "id=" + frame.id() + " type=" + frame.type()
				+ " body=" + body.length() + " chars"
				+ (body.isEmpty() ? "" : " [" + body.substring(0, Math.min(60, body.length()))
					.replace("\n", "\\n") + "]");
		} catch (java.io.EOFException closed) {
			return "closed by the server";
		} catch (java.net.SocketTimeoutException quiet) {
			return "nothing came back within three seconds";
		} catch (IOException failed) {
			return "error: " + failed;
		}
	}

	private static void clean() throws IOException {
		if (!Files.exists(BASE)) return;
		try (var walk = Files.walk(BASE)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException stubborn) {
					System.out.println("  could not remove " + path);
				}
			});
		}
		System.out.println("Removed " + BASE);
	}

	private static int freePort() throws IOException {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1)) {
			return socket.getLocalPort();
		}
	}
}
