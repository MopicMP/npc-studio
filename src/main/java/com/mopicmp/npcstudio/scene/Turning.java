package com.mopicmp.npcstudio.scene;

/**
 * Keeping a turning number continuous, so that a wheel can be turned smoothly.
 *
 * <h2>The problem this exists for</h2>
 *
 * A ship's wheel is the thing the first scene needs and it is the thing every
 * naive animation system gets wrong. Angles in the game are reported wrapped:
 * turn past three hundred and fifty-nine and the next reading is zero. Record
 * that and the stored series reads 350, 355, 0, 5 — and every reasonable way of
 * playing it back sends the wheel spinning three hundred and fifty degrees
 * <em>backwards</em> in a twentieth of a second, once per revolution.
 *
 * <h2>Why the fix belongs here and not in the interpolation</h2>
 *
 * The tempting fix is to interpolate angles the short way round, and it is
 * wrong: it makes the backward flick disappear and it also makes more than one
 * revolution impossible to express, because "go the short way" can never mean
 * "go round twice". A wheel being spun hard is exactly the case that needs two
 * revolutions.
 *
 * So the numbers are made continuous when they are written down instead. Three
 * turns is one thousand and eighty degrees, the curve through it is an ordinary
 * straight climb, and every part of the system downstream — interpolation,
 * thinning, the number shown in a box — is plain arithmetic on a plain number
 * with no special case in it anywhere.
 */
public final class Turning {

	public static final float FULL = 360f;

	private Turning() { }

	/**
	 * The same angle as the reading, said as a continuation of what came before.
	 *
	 * Picks the whole number of turns that lands nearest to where the value
	 * already was, which is the right answer whenever the thing moved less than
	 * half a turn between two readings. Beyond that there is no right answer from
	 * two numbers alone — a wheel that spun 190 degrees in one tick and one that
	 * spun 170 the other way produce the same reading — so the assumption is that
	 * nothing turns more than half a revolution in a twentieth of a second, which
	 * is thirty-six revolutions a second.
	 *
	 * @param running what the value was a moment ago, already continuous
	 * @param reading the angle now, as the game reports it, wrapped
	 */
	public static float continued(float running, float reading) {
		float turns = Math.round((running - reading) / FULL);
		return reading + turns * FULL;
	}

	/**
	 * A whole series of readings made continuous, in order.
	 *
	 * The first is taken as it stands: there is nothing before it to continue
	 * from, and picking any other representative of the same angle would only move
	 * where the numbers start without changing what they do.
	 */
	public static float[] continued(float[] readings) {
		float[] running = new float[readings.length];
		for (int i = 0; i < readings.length; i++) {
			running[i] = i == 0 ? readings[i] : continued(running[i - 1], readings[i]);
		}
		return running;
	}

	/** The same angle brought back into 0..360, for showing rather than for storing. */
	public static float wrapped(float angle) {
		float within = angle % FULL;
		return within < 0 ? within + FULL : within;
	}
}
