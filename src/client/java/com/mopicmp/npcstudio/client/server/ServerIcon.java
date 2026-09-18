package com.mopicmp.npcstudio.client.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.server.ManagedServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The picture a server puts on itself.
 *
 * {@code server-icon.png} is a real file the server reads, sixty-four pixels
 * square, and it is what everyone sees in their list. So the card shows the same
 * file rather than a picture of our own — a manager that draws its own idea of a
 * server next to the name is showing something nobody else will ever see.
 *
 * Registered once and kept, like {@code ImportedIcons}: there are a handful of
 * servers, not seven hundred.
 */
public final class ServerIcon {

	private static final Map<String, Identifier> registered = new HashMap<>();

	private ServerIcon() {
	}

	/** The icon of this server, or null when it has none or it will not read. */
	public static Identifier of(ManagedServer server) {
		if (registered.containsKey(server.id)) return registered.get(server.id);

		Path file = server.path().resolve("server-icon.png");
		if (!Files.isRegularFile(file)) {
			// Remembered as absent, so a card being drawn sixty times a second does
			// not ask the disk sixty times a second.
			registered.put(server.id, null);
			return null;
		}
		try (InputStream stream = Files.newInputStream(file)) {
			NativeImage image = NativeImage.read(stream);
			Identifier where = NpcStudio.id("server-icon/" + server.id + ".png");
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> server.id, image));
			registered.put(server.id, where);
			return where;
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read the icon of '{}': {}", server.id, broken.toString());
			registered.put(server.id, null);
			return null;
		}
	}

	/** Look again next time — after the file has been changed or the server removed. */
	public static void forget(String id) {
		registered.remove(id);
	}
}
