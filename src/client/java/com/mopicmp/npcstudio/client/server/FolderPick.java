package com.mopicmp.npcstudio.client.server;

import java.nio.file.Path;

import org.lwjgl.util.tinyfd.TinyFileDialogs;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Choosing a folder, for the one thing here that arrives as a folder.
 *
 * A dimension is not a file. It is a folder of {@code .mca} files and nobody has
 * it as an archive unless they made one — a downloaded map is a folder, another
 * server's nether is a folder, and the folder beside this one is a folder. So the
 * chooser asks for what people actually have.
 */
public final class FolderPick {

	private FolderPick() {
	}

	/** The folder somebody chose, or null when they closed the window. */
	public static Path choose(String title) {
		String picked;
		try {
			picked = TinyFileDialogs.tinyfd_selectFolderDialog(title, null);
		} catch (Throwable unavailable) {
			NpcStudio.LOGGER.warn("No folder chooser available: {}", unavailable.toString());
			return null;
		}
		if (picked == null || picked.isBlank()) return null;
		return Path.of(picked);
	}
}
