package com.mopicmp.npcstudio.client.editor;

import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.NpcPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Opening a character's settings from wherever you happen to be.
 *
 * The screen exists already; what was missing was a way in that did not involve
 * leaving whatever you were doing, finding the NPC in the world and crouching
 * at it. Anyone editing a dialogue or choosing an animation is working on a
 * character, and that is exactly when they want its settings.
 *
 * Which character is not asked, because there is almost never a choice worth
 * offering: it is the nearest one. Asking would mean a list of entity numbers,
 * which is not a question anybody can answer.
 */
public final class NpcSettingsButton {

	/** As far as an NPC can be and still be the one obviously meant. */
	private static final double RANGE = 64.0;

	/**
	 * The screen to come back to once the settings are done with.
	 *
	 * Remembered here rather than passed along, because the settings screen is
	 * not opened by the click — it is opened later, when the server answers, by
	 * code that has no idea where the click came from. Closing on save used to
	 * drop the player back into the world with the editor they were working in
	 * gone.
	 */
	private static Screen returnTo;

	public static Screen returnTo() {
		Screen was = returnTo;
		returnTo = null;
		return was;
	}

	private NpcSettingsButton() { }

	/**
	 * @param from the screen to come back to, since the game does not report which
	 *             one is showing and only the caller knows
	 */
	public static void open(Screen from) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;

		NpcEntity nearest = null;
		double closest = RANGE * RANGE;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof NpcEntity npc)) continue;
			double distance = npc.distanceToSqr(client.player);
			if (distance < closest) {
				closest = distance;
				nearest = npc;
			}
		}

		if (nearest == null) {
			// Said out loud rather than doing nothing. A button that silently
			// declines is indistinguishable from one that is broken.
			if (client.player != null) {
				client.player.sendOverlayMessage(Component.literal("No NPC within reach."));
			}
			return;
		}
		returnTo = from;
		// The server decides whether this is allowed and answers with the details;
		// the screen opens when they arrive. Asking rather than opening is what
		// keeps the two halves from disagreeing about what the character is.
		ClientPlayNetworking.send(new NpcPayloads.Open(nearest.getId()));
	}
}
