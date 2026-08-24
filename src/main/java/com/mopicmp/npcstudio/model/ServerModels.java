package com.mopicmp.npcstudio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The models a world knows about.
 *
 * <h2>Why the server holds models at all</h2>
 *
 * It did not, and that was the shape of three separate problems. A player
 * without the file saw nothing where an object stood. The server could not work
 * out what a placed object was shaped like, so the client had to measure it and
 * send the numbers — two calculations of one thing, which disagreed, which is a
 * wall in mid-air on one side and open sky on the other. And a world moved to
 * another machine arrived with objects that had no models.
 *
 * One document, in the world, is the answer to all three. The client that drew
 * it sends it once; from then on the server can measure the thing itself and
 * hand the drawing to whoever else turns up.
 *
 * <h2>In the world folder, not the config</h2>
 *
 * Beside the costumes and the dialogues, and for the same reason: an object
 * standing in a world is part of that world. A model in the config folder is a
 * model belonging to whoever happens to be running the game, which is the wrong
 * answer the moment there are two of them.
 *
 * The file is a {@code .bbmodel}, the same as on the client, so the world's copy
 * opens in Blockbench like any other.
 */
public final class ServerModels {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Map<String, Model> known = new HashMap<>();
	private static Path root;

	private ServerModels() { }

	public static void open(MinecraftServer server) {
		known.clear();
		root = server.getWorldPath(LevelResource.ROOT).resolve("npc_studio").resolve("models");
		if (!Files.isDirectory(root)) return;

		try (var listing = Files.list(root)) {
			listing.filter(path -> path.getFileName().toString().endsWith(".bbmodel"))
				.forEach(ServerModels::read);
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.warn("Could not list the world's models: {}", unreadable.toString());
		}
		NpcStudio.LOGGER.info("NPC Studio: {} model(s) in this world", known.size());
	}

	public static void close() {
		known.clear();
		root = null;
	}

	private static void read(Path file) {
		String name = file.getFileName().toString();
		name = name.substring(0, name.length() - ".bbmodel".length());
		try {
			JsonObject json = JsonParser.parseString(
				Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			known.put(name, ModelIO.readBlockbench(json));
		} catch (IOException | RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("Could not read the world's model {}: {}", name,
				unreadable.toString());
		}
	}

	public static Model get(String name) {
		return name == null ? null : known.get(name);
	}

	public static List<String> names() {
		List<String> found = new ArrayList<>(known.keySet());
		found.sort(String::compareToIgnoreCase);
		return found;
	}

	/** The document as it travels: the same JSON the file holds. */
	public static String written(String name) {
		Model model = get(name);
		return model == null ? null : GSON.toJson(ModelIO.writeBlockbench(model));
	}

	/**
	 * Takes a model somebody sent, and writes it down.
	 *
	 * Held in memory even when the write fails, because a world that cannot be
	 * written to is a world where things should still work until it is shut down —
	 * losing the session's work at the moment of the failure would be a second
	 * problem on top of the first.
	 */
	public static Model take(String name, String json) {
		if (name == null || name.isBlank() || json == null) return null;
		Model model;
		try {
			model = ModelIO.readBlockbench(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("A client sent a model that will not read: {}",
				unreadable.toString());
			return null;
		}

		known.put(name, model);
		if (root == null) return model;
		try {
			Files.createDirectories(root);
			Files.writeString(root.resolve(name + ".bbmodel"), json, StandardCharsets.UTF_8);
		} catch (IOException unwritable) {
			NpcStudio.LOGGER.warn("Could not write the world's model {}: {}", name,
				unwritable.toString());
		}
		return model;
	}
}
