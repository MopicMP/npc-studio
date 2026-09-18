package com.mopicmp.npcstudio.client.dialogue;

import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;

/**
 * The speaker's face beside their line.
 *
 * The first attempt cropped the 8×8 patch out of the skin by hand and drew it
 * with raw texture coordinates. It rendered nothing, and it deserved to: the
 * game already has {@link PlayerFaceExtractor} for exactly this, it knows where
 * a face lives in a skin, and it lays the hat over the top the way the tab list
 * and every other head icon in the game does.
 *
 * It takes the profile rather than a resolved skin, so this works for any NPC
 * without going through the client-side subclass, and it stays correct while a
 * skin is still being fetched.
 *
 * Only the ordinary case is covered — an NPC shaped like a player. A floating
 * eye or a dragon has no face to crop out of a skin and will need a sprite of
 * its own; that is a later piece of work.
 */
public final class DialogueIcon {

	private static final int FRAME = 0x66000000;

	private DialogueIcon() { }

	public static void draw(GuiGraphicsExtractor graphics, NpcEntity npc, int x, int y, int size) {
		// A dark square behind the face: a skin with a pale background would
		// otherwise dissolve into the bar and leave the head looking cut out.
		graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, FRAME);

		// An uploaded skin is not in the profile — the profile still names whoever
		// the NPC was before somebody handed it a file — so asking the game to
		// crop a face out of the profile gave the old head next to the new body.
		var uploaded = com.mopicmp.npcstudio.client.skin.CustomSkins.get(npc.skinMark());
		if (uploaded != null) {
			face(graphics, uploaded.body().texturePath(), x, y, size);
			return;
		}
		PlayerFaceExtractor.extractRenderState(graphics, npc.getProfile(), x, y, size);
	}

	/**
	 * The player's own head, for a line the player is the one saying.
	 *
	 * Their real skin, whatever it is, and nothing here decides otherwise. A line
	 * spoken by the player is spoken by the person holding the keyboard, and drawing
	 * somebody else's face over it would be the one thing that reads as a bug rather
	 * than as a scene.
	 */
	public static void draw(GuiGraphicsExtractor graphics,
			net.minecraft.client.player.AbstractClientPlayer player, int x, int y, int size) {
		graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, FRAME);
		// The resolved skin rather than the profile, which is what the client already
		// holds for a player standing in the world — and which is right while a skin is
		// still being fetched, where a profile lookup would draw Steve for a moment.
		PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), x, y, size);
	}

	/**
	 * Crops a face out of a skin by hand.
	 *
	 * The same two patches the game uses: the face at (8, 8) and the hat over it
	 * at (40, 8), each eight by eight of a sixty-four-wide skin. Written in those
	 * proportions rather than in pixels so that a high-resolution skin — which is
	 * the same layout at a larger size — crops in the right place.
	 */
	private static void face(GuiGraphicsExtractor graphics,
			net.minecraft.resources.Identifier skin, int x, int y, int size) {
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skin,
			x, y, 8f, 8f, size, size, 8, 8, 64, 64);
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skin,
			x, y, 40f, 8f, size, size, 8, 8, 64, 64);
	}
}
