package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mopicmp.npcstudio.NpcStudio;

/**
 * Which jar came from which project in the catalogue.
 *
 * A small note kept beside the jars, because the jar itself does not say. The
 * browser has to know whether what it is offering is already there — otherwise
 * the button says "install" next to something installed — and matching by name
 * does not work: a plugin whose descriptor says "Essentials" comes from a
 * project called "essentialsx" in a file called "EssentialsX-2.21.0.jar", and
 * none of those three strings is either of the others.
 *
 * Written to the server's own folder rather than to our settings, so that a
 * server copied to another machine takes it along, and a jar deleted by hand
 * only leaves a line here that stops matching anything.
 */
public final class InstalledIndex {

	private static final Gson GSON = new Gson();
	private static final String FILE = "npc-studio-content.json";

	private InstalledIndex() {
	}

	private static Path file(ManagedServer server) {
		return server.path().resolve(FILE);
	}

	/** File name to project id. Missing or damaged reads as empty, never as an error. */
	public static Map<String, String> read(ManagedServer server) {
		Path path = file(server);
		if (!Files.isRegularFile(path)) return new LinkedHashMap<>();
		try {
			Map<String, String> read = GSON.fromJson(
				Files.readString(path, StandardCharsets.UTF_8),
				new TypeToken<LinkedHashMap<String, String>>() {
				}.getType());
			return read == null ? new LinkedHashMap<>() : read;
		} catch (Exception broken) {
			NpcStudio.LOGGER.debug("Could not read {}: {}", path, broken.toString());
			return new LinkedHashMap<>();
		}
	}

	public static void put(ManagedServer server, String jar, String project) {
		Map<String, String> index = read(server);
		index.put(jar, project);
		write(server, index);
	}

	public static void drop(ManagedServer server, String jar) {
		Map<String, String> index = read(server);
		if (index.remove(jar) != null) write(server, index);
	}

	private static void write(ManagedServer server, Map<String, String> index) {
		try {
			Files.createDirectories(server.path());
			Files.writeString(file(server), GSON.toJson(index), StandardCharsets.UTF_8);
		} catch (IOException unwritable) {
			// Losing this costs a button that says the wrong word, not a server.
			NpcStudio.LOGGER.warn("Could not write {}: {}", file(server), unwritable.getMessage());
		}
	}
}
