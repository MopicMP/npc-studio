package com.mopicmp.npcstudio.foe;

/**
 * How alarming a person is, as opposed to how visible.
 *
 * <h2>The distinction this adds</h2>
 *
 * Seeing somebody is one fact. What they are and what they are doing is another,
 * and it is the one worth acting on. A stranger walking past is not a stranger
 * running at you, and a stranger running at you with a knife is not the same as a
 * stranger running <em>away from something</em> who happens to be carrying one.
 *
 * Everything here is about the second question. It answers in the same currency
 * as {@link Din#urgencyFor} — how much this demands to be dealt with — so that a
 * person and a bang can be compared at all.
 *
 * <h2>Two signs, and why these two</h2>
 *
 * What is in their hands, and whether they are closing on you. Those are the two
 * a person actually reads across a courtyard, and they are the two that separate
 * the cases above. Everything else — expression, gait, what they shout — is
 * either not modelled or not visible at the ranges this matters at.
 */
public final class Menace {

	private Menace() { }

	/** What somebody unremarkable, walking about their business, comes to. */
	public static final float PASSER_BY = 0.35f;

	/** And what somebody bearing down on you with a weapon comes to. */
	public static final float BEARING_DOWN = 1f;

	/**
	 * How threatening somebody looks.
	 *
	 * @param armed    whether they are holding something made for hurting people
	 * @param closing  how fast the distance is shrinking, in blocks per tick;
	 *                 negative means they are leaving
	 * @param aimingAt whether they are looking straight at this character
	 */
	public static float of(boolean armed, double closing, boolean aimingAt) {
		float menace = PASSER_BY;

		// A weapon on its own is not much: half the countryside is carrying an axe
		// and using it on trees. It is what turns everything else serious.
		if (armed) menace += 0.15f;

		// Closing is the sign that means the most, because it is the one thing
		// somebody minding their own business does not do. Scaled against a walk, so
		// that a sprint counts for more than a stroll.
		if (closing > 0) {
			float approach = (float) Math.clamp(closing / 0.2, 0, 1);
			menace += approach * (armed ? 0.4f : 0.2f);
		}

		// And being looked at while it happens. Somebody running past you and
		// somebody running at you look identical from behind a number; this is what
		// tells them apart, and it is why the fleeing man with a knife is ignored.
		if (aimingAt && closing > 0) menace += armed ? 0.3f : 0.1f;

		return Math.clamp(menace, 0f, BEARING_DOWN);
	}

	/**
	 * Whether an aim counts as being aimed at you.
	 *
	 * Generous, because a weapon does not have to be pointed exactly to be pointed
	 * at you, and because the whole judgement is being made at a glance from some
	 * distance away. Thirty degrees either side is "looking in my direction and
	 * coming this way", which is quite enough to be getting on with.
	 */
	public static boolean aimedAt(double angleOff) {
		return angleOff <= 30;
	}
}
