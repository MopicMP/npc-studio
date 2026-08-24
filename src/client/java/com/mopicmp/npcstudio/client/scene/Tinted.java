package com.mopicmp.npcstudio.client.scene;

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
 * A picture of the game's, in another colour.
 *
 * <h2>Why the rain is coloured this way and the sun is not</h2>
 *
 * Because the two are drawn differently, and the difference decides the method.
 * The sun goes through a pipeline that takes a colour — the game hands it white
 * and an alpha, and putting another colour there is one number changed on its way
 * past. Rain takes no colour at all: it binds its texture and draws, and there is
 * nowhere in that to say "but red".
 *
 * So the rain is recoloured where a colour still exists — in the picture. The
 * game's own rain texture is read, multiplied through, and registered as a
 * texture of ours; the renderer is then handed our name instead of its own at the
 * single line where it asks for one. Nothing about the drawing changes, which is
 * why it keeps working when the drawing does.
 *
 * <h2>Made once</h2>
 *
 * Keyed by which picture and which colour, so dragging a colour slider makes one
 * texture per shade it passes through and then stops. That is a few dozen small
 * textures in the worst case somebody can produce by hand, and none at all until
 * somebody asks for a colour.
 *
 * A picture that cannot be read is remembered as unreadable and asked for once.
 * The alternative is trying again sixty times a second for as long as the world
 * is open, which is how a missing file becomes a frame rate.
 */
public final class Tinted {

	/** White: the colour that means "as it was", and never makes a copy. */
	private static final int PLAIN = 0xFFFFFF;

	private static final Map<Long, Identifier> MADE = new HashMap<>();
	private static final Set<Identifier> UNREADABLE = new HashSet<>();

	private Tinted() { }

	/**
	 * The same picture in this colour, or the picture itself when there is nothing
	 * to do.
	 *
	 * @param source what the game was about to draw
	 * @param rgb    the colour to multiply it by, white meaning leave it alone
	 */
	public static Identifier of(Identifier source, int rgb) {
		int colour = rgb & 0xFFFFFF;
		if (source == null || colour == PLAIN || UNREADABLE.contains(source)) return source;

		long key = ((long) source.hashCode() << 24) ^ colour;
		Identifier known = MADE.get(key);
		if (known != null) return known;

		Identifier made = make(source, colour);
		if (made == null) {
			UNREADABLE.add(source);
			return source;
		}
		MADE.put(key, made);
		return made;
	}

	private static Identifier make(Identifier source, int colour) {
		Minecraft client = Minecraft.getInstance();
		var found = client.getResourceManager().getResource(source);
		if (found.isEmpty()) return null;

		try (var stream = found.get().open()) {
			NativeImage image = NativeImage.read(stream);
			float red = ((colour >> 16) & 0xFF) / 255f;
			float green = ((colour >> 8) & 0xFF) / 255f;
			float blue = (colour & 0xFF) / 255f;

			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int was = image.getPixel(x, y);
					// Alpha is left exactly as it was. Rain is mostly transparent and
					// its shape lives entirely in the alpha — multiplying that would
					// not tint the rain, it would thin it out.
					image.setPixel(x, y, (was & 0xFF000000)
						| (Math.round(((was >> 16) & 0xFF) * red) << 16)
						| (Math.round(((was >> 8) & 0xFF) * green) << 8)
						| Math.round((was & 0xFF) * blue));
				}
			}

			Identifier name = NpcStudio.id("tinted/" + Integer.toHexString(source.hashCode())
				+ "_" + Integer.toHexString(colour));
			client.getTextureManager().register(name,
				new DynamicTexture(name::toString, image));
			return name;
		} catch (Exception unreadable) {
			NpcStudio.LOGGER.warn("Could not tint {}: {}", source, unreadable.toString());
			return null;
		}
	}
}
