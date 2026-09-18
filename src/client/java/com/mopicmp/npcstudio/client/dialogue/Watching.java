package com.mopicmp.npcstudio.client.dialogue;

import java.util.List;

import com.mopicmp.npcstudio.net.WatchPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * What the last step of a conversation decided, written down the side of the screen.
 *
 * <h2>Why this exists</h2>
 *
 * Asked for as: "I worked with the branch, declared the variables, and something breaks
 * and I cannot tell what." Which is the honest description of a graph that runs
 * correctly and does something other than intended — nothing is wrong, so nothing
 * complains, and the only symptom is a scene going the wrong way.
 *
 * The validator cannot help. It answers what can be known without running: whether a
 * name is declared, whether types match, whether a node exists. Whether
 * {@code errand_sent} is true <em>at this moment</em> only has an answer while somebody
 * is standing in front of a character.
 *
 * <h2>Why it is plain text in a corner</h2>
 *
 * Because it is scaffolding, and scaffolding that looks designed gets left up. It is off
 * unless asked for, it says what happened in the words the author wrote, and there is
 * nothing to learn about reading it.
 */
public final class Watching {

	private Watching() { }

	private static boolean on;
	private static List<String> lines = List.of();

	public static boolean on() {
		return on;
	}

	/**
	 * Turns it on or off, here and on the server.
	 *
	 * Both, because the deciding happens there and the drawing happens here — and the
	 * server does no work at all for a player who has not asked, which is what makes
	 * this safe to leave in a released mod.
	 */
	public static void turn(boolean now) {
		on = now;
		if (!now) lines = List.of();
		ClientPlayNetworking.send(new WatchPayloads.Watch(now));
	}

	public static void accept(List<String> told) {
		lines = List.copyOf(told);
	}

	/** Dropped with the world. The next one is a different graph and a different answer. */
	public static void forget() {
		on = false;
		lines = List.of();
	}

	/**
	 * Down the left edge, under whatever else is there.
	 *
	 * The left because the right is where a portrait stands, and the top because the
	 * account is read from its first line — a list that grows downwards from a fixed top
	 * can be read while it changes, and one anchored to the bottom moves under the eye.
	 */
	public static void draw(GuiGraphicsExtractor graphics) {
		if (!on || lines.isEmpty()) return;
		// Under whatever the player is actually being shown, rather than on top of it.
		// The gauges are the game; this is scaffolding, and scaffolding gives way.
		int y = 4 + Gauges.takenTopLeft();
		for (String line : lines) {
			if (y > graphics.guiHeight() - 12) break;
			// Indented lines are the arms of a branch and the rest are what happened
			// around them. Dimmer, so the shape of the account is visible before it is
			// read: this happened, and here is why.
			graphics.text(font(), Component.literal(line), 4, y,
				line.startsWith("  ") ? 0xFF8A99A6 : 0xFFECEFF1);
			y += 9;
		}
	}

	private static net.minecraft.client.gui.Font font() {
		return net.minecraft.client.Minecraft.getInstance().font;
	}
}
