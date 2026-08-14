package com.mopicmp.npcstudio.client.emote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.mopicmp.npcstudio.NpcStudio;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Bringing in an emote from somewhere else on the disk.
 *
 * The system's own file chooser rather than one drawn inside the game. A file
 * browser is a genuinely hard thing to build and a familiar thing to use, and
 * the one the player already knows will remember where they keep their
 * downloads.
 *
 * The file is copied rather than referenced. A path into someone's downloads
 * folder is a path that stops working the moment they tidy up, and an emote a
 * character depends on should not vanish because of housekeeping.
 */
public final class EmoteImport {

	private EmoteImport() { }

	/** What happened, in words meant for the person who pressed the button. */
	public record Result(boolean ok, String message) { }

	/**
	 * Asks for a file and copies it in.
	 *
	 * Blocks while the chooser is open, which is why the caller runs it off the
	 * render thread — a native dialog and a game loop cannot share one.
	 */
	public static Result choose() {
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(1);
			filters.put(stack.UTF8("*.json"));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_openFileDialog(
				"Choose an emote", null, filters, "EmoteCraft emote (*.json)", false);
		} catch (Throwable unavailable) {
			// A machine without a working native dialog should be told to use the
			// folder rather than left with a button that does nothing.
			NpcStudio.LOGGER.warn("No file chooser available: {}", unavailable.toString());
			return new Result(false, "Could not open a file chooser — put the file in " + EmoteLibrary.folder());
		}
		if (picked == null) return new Result(false, "");
		return copy(Path.of(picked));
	}

	private static Result copy(Path source) {
		if (!Files.isRegularFile(source)) return new Result(false, "That file is not there.");

		try {
			// Read before copying, so a file that is not an emote is refused rather
			// than filed away to fail quietly later.
			try (var reader = Files.newBufferedReader(source, java.nio.charset.StandardCharsets.UTF_8)) {
				var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
				EmoteParser.parse("checking", root);
			} catch (Exception broken) {
				return new Result(false, "That does not look like an emote: " + broken.getMessage());
			}

			Path folder = EmoteLibrary.folder();
			Files.createDirectories(folder);
			Path target = folder.resolve(source.getFileName().toString());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

			// The thumbnail travels with it if there is one. Packs ship them beside
			// the emote under the same name, and an emote arriving without its
			// picture would look broken next to the ones that have theirs.
			String name = source.getFileName().toString();
			Path picture = source.resolveSibling(name.substring(0, name.length() - 5) + ".png");
			if (Files.isRegularFile(picture)) {
				Files.copy(picture, folder.resolve(picture.getFileName().toString()),
					StandardCopyOption.REPLACE_EXISTING);
			}
			return new Result(true, "Imported " + name);
		} catch (Exception failed) {
			return new Result(false, "Could not copy it: " + failed.getMessage());
		}
	}

	/** Rereads the folder, so an import shows up without restarting the game. */
	public static void refresh() {
		EmoteLibrary.reload(net.minecraft.client.Minecraft.getInstance().getResourceManager());
	}
}
