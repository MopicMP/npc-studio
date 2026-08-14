package com.mopicmp.npcstudio.client.entity;

/**
 * When a character has its eyes shut, and in what manner.
 *
 * <h2>Why a blink is not one movement</h2>
 *
 * The first version of this had one: a short pulse, every few seconds, for ever.
 * It is the right movement and having only it is what made a character look
 * mechanical — the eyes did the identical thing at an identical speed all day,
 * which nobody's do. A metronome is not made lifelike by slowing it down.
 *
 * So a character has four things it can do with its lids, chosen afresh each
 * time and never in an order anybody could predict:
 *
 * <ul>
 * <li>an ordinary blink, which is most of them;
 * <li>a double blink — two quick ones together, the commonest human tic;
 * <li>a slow blink, which shuts, rests a moment, and opens again;
 * <li>a squint, held half closed for about a second, as though thinking.
 * </ul>
 *
 * They are all the same drawing at the bottom — a lid coming down over an eye —
 * so nothing new has to be right for them to work. What varies is only how far
 * and for how long, which is exactly where the life is.
 *
 * <h2>And they are still worked out rather than stored</h2>
 *
 * Each character's clock is offset by its own entity id, and the gap between
 * blinks varies with it, so a crowd never blinks in step — the single detail
 * Fresh Animations gets right and everything without it gets wrong. Which of the
 * four a given blink is comes from the same id and the number of that blink, so
 * every client works out the same answer at the same moment with nothing sent
 * between them and nothing kept.
 */
public final class Blinking {

	/** How long an ordinary blink takes, shut and open again, in seconds. */
	private static final float LENGTH = 0.18f;

	/**
	 * The gap between blinks, and how much it varies from character to character.
	 *
	 * A person blinks every three or four seconds, which is what this was set to
	 * and which turned out to be too much — a character is looked at from across a
	 * room, and a movement noticed at that distance reads as more frequent than
	 * the same movement on a face in front of you. Six to ten and a half seconds
	 * is roughly half as often, and it still never looks like staring.
	 */
	private static final float GAP = 6.0f;
	private static final float SPREAD = 4.5f;

	/** How far apart the two halves of a double blink sit. */
	private static final float AGAIN = 0.1f;

	/** How long a slow blink takes, and how much of that it spends shut. */
	private static final float SLOW = 0.5f;
	private static final float SLOW_HELD = 0.16f;

	/** How far a squint closes, and how long it is held there. */
	private static final float SQUINT = 0.45f;
	private static final float SQUINT_HELD = 0.9f;
	private static final float SQUINT_EDGE = 0.22f;

	/** What a character can do with its lids. */
	enum Act { BLINK, DOUBLE, SLOW_BLINK, SQUINT }

	private Blinking() { }

	/**
	 * How shut the eyes are, nought to one.
	 *
	 * @param entityId whose eyes, so that two characters never blink together
	 * @param ticks    the character's own age, in ticks
	 */
	public static float shut(int entityId, float ticks) {
		float scatter = scatter(entityId);
		float gap = GAP + scatter * SPREAD;
		float when = ticks / 20f + scatter * gap;
		int turn = (int) Math.floor(when / gap);
		float within = when - turn * gap;

		return shape(act(entityId, turn), within);
	}

	/**
	 * Which of the four this blink is.
	 *
	 * Ordinary most of the time: three quarters. The others are worth having
	 * because they are noticed, and worth being rare for the same reason — a
	 * character that slow-blinks every time looks drowsy rather than alive.
	 */
	static Act act(int entityId, int turn) {
		return switch (Math.floorMod(mix(entityId ^ mix(turn)), 12)) {
			case 9, 10 -> Act.DOUBLE;
			case 11 -> Act.SLOW_BLINK;
			case 8 -> Act.SQUINT;
			default -> Act.BLINK;
		};
	}

	/** How shut the eyes are, this far into one of the four. */
	static float shape(Act act, float since) {
		if (since < 0) return 0;
		return switch (act) {
			case BLINK -> pulse(since, LENGTH);
			// Two of them, the second following straight on. Which is nearer to what
			// a person does than one blink twice as long would be.
			case DOUBLE -> Math.max(pulse(since, LENGTH), pulse(since - LENGTH - AGAIN, LENGTH));
			case SLOW_BLINK -> hold(since, SLOW, SLOW_HELD, 1f);
			case SQUINT -> hold(since, SQUINT_EDGE, SQUINT_HELD, SQUINT);
		};
	}

	/**
	 * One eye shut, held a moment, and opened again.
	 *
	 * A wink is not a blink with one eye missing: it is slower, and it <em>stops</em>
	 * at the bottom. Everything else the lids do passes through being shut on its
	 * way back up, and the difference between passing through and pausing there is
	 * the whole difference between a blink and a joke.
	 *
	 * @param since how long since the wink began, in seconds
	 */
	public static float wink(float since) {
		return hold(since, WINK_EDGE, WINK_HELD, 1f);
	}

	/** How long a wink takes to close, and how long it stays closed. */
	private static final float WINK_EDGE = 0.13f;
	private static final float WINK_HELD = 0.22f;

	/**
	 * Which eye a character winks with.
	 *
	 * Its own, and always the same one: people have a side they wink with, and a
	 * character that alternates looks like it has something in its eye. Taken from
	 * the id so that a crowd is not all winking with the same eye either.
	 */
	public static boolean winksRight(int entityId) {
		return (mix(entityId) & 1) == 0;
	}

	/**
	 * Shut and open again in one arc.
	 *
	 * Half a sine is the cheapest curve that starts and ends at rest, which is what
	 * keeps a lid from snapping into place at either end.
	 */
	private static float pulse(float since, float length) {
		if (since <= 0 || since >= length) return 0;
		return (float) Math.sin(since / length * Math.PI);
	}

	/**
	 * Shut, stay shut a moment, and open again — with the closing eased at both
	 * ends so that neither the going nor the coming back is a corner.
	 *
	 * @param edge how long the closing takes, and the opening
	 * @param held how long it stays where it got to
	 * @param far  how far shut it goes: all the way for a slow blink, part way for
	 *             a squint
	 */
	private static float hold(float since, float edge, float held, float far) {
		if (since <= 0 || since >= edge * 2 + held) return 0;
		if (since < edge) return far * smooth(since / edge);
		if (since < edge + held) return far;
		return far * smooth(1f - (since - edge - held) / edge);
	}

	private static float smooth(float t) {
		float clamped = Math.clamp(t, 0f, 1f);
		return clamped * clamped * (3f - 2f * clamped);
	}

	/**
	 * A stable scatter from an id: the fractional part of a multiple of the golden
	 * ratio, which spreads consecutive ids about as far apart as anything can.
	 *
	 * Consecutive ids are exactly what a row of NPCs placed one after another will
	 * have.
	 */
	static float scatter(int entityId) {
		float value = (entityId * 0.6180339887f) % 1f;
		return value < 0 ? value + 1f : value;
	}

	/** A cheap avalanche, so neighbouring numbers give unrelated answers. */
	private static int mix(int value) {
		int mixed = value * 0x9E3779B1;
		mixed ^= mixed >>> 15;
		mixed *= 0x85EBCA6B;
		mixed ^= mixed >>> 13;
		return mixed;
	}
}
