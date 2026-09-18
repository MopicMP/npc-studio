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

	/**
	 * The name of the route she is walking, or empty when she is not on one.
	 *
	 * <h2>Why this exists beside {@link #WALKING}</h2>
	 *
	 * Because a flag cannot say <em>whose</em> walk. {@link Node.Walk} has to wait
	 * for the route it ordered and no other, and a character crossing a courtyard
	 * for some entirely different reason answers yes to "are you walking" — so a
	 * node reading the flag would move on the moment anything at all stopped moving.
	 *
	 * The name is the id of the node that ordered it, which is why it is worth
	 * offering to authors as well: {@code walking_to = "патруль"} is a readable way
	 * of asking whether she is on her round, and there was no way to ask it before.
	 */
	public static final String WALKING_TO = "walking_to";

	/**
	 * The name of the route she has just finished, or empty.
	 *
	 * Set when the last point is reached — and also when the route is abandoned
	 * because there is no way through or she is wedged. That is deliberate and it is
	 * the difference between a character who gives up and a graph that hangs:
	 * "finished" here means she is no longer at it, not that she succeeded. A wall
	 * built across a patrol overnight should leave the guard doing the next thing
	 * her graph says, not standing in a doorway for ever.
	 *
	 * Cleared once the graph has taken it — see {@link Effect.Arrived}.
	 */
	public static final String WALKED = "walked";

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

	/**
	 * Which skill of her brain she has running, or empty for none.
	 *
	 * The one reading a scenario needs about itself: "am I already fighting" is
	 * the question that stops a scenario ordering the same fight twenty times a
	 * second, and it is not answerable from anything else.
	 */
	public static final String DOING = "doing";

	/**
	 * Whether whoever this skill was told to act on is still there.
	 *
	 * <h2>Why a skill needs its own pair of these</h2>
	 *
	 * Because a skill is written about {@code target} and nothing else. "How to
	 * fight" that asked about {@code kin} would only ever serve a duel; asked
	 * about the target, the same nodes serve a duel, a brawl and a guard post,
	 * and the caller decides which by naming who.
	 *
	 * Outside a skill these read as nothing and nought, the same as everything
	 * else that names nobody.
	 */
	public static final String TARGET = "target";

	public static final String TARGET_DISTANCE = "target.distance";

	/**
	 * Whether she could hit the target from where she stands.
	 *
	 * <h2>Why this is asked rather than worked out in the graph</h2>
	 *
	 * Because the graph cannot know the answer. Reach is her interaction range plus
	 * half of each body, so it changes with a weapon, with a build, with anything
	 * that scales her — and every graph that guessed at it would guess a different
	 * number and all of them would be wrong for somebody.
	 *
	 * It was guessed at, and the guess cost an exchange. The fighting struck below
	 * two and eight tenths while the body could reach three and a half, so a blow's
	 * own knockback pushed her into the gap between the two: still able to hit, and
	 * told to walk. Every blow was followed by an approach, which is why a fight
	 * read as strike, shove, walk, strike.
	 */
	public static final String IN_REACH = "target.in_reach";

	/** Whether another character running the same graph is in sight. */
	public static final String KIN = "kin";

	/** And how far off, or a thousand when there is none. */
	public static final String KIN_DISTANCE = "kin.distance";

	public static final List<String> KNOWN = List.of(
		ALARM, MOOD, LEAD, LEAD_STRENGTH, LEAD_URGENCY, LEAD_SEEN, LEAD_DISTANCE,
		WALKING, WALKING_TO, WALKED, ARMED, WEAPON, HAND, DOING, PLAYER_DISTANCE,
		KIN, KIN_DISTANCE, TARGET, TARGET_DISTANCE, IN_REACH);

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
				KIN_DISTANCE, TARGET_DISTANCE -> "number";
			case MOOD, WEAPON, HAND, DOING, WALKING_TO, WALKED -> "text";
			case LEAD, LEAD_SEEN, WALKING, ARMED, KIN, TARGET, IN_REACH -> "flag";
			default -> null;
		};
	}
}
