package com.mopicmp.npcstudio.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Which java runs the server.
 *
 * The answer is: the one already running the game, unless somebody says
 * otherwise. It is not a shortcut. A server needs a JVM at least as new as the
 * version of Minecraft it serves, and the client sitting in front of us is
 * running that exact version of Minecraft — so its JVM is new enough by
 * construction, whatever the machine's {@code JAVA_HOME} happens to point at.
 * Asking the operating system for "java" instead is how a launcher ends up
 * starting a modern server on an ancient JRE and reporting an
 * {@code UnsupportedClassVersionError} to somebody who did not install either.
 */
public final class JavaBinary {

	private JavaBinary() {
	}

	/** The binary to launch with, from a per-server override or from this process. */
	public static Path forServer(ManagedServer server) {
		if (server.javaPath != null && !server.javaPath.isBlank()) {
			return Path.of(server.javaPath);
		}
		return current();
	}

	public static Path current() {
		Optional<String> running = ProcessHandle.current().info().command();
		if (running.isPresent()) {
			Path binary = console(Path.of(running.get()));
			if (Files.isExecutable(binary)) return binary;
		}
		return console(fromHome());
	}

	private static Path fromHome() {
		String home = System.getProperty("java.home", "");
		String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
			.startsWith("win") ? "java.exe" : "java";
		return Path.of(home, "bin", name);
	}

	/**
	 * {@code javaw} swapped for {@code java}.
	 *
	 * The launcher starts the game with the windowless binary, which has no
	 * console attached. Output still reaches our redirected file either way, so
	 * this is not a fix for a bug — it is the difference between a server whose
	 * process looks like a server in a task list and one that does not.
	 */
	private static Path console(Path binary) {
		String name = binary.getFileName() == null ? "" : binary.getFileName().toString();
		if (name.equals("javaw.exe")) return binary.resolveSibling("java.exe");
		if (name.equals("javaw")) return binary.resolveSibling("java");
		return binary;
	}
}
