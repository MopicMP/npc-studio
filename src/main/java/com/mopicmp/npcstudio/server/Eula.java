package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Mojang's agreement, and the one rule about it.
 *
 * <b>This file is never written before somebody has been shown the terms and has
 * pressed the button.</b> Writing it for them would be forging a signature —
 * the whole content of {@code eula.txt} is a person saying they agreed, and a
 * program cannot agree on their behalf however obvious the answer seems. It
 * would also be the fastest way to make this mod somebody else's legal problem.
 *
 * That is why the agreement is a gate on creating a server rather than a gate on
 * opening the manager. Managing somebody's hosted server runs no core here and
 * needs no agreement to anything.
 */
public final class Eula {

	private Eula() {
	}

	/** Where the terms are. Shown as a link; the text is theirs, not ours to copy. */
	public static final String URL = "https://aka.ms/MinecraftEULA";

	/**
	 * Record the agreement.
	 *
	 * Call this from the handler of an explicit press, and from nowhere else. It
	 * takes the folder and not a boolean on purpose: a method that can be called
	 * with {@code false} is a method somebody will call with a variable.
	 */
	public static void accept(Path directory) throws IOException {
		Files.createDirectories(directory);
		String text = "#By changing the setting below to TRUE you are indicating your agreement"
			+ " to our EULA (" + URL + ").\n"
			+ "#Accepted in Minecraft on " + Instant.now() + "\n"
			+ "eula=true\n";
		Files.writeString(directory.resolve("eula.txt"), text, StandardCharsets.UTF_8);
	}

	/** Whether this server folder already carries an agreement. */
	public static boolean accepted(Path directory) {
		Path file = directory.resolve("eula.txt");
		if (!Files.isRegularFile(file)) return false;
		try {
			return PropertiesFile.read(file).getBoolean("eula", false);
		} catch (IOException unreadable) {
			return false;
		}
	}
}
