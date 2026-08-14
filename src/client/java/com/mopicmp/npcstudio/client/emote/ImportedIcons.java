package com.mopicmp.npcstudio.client.emote;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Thumbnails for emotes that came off the disk rather than out of a pack.
 *
 * The built-in ones need nothing like this: they sit in the mod's resources
 * under ordinary identifiers, and the game loads and forgets them by itself.
 * A file the player saved five minutes ago has no such home, so its picture has
 * to be handed to the texture manager by hand.
 *
 * Registered once and kept for the session. There will be a handful of these,
 * not seven hundred — somebody importing emotes one at a time is not going to
 * fill a graphics card — so the simple thing is the right one.
 */
public final class ImportedIcons {

	private static final Map<String, Identifier> registered = new HashMap<>();

	private ImportedIcons() { }

	/**
	 * The thumbnail beside an imported emote, or null if it has none.
	 *
	 * A missing picture is not a problem worth reporting: the picker draws an
	 * initial instead, exactly as it does for the built-in gestures.
	 */
	public static Identifier load(String id, Path emote) {
		Identifier already = registered.get(id);
		if (already != null) return already;

		String file = emote.getFileName().toString();
		Path picture = emote.resolveSibling(file.substring(0, file.length() - 5) + ".png");
		if (!Files.isRegularFile(picture)) return null;

		try (InputStream stream = Files.newInputStream(picture)) {
			NativeImage image = NativeImage.read(stream);
			Identifier where = NpcStudio.id("imported/" + id.replace("imported/", "") + ".png");
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> id, image));
			registered.put(id, where);
			return where;
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read the picture for {}: {}", id, broken.toString());
			return null;
		}
	}
}
