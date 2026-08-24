package com.mopicmp.npcstudio.client.model;

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
import com.mopicmp.npcstudio.model.Model;
import com.mopicmp.npcstudio.model.ModelIO;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The models this client knows about, as files.
 *
 * <h2>Files rather than resources</h2>
 *
 * A resource pack is read once at startup and is not written to. These are
 * documents somebody is editing, so they live where documents live: a folder
 * beside the config, one {@code .bbmodel} each, openable in Blockbench without
 * exporting anything. That last part is the whole reason for choosing the
 * format — a model made here can be finished there and back again.
 *
 * <h2>What is cached</h2>
 *
 * The document, because it is what gets edited and what the renderer reads. The
 * drawing is not cached at all: the faces are worked out from the document every
 * frame, which is what lets a box change block or size and be right immediately.
 * Forty boxes of arithmetic a frame is nothing, and any scheme for patching a
 * baked mesh would be something to get subtly wrong.
 */
public final class ModelStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Map<String, Model> documents = new HashMap<>();

	private ModelStore() { }

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("npc_studio").resolve("models");
	}

	/** Every model on disk, by name, read afresh. */
	public static List<String> names() {
		List<String> found = new ArrayList<>();
		Path folder = folder();
		if (!Files.isDirectory(folder)) return found;
		try (var listing = Files.list(folder)) {
			listing.filter(path -> path.getFileName().toString().endsWith(".bbmodel"))
				.forEach(path -> {
					String file = path.getFileName().toString();
					found.add(file.substring(0, file.length() - ".bbmodel".length()));
				});
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.warn("Could not list the models folder: {}", unreadable.toString());
		}
		found.sort(String::compareToIgnoreCase);
		return found;
	}

	/**
	 * The document by that name, read from disk the first time it is asked for.
	 *
	 * A name nobody has a file for comes back as null rather than as an empty
	 * model: an object pointing at a model that is not there is a thing worth
	 * seeing as missing, not as a model with no boxes in it.
	 */
	public static Model get(String name) {
		if (name == null || name.isBlank()) return null;
		Model held = documents.get(name);
		if (held != null) return held;

		Path file = folder().resolve(name + ".bbmodel");
		if (!Files.isRegularFile(file)) return null;
		try {
			JsonObject json = JsonParser.parseString(
				Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			Model read = ModelIO.readBlockbench(json);
			documents.put(name, read);
			return read;
		} catch (IOException | RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("Could not read the model {}: {}", name, unreadable.toString());
			return null;
		}
	}

	/** Holds a document without writing it, which is what an unsaved edit is. */
	public static void hold(String name, Model model) {
		documents.put(name, model);
	}

	public static boolean save(String name, Model model) {
		hold(name, model);
		try {
			Files.createDirectories(folder());
			Files.writeString(folder().resolve(name + ".bbmodel"),
				GSON.toJson(ModelIO.writeBlockbench(model)), StandardCharsets.UTF_8);
			return true;
		} catch (IOException unwritable) {
			NpcStudio.LOGGER.warn("Could not save the model {}: {}", name, unwritable.toString());
			return false;
		}
	}

	/** Forgets everything, for a world change or a folder edited from outside. */
	public static void forget() {
		documents.clear();
	}
}
