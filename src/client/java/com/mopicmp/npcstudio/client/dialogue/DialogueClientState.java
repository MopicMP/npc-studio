package com.mopicmp.npcstudio.client.dialogue;

import java.util.List;

import com.mopicmp.npcstudio.net.ShowChoicePayload;
import com.mopicmp.npcstudio.net.ShowLinePayload;

import net.minecraft.util.Util;

/**
 * What the conversation is showing right now.
 *
 * One at a time and held statically: a player is in exactly one conversation,
 * and threading this through the render call would be ceremony around a fact
 * that does not change.
 *
 * It has to be cleared on disconnect. Being static means it outlives the world,
 * and a line left over from the last server would otherwise be sitting there
 * when the next one loads.
 */
public final class DialogueClientState {

	private static DialogueClientState current;

	private final int npcId;
	private final String speaker;
	private final String text;
	private final List<ShowChoicePayload.Option> options;
	private final int mode;
	private final long startedAt;
	private boolean typingSkipped;

	private DialogueClientState(int npcId, String speaker, String text,
			List<ShowChoicePayload.Option> options, int mode) {
		this.mode = mode;
		this.npcId = npcId;
		this.speaker = speaker;
		this.text = text;
		this.options = options;
		this.startedAt = Util.getMillis();
	}

	public static DialogueClientState current() {
		return current;
	}

	public static void show(ShowLinePayload payload) {
		current = new DialogueClientState(payload.npc(), payload.speaker(), payload.text(),
			List.of(), payload.mode());
	}

	public static void show(ShowChoicePayload payload) {
		current = new DialogueClientState(payload.npc(), payload.speaker(), payload.prompt(),
			payload.options(), payload.mode());
	}

	/**
	 * Whether this moment wants the screen to itself.
	 *
	 * Compared against the ordinal rather than the enum because the mode crosses
	 * the wire as a number; the client and the server share the enum, so the
	 * ordinals agree by construction.
	 */
	public boolean takesOverScreen() {
		return mode == com.mopicmp.npcstudio.dialogue.Presentation.FULLSCREEN.ordinal();
	}

	/** A staged scene: the camera leaves and the player stops being one. */
	public boolean isCutscene() {
		return mode == com.mopicmp.npcstudio.dialogue.Presentation.CUTSCENE.ordinal();
	}

	public static void close() {
		current = null;
	}

	/**
	 * Whether the chat screen is open.
	 *
	 * Tracked by hand because in this version {@code Minecraft} no longer exposes
	 * the screen it is showing — there is no field and no getter for it. The
	 * screen events say when one opens and closes, which is the same information
	 * arrived at from the other end.
	 *
	 * It matters twice: chat steps aside for a conversation unless the player is
	 * actually reading it, and answers can only be clicked while there is a
	 * cursor to click them with.
	 */
	private static boolean chatOpen;

	public static void chatOpened() { chatOpen = true; }

	public static void chatClosed() { chatOpen = false; }

	public static boolean isChatOpen() { return chatOpen; }

	/**
	 * Puts the rest of the line up at once.
	 *
	 * Standard for anything that types text out: the animation is for the first
	 * read, and making someone wait through it a second time is a way of telling
	 * them their time is worth less than the effect.
	 */
	public static void skipTyping() {
		if (current != null) current.typingSkipped = true;
	}

	public int npcId() { return npcId; }

	public String speaker() { return speaker; }

	public List<ShowChoicePayload.Option> options() { return options; }

	/**
	 * The whole line, however much of it has been typed.
	 *
	 * The layout needs the finished text to work out how wide the card should be;
	 * measuring what is on screen so far would have the box grow letter by letter.
	 */
	public String text() { return text; }

	/**
	 * As much of the line as has been typed so far.
	 *
	 * Timed from when the line arrived rather than counted per frame, so the
	 * speed is the same on any machine — a frame counter would type faster for
	 * whoever had the better graphics card.
	 */
	public String visibleText(float charsPerSecond) {
		if (typingSkipped) return text;
		long elapsed = Util.getMillis() - startedAt;
		int shown = (int) (elapsed * charsPerSecond / 1000f);
		return shown >= text.length() ? text : text.substring(0, Math.max(0, shown));
	}

	/** True once the whole line is on screen. A click before this completes it instead. */
	public boolean finishedTyping(float charsPerSecond) {
		return typingSkipped || visibleText(charsPerSecond).length() >= text.length();
	}
}
