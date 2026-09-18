package com.mopicmp.npcstudio.client.server;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Choosing the picture a server or a save wears.
 *
 * The file has to be exactly sixty-four pixels square — the server reads it
 * itself and simply ignores anything else, silently, which is the worst way for
 * this to fail. So whatever is chosen is scaled here rather than refused: people
 * have a picture they want, not a picture of the right size, and "that image is
 * 512×512" is a message about our convenience.
 *
 * The same for a world's {@code icon.png}, which the game makes at the same size
 * and reads the same way. One routine rather than two nearly identical ones: the
 * only difference between them is the name of the file it lands in.
 */
public final class ServerIconPick {

	private ServerIconPick() {
	}

	/** What the server expects, and the only size it will read. */
	private static final int SIZE = 64;

	/** Chosen, scaled and written; returns what to tell somebody, or empty. */
	public static String choose(Path serverFolder) {
		return choose(serverFolder, "server-icon.png", "Server icon");
	}

	/** The same, into a named file — a world's own icon rather than a server's. */
	public static String choose(Path folder, String name, String title) {
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(3);
			filters.put(stack.UTF8("*.png"));
			filters.put(stack.UTF8("*.jpg"));
			filters.put(stack.UTF8("*.jpeg"));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_openFileDialog(
				title, null, filters, "Images (*.png, *.jpg)", false);
		} catch (Throwable unavailable) {
			NpcStudio.LOGGER.warn("No file chooser available: {}", unavailable.toString());
			return "no file chooser";
		}
		if (picked == null || picked.isBlank()) return "";

		try {
			BufferedImage read = ImageIO.read(Path.of(picked).toFile());
			if (read == null) return "that file is not an image";

			BufferedImage square = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D pen = square.createGraphics();
			pen.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			// The middle square of the picture, so a wide photograph does not
			// arrive squashed.
			int side = Math.min(read.getWidth(), read.getHeight());
			int left = (read.getWidth() - side) / 2;
			int top = (read.getHeight() - side) / 2;
			pen.drawImage(read, 0, 0, SIZE, SIZE, left, top, left + side, top + side, null);
			pen.dispose();

			Files.createDirectories(folder);
			ImageIO.write(square, "png", folder.resolve(name).toFile());
			return "";
		} catch (IOException broken) {
			NpcStudio.LOGGER.warn("Could not write the icon: {}", broken.toString());
			return broken.getMessage() == null ? broken.toString() : broken.getMessage();
		}
	}
}
