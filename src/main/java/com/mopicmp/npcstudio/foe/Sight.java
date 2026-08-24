package com.mopicmp.npcstudio.foe;

/**
 * How well something is seen: the geometry alone, with no world in it.
 *
 * <h2>A number rather than a yes</h2>
 *
 * The obvious shape for this is {@code boolean canSee(target)}, and it is the
 * wrong one. A boolean cannot say that somebody at the very edge of vision,
 * forty blocks off and nearly behind, is <em>barely</em> seen — and that is the
 * whole of what makes noticing feel like noticing rather than like a switch
 * being thrown. Epic Fight arrives at the same conclusion from the other end:
 * its own conditions test the angle to a target against a band of degrees
 * chosen per behaviour, which only works because the angle is kept as a number
 * for somebody else to judge. So this returns a strength, and what counts as
 * enough is decided by {@link Alarm}, later and elsewhere.
 *
 * <h2>Nothing here touches the world</h2>
 *
 * No blocks, no entities, no level. Walls are somebody else's question and they
 * are asked once, in {@link Watch}, where there is a world to ask. Keeping this
 * to arithmetic is what lets the part that is easy to get subtly wrong be tested
 * without starting a game — and getting it subtly wrong is the failure mode
 * here, because a guard who notices a fraction too eagerly does not throw, he is
 * just tiring to sneak past and nobody can say why.
 *
 * @param range how far this can see at all, in blocks
 * @param cone  the whole width of vision in degrees, split either side of the
 *              nose: 120 means sixty degrees each way
 * @param near  how close somebody has to be to be <em>sensed</em> whatever the
 *              angle. Sensed, not seen — see {@link #SENSED}
 */
public record Sight(double range, float cone, double near) {

	/** What an ordinary watchful character can see: a wide human cone. */
	public static final Sight ORDINARY = new Sight(24, 120, 2.0);

	/**
	 * How strongly somebody standing right behind you registers.
	 *
	 * <h2>The bug this number is the fix for</h2>
	 *
	 * This used to be full strength over three and a half blocks, and it made
	 * vision appear to work through the whole circle: walk up behind a character
	 * and she knew exactly where you were, at once, as surely as if she were
	 * staring at you. That was reported, and it was fair.
	 *
	 * The opposite is worse, though, and is why the radius exists at all. A
	 * character with somebody breathing on the back of her neck who notices
	 * nothing whatever is not stealthy — she is broken.
	 *
	 * What a person has there is not sight, it is the feeling that somebody is
	 * there. So it registers weakly: enough to stir, not enough to be sure. She
	 * turns her head, and <em>then</em> she sees you, and then she is sure. That
	 * chain — sensed, turned, seen — is worth more than either extreme and costs
	 * one constant.
	 */
	public static final float SENSED = 0.35f;

	public Sight {
		range = Math.max(0, range);
		cone = Math.clamp(cone, 0, 360);
		near = Math.max(0, near);
	}

	/**
	 * How strongly a target at this distance and this angle is seen, nought to one.
	 *
	 * <h2>The two falls, and why neither is linear</h2>
	 *
	 * Distance falls off from full to nothing, but with the near half of the range
	 * counted as full: somebody at ten blocks in clear sight is not half-seen, they
	 * are seen. What the fall is for is the far edge, where a figure is a shape
	 * that might be a figure. So the taper starts at half the range and runs to the
	 * end of it.
	 *
	 * Angle falls off from the nose to the edge of the cone and is squared on the
	 * way, which puts most of the sharpness in the middle where a person is
	 * actually looking. Straight-line falloff made the edge of vision far too good:
	 * a figure at fifty-five degrees off, all but out of the corner of the eye, was
	 * still coming in at nearly a tenth.
	 *
	 * @param distance how far away, in blocks
	 * @param angleOff how far from straight ahead, in degrees, never negative
	 */
	public float strength(double distance, double angleOff) {
		if (distance > range || range <= 0) {
			// Still sensed if they are practically touching you, even past the range —
			// which can only happen with a range set absurdly short, and answering
			// "nothing" there would be a stranger lie than answering "something".
			return distance <= near ? SENSED : 0;
		}

		float half = cone / 2f;
		if (angleOff > half) return distance <= near ? SENSED : 0;

		double far = range / 2;
		float byDistance = distance <= far ? 1
			: (float) (1 - (distance - far) / (range - far));

		// Squared, so the corner of the eye is genuinely poor rather than nominally
		// worse. A cone with no falloff at all is a torch beam and reads as one.
		float byAngle = half <= 0 ? 1 : 1 - (float) (angleOff / half);
		byAngle *= byAngle;

		float seen = byDistance * byAngle;
		// Never less than sensed when they are close enough to touch. Inside the cone
		// but right at its edge, the squared falloff drops to nearly nothing, and a
		// character noticing somebody less at arm's length than at nine paces is the
		// sort of thing nobody would ever guess was arithmetic.
		if (distance <= near) seen = Math.max(seen, SENSED);
		return Math.clamp(seen, 0f, 1f);
	}

	/**
	 * How much of somebody is visible at this much light, nought to one.
	 *
	 * <h2>Why not simply the light level over fifteen</h2>
	 *
	 * Because eyes adapt and that number does not. Going from a torchlit room to a
	 * bright one barely changes what you can make out; going from a dim one to a
	 * pitch-dark one changes everything. So the curve is steep at the bottom and
	 * nearly flat at the top, which is where the difference actually lives.
	 *
	 * <h2>And why it never reaches nothing</h2>
	 *
	 * Because it is not true, and because a floor of zero is a cheat waiting to be
	 * found: dig a one-block hole, stand in it, become literally invisible at any
	 * range. Even in the dark a moving shape at arm's length registers. It has to be
	 * the sort of registering that fades with distance, and it is — this multiplies
	 * a strength that is already falling off, so darkness shortens the range rather
	 * than switching sight off.
	 *
	 * @param light the block's brightness, nought to fifteen, night already counted
	 */
	public static float byLight(int light) {
		float lit = Math.clamp(light / 15f, 0f, 1f);
		return IN_THE_DARK + (1 - IN_THE_DARK) * (float) Math.sqrt(lit);
	}

	/** What is left of sight with no light at all: a shape, close to, and no more. */
	public static final float IN_THE_DARK = 0.12f;

	/**
	 * The angle between where something faces and where something else is, in
	 * degrees, always nought to a hundred and eighty.
	 *
	 * Kept here rather than at the call site because the wrapping is where this
	 * sort of thing goes wrong: a character facing due north at 179 degrees and a
	 * target at -179 are two degrees apart, and subtraction alone says 358.
	 *
	 * @param facing  which way the looker is turned, in Minecraft's yaw
	 * @param towards the yaw from the looker to the target
	 */
	public static double turnBetween(float facing, double towards) {
		double difference = Math.abs(towards - facing) % 360;
		return difference > 180 ? 360 - difference : difference;
	}

	/**
	 * The yaw from one point to another, in the game's own convention.
	 *
	 * Minecraft measures yaw from south, turning towards west, which is why this is
	 * {@code atan2(-dx, dz)} and not any of the five other arrangements of those
	 * three symbols. Written once, here, because every one of the other five looks
	 * equally plausible at the call site and only one of them is right.
	 */
	public static double yawTo(double fromX, double fromZ, double toX, double toZ) {
		return Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
	}
}
