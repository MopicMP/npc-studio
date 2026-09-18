package com.mopicmp.npcstudio.client.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Choosing jars to put on a server.
 *
 * Several at once, because nobody installs one plugin: a set arrives as a set,
 * and one trip through a file dialogue per jar is the kind of interface people
 * work around by opening the folder instead — which is what this exists to make
 * unnecessary.
 */
public final class AddonPick {

	private AddonPick() {
	}

	public static List<Path> choose() {
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Archives as well as jars, because a set of mods usually arrives as
			// one file — a zip somebody sent, or a Modrinth pack, which is a zip
			// with a list of downloads in it.
			PointerBuffer filters = stack.mallocPointer(3);
			filters.put(stack.UTF8("*.jar"));
			filters.put(stack.UTF8("*.zip"));
			filters.put(stack.UTF8("*.mrpack"));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_openFileDialog(
				"Choose mods, plugins or a pack", null, filters,
				"Mods and packs (*.jar, *.zip, *.mrpack)", true);
		} catch (Throwable unavailable) {
			NpcStudio.LOGGER.warn("No file chooser available: {}", unavailable.toString());
			return List.of();
		}
		if (picked == null || picked.isBlank()) return List.of();

		List<Path> jars = new ArrayList<>();
		// tinyfd hands several files back as one string, separated by pipes.
		for (String each : picked.split(java.util.regex.Pattern.quote("|"))) {
			if (!each.isBlank()) jars.add(Path.of(each));
		}
		return jars;
	}
}
