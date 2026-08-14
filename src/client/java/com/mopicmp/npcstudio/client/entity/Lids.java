package com.mopicmp.npcstudio.client.entity;

/**
 * Where a character's lids rest when it is not blinking.
 *
 * <h2>Why a lid has a resting place at all</h2>
 *
 * Every movement the eyes had until now returned to the same open eye: the blink
 * came and went, the glance came and went, and between them the face was always
 * the face the artist drew. That is why a character could look busy and still not
 * look like it was <em>anywhere</em> — nothing about it answered to the place it
 * was standing in.
 *
 * Two things move the lids without being a blink, and both are true of the world
 * rather than invented:
 *
 * <ul>
 * <li><b>Bright light.</b> Standing in noon sun, a person's lids come down. It
 *     pairs with the pupil, which is narrowing at the same moment for the same
 *     reason, and the two together read as glare far better than either alone.
 * <li><b>The small hours.</b> Late at night the lids get heavy. This is the one
 *     that makes a village at midnight feel different from the same village at
 *     noon without a single new animation being drawn.
 * <li><b>Being unwell.</b> Poison, wither, weakness: the lids sit lower, which
 *     is the cheapest way a face has ever had of saying something is wrong.
 * </ul>
 *
 * <h2>And one thing that is not a resting place at all</h2>
 *
 * A <b>wince</b>: the hard, fast squeeze of being hit. It belongs here because it
 * is the lids and nothing else, and because it comes from a number the game
 * already keeps — {@code hurtTime}, which counts down from ten after any damage.
 * Nothing has to be stored, sent or invented for a character to flinch, which
 * makes it the cheapest expression in the whole mod and the first one worth
 * having: it is an <em>answer to something that happened</em>, which is what all
 * the rest of the eyes still cannot do.
 *
 * Both are small on purpose. A lid resting a third of the way down is a
 * character squinting into the sun; a lid resting half way down is a character
 * who looks unwell, and there is not much distance between the two on a face
 * eight pixels tall.
 */
public final class Lids {

	/** How far the lids come down in full sun, and the light at which that starts. */
	private static final float GLARE = 0.22f;
	private static final int BRIGHT = 12;

	/** How far they come down in the small hours. */
	private static final float DROWSY = 0.2f;

	/**
	 * When the night is at its heaviest, in the day's twenty-four thousand ticks.
	 *
	 * Midnight is eighteen thousand. The weight comes on from dusk and is gone by
	 * dawn, so a character is heavy-lidded at the hours somebody walking through a
	 * village at night would expect and not at any other.
	 */
	private static final int DUSK = 13000;
	private static final int DAWN = 23000;

	private Lids() { }

	/** How far down the lids go at the worst of being unwell. */
	private static final float UNWELL = 0.28f;

	/**
	 * How far the lids rest below open, nought to one.
	 *
	 * @param light  how lit the character is, 0 to 15
	 * @param time   the world's own time of day, in ticks
	 * @param unwell how ill the character is, nought to one
	 */
	public static float rest(int light, long time, float unwell) {
		return Math.max(Math.max(glare(light), drowse(time)),
			UNWELL * Math.clamp(unwell, 0f, 1f));
	}

	/**
	 * How hard the eyes are screwed shut by a blow.
	 *
	 * <h2>Why this is the sharpest movement the eyes have</h2>
	 *
	 * Because a flinch is sharp. Every other movement here is eased at both ends so
	 * that nothing snaps; this one snaps on purpose — shut at once and open again
	 * over half a second. A wince that fades in is not a wince, it is a character
	 * getting sleepy at the moment it was hit.
	 *
	 * @param hurtTime the count the game keeps after damage: ten, then down to
	 *                 nought over half a second
	 */
	public static float wince(int hurtTime) {
		if (hurtTime <= 0) return 0f;
		// Shut immediately and let go smoothly, which is the shape of a flinch and
		// the reverse of everything else on this page.
		return smooth(Math.clamp(hurtTime / (float) HURT, 0f, 1f));
	}

	/** How long the game's own hurt count runs for. */
	private static final int HURT = 10;

	/** How much the light alone is worth. */
	static float glare(int light) {
		if (light <= BRIGHT) return 0f;
		return GLARE * (light - BRIGHT) / (15f - BRIGHT);
	}

	/**
	 * How much the hour alone is worth.
	 *
	 * Eased in and out rather than switched at dusk, because a whole village
	 * closing its eyes a third of the way in the same tick is the kind of thing
	 * that reads as a bug even when it is deliberate.
	 */
	static float drowse(long time) {
		long today = Math.floorMod(time, 24000L);
		if (today <= DUSK || today >= DAWN) return 0f;

		float through = (today - DUSK) / (float) (DAWN - DUSK);
		// A hump: nothing at either end, all of it in the middle of the night.
		return DROWSY * smooth(1f - Math.abs(through * 2f - 1f));
	}

	private static float smooth(float t) {
		float clamped = Math.clamp(t, 0f, 1f);
		return clamped * clamped * (3f - 2f * clamped);
	}
}
