package com.mopicmp.npcstudio.client.entity;

/**
 * Which way a character's eyes are turned, from its own left to its own right.
 *
 * <h2>Why a fraction and not one of three columns</h2>
 *
 * An eye on a Minecraft skin is two pixels across, so the first version of this
 * answered with a whole number: left, ahead or right, the iris being redrawn a
 * column across. That was wrong twice over. It looked like the picture being
 * swapped for a different picture rather than like an eye moving — and it was,
 * since a whole pixel is the entire width of the thing.
 *
 * The eye travels the same one pixel now, but it <b>slides</b>: the drawing is
 * moved by a fraction of a pixel at a time, which is geometry rather than
 * texture and so has no smallest step. The answer here is therefore continuous,
 * and a glance is a movement lasting a sixth of a second rather than a frame in
 * which everything is suddenly elsewhere.
 *
 * <h2>The head turns first, and the eyes take up what is left</h2>
 *
 * A character already turns its head towards whoever is near. If the eyes simply
 * followed the same target they would sit dead centre for ever, because the head
 * has already done the work. What is left over is the interesting part: the head
 * only turns so far, so when somebody stands off to the side, or behind, the
 * head stops short and the eyes carry the rest. That is exactly when a glance is
 * worth having — and it costs nothing to compute, being a subtraction.
 *
 * <h2>And when nobody is there</h2>
 *
 * The eyes wander, on the same principle as the blink: a stable scatter from the
 * character's own id, so two characters standing together never glance in step,
 * and nothing is stored or sent. Mostly they rest in the middle — a character
 * whose eyes are constantly darting looks alarmed rather than alive.
 */
public final class Gazing {

	/**
	 * How far off the head has to be looking before the eyes take up any slack,
	 * and how far off before they have taken up all they can.
	 *
	 * The first is generous on purpose: the head's own turning is smoothed over
	 * several ticks, so a smaller angle would have the eyes drifting during every
	 * ordinary turn. Between the two the answer is a ramp rather than a switch, so
	 * an eye follows somebody walking past instead of snapping over when they
	 * cross a line.
	 */
	private static final float DEAD = 12f;
	private static final float FULL = 45f;

	/** How long a wandering gaze rests before moving, in seconds, and its spread. */
	private static final float HOLD = 1.9f;
	private static final float VARY = 1.3f;

	/**
	 * How long a wandering eye takes to travel, in seconds.
	 *
	 * A real saccade is nearer a twentieth of a second, and at this size it looks
	 * like a dropped frame rather than like an eye: the whole journey is one pixel
	 * wide, so however fast a real eye is, all of it has to be visible inside that
	 * pixel or it reads as the picture changing.
	 *
	 * Measured against the frame rather than the tick, which is the thing that
	 * caught this being too quick. At a sixth of a second the eye moves about a
	 * third of its travel per frame at its quickest — visible as a movement. At the
	 * tenth of a second it was, a single tick could carry it more than half way,
	 * which is what "not smooth" meant.
	 */
	private static final float MOVE = 0.16f;

	/** The character's own left, straight ahead, and its own right. */
	public static final float LEFT = -1f;
	public static final float AHEAD = 0f;
	public static final float RIGHT = 1f;

	private Gazing() { }

	/**
	 * Which way the eyes are turned this frame, from −1 to 1.
	 *
	 * Fractions all the way through, because the eye moves by sliding rather than
	 * by being redrawn a column across. A whole number would be a picture swapped
	 * for another picture, which is what this looked like when it was one.
	 *
	 * @param entityId whose eyes, so that two characters never glance together
	 * @param ticks    the character's own age, in ticks
	 * @param slack    how far the head is short of what it is looking at, in
	 *                 degrees, positive when that is to the character's right
	 * @param watching whether there is anything to look at at all
	 * @return −1 for the character's own left through 1 for its own right, in its
	 *         own terms rather than the viewer's
	 */
	public static float aside(int entityId, float ticks, float slack, boolean watching) {
		float watched = watching ? ramp(slack) : 0f;
		// The wandering is squeezed out as the attention takes hold, rather than
		// being switched off. Somebody standing straight ahead does not stop a
		// character's eyes from moving — they only stop them from moving far.
		return watched + (1f - Math.abs(watched)) * wander(entityId, ticks);
	}

	/**
	 * How far up or down the eyes are turned this frame, from −1 to 1.
	 *
	 * <h2>Why this is not the sideways one turned ninety degrees</h2>
	 *
	 * Sideways, an eye <em>slides</em>: its own pixels are redrawn a column across,
	 * into the white beside them. Up and down there is nothing to slide into. The
	 * iris fills the whole height of its eye on every face marked by hand, so an
	 * eye moved down a row would be half on a cheek.
	 *
	 * What a pixel artist draws instead is a <b>shorter pupil, pushed to one
	 * side</b>: looking down, the eye's lower row is iris and the upper row is
	 * white. So that is what this drives — not a movement of the drawing but which
	 * part of its own eye the pupil occupies. It always has room, because giving
	 * room up is the movement.
	 *
	 * @param entityId whose eyes
	 * @param ticks    the character's own age, in ticks
	 * @param slack    how far the head is short of what it is looking at, in
	 *                 degrees, positive when that is above the character
	 * @param watching whether there is anything to look at at all
	 */
	public static float upDown(int entityId, float ticks, float slack, boolean watching) {
		float watched = watching ? ramp(slack) : 0f;
		// A different character as far as the wandering is concerned, so the eyes do
		// not happen to go up every time they go left.
		return watched + (1f - Math.abs(watched)) * wander(entityId ^ ACROSS, ticks) * IDLE;
	}

	/**
	 * How much of its travel an idle eye spends looking up or down.
	 *
	 * Less than sideways. A person's eyes wander mostly across what is in front of
	 * them; one that keeps looking at the ceiling reads as distracted rather than
	 * alive, and on a two-row eye the whole movement is half a pixel anyway.
	 */
	private static final float IDLE = 0.6f;

	/** Anything that makes the vertical wandering a different sequence from the other. */
	private static final int ACROSS = 0x5EED;

	/** How much of the eye's travel an angle the head could not manage is worth. */
	static float ramp(float slack) {
		float over = (Math.abs(slack) - DEAD) / (FULL - DEAD);
		return Math.clamp(over, 0f, 1f) * Math.signum(slack);
	}

	/**
	 * Where the eyes rest when nothing has their attention.
	 *
	 * Three of every five glances are straight ahead, so the eyes spend most of
	 * their time still and the movement is noticed when it happens. Each is eased
	 * out of the one before it, which is why this is worked out from the step the
	 * clock is in <em>and</em> the one before — there is nowhere to remember a
	 * movement half finished, and nowhere that would agree between clients.
	 */
	static float wander(int entityId, float ticks) {
		float scatter = scatter(entityId);
		float hold = HOLD + scatter * VARY;
		float when = ticks / 20f + scatter * hold;
		int step = (int) Math.floor(when / hold);

		float since = when - step * hold;
		float part = Math.clamp(since / MOVE, 0f, 1f);
		float eased = part * part * (3f - 2f * part);
		return rest(entityId, step - 1) + (rest(entityId, step) - rest(entityId, step - 1)) * eased;
	}

	/** Where one of a character's resting places is. */
	private static float rest(int entityId, int step) {
		return switch (Math.floorMod(mix(entityId ^ mix(step)), 5)) {
			case 3 -> LEFT;
			case 4 -> RIGHT;
			default -> AHEAD;
		};
	}

	/**
	 * A stable scatter from an id: the fractional part of a multiple of the golden
	 * ratio, which spreads consecutive ids about as far apart as anything can.
	 *
	 * The same trick the blink uses, and for the same reason — a row of characters
	 * placed one after another has consecutive ids.
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
