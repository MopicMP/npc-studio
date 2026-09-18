package com.mopicmp.npcstudio.client.puppet;

import java.util.HashMap;
import java.util.Map;

import com.mopicmp.npcstudio.client.wardrobe.Costumes;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Where a picture has drawing on it, as a plain grid of yes and no.
 *
 * <h2>Why the alpha and nothing else</h2>
 *
 * Because it is the part two variants of one slot share. A second pair of eyes may be a
 * different colour in every pixel and still be the same eyes in the same place — see
 * {@link com.mopicmp.npcstudio.puppet.Fitting}, which is the only thing that asks for
 * these. Comparing colours would find nothing at all.
 *
 * <h2>Why they are kept</h2>
 *
 * Decoding a PNG is not free and the answer never changes: a picture is named by its own
 * fingerprint, so the same fingerprint is the same bytes for ever. Placing a slot of
 * twelve variants asks for the same two masks a dozen times over as somebody nudges.
 *
 * Small in the way that matters: a mask is one boolean per pixel, so the largest picture
 * in a real portrait set costs a few hundred kilobytes, and only the ones actually being
 * placed are ever asked for.
 */
public final class Masks {

	private Masks() { }

	private static final Map<String, boolean[]> masks = new HashMap<>();

	/**
	 * The mask for this picture, or null while its bytes are still on their way.
	 *
	 * Null rather than an empty mask, because "not here yet" and "nothing drawn on it"
	 * are different answers and only one of them is worth acting on. Asking is what
	 * starts the fetch, through the same guarded path the drawing uses.
	 */
	public static boolean[] of(String fingerprint) {
		if (fingerprint == null || fingerprint.isEmpty()) return null;
		boolean[] known = masks.get(fingerprint);
		if (known != null) return known;

		byte[] png = Costumes.pixels(fingerprint);
		if (png == null) return null;
		try (NativeImage image = NativeImage.read(png)) {
			boolean[] mask = new boolean[image.getWidth() * image.getHeight()];
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					// Anything not fully clear counts as drawing. A softened edge is part
					// of the shape — trimming it off would make two variants disagree
					// about their own outlines, which is exactly what is being compared.
					mask[y * image.getWidth() + x] = (image.getPixel(x, y) >>> 24) != 0;
				}
			}
			masks.put(fingerprint, mask);
			return mask;
		} catch (Exception failed) {
			// A picture that will not decode is one this cannot help with. Remembered as
			// nothing so it is not decoded again sixty times a second.
			masks.put(fingerprint, new boolean[0]);
			return null;
		}
	}

	/** Dropped with the world, like every other picture this client was holding. */
	public static void forget() {
		masks.clear();
	}
}
