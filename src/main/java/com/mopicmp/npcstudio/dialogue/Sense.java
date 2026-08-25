package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * The names of everything a graph may ask a character about herself.
 *
 * <h2>Why the names live here and the answers do not</h2>
 *
 * Because these two things are needed in different places. The validator has to
 * know whether {@code lead.strngth} is a typo, and the editor has to offer a
 * list to pick from — neither of them has a character to ask, and neither of
 * them may touch Minecraft. Answering, on the other hand, needs a real character
 * standing in a real world, and that lives in {@code brain/Senses}.
 *
 * So this is the vocabulary and that is the dictionary. They are kept apart the
 * same way the engine is kept apart from the world, and for the same reason: a
 * graph can then be checked without a game running.
 *
 * <h2>The rule for adding one</h2>
 *
 * A reading earns its place by being needed by a graph somebody is writing, not
 * by seeming useful. Names here are harder to change than they look — graphs in
 * the wild will use them, and renaming one breaks those graphs silently, since
 * an unknown reading is false rather than an error at runtime.
 */
public final class Sense {

	private Sense() { }

	/** How frightened she is, nought to one. */
	public static final String ALARM = "alarm";

	/** The same, as one of "calm", "suspicious", "alert". */
	public static final String MOOD = "mood";

	/** Whether she is on to anything at all; the rest are nought without it. */
	public static final String LEAD = "lead";

	public static final String LEAD_STRENGTH = "lead.strength";
	public static final String LEAD_URGENCY = "lead.urgency";

	/** Seen, or only heard — the difference that decides whether she is certain. */
	public static final String LEAD_SEEN = "lead.seen";

	public static final String LEAD_DISTANCE = "lead.distance";

	/** Whether she is on her way somewhere. */
	public static final String WALKING = "walking";

	/** Whether there is anything in her main hand. Not which weapon it is. */
	public static final String ARMED = "armed";

	/**
	 * What sort of thing is in her main hand: nothing, melee, drawn, loaded,
	 * swung, shield, thrown, other.
	 *
	 * A guess made from what the item says about itself, and honest about
	 * failing: an item nobody can place reads as "other" rather than as the
	 * nearest thing. See {@code foe/Arms}.
	 */
	public static final String WEAPON = "weapon";

	/**
	 * The name of what is in her main hand, or empty.
	 *
	 * <h2>Why the raw name is offered as well as the guess</h2>
	 *
	 * Because it is how an author corrects us without touching java. The guess
	 * will be wrong about somebody's weapon eventually - a mod that fires on a
	 * plain use, a datapack that hides a gun inside a stick - and when it is, a
	 * graph can say "if what she is holding is called this, treat it as that" and
	 * carry on.
	 *
	 * That is the whole of what was meant by teaching the brain with blocks, and
	 * it costs one reading rather than an extension point.
	 */
	public static final String HAND = "hand";

	/** How far the nearest living player is. */
	public static final String PLAYER_DISTANCE = "player.distance";

	/** Whether another character running the same graph is in sight. */
	public static final String KIN = "kin";

	/** And how far off, or a thousand when there is none. */
	public static final String KIN_DISTANCE = "kin.distance";

	public static final List<String> KNOWN = List.of(
		ALARM, MOOD, LEAD, LEAD_STRENGTH, LEAD_URGENCY, LEAD_SEEN, LEAD_DISTANCE,
		WALKING, ARMED, WEAPON, HAND, PLAYER_DISTANCE, KIN, KIN_DISTANCE);

	/**
	 * A distance meaning "nobody" or "nothing".
	 *
	 * A thousand blocks rather than infinity, so that a graph written the obvious
	 * way — {@code player.distance < 10} — is simply false when there is nobody,
	 * instead of having to be written with a second condition guarding it.
	 */
	public static final double OUT_OF_MIND = 1000;

	public static boolean known(String name) {
		return KNOWN.contains(name);
	}

	/**
	 * What kind of thing a reading is, in the same words {@link Value#typeName}
	 * uses, or null when nobody has heard of it.
	 *
	 * Here so that {@code mood > 0.5} is caught by the validator rather than
	 * being quietly false for ever. That failure mode is the one worth spending
	 * a table on: it looks like a character who ignores her orders, and there is
	 * nothing in the graph to point at.
	 */
	public static String typeOf(String name) {
		return switch (name) {
			case ALARM, LEAD_STRENGTH, LEAD_URGENCY, LEAD_DISTANCE, PLAYER_DISTANCE,
				KIN_DISTANCE -> "number";
			case MOOD, WEAPON, HAND -> "text";
			case LEAD, LEAD_SEEN, WALKING, ARMED, KIN -> "flag";
			default -> null;
		};
	}
}
