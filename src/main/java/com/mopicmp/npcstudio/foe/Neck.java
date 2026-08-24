package com.mopicmp.npcstudio.foe;

/**
 * Turning to look at something, at the rate a person turns.
 *
 * <h2>The bug this exists for</h2>
 *
 * The first version used the game's own {@code Entity.lookAt}, on the strength
 * of a comment claiming it walked the head round at a neck's pace. It does not.
 * Read as bytecode it is three instant assignments:
 *
 * <pre>
 * setXRot(pitch)            // at once
 * setYRot(yaw)              // at once, and this is the body
 * setYHeadRot(getYRot())    // the head snapped to the body
 * </pre>
 *
 * So a character noticing you did not turn to look, it teleported round — the
 * whole body, in one tick, pitch included. That was reported as the head
 * snapping and as vision seeming to work through three hundred and sixty
 * degrees, and the second complaint follows from the first: once the body has
 * spun to face you, of course you are in front of it.
 *
 * <h2>Head first, body after</h2>
 *
 * People turn their heads and leave their bodies alone until they run out of
 * neck. Reproducing that is not a flourish — it is what makes the difference
 * between glancing at a noise and squaring up to somebody, and those two need to
 * look different from across a room, because for the player they mean "I might
 * still get away" and "I will not".
 *
 * So the head moves towards what it is looking at every tick, and the body only
 * moves when the head has reached the limit of how far it can turn relative to
 * it. The game already has an opinion about that limit and it is asked rather
 * than guessed.
 */
public final class Neck {

	private Neck() { }

	/**
	 * One tick of turning from one angle towards another.
	 *
	 * Goes the short way round, always, which is right here and wrong in an
	 * animation — see {@link com.mopicmp.npcstudio.scene.Turning} for the case
	 * where "the short way" would make two revolutions inexpressible. A neck has
	 * no such case: nobody looks over their shoulder by turning the long way.
	 *
	 * @param from       where it points now
	 * @param toward     where it should point
	 * @param mostPerTick how far it may move this tick, in degrees
	 */
	public static float step(float from, float toward, float mostPerTick) {
		float difference = wrap(toward - from);
		float moved = Math.clamp(difference, -mostPerTick, mostPerTick);
		return from + moved;
	}

	/**
	 * How far the body has to turn for the head to be within its limit, signed.
	 *
	 * Nought while the head is comfortable, which is most of the time and is the
	 * whole point: a character watching somebody walk past the end of a corridor
	 * should follow them with its eyes and not shuffle round on the spot.
	 *
	 * @param limit how far the head may be turned relative to the body, in degrees
	 * @return the degrees to add to the body, or nought
	 */
	public static float overTurn(float head, float body, float limit) {
		float difference = wrap(head - body);
		if (difference > limit) return difference - limit;
		if (difference < -limit) return difference + limit;
		return 0;
	}

	/**
	 * The head, brought back within what the neck allows.
	 *
	 * <h2>Why this is needed as well as {@link #overTurn}</h2>
	 *
	 * Because the head turns faster than the body, which is the point of the whole
	 * arrangement — and it means that between the head reaching its limit and the
	 * body catching up, the head goes on outrunning it. A test walking the two
	 * round together caught it at the sixth tick: head at ninety, body at five,
	 * eighty-five degrees of neck where seventy-five is the most there is.
	 *
	 * Nobody would have seen that as a number. They would have seen a character
	 * whose head was screwed round further than a head goes, for a fifth of a
	 * second at a time, every time she turned round.
	 *
	 * So the limit is applied to the head last, after the body has moved. A neck
	 * cannot be over-extended even for one tick; it simply stops, and the body
	 * brings the rest round.
	 */
	public static float held(float head, float body, float limit) {
		return body + Math.clamp(wrap(head - body), -limit, limit);
	}

	/** The pitch from one point to another, in the game's convention: down is positive. */
	public static float pitchTo(double fromX, double fromY, double fromZ,
			double toX, double toY, double toZ) {
		double flat = Math.sqrt((toX - fromX) * (toX - fromX) + (toZ - fromZ) * (toZ - fromZ));
		return (float) -Math.toDegrees(Math.atan2(toY - fromY, flat));
	}

	/** An angle brought into -180..180, which is where a difference belongs. */
	public static float wrap(float angle) {
		float within = angle % 360;
		if (within >= 180) within -= 360;
		if (within < -180) within += 360;
		return within;
	}
}
