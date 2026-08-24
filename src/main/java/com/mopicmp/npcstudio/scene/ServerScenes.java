package com.mopicmp.npcstudio.scene;

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
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The scenes a world knows about.
 *
 * <h2>In the world folder, and why that is the whole point</h2>
 *
 * A scene names characters and objects that stand in this world, and it names
 * them by ids this world hands out. Anywhere else, that is a scene full of
 * pointers to nothing. Beside the models, the costumes and the dialogues, it is
 * a part of the world that travels with it — and it means a scene survives being
 * logged out of, which is the whole thing a session-long list of parts cannot do.
 *
 * <h2>Written whole, every time</h2>
 *
 * A scene is a few pages of text. Writing the file again on each change is
 * cheaper than working out what changed, and it means there is never a moment
 * where the file on disk is half of one version and half of another.
 */
public final class ServerScenes {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String SUFFIX = ".json";

	private static final Map<String, Scene> known = new HashMap<>();
	private static Path root;

	private ServerScenes() { }

	public static void open(MinecraftServer server) {
		known.clear();
		root = server.getWorldPath(LevelResource.ROOT).resolve("npc_studio").resolve("scenes");
		if (!Files.isDirectory(root)) return;

		try (var listing = Files.list(root)) {
			listing.filter(path -> path.getFileName().toString().endsWith(SUFFIX))
				.forEach(ServerScenes::read);
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.warn("Could not list the world's scenes: {}", unreadable.toString());
		}
		NpcStudio.LOGGER.info("NPC Studio: {} scene(s) in this world", known.size());
	}

	public static void close() {
		known.clear();
		root = null;
	}

	private static void read(Path file) {
		String name = file.getFileName().toString();
		name = name.substring(0, name.length() - SUFFIX.length());
		try {
			known.put(name, SceneIO.read(JsonParser.parseString(
				Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()));
		} catch (IOException | RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("Could not read the world's scene {}: {}", name,
				unreadable.toString());
		}
	}

	public static Scene get(String name) {
		return name == null ? null : known.get(name);
	}

	public static List<String> names() {
		List<String> found = new ArrayList<>(known.keySet());
		found.sort(String::compareToIgnoreCase);
		return found;
	}

	public static String written(String name) {
		Scene scene = get(name);
		return scene == null ? null : GSON.toJson(SceneIO.write(scene));
	}

	/**
	 * A name that cannot climb out of the scenes folder.
	 *
	 * The name arrives from a client and is about to become a path. Everything
	 * that is not plainly part of a filename is refused rather than replaced,
	 * because replacing means two different names can quietly become one file and
	 * overwrite each other's work.
	 */
	public static boolean nameable(String name) {
		if (name == null || name.isBlank() || name.length() > 64) return false;
		for (int i = 0; i < name.length(); i++) {
			char letter = name.charAt(i);
			if (letter == '.' || letter == '/' || letter == '\\' || letter < ' ') return false;
		}
		return true;
	}

	/**
	 * Takes a scene somebody sent, and writes it down.
	 *
	 * Kept in memory even when the write fails, for the same reason the models
	 * are: a world that cannot be written to is a world where things should
	 * still work until it is shut down, and losing the afternoon's work at the
	 * moment of the failure would be a second problem on top of the first.
	 */
	public static Scene take(String name, String json) {
		if (!nameable(name) || json == null) return null;
		Scene scene;
		try {
			scene = SceneIO.read(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("A client sent a scene that will not read: {}",
				unreadable.toString());
			return null;
		}

		known.put(name, scene);
		if (root == null) return scene;
		try {
			Files.createDirectories(root);
			Files.writeString(root.resolve(name + SUFFIX), GSON.toJson(SceneIO.write(scene)),
				StandardCharsets.UTF_8);
		} catch (IOException unwritable) {
			NpcStudio.LOGGER.warn("Could not write the world's scene {}: {}", name,
				unwritable.toString());
		}
		return scene;
	}

	public static boolean remove(String name) {
		if (!nameable(name) || known.remove(name) == null) return false;
		if (root == null) return true;
		try {
			Files.deleteIfExists(root.resolve(name + SUFFIX));
		} catch (IOException unremovable) {
			NpcStudio.LOGGER.warn("Could not remove the world's scene {}: {}", name,
				unremovable.toString());
		}
		return true;
	}
}
