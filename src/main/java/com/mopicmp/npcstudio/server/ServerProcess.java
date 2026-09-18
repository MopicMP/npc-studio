package com.mopicmp.npcstudio.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Starting a server, finding it again, and stopping it.
 *
 * The decision this whole class is built around: <b>the server does not belong
 * to the game session that started it.</b> A process started here keeps running
 * when the game closes, and the next time the manager opens it finds the same
 * server still up and attaches to it. That is the entire difference between this
 * and "open to LAN", and it is what people actually want from a server — that
 * their friends are not thrown off because the host alt-tabbed into a crash.
 *
 * Everything awkward here follows from that one decision:
 *
 * <ul>
 * <li>no pipe. Standard output goes to a file, standard input comes from
 * nowhere, and commands go over {@link Rcon} — because a pipe dies with the
 * parent and a socket does not;</li>
 * <li>a process id is not enough to find it again. Ids are reused, so the moment
 * it started is stored beside it;</li>
 * <li><b>stopping is RCON or nothing.</b> On Windows there is no polite kill:
 * {@code ProcessHandle.destroy()} is a hard terminate, which for a Minecraft
 * server means the world as it was some minutes ago. So the command channel is
 * not a convenience on a local server, it is the only correct way to stop
 * one, and forcing is offered as what it is — losing whatever was not saved.</li>
 * </ul>
 */
public final class ServerProcess {

	private ServerProcess() {
	}

	/** How far apart two readings of one process's start time may be and still be it. */
	private static final long START_TOLERANCE_MS = 10_000;

	/**
	 * Make sure the command channel will exist when the server comes up.
	 *
	 * Runs before every start rather than once at creation, because these are
	 * read by the server at boot and cannot be changed in a running one — and
	 * because the file belongs to the owner, who may have edited it between two
	 * starts.
	 *
	 * An existing password is adopted, not replaced. Somebody who set their own
	 * has a reason, and quietly overwriting it would break every other tool they
	 * point at this server.
	 */
	static void applyRcon(PropertiesFile properties, ManagedServer server) {
		String existing = properties.getOr("rcon.password", "").trim();
		if (!existing.isEmpty()) {
			server.rconPassword = existing;
		} else if (server.rconPassword == null || server.rconPassword.isBlank()) {
			server.rconPassword = ManagedServer.newSecret();
		}

		int stated = properties.getInt("rcon.port", 0);
		if (stated > 0) {
			server.rconPort = stated;
		} else if (server.rconPort <= 0) {
			int picked = Ports.pickRcon();
			server.rconPort = picked > 0 ? picked : 25575;
		}

		properties.set("enable-rcon", "true");
		properties.set("rcon.port", String.valueOf(server.rconPort));
		properties.set("rcon.password", server.rconPassword);
		if (server.port > 0) properties.set("server-port", String.valueOf(server.port));
	}

	private static void prepare(ManagedServer server) throws IOException {
		Path file = server.propertiesPath();
		PropertiesFile properties = Files.exists(file)
			? PropertiesFile.read(file)
			: PropertiesFile.empty();
		applyRcon(properties, server);
		properties.write(file);
	}

	/**
	 * The whole command line, as a list.
	 *
	 * Separated out and free of side effects so that what gets launched can be
	 * checked in a test and shown in the interface. "It will not start" is
	 * answered much faster by a person who can read the exact line than by one
	 * being told that starting failed.
	 */
	public static List<String> commandLine(ManagedServer server, Path java) {
		List<String> line = new ArrayList<>();
		line.add(java.toString());
		int heap = Math.max(512, server.memoryMb);
		// The floor is the ceiling, which is what every server start script does
		// and what this got wrong. Starting small and growing sounds thrifty and
		// buys a pause every time the heap is resized — and a resize on G1 can be
		// a full collection. On a machine where the game is running beside the
		// server, those pauses are what throws people off it.
		line.add("-Xms" + heap + "M");
		line.add("-Xmx" + heap + "M");
		line.addAll(encoding());
		line.addAll(Machine.jvmFlags());
		if (server.jvmArgs != null) line.addAll(server.jvmArgs);
		line.add("-jar");
		// Absolute, and not because it is tidier. The process runs with the server
		// folder as its working directory, so a relative path here is resolved
		// against that folder — and a server recorded with a relative directory
		// then looks for its jar inside itself. The error is "Unable to access
		// jarfile", which says nothing about the two paths being combined.
		line.add(server.jarPath().toAbsolutePath().toString());
		if (server.gameArgs != null) line.addAll(server.gameArgs);
		return line;
	}

	/**
	 * Tell the child which alphabet to write its output in.
	 *
	 * Not tuning, and not a preference: it is the difference between a console
	 * anybody can read and one that is a row of diamonds. A JVM whose output goes
	 * to a file rather than to a terminal chooses the <em>operating system's</em>
	 * encoding for it — cp1251 on a Russian Windows — while this reads the file as
	 * UTF-8, so every Cyrillic letter arrived as one byte that is not valid UTF-8
	 * and came out as one replacement character. Fabric's loader translates its own
	 * start-up lines, so the very first screenful was the part that broke.
	 *
	 * Three properties rather than one because they are three different decisions
	 * in the JVM: {@code file.encoding} is what the server's logger writes with,
	 * and the two stream properties are what everything printed before the logger
	 * exists goes through.
	 */
	private static List<String> encoding() {
		return List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
			"-Dstderr.encoding=UTF-8");
	}

	/**
	 * Start it, and remember enough to find it again.
	 *
	 * @throws IOException if the folder or the jar is not there, or the process
	 *                     could not be created at all
	 */
	public static ProcessHandle start(ManagedServer server) throws IOException {
		if (running(server)) {
			throw new IOException("That server is already running as process " + server.pid);
		}
		// And nothing else is serving on its port either. This is not caution: it
		// has happened, and the log of it is why the check is here. A server was
		// started, its identifier was lost — see {@link #attach} — the window then
		// showed it as down, it was started a second time, the second one could not
		// bind the port and died, and the first went on running for hours with
		// nothing in this mod able to see it or stop it.
		if (Ping.answers("127.0.0.1", server.port)) {
			throw new IOException("Something is already serving Minecraft on port " + server.port
				+ ". If that is this server, it is already up; if it is another, give this one a"
				+ " port of its own.");
		}
		Path directory = server.path();
		if (!Files.isDirectory(directory)) {
			throw new IOException("No server folder at " + directory);
		}
		if (!Files.isRegularFile(server.jarPath())) {
			throw new IOException("No server jar at " + server.jarPath());
		}
		prepare(server);

		Path java = JavaBinary.forServer(server);
		List<String> line = commandLine(server, java);
		rotate(server);
		banner(server, line);

		ProcessBuilder builder = new ProcessBuilder(line)
			.directory(directory.toFile())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(server.consolePath().toFile()))
			.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

		Process process = builder.start();
		server.pid = process.pid();
		server.pidStarted = process.info().startInstant()
			.map(Instant::toEpochMilli)
			.orElseGet(System::currentTimeMillis);
		writeMark(server);
		NpcStudio.LOGGER.info("Started server '{}' as process {}", server.id, server.pid);
		// After the start rather than as part of it: how many cores a process may
		// use is not something a command line can say, and it is not worth failing
		// a start over.
		Cpu.apply(server.pid, server.cores, Cpu.Priority.of(server.priority));
		return process.toHandle();
	}

	/**
	 * A line of our own at the top of every run.
	 *
	 * The console file is appended to across restarts, and without this the log
	 * of a server that has been up and down four times is one undivided wall.
	 * Written before the process starts, so it is also there when the process
	 * never does.
	 */
	/** Past this the console file is put aside, so it cannot grow without end. */
	private static final long CONSOLE_LIMIT = 8L * 1024 * 1024;

	/**
	 * Keep one old console beside the current one, and no more.
	 *
	 * The file is appended to across every start, so a server that has been up
	 * and down for a month has a log nobody will read and a disk that noticed.
	 * One generation is kept because the interesting one is usually the run
	 * before the one you are looking at.
	 */
	private static void rotate(ManagedServer server) {
		Path console = server.consolePath();
		try {
			if (!Files.isRegularFile(console) || Files.size(console) < CONSOLE_LIMIT) return;
			Files.move(console, console.resolveSibling("npc-studio-console.old.log"),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException stubborn) {
			NpcStudio.LOGGER.warn("Could not set aside the console of '{}': {}",
				server.id, stubborn.getMessage());
		}
	}

	private static void banner(ManagedServer server, List<String> line) {
		String text = System.lineSeparator()
			+ "--- npc studio: starting '" + server.id + "' at " + Instant.now() + System.lineSeparator()
			+ "--- " + String.join(" ", line) + System.lineSeparator();
		try {
			Files.writeString(server.consolePath(), text, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException unwritable) {
			NpcStudio.LOGGER.warn("Could not write the console file for '{}': {}",
				server.id, unwritable.getMessage());
		}
	}

	private static File nullDevice() {
		boolean windows = System.getProperty("os.name", "")
			.toLowerCase(Locale.ROOT).startsWith("win");
		return new File(windows ? "NUL" : "/dev/null");
	}

	/**
	 * The process this server was last started as, if it is still that process.
	 *
	 * Three checks, and they are not the same check. It exists; it started when
	 * ours did, which is what rules out a reused id; and it is a java binary,
	 * which is a cheap second opinion for the case where the machine's clock
	 * moved between the two readings.
	 */
	public static Optional<ProcessHandle> attach(ManagedServer server) {
		if (server.pid <= 0) {
			// Nothing in memory, so ask the server's own folder. See writeMark: the
			// list this mod keeps is not the only record any more, precisely because
			// it turned out to be losable.
			return adopt(server);
		}
		Optional<ProcessHandle> found = ProcessHandle.of(server.pid);
		if (found.isEmpty() || !found.get().isAlive()) return Optional.empty();

		ProcessHandle handle = found.get();
		if (server.pidStarted > 0) {
			Optional<Instant> started = handle.info().startInstant();
			if (started.isPresent()
				&& Math.abs(started.get().toEpochMilli() - server.pidStarted) > START_TOLERANCE_MS) {
				return Optional.empty();
			}
		}
		Optional<String> command = handle.info().command();
		// Absent is not suspicious: on some systems another process's command line
		// is simply not readable. Present and not java is.
		if (command.isPresent()) {
			String name = Path.of(command.get()).getFileName().toString()
				.toLowerCase(Locale.ROOT);
			if (!name.startsWith("java")) return Optional.empty();
		}
		return Optional.of(handle);
	}

	public static boolean running(ManagedServer server) {
		return attach(server).isPresent();
	}

	/** Forget a process that is gone, so nothing offers to stop it. */
	public static void forget(ManagedServer server) {
		server.pid = 0;
		server.pidStarted = 0;
		try {
			Files.deleteIfExists(markPath(server));
		} catch (IOException stubborn) {
			// A mark left behind names a process that is gone, and the next reading
			// of it checks that before believing it. Nothing to do here.
			NpcStudio.LOGGER.debug("Could not remove {}: {}", markPath(server), stubborn.toString());
		}
	}

	/**
	 * A note in the server's own folder saying which process is running it.
	 *
	 * <b>Why there is a second record at all.</b> The first one is the list this
	 * mod keeps, and it is losable: a start whose identifier never reached the disk,
	 * one failed reading that made the tick decide the process was gone and write
	 * the loss down, a copy of the folder moved to another machine. When it is lost,
	 * the server is still running — it just becomes invisible, which is how a second
	 * one comes to be started on top of the first.
	 *
	 * <p>In the folder rather than beside the list because it describes that folder:
	 * whoever is running this server is running <em>this</em>, and a folder copied
	 * elsewhere carries a note about a process that is not its own — which the
	 * reading catches, because it checks the process before believing the note.
	 *
	 * <p>Two lines of plain text, so that a person looking at a folder full of
	 * somebody's software can see what it is.
	 */
	static Path markPath(ManagedServer server) {
		return server.path().resolve("npc-studio.pid");
	}

	private static void writeMark(ManagedServer server) {
		try {
			Files.writeString(markPath(server),
				"pid=" + server.pid + System.lineSeparator()
					+ "started=" + server.pidStarted + System.lineSeparator(),
				StandardCharsets.UTF_8);
		} catch (IOException failed) {
			// Not a reason to fail a start: it is a second record, and everything
			// works without it exactly as it did before.
			NpcStudio.LOGGER.warn("Could not write {}: {}", markPath(server), failed.toString());
		}
	}

	/**
	 * Take back a process this mod started and then lost track of.
	 *
	 * Reads the note in the folder and checks it the same way {@link #attach} checks
	 * the list: the process exists, it started when the note says, and it is a java
	 * binary. Only then is it adopted — the identifier goes back into the server, so
	 * that stopping, the core count and everything else work on it again rather than
	 * merely a label saying "up".
	 */
	private static Optional<ProcessHandle> adopt(ManagedServer server) {
		long[] said = readMark(server);
		if (said == null) return Optional.empty();
		Optional<ProcessHandle> found = ProcessHandle.of(said[0]);
		if (found.isEmpty() || !found.get().isAlive()) {
			// A note about a process that has since died. Removed, so that the next
			// look does not go through this again.
			forget(server);
			return Optional.empty();
		}
		ProcessHandle handle = found.get();
		Optional<Instant> started = handle.info().startInstant();
		if (said[1] > 0 && started.isPresent()
			&& Math.abs(started.get().toEpochMilli() - said[1]) > START_TOLERANCE_MS) {
			return Optional.empty();
		}
		Optional<String> command = handle.info().command();
		if (command.isPresent() && !Path.of(command.get()).getFileName().toString()
			.toLowerCase(Locale.ROOT).startsWith("java")) {
			return Optional.empty();
		}
		server.pid = said[0];
		server.pidStarted = said[1];
		NpcStudio.LOGGER.info("Took '{}' back: it is still running as process {}",
			server.id, server.pid);
		return Optional.of(handle);
	}

	/** The two numbers in the note, or null when there is no usable note. */
	private static long[] readMark(ManagedServer server) {
		Path file = markPath(server);
		if (!Files.isRegularFile(file)) return null;
		try {
			long pid = 0;
			long started = 0;
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				int equals = line.indexOf('=');
				if (equals < 0) continue;
				String key = line.substring(0, equals).trim();
				String value = line.substring(equals + 1).trim();
				if (key.equals("pid")) pid = Long.parseLong(value);
				else if (key.equals("started")) started = Long.parseLong(value);
			}
			return pid > 0 ? new long[] {pid, started} : null;
		} catch (IOException | NumberFormatException unreadable) {
			return null;
		}
	}

	/**
	 * Ask the server to stop and wait for it to finish saving.
	 *
	 * Returns false when the server is still up afterwards — either the command
	 * channel could not be reached or the shutdown is taking longer than the
	 * patience given. Neither is an error here: what to do about it is a
	 * decision for whoever is watching, and the only remaining option is
	 * {@link #kill}, which loses data.
	 */
	public static boolean stop(ManagedServer server, int graceSeconds) {
		Optional<ProcessHandle> handle = attach(server);
		if (handle.isEmpty()) {
			forget(server);
			return true;
		}
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			rcon.command("stop");
		} catch (IOException unreachable) {
			NpcStudio.LOGGER.warn("Could not tell '{}' to stop over RCON: {}",
				server.id, unreachable.getMessage());
			return false;
		}
		try {
			handle.get().onExit().get(graceSeconds, TimeUnit.SECONDS);
			forget(server);
			return true;
		} catch (java.util.concurrent.TimeoutException slow) {
			return false;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		} catch (java.util.concurrent.ExecutionException failed) {
			return !running(server);
		}
	}

	/**
	 * End the process without asking it to save first.
	 *
	 * On Windows this is a hard terminate — there is no signal Java can send that
	 * a Minecraft server treats as "shut down properly". So this is the last
	 * resort it looks like, and the world loses whatever happened since its last
	 * save.
	 */
	public static void kill(ManagedServer server) {
		attach(server).ifPresent(handle -> {
			NpcStudio.LOGGER.warn("Killing server '{}' (process {}) without a save",
				server.id, server.pid);
			handle.destroy();
		});
	}
}
