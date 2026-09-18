package com.mopicmp.npcstudio.client.dialogue;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Moving a conversation on when there is nobody to click.
 *
 * <h2>What was missing</h2>
 *
 * A line is advanced by pointing at whoever said it. That is the whole gesture, and
 * it is a good one — it says which conversation, it works with several characters in
 * a room, and nobody has to be taught it.
 *
 * It has one hole, and a scene fell straight through it: a conversation a doorway
 * began is spoken by the room. There is nothing to point at, so the line sat on the
 * screen until it timed out and the thread never moved again. Asked as exactly that
 * — there is a line and no character, what do you press.
 *
 * <h2>Why the use button and not a key of its own</h2>
 *
 * Because it is the same act. Right click is already how a conversation is carried
 * on; the only difference here is that the crosshair is over nothing, and inventing a
 * second button for "the same thing, but at air" would be a thing to learn for a case
 * nobody should have to think about.
 *
 * <h2>Why it is taken only when there is genuinely nobody</h2>
 *
 * The button belongs to the game. It opens doors, eats food, places blocks — and a
 * mod that quietly keeps it during every conversation would be a mod that stops the
 * player using their own hands whenever a character is talking. So it is borrowed on
 * the narrowest condition there is: a line is up, and the thing saying it is not in
 * the world. A character's line is still advanced by clicking the character, and
 * everything else about the button carries on working.
 */
public final class SpeakingOn {

	private SpeakingOn() { }

	/**
	 * Moves whatever this is one step on, by whichever packet fits it.
	 *
	 * <h2>Why every screen has to come through here</h2>
	 *
	 * Because there are two packets and the difference between them is not obvious:
	 * a character's line travels with the character, and a place's line has no
	 * character to travel with. Each screen worked that out for itself, and each got
	 * it wrong differently — the full-screen one never sent anything at all, and the
	 * cutscene named an entity that does not exist for a scene a doorway began.
	 *
	 * One place to be right, and one place to be wrong once rather than three times.
	 */
	public static void carryOn(DialogueClientState state) {
		if (state == null) return;
		if (state.npcId() < 0) {
			ClientPlayNetworking.send(new com.mopicmp.npcstudio.net.SpeakOnPayload());
			return;
		}
		ClientPlayNetworking.send(
			new com.mopicmp.npcstudio.net.AdvancePayload(state.npcId()));
	}

	/**
	 * Whether this press belongs to a conversation rather than to the world.
	 *
	 * @return true when the press was taken, and the caller should do nothing else
	 */
	public static boolean took() {
		DialogueClientState state = DialogueClientState.current();
		if (state == null) return false;
		// A full screen has its own buttons and its own keyboard. Taking the press here
		// as well would advance the line and click whatever was under the cursor.
		if (DialogueScreen.isOpen() || CutsceneScreen.isOpen()) return false;
		// Somebody is speaking, so they can be clicked. That is the ordinary gesture
		// and it must stay the only one — otherwise a press aimed at a door across the
		// room would skip a character's line from six blocks away.
		if (state.npcId() >= 0) return false;
		// A question is answered by choosing an answer, not by pressing on.
		if (!state.options().isEmpty()) return false;
		if (Minecraft.getInstance().player == null) return false;

		carryOn(state);
		return true;
	}
}
