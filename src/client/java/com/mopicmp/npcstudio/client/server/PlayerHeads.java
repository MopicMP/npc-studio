package com.mopicmp.npcstudio.client.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The face beside a name, when there is one to be had.
 *
 * <b>Often there is not, and that is not a failure.</b> A skin belongs to a
 * Mojang account, and the servers this window is most used on are the ones where
 * accounts are not checked: there the uuid is made out of the name itself, no
 * account stands behind it, and asking about it would be asking after somebody
 * who may not exist. So this asks the game — which keeps what it has already
 * fetched — and when nothing comes back the row draws a letter instead. A letter
 * is honest; a stolen face belonging to whoever owns that name elsewhere is not.
 *
 * <p>The asking is the game's own {@code SkinManager}, so nothing here talks to
 * Mojang directly, nothing here caches to disk, and the rules about what may be
 * fetched stay the game's rather than becoming ours.
 */
public final class PlayerHeads {

	private static final Map<String, Supplier<PlayerSkin>> asked = new HashMap<>();

	private PlayerHeads() {
	}

	/**
	 * The skin texture for this player, or null while there is none.
	 *
	 * Null is also the answer while it is being fetched: the lookup answers with
	 * the default skin until the real one arrives, and drawing Steve for somebody
	 * would be a face that is not theirs.
	 */
	public static Identifier of(String uuid, String name) {
		PlayerSkin skin = skinOf(uuid, name);
		return skin == null ? null : skin.body().texturePath();
	}

	/**
	 * The whole skin, for when a face is not enough.
	 *
	 * A figure needs two things a head does not: the picture, and whether it is
	 * drawn on the narrow-armed model — get that wrong and the arms are a pixel
	 * thick on one kind of skin and boxy on the other.
	 */
	public static PlayerSkin skinOf(String uuid, String name) {
		if (name.isBlank()) return null;
		Supplier<PlayerSkin> lookup = asked.computeIfAbsent(uuid, id -> {
			try {
				return Minecraft.getInstance().getSkinManager()
					.createLookup(new GameProfile(UUID.fromString(id), name), false);
			} catch (RuntimeException notAProfile) {
				return null;
			}
		});
		if (lookup == null) return null;
		PlayerSkin skin = lookup.get();
		return skin == null || !skin.secure() ? null : skin;
	}

	/** Whether this skin wants the narrow-armed model. */
	public static boolean slim(PlayerSkin skin) {
		return skin != null && skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
	}

	/**
	 * The head out of a skin: the face, with the hat over it.
	 *
	 * The two are drawn from the two places in the sheet the game itself takes them
	 * from — face at 8,8 and hat at 40,8, both eight by eight in a sixty-four
	 * square — because a head without its hat layer is bald where half the players
	 * are not.
	 */
	public static void draw(GuiGraphicsExtractor graphics, Identifier skin, int x, int y,
			int size) {
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skin,
			x, y, 8, 8, size, size, 8, 8, 64, 64);
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skin,
			x, y, 40, 8, size, size, 8, 8, 64, 64);
	}

	/** Ask again for everyone — after a list that may hold new people. */
	public static void forget() {
		asked.clear();
	}
}
