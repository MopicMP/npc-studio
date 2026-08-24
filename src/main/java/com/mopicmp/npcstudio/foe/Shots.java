package com.mopicmp.npcstudio.foe;

import net.minecraft.world.phys.Vec3;

/**
 * Working out where a shot came from by watching it go past.
 *
 * <h2>What this is for</h2>
 *
 * An arrow that misses tells you a great deal, and none of it through your ears.
 * You did not hear the bow — it is quiet and it was a long way off — but the
 * thing went past at head height travelling that way, and whoever loosed it is
 * back along that line. Every soldier knows this and no game character does
 * unless somebody writes it down.
 *
 * It works for anything thrown or fired, including from other mods, because it
 * asks the projectile where it is going rather than what it is.
 *
 * <h2>And it takes a moment</h2>
 *
 * Deliberately. A character who snaps to the exact firing position on the frame
 * an arrow passes has not deduced anything, she has read the arrow's owner field
 * — and it feels like it, because nothing that fast is a thought. The delay is
 * short, well under a second, and it is the difference between a character
 * working something out and a character being told.
 */
public final class Shots {

	private Shots() { }

	/**
	 * How long the working-out takes, in ticks.
	 *
	 * Long enough to read as a reaction rather than as omniscience, short enough
	 * that the second arrow has not landed yet.
	 */
	public static final int WORKING_IT_OUT = 9;

	/**
	 * How far back along its flight the shooter is assumed to be, in blocks.
	 *
	 * A guess, and it does not have to be a good one: what matters is the
	 * <em>direction</em>, since that is what the arrow actually carries. Wrong about
	 * the distance means looking at the right bearing and the wrong range, which is
	 * a character searching in the right direction — exactly what somebody in that
	 * position would be doing.
	 */
	public static final double BACK_ALONG = 16;

	/**
	 * Where the shooter probably is, given where the shot is and where it is going.
	 *
	 * @param flight which way it is travelling; its length is ignored, only the
	 *               bearing matters
	 * @param stopAt how far back the line runs before something is in the way, or a
	 *               number at or past {@link #BACK_ALONG} for a clear line
	 */
	public static Vec3 firedFrom(Vec3 at, Vec3 flight, double stopAt) {
		double speed = flight.length();
		if (speed < 1e-6) return at;
		double back = Math.clamp(stopAt, 1, BACK_ALONG);
		return at.subtract(flight.scale(back / speed));
	}

	/**
	 * How urgent a shot going past is.
	 *
	 * Higher than any noise, and it should be: a bang means something happened, an
	 * arrow past your ear means somebody is shooting at you, and there is no third
	 * thing to be doing about that. It is not quite certainty — see {@link
	 * Noise#CEILING} — because knowing you are being shot at is not the same as
	 * knowing who by or from exactly where.
	 */
	public static final float URGENCY = 1f;

	/**
	 * How strongly a shot registers, by how close it went past.
	 *
	 * One that nearly parts your hair is unmissable; one at the far edge of vision
	 * is a thing you half-noticed. Which is also what stops a battle two hundred
	 * blocks away from putting every guard on the map on alert.
	 */
	public static float startle(double howClose) {
		if (howClose >= NOTICED_WITHIN) return 0;
		return (float) Math.clamp(1 - howClose / NOTICED_WITHIN, 0, 1);
	}

	/** Past this far away, a shot going by is somebody else's business. */
	public static final double NOTICED_WITHIN = 20;
}
