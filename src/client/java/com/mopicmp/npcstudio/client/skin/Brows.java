package com.mopicmp.npcstudio.client.skin;

/**
 * Where a character's brows sit this frame, against where the skin drew them.
 *
 * <h2>Why the brows are worth more than everything else on the face</h2>
 *
 * Because they are the only part of an expression that survives being eight
 * pixels tall. An eye can narrow, widen and turn, and all three are a fraction
 * of a pixel on a face this size — real, but felt rather than seen. A brow moves
 * a <em>whole pixel</em> and it moves against the forehead, which is a flat
 * field of one colour, so the change is unmistakable at the distance a character
 * is actually looked at.
 *
 * It is also the one thing a person reads first. Surprise, anger, doubt and
 * worry are all brows; the eyes underneath them are doing much the same thing in
 * every case.
 *
 * <h2>Two numbers, not a drawing</h2>
 *
 * A brow has exactly two degrees of freedom worth having, and every expression
 * anybody names is made of them:
 *
 * <ul>
 * <li><b>raise</b> — the whole brow up or down. Up is surprise, down is a scowl.
 * <li><b>slant</b> — the inner end up against the outer end down, pivoting about
 *     the middle rather than lifting. Inner ends up is worry and sadness, and it
 *     is the single most recognisable shape a brow makes; inner ends down is the
 *     knot of a frown.
 * </ul>
 *
 * Raised on one side only is the third thing people name — doubt, scepticism, the
 * raised eyebrow — and it is not a third number. It is the same raise with the
 * other side left alone, which is why the two sides are carried separately here
 * and the slant is not.
 *
 * <h2>And it is still the skin's own pixels</h2>
 *
 * Nothing here is a drawing of a brow. The brow the artist painted is picked up,
 * put down a little higher or lower, and the place it came from is painted out
 * with the bare forehead beside it — the same trick as the eyelid and the glance,
 * and for the same reason: a mod that shipped its own brows would be shipping its
 * own character over the top of somebody's.
 */
public record Brows(float raiseRight, float raiseLeft, float slant) {

	/** Brows exactly where the skin drew them. */
	public static final Brows NONE = new Brows(0f, 0f, 0f);

	/**
	 * How far a brow may travel, in skin pixels.
	 *
	 * One, and one is a lot. A brow is one or two pixels tall, so a whole pixel is
	 * half the brow or all of it — past that it separates from the face and reads
	 * as a mark on the forehead rather than as a brow that has moved.
	 */
	public static final float TRAVEL = 1f;

	/**
	 * How far each end of a brow moves when it is fully slanted.
	 *
	 * Less than a full raise on purpose. A slant is a shape rather than a
	 * displacement — what is read is the <em>tilt</em>, and a tilt is legible long
	 * before either end has gone anywhere much.
	 */
	public static final float SLANT = 0.6f;

	/** Below this, a brow has not moved enough to be worth two more quads. */
	public static final float NOTHING = 0.02f;

	/** How far one of the two is raised, before the slant is added to it. */
	public float raise(boolean rightSide) {
		return rightSide ? raiseRight : raiseLeft;
	}

	/**
	 * How far the inner end of one brow is above where the skin drew it, in pixels.
	 *
	 * Above rather than below, so that the number reads the way a person would say
	 * it. The drawing turns it round: on the face, up is a smaller y.
	 */
	public float inner(boolean rightSide) {
		return within(raise(rightSide) * TRAVEL + slant * SLANT);
	}

	/** And its outer end, which the slant carries the other way. */
	public float outer(boolean rightSide) {
		return within(raise(rightSide) * TRAVEL - slant * SLANT);
	}

	/** Whether this brow has moved far enough to be drawn at all. */
	public boolean moves(boolean rightSide) {
		return Math.abs(inner(rightSide)) > NOTHING || Math.abs(outer(rightSide)) > NOTHING;
	}

	/** Whether either has. */
	public boolean any() {
		return moves(true) || moves(false);
	}

	/**
	 * Held inside a pixel, however the raise and the slant add up.
	 *
	 * They are separate numbers and both can be at full, which would put an inner
	 * end more than a pixel and a half up. Clamping here rather than refusing the
	 * combination keeps an author from having to know that the two share a budget.
	 */
	private static float within(float lift) {
		return Math.clamp(lift, -TRAVEL, TRAVEL);
	}
}
