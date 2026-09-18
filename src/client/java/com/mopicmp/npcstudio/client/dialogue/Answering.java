package com.mopicmp.npcstudio.client.dialogue;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Answering a question that is in the bar rather than on a screen.
 *
 * <h2>What was wrong with it</h2>
 *
 * Nothing could be clicked. The bar is not a screen — that is its whole point, the
 * player keeps the game and can walk away mid-sentence — and a game without a screen
 * has no cursor. So the answers were drawn, and the only way to reach them was to
 * open the chat window first, which the code admitted to in a comment and nothing
 * anywhere admitted to the player. Reported as exactly that: no reaction at all.
 *
 * <h2>Why numbers and not a cursor</h2>
 *
 * Because giving it a cursor means opening a screen, and a screen takes the movement
 * keys — which would turn the bar into the full-screen mode it exists to be an
 * alternative to. The bar's promise is that the world keeps going, and a question in
 * it has to be answerable while walking.
 *
 * The number keys are already the game's own row of quick choices, they are under the
 * hand that is already there, and there are never more answers than there are of
 * them. Being taken from the hotbar for the moment a question is up costs a scene
 * nothing: a player being asked something is not rearranging their belt.
 *
 * <h2>Why the press is swallowed</h2>
 *
 * Or answering would also change what is in the player's hand, which is the sort of
 * thing that looks like the mod misfiring rather than like an answer being given.
 */
public final class Answering {

	private Answering() { }

	/** As many answers as there are number keys, which is more than any question has. */
	public static final int MOST = 9;

	/**
	 * Whether a number key has just answered the question in the bar.
	 *
	 * @return true when a press was taken, and the caller should skip this tick's
	 *         other keybinds — which is one tick, only on the press that answered
	 */
	public static boolean took() {
		DialogueClientState state = DialogueClientState.current();
		if (state == null || state.options().isEmpty()) return false;
		// A screen has a cursor and its own clicking. Taking the number keys as well
		// would answer twice from one press.
		if (DialogueScreen.isOpen() || CutsceneScreen.isOpen()) return false;
		if (DialogueClientState.isChatOpen()) return false;

		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options == null) return false;

		int many = Math.min(MOST, state.options().size());
		for (int slot = 0; slot < many; slot++) {
			if (!client.options.keyHotbarSlots[slot].consumeClick()) continue;
			ClientPlayNetworking.send(new com.mopicmp.npcstudio.net.AnswerPayload(
				state.npcId(), state.options().get(slot).index()));
			return true;
		}
		return false;
	}
}
