package com.mopicmp.npcstudio.client.server;

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
 * The picture a save wears, which the game itself made.
 *
 * {@code icon.png} at the top of a world folder is the game's own: it takes it
 * the first time somebody plays and it is what the singleplayer list shows. So a
 * world copied in from a client already has one, and drawing it here is drawing
 * the same picture that person already knows the world by — which is the whole
 * point of a picture in a list of otherwise identical grey rows.
 *
 * A server never writes one, so worlds a server made are blank until somebody
 * chooses. That is why choosing is offered on the picture itself.
 *
 * Kept once read, like {@link ServerIcon}: the row is drawn sixty times a second
 * and the disk should not be asked sixty times a second. Registered under a
 * number rather than under the folder name, because a texture name may hold
 * lowercase letters, digits and a few marks, and a world may be called anything
 * a folder may be called.
 */
public final class WorldIcon {

	private static final Map<String, Identifier> registered = new HashMap<>();
	private static int counter;

	private WorldIcon() {
	}

	/** The icon of this save, or null when it has none or it will not read. */
	public static Identifier of(Path folder) {
		String key = folder.toAbsolutePath().normalize().toString();
		if (registered.containsKey(key)) return registered.get(key);

		Path file = folder.resolve("icon.png");
		if (!Files.isRegularFile(file)) {
			registered.put(key, null);
			return null;
		}
		try (InputStream stream = Files.newInputStream(file)) {
			NativeImage image = NativeImage.read(stream);
			Identifier where = NpcStudio.id("world-icon/" + (counter++) + ".png");
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> "world icon", image));
			registered.put(key, where);
			return where;
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read the icon of '{}': {}", key, broken.toString());
			registered.put(key, null);
			return null;
		}
	}

	/** Look again next time — after the file has been changed. */
	public static void forget(Path folder) {
		registered.remove(folder.toAbsolutePath().normalize().toString());
	}
}
