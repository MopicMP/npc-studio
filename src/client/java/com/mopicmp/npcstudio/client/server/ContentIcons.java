package com.mopicmp.npcstudio.client.server;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Pictures for the things in the content lists, and what to do when there is none.
 *
 * Two sources. An installed Fabric mod carries its own icon inside its jar, so
 * that one is read off the disk and is always a PNG. A project in the catalogue
 * has a picture on somebody's CDN, and there the format is not ours to choose —
 * Modrinth serves WebP, and the game's decoder reads PNG, JPEG, GIF and a few
 * others but not that. So some of them will not load, and that is a fact about
 * the two ends rather than a bug to chase.
 *
 * Which is why the fallback is not an apology: a tile with the first letter of
 * the name, drawn in the row's own colours. A list where some rows have pictures
 * and the rest have a blank square looks broken; one where the rest have letters
 * looks made.
 */
public final class ContentIcons {

	private static final Map<String, Identifier> registered = new HashMap<>();

	/**
	 * How big each picture turned out, in pixels.
	 *
	 * A row icon is drawn in a square and does not care. A screenshot does: they
	 * arrive at whatever shape the author took them, and a picture drawn into a
	 * fixed rectangle is a picture with people stretched sideways.
	 */
	private static final Map<String, int[]> sizes = new HashMap<>();

	/** The width and height of a picture, or null while there is none. */
	public static int[] size(String key) {
		return sizes.get(key);
	}

	/** Keys already asked about, whether or not anything came of it. */
	private static final Set<String> asked = new HashSet<>();

	private ContentIcons() {
	}

	/** The picture for this key, or null while there is none. */
	public static Identifier of(String key) {
		return registered.get(key);
	}

	/** Whether this key is worth fetching: not held, and not already tried. */
	public static boolean wanted(String key) {
		return key != null && !key.isBlank() && !asked.contains(key);
	}

	public static void asked(String key) {
		asked.add(key);
	}

	/**
	 * Take some bytes and make a texture of them, or remember that it did not work.
	 *
	 * Called on the client thread — registering a texture is not something to do
	 * from a worker.
	 */
	public static void offer(String key, byte[] raw) {
		asked.add(key);
		if (raw == null || raw.length == 0) return;
		Identifier where = NpcStudio.id("content-icon/"
			+ key.replaceAll("[^a-z0-9_.-]", "_").toLowerCase(java.util.Locale.ROOT) + ".png");

		// The game's decoder first, because it is the cheap path and covers the
		// PNGs. Whatever it refuses — WebP, GIF — goes through the other one and
		// comes back as a PNG. Sniffing the format first would mean keeping a
		// list of what each decoder knows; trying is shorter and always right.
		NativeImage image = decode(raw);
		if (image == null) {
			byte[] png = Pictures.toPng(raw);
			if (png != null) image = decode(png);
		}
		if (image == null) return;

		Minecraft.getInstance().getTextureManager()
			.register(where, new DynamicTexture(() -> key, image));
		registered.put(key, where);
		sizes.put(key, new int[] {image.getWidth(), image.getHeight()});
	}

	private static NativeImage decode(byte[] bytes) {
		try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
			return NativeImage.read(in);
		} catch (Exception unreadable) {
			// Not a warning: a format this one does not know is the ordinary case
			// here, and there is a second decoder behind it.
			return null;
		}
	}
}
