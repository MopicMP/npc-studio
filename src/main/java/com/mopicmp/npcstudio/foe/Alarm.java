package com.mopicmp.npcstudio.foe;

/**
 * Noticing, as something that takes time.
 *
 * <h2>Why this is not a boolean</h2>
 *
 * Because "can I see him" and "have I noticed him" are different questions and
 * only the second one is interesting. A character who becomes hostile on the
 * frame a player enters his cone is a tripwire: there is no creeping past, no
 * ducking back, no moment where you know you have been spotted and might still
 * get away. Every game that feels good about this fills a meter, and the meter
 * is the feature.
 *
 * It also solves a problem that has nothing to do with feel. Vision flickers —
 * a target steps behind a lamp post, the pathing jitters, the head turns a few
 * degrees while walking — and a boolean flickers with it. A meter that takes
 * three quarters of a second to fill does not care about a frame of occlusion,
 * so nothing downstream has to smooth anything.
 *
 * <h2>Filling faster than it drains</h2>
 *
 * Deliberately, and by about three to one. Being spotted should be quick and
 * being forgotten should be slow, because the alternative — walk out of sight,
 * count to one, walk back in unnoticed — is the oldest silly behaviour there is.
 * The numbers here are a starting point to be argued with once somebody has
 * actually tried sneaking past one.
 */
public final class Alarm {

	/** Full alarm from nothing takes about this long in clear sight, in ticks. */
	private static final float FILLS_IN = 15;

	/** And empties from full in about this long with nothing seen. */
	private static final float EMPTIES_IN = 60;

	/**
	 * Where suspicion becomes certainty, and where it stops again.
	 *
	 * Two numbers rather than one, and the gap between them is the point. With a
	 * single threshold a target sitting exactly at the edge of vision flips between
	 * moods several times a second — the classic twitch, and it looks like a fault
	 * in the character rather than in the arithmetic. Going up needs the full
	 * meter; coming down needs it to fall well under.
	 */
	private static final float SURE = 1f;
	private static final float FORGETS = 0.35f;

	/** Below this, nothing has been noticed at all. */
	private static final float STIRRED = 0.08f;

	private Alarm() { }

	/** What a character makes of the world at a given level of alarm. */
	public enum Mood {
		/** Nothing seen, nothing suspected. */
		CALM,
		/** Something is there. Look towards it; do not act on it yet. */
		SUSPICIOUS,
		/** Seen, known, and acted upon. */
		ALERT
	}

	/**
	 * The alarm a tick later.
	 *
	 * @param now      what it was, nought to one
	 * @param strength how strongly the target is seen this tick, from {@link Sight}
	 * @return what it becomes, clamped to nought and one
	 */
	public static float next(float now, float strength) {
		return next(now, strength, 1f);
	}

	/**
	 * The same, but with a limit on how far this kind of evidence can take it.
	 *
	 * <h2>What the ceiling is for</h2>
	 *
	 * Hearing. A guard who hears footsteps knows something is there and does not
	 * know what or exactly where, and the difference between that and seeing you is
	 * the whole of what makes hiding worth doing. So sound fills the meter to just
	 * short of certain and stops — see {@link Noise#CEILING} — and only eyes finish
	 * the job.
	 *
	 * The ceiling caps rising and never forces a fall. A character who has already
	 * seen you and then loses sight of you while still hearing you must not be
	 * dragged back down to merely suspicious by the sound of your feet; she forgets
	 * at the ordinary rate, from wherever she was.
	 */
	public static float next(float now, float strength, float ceiling) {
		float moved = strength > 0
			? now + strength / FILLS_IN
			: now - 1 / EMPTIES_IN;
		moved = Math.clamp(moved, 0f, 1f);
		return moved > now ? Math.min(moved, Math.max(now, ceiling)) : moved;
	}

	/**
	 * The mood at this level, given the mood before it.
	 *
	 * The previous mood is an argument and not a field because that is what makes
	 * the hysteresis testable: the whole behaviour worth checking is that the same
	 * number means different things depending on which way it was travelling, and a
	 * function that hides where it came from cannot be asked about that.
	 */
	public static Mood moodOf(float alarm, Mood before) {
		if (before == Mood.ALERT) {
			// Stays alert until well down. Stepping out of sight for a moment is not
			// the same as being got away from.
			return alarm > FORGETS ? Mood.ALERT : Mood.SUSPICIOUS;
		}
		if (alarm >= SURE) return Mood.ALERT;
		return alarm > STIRRED ? Mood.SUSPICIOUS : Mood.CALM;
	}
}
