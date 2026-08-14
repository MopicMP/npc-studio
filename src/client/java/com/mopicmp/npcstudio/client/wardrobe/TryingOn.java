package com.mopicmp.npcstudio.client.wardrobe;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * A costume held up against a character, before anybody has agreed to it.
 *
 * The wardrobe used to draw the character exactly as it already was, which made
 * the largest thing on the screen the one thing that never answered: picking a
 * costume changed the grid and left the figure alone. A fitting room that will
 * not show you the clothes is a cupboard.
 *
 * Only this client, and only while the screen is open — the same arrangement as
 * the build being dragged through a slider, and for the same reason. Nothing is
 * decided until "надеть" is pressed, so nobody else should see anything.
 */
public final class TryingOn {

	private static int who = -1;
	private static String fingerprint = "";

	private static final Map<String, PlayerSkin> dressed = new HashMap<>();

	private TryingOn() { }

	/** The skin to draw this character in, or null to use whatever it owns. */
	public static PlayerSkin of(int entityId) {
		if (entityId != who || fingerprint.isEmpty()) return null;
		PlayerSkin ready = dressed.get(fingerprint);
		if (ready != null) return ready;

		// The picture is asked for the same way the grid asks for it, so a costume
		// already drawn as a tile costs nothing more to try on.
		byte[] png = Costumes.pixels(fingerprint);
		if (png == null) {
			Costumes.texture(fingerprint);
			return null;
		}
		return build(png);
	}

	public static void show(int entityId, String costume) {
		who = entityId;
		fingerprint = costume == null ? "" : costume;
	}

	public static void stop() {
		who = -1;
		fingerprint = "";
	}

	private static PlayerSkin build(byte[] png) {
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
			Identifier where = NpcStudio.id("trying/" + fingerprint);
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> fingerprint, image));
			PlayerSkin skin = com.mopicmp.npcstudio.client.skin.CustomSkins.uploaded(where);
			dressed.put(fingerprint, skin);
			return skin;
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not try on {}: {}", fingerprint, broken.toString());
			// Remembered as nothing rather than retried: a picture that will not open
			// this frame will not open on the next one either, and this is called
			// from drawing.
			dressed.put(fingerprint, null);
			return null;
		}
	}

	/** Entity ids mean nothing in the next world, and neither do these. */
	public static void forget() {
		stop();
		dressed.clear();
	}
}
