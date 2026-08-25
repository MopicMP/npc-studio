package com.mopicmp.npcstudio.dialogue;

/**
 * Something the engine asks the world to do.
 *
 * The engine never touches the world itself — it returns a list of these and
 * the server carries them out. That is what keeps it testable: a test can run a
 * whole conversation and then assert on the effects that came out, without a
 * server, an inventory, or a single Minecraft class.
 *
 * It also keeps the order honest. Effects come out in the order they were
 * reached, so replaying them replays the conversation.
 */
public sealed interface Effect {

	/** Runs a command as the server, with the player as context. */
	record RunCommand(String command) implements Effect { }

	record GiveItem(String item, int count) implements Effect { }

	record TakeItem(String item, int count) implements Effect { }

	/**
	 * Places a saved structure.
	 *
	 * The anchor is a named point rather than coordinates, so a scene can be
	 * copied to another part of the map without editing every action in it.
	 * `overTicks` spreads the placement out — a building that appears one block
	 * at a time is the point of the feature; one that blinks into existence is
	 * just a command block with extra steps.
	 */
	record PlaceStructure(String structure, String anchor, int overTicks) implements Effect { }

	record PlaySound(String sound, float volume, float pitch) implements Effect { }

	/**
	 * Starts an animation on the NPC that is speaking, for a while.
	 *
	 * <h2>Why there is a duration at all</h2>
	 *
	 * Because without one an animation never finishes. A looping emote loops for
	 * the rest of the session; a non-looping one is worse, because its playhead
	 * stops at the last keyframe and stays there — a character told to bow stayed
	 * bowed until somebody told it to do something else. Both read as a bug and
	 * neither was one: nothing had ever been asked to end.
	 *
	 * @param ticks how long to hold it, in ticks. Above zero, the character goes
	 *              back to standing or walking afterwards; zero or below means it
	 *              keeps going until something else takes over, which is what a
	 *              guard permanently at attention wants.
	 */
	record PlayAnimation(String animation, int ticks) implements Effect { }

	/**
	 * Sets the face the speaking NPC makes, for a while.
	 *
	 * <h2>Why an author says this rather than the mod working it out</h2>
	 *
	 * It was going to be derived from whichever animation was playing, which would
	 * have cost nothing — the animation is already synchronised. Then the pack was
	 * counted: of seven hundred and twelve animations, <b>four</b> are named after
	 * a mood. A rule reading names would have fired once in a blue moon and at the
	 * wrong moment.
	 *
	 * So it is authored, like the line it belongs to. Which is the better answer
	 * anyway: the same words can be said kindly or bitterly, and only the person
	 * writing them knows which.
	 *
	 * @param expression one of {@link com.mopicmp.npcstudio.entity.Expression}, by
	 *                   name. An unknown one is an ordinary face rather than an
	 *                   error — a conversation must not stop over a typo.
	 * @param ticks      how long to hold it. Zero or below holds it until
	 *                   something else changes it, the same as an animation.
	 */
	record Express(String expression, int ticks) implements Effect { }

	// ------------------------------------------------------------- the verbs

	/**
	 * Walk to a {@link Mark}.
	 *
	 * <h2>Why this does not wait</h2>
	 *
	 * It orders a walk and returns at once, and the graph waits for the arrival
	 * itself — {@code until walking is false}. That is not a shortcut: it is what
	 * lets a character do something else on the way. A walk that blocked the
	 * graph until it finished would be a character who cannot look at anything,
	 * change her mind, or notice she is being shot at while crossing a courtyard.
	 *
	 * @param pace nought to one. Below half is a stroll to go and see something;
	 *             one is a run. The number is the same one the legs already
	 *             work in, so an author can ask for anything in between.
	 */
	record WalkTo(String mark, float pace) implements Effect { }

	/** Stop where she is. The walk is abandoned, not paused. */
	record Halt() implements Effect { }

	/**
	 * Turn to face a {@link Mark}, and keep facing it.
	 *
	 * Held rather than done once, because looking at somebody is a state and not
	 * an act — a character told to look at you and then left alone should still
	 * be looking at you a second later. {@link Mark#NOTHING} lets go.
	 *
	 * <h2>What this overrules</h2>
	 *
	 * The watching reflex, which turns her head towards whatever she notices. A
	 * graph saying where to look is an author saying it, and an author outranks a
	 * reflex — otherwise a scripted stare would be broken by a door closing
	 * somewhere behind the camera.
	 */
	record LookAt(String mark) implements Effect { }

	/**
	 * Aim at a {@link Mark} and shoot whatever is in her hand.
	 *
	 * She keeps aiming for as long as the weapon takes to ready, rather than
	 * pointing once at the moment of the order: a bow takes a second to draw, and
	 * anything worth shooting at has moved by then.
	 *
	 * Whether there is ammunition, how long the draw is worth, whether the item
	 * can be fired at all — none of that is here. It belongs to the weapon and is
	 * already answered by it; this only says at whom.
	 */
	record Fire(String mark) implements Effect { }
}
