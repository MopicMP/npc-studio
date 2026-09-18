package com.mopicmp.npcstudio.client.dialogue;

import java.util.List;

import com.mopicmp.npcstudio.net.ShowChoicePayload;
import com.mopicmp.npcstudio.net.ShowLinePayload;

import com.mopicmp.npcstudio.dialogue.text.Words;

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
	private final Words text;
	private final List<ShowChoicePayload.Option> options;
	private final int mode;
	private final long startedAt;
	private boolean typingSkipped;

	/**
	 * How this document asked to be shown.
	 *
	 * Carried with the line rather than fetched, because the client has no documents
	 * — it has whatever the server just told it — and because a line already on screen
	 * should go on being shown the way it arrived.
	 */
	private final com.mopicmp.npcstudio.dialogue.Manner showing;

	/**
	 * Whose head is drawn beside this, and what colour the name is.
	 *
	 * Both arrive settled. The client is not asked to work out whether a line meant
	 * the character or the player, nor to weigh a document's colour against a line's
	 * override — those are questions about a document, and the client has none.
	 */
	private final com.mopicmp.npcstudio.dialogue.Node.Line.Face face;
	private final String nameColour;

	private DialogueClientState(int npcId, String speaker, Words text,
			List<ShowChoicePayload.Option> options, int mode,
			com.mopicmp.npcstudio.dialogue.Manner showing,
			com.mopicmp.npcstudio.dialogue.Node.Line.Face face, String nameColour) {
		this.face = face == null ? com.mopicmp.npcstudio.dialogue.Node.Line.Face.SPEAKER : face;
		this.nameColour = nameColour == null ? "" : nameColour;
		this.mode = mode;
		this.showing = showing == null
			? com.mopicmp.npcstudio.dialogue.Manner.ORDINARY : showing;
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
		var faces = com.mopicmp.npcstudio.dialogue.Node.Line.Face.values();
		int which = payload.face();
		current = new DialogueClientState(payload.npc(), payload.speaker(), payload.text(),
			List.of(), payload.mode(), payload.showing(),
			// Out of range is a packet from a newer server than this client. The
			// character is the answer every line meant before any of this existed, and
			// it is the right answer to fall back to rather than refusing to draw.
			which >= 0 && which < faces.length ? faces[which] : faces[0],
			payload.nameColour());
		holdIfAsked();
	}

	public static void show(ShowChoicePayload payload) {
		// A question is always the character's, and its name is drawn the usual colour.
		// Not an oversight: an answer already carries its own colour, and a prompt that
		// could also be recoloured would be two colours arguing on one screen.
		current = new DialogueClientState(payload.npc(), payload.speaker(), payload.prompt(),
			payload.options(), payload.mode(), payload.showing(),
			com.mopicmp.npcstudio.dialogue.Node.Line.Face.SPEAKER, "");
		holdIfAsked();
	}

	public com.mopicmp.npcstudio.dialogue.Node.Line.Face face() {
		return face;
	}

	public String nameColour() {
		return nameColour;
	}

	/**
	 * The document's blanket answer, applied to the line that has just arrived.
	 *
	 * Only for the bar. The other two modes are screens and have the keyboard already,
	 * and two things holding one player is fine right up until one of them lets go.
	 */
	private static void holdIfAsked() {
		Holding.line(current != null
			&& !current.takesOverScreen() && !current.isCutscene()
			&& current.showing().holdsPlayer());
	}

	/** How this document asked to be shown: how wide, how tall, how fast. */
	public com.mopicmp.npcstudio.dialogue.Manner showing() {
		return showing;
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
		// The blanket rule and nothing else, for the reason given at Holding.barGone:
		// the bar leaving is not the conversation ending, and a graph that took the
		// keys for a walk still has them.
		Holding.barGone();
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
	public Words text() { return text; }

	/**
	 * As much of the line as has been typed so far.
	 *
	 * Timed from when the line arrived rather than counted per frame, so the
	 * speed is the same on any machine — a frame counter would type faster for
	 * whoever had the better graphics card.
	 */
	public Words visibleText(float playerPace) {
		if (typingSkipped) return text;
		long elapsed = Util.getMillis() - startedAt;
		// Through Pace rather than by multiplying, so that full stops, commas and
		// dashes cost what they are worth. It used to be one number times another,
		// and the result was a line with no shape in it at all — every mark in the
		// text typed at the speed of the letters beside it.
		//
		// Which of the two paces applies is the document's decision and is made in
		// one place; a document that says nothing leaves the player's own setting
		// alone, which is the only honest default for somebody else's preference.
		int shown = com.mopicmp.npcstudio.dialogue.text.Pace.shown(
			text.plain(), showing.paceOr(playerPace), elapsed, showing.breathes());
		// Cut as a decorated line rather than as a string. Typing out the plain text
		// and colouring it at the end would show the whole line arriving in one
		// colour and then repainting itself, which reads as a fault, not an effect.
		return text.first(shown);
	}

	/** True once the whole line is on screen. A click before this completes it instead. */
	public boolean finishedTyping(float playerPace) {
		return typingSkipped || visibleText(playerPace).length() >= text.length();
	}
}
