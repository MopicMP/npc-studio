package com.mopicmp.npcstudio.client.dialogue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Whether the player is being kept still, and every way out of it.
 *
 * <h2>Why this is its own thing with its own clock</h2>
 *
 * Because the thing it does is the one thing in this mod that can take the game away
 * from somebody. Everything else that goes wrong here is a character behaving oddly;
 * this one is a person unable to move, and a person unable to move has no way to go
 * and read a log about it.
 *
 * It was tied to the line on screen and nothing else, and that was reported the way
 * such things always are: the character said her piece and walked off, the line
 * stayed up while she walked, and the player stood rooted watching her go.
 *
 * <h2>The three ways out, and why there are three</h2>
 *
 * Because any one of them can fail. They are meant to overlap.
 *
 * <ol>
 *   <li><b>The graph says so.</b> The ordinary way: a verb takes it and a verb gives
 *       it back, and the conversation ending gives it back whatever the graph forgot
 *       — the same bargain the walls and the gaze are held to.</li>
 *   <li><b>The clock.</b> Nothing holds anybody for longer than {@link #LONGEST},
 *       ever, for any reason. This is not a tidy-up; it is the promise that no
 *       mistake in anybody's graph, and none of mine, can cost somebody their
 *       evening.</li>
 *   <li><b>The player.</b> Holding sneak lets go by hand. Long enough to be meant,
 *       short enough to be found — and said on screen while it is happening, because
 *       an escape nobody knows about is not an escape.</li>
 * </ol>
 *
 * <h2>Why sneak rather than escape</h2>
 *
 * Escape opens the menu, which already takes the keyboard, so it would look like it
 * had worked and then not have. Sneak is held rather than pressed, which is what
 * makes it deliberate — nobody leans on it by accident — and it is a key every
 * player already has under a finger.
 */
public final class Holding {

	private Holding() { }

	/**
	 * As long as anybody may be held, whatever anything else says.
	 *
	 * A minute, which is the same patience the server gives a conversation that has
	 * stopped moving — on purpose, so that the two limits cannot disagree about how
	 * long a scene may hang before somebody is let out of it.
	 */
	public static final long LONGEST = 60_000;

	/** How long sneak must be held to break out. Deliberate, not a twitch. */
	public static final long BREAKS_OUT = 800;

	/**
	 * The two reasons somebody may be standing still, and why they are separate.
	 *
	 * A verb in the graph says it from here to there; a document may also say "hold
	 * while a line is up", which is the simple case and wants no verb at all. They are
	 * different facts and either may be true alone — a line that arrives without the
	 * document asking must not release a hold the graph put on deliberately.
	 *
	 * They are two flags and <em>one</em> gate. Everything that asks whether somebody
	 * is held asks {@link #held}, so the clock and the way out cannot be got round by
	 * whichever reason happens to be in force.
	 */
	private static boolean byGraph;
	private static boolean byLine;

	/**
	 * Whether the graph has said anything at all about holding, this conversation.
	 *
	 * <h2>Why the blanket rule stands down once it has</h2>
	 *
	 * Because otherwise they fight, and the report is what that looks like: the
	 * author put a release node in, the release ran, and the player was still stuck —
	 * because the document also said "hold while a line is showing" and the line was
	 * still showing. The node did exactly what it said and changed nothing.
	 *
	 * The same rule the gaze already lives by, which is worth naming because it keeps
	 * coming up: an author outranks a reflex. A document's blanket answer is a
	 * convenience for scenes that never think about this; the moment one does think
	 * about it, the thinking wins, and the blanket is out of the way for the rest of
	 * the conversation.
	 */
	private static boolean graphSpoke;
	private static long since;
	private static long sneakingSince;
	private static boolean brokenOut;

	/** The graph has taken the movement keys, or handed them back. */
	public static void told(boolean held) {
		// Even when it changes nothing: what matters is that the graph has an opinion,
		// and a graph that opens by saying "let them walk" has one.
		graphSpoke = true;
		byLine = false;
		if (held == byGraph) return;
		byGraph = held;
		began();
	}

	/**
	 * A line has arrived, and its document has an opinion about standing still.
	 *
	 * Called for every line, so a document that says nothing hands the keys back at
	 * the next line rather than at the end of the conversation.
	 */
	public static void line(boolean held) {
		if (graphSpoke) return;
		if (held == byLine) return;
		byLine = held;
		began();
	}

	private static void began() {
		since = Util.getMillis();
		// A fresh order clears a break-out. Somebody who let themselves go and then
		// walked into the next scene should be held by it — otherwise one escape would
		// free them from every scene for the rest of the session.
		brokenOut = false;
		sneakingSince = 0;
	}

	/**
	 * The bar has gone, which is not the same as the conversation ending.
	 *
	 * Only the blanket rule is dropped. A hold the graph put on spans nodes on purpose
	 * — held while she walks off, released when the scene says — and a line timing out
	 * in the middle of that is not the graph changing its mind. Letting go here would
	 * make "take the bar away after four seconds" quietly mean "and also hand back the
	 * keys", which is two things wearing one name.
	 *
	 * What still lets go of a graph's hold: the graph, the end of the conversation,
	 * the minute, and the player's own hands.
	 */
	public static void barGone() {
		if (byLine) began();
		byLine = false;
	}

	/** Everything is let go of: the conversation is over, or there is no world. */
	public static void forget() {
		byGraph = false;
		byLine = false;
		graphSpoke = false;
		brokenOut = false;
		sneakingSince = 0;
	}

	/**
	 * Whether the player is being kept still at this moment.
	 *
	 * Asked by the input every tick, and it is where the clock and the break-out are
	 * applied — so there is no path anywhere that consults the flag without also
	 * consulting the ways out of it.
	 */
	public static boolean held() {
		if (!(byGraph || byLine) || brokenOut) return false;
		if (Util.getMillis() - since > LONGEST) return false;
		return true;
	}

	/**
	 * One tick of watching for somebody letting themselves out.
	 *
	 * Measured as a length of holding rather than as a press, so it cannot be done by
	 * accident and cannot be missed by a dropped tick.
	 */
	public static void tick() {
		if (!held()) {
			sneakingSince = 0;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		boolean sneaking = client.options != null && client.options.keyShift.isDown();
		if (!sneaking) {
			sneakingSince = 0;
			return;
		}
		if (sneakingSince == 0) {
			sneakingSince = Util.getMillis();
			return;
		}
		if (Util.getMillis() - sneakingSince >= BREAKS_OUT) brokenOut = true;
	}

	/** How far through letting themselves out somebody is, nought to one. */
	public static float breakingOut() {
		if (sneakingSince == 0 || !held()) return 0;
		return Math.clamp((Util.getMillis() - sneakingSince) / (float) BREAKS_OUT, 0f, 1f);
	}

	/**
	 * Whether the reason for standing still is worth saying on screen.
	 *
	 * Always, while it is happening. A scene that takes the keys and says nothing is
	 * indistinguishable from a game that has frozen, and that is the reading somebody
	 * arrives at in about two seconds.
	 */
	public static boolean sayingSo() {
		return held();
	}
}
