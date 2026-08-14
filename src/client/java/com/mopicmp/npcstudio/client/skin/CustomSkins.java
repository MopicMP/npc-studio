package com.mopicmp.npcstudio.client.skin;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Skins that came from a file rather than from a player's name.
 *
 * The server keeps the picture and only tells everyone its fingerprint. A
 * client that has never seen that fingerprint asks for the picture once and
 * keeps it; a client that already has it stays quiet. That is the whole reason
 * for the fingerprint: a few kilobytes is nothing to send once and a great deal
 * to send with every entity update, and entities update constantly.
 */
public final class CustomSkins {

	private static final Map<String, PlayerSkin> ready = new HashMap<>();

	/**
	 * The bytes as they arrived, kept beside the texture.
	 *
	 * The picture on the graphics card cannot be read back, and something does
	 * want to read it: before a lid is drawn over a character's eyes, somebody
	 * has to know the character has eyes. A few kilobytes per skin is a cheap
	 * price for not having to ask the card.
	 */
	private static final Map<String, byte[]> pixels = new HashMap<>();

	public static byte[] pixelsFor(String mark) {
		return pixels.get(mark);
	}

	/** Fingerprints already asked about, so a missing skin is chased once and not every frame. */
	private static final Set<Integer> asked = new HashSet<>();

	/**
	 * A texture that is already loaded, described the way the game asks.
	 *
	 * {@code ClientAsset.Texture} is an interface rather than something to
	 * construct, because ordinarily an asset is a name that gets resolved against
	 * the resource packs. This one is neither in a pack nor waiting to be
	 * resolved — it was handed over the network and registered on the spot — so
	 * both questions have the same answer.
	 */
	private record Uploaded(Identifier id) implements ClientAsset.Texture {
		@Override
		public Identifier texturePath() {
			return id;
		}
	}

	private CustomSkins() { }

	/**
	 * Dresses an already-registered texture up as a skin the game will accept.
	 *
	 * Shared rather than written twice, because the wardrobe needs exactly this to
	 * hold a costume up against a character before anybody has agreed to it. Two
	 * copies would be two places to get {@code ClientAsset.Texture} subtly wrong.
	 */
	public static PlayerSkin uploaded(Identifier texture) {
		// Insecure because it is: this picture came from a person rather than from
		// Mojang, and saying so is what the flag is for.
		return PlayerSkin.insecure(new Uploaded(texture), null, null, PlayerModelType.WIDE);
	}

	/** The skin for a fingerprint, or null if it has not arrived yet. */
	public static PlayerSkin get(String mark) {
		return mark == null || mark.isEmpty() ? null : ready.get(mark);
	}

	/**
	 * Asks the server for a character's skin, once.
	 *
	 * Called from drawing, which happens sixty times a second, so the guard is
	 * not politeness — without it a character whose skin is still in flight would
	 * ask for it again on every frame it was visible.
	 */
	public static void request(int entityId) {
		if (!asked.add(entityId)) return;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new com.mopicmp.npcstudio.net.NpcPayloads.SkinPlease(entityId));
	}

	/** Takes delivery of a picture and turns it into something drawable. */
	public static void accept(int entityId, byte[] bytes) {
		asked.remove(entityId);
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
			String mark = Integer.toHexString(java.util.Arrays.hashCode(bytes));
			CustomSkins.pixels.put(mark, bytes);
			if (ready.containsKey(mark)) return;

			Identifier where = NpcStudio.id("skins/" + mark);
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> mark, image));
			// Insecure because it is: this picture came from a person rather than
			// from Mojang, and saying so is what the flag is for.
			ready.put(mark, PlayerSkin.insecure(
				new Uploaded(where), null, null, PlayerModelType.WIDE));
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read a skin sent for entity {}: {}",
				entityId, broken.toString());
		}
	}

	/** Dropped when leaving a world, since entity ids mean nothing in the next one. */
	public static void forget() {
		asked.clear();
		SkinPixels.forget();
	}
}
