package com.mopicmp.npcstudio.client.entity;

/**
 * How wide a character's pupils are, and what widens them.
 *
 * <h2>What a pupil is, on a face two pixels wide</h2>
 *
 * The white and the iris are neighbouring columns, so the size of the pupil is
 * not a shape to be drawn — it is <b>where the boundary between those two
 * columns sits</b>. Move it towards the white and the pupil swells; move it the
 * other way and the white grows. One number does all of it, and every value of
 * it is something the artist could have drawn.
 *
 * <h2>What moves it</h2>
 *
 * The light, mostly, because that is what moves a real one and because it is
 * true of the world the character is standing in rather than of anything we have
 * to invent. A character in a cave has wide dark eyes; the same character out in
 * the snow has pinpricks. Nobody has to author it and it is right on every skin.
 *
 * Then attention: eyes widen a little at somebody who has come close. That is a
 * real reflex and it is also the one that makes a character seem to have noticed
 * you, which is most of what an NPC's face is for.
 *
 * And under both, a slow wander of a few hundredths — the flutter a real pupil
 * has even in steady light. Too small to watch and enough that the eye is never
 * a still object.
 *
 * <h2>Why it never shuts entirely</h2>
 *
 * A pupil that closed to nothing would leave a blank white eye, which does not
 * read as bright light — it reads as a character whose eyes have been taken out.
 * So the closing is capped well short and the widening is not: at its widest the
 * iris fills its own eye, which is exactly how a wide-eyed character is drawn.
 */
public final class Pupils {

	/** Widest and narrowest, as a share of the eye either side of where it was drawn. */
	private static final float WIDEST = 1f;
	private static final float NARROWEST = -0.5f;

	/**
	 * How wide the pupil goes in the pitch dark, and how narrow in full sun.
	 *
	 * <h2>Why these came down, twice</h2>
	 *
	 * The dark was three quarters, on a scale where one means the iris has
	 * swallowed the whole eye. Add the third that somebody standing near is worth
	 * and the flutter on top and every character in every unlit room sat at the
	 * ceiling — not wide-eyed, which happens, but permanently all pupil, which
	 * reads as a doll rather than a person.
	 *
	 * Lowering it was not enough, and it is worth writing down why, because the
	 * number was never the whole fault. The drawing took this as a distance in
	 * model pixels rather than as the share of the eye it claims to be, and on an
	 * eye drawn with any care a third of a pixel already reaches the far side of
	 * the socket. So the ceiling was much lower than the scale said, and every
	 * value above roughly a tenth looked identical. That is fixed where it lived,
	 * in the drawing; these are set for the scale as it now actually behaves.
	 *
	 * Which leaves them, checked by drawing the face and looking at it: a fifth of
	 * the white given up in the pitch dark, a third with somebody at arm's length,
	 * and none of it at noon — where instead the iris gives up a share of itself.
	 *
	 * A fifth sounds timid and is not, because what the marking calls the iris is
	 * the whole coloured part of the eye and on the reported face that is already
	 * half of it. Half the white on top of that is three quarters of the eye gone
	 * dark, which is the picture that was being complained about.
	 */
	private static final float IN_DARK = 0.22f;
	private static final float IN_SUN = -0.4f;

	/**
	 * How much somebody standing close to the character is worth.
	 *
	 * A third of what it was. It is a second-order thing — a flicker of noticing
	 * on top of however dark-adapted the eye already is — and at three tenths it
	 * was as loud as the light itself, which put the two together at the ceiling in
	 * any room without a torch in it.
	 */
	private static final float NOTICED = 0.08f;

	/** How near counts as close, in blocks, and how far still counts at all. */
	private static final float CLOSE = 3f;
	private static final float AWARE = 10f;

	/** The flutter a pupil has even in steady light: how far, and how slowly. */
	private static final float FLUTTER = 0.04f;
	private static final float FLUTTER_TIME = 2.7f;

	private Pupils() { }

	/**
	 * How much wider than drawn the pupil is, from {@code -0.5} to {@code 1}.
	 *
	 * @param entityId whose eyes, so that two characters never pulse together
	 * @param ticks    the character's own age, in ticks
	 * @param light    how lit the character is, 0 to 15, as the world counts it
	 * @param distance how far off the nearest person is, in blocks, or a large
	 *                 number when there is nobody
	 */
	public static float wide(int entityId, float ticks, int light, float distance) {
		float lit = Math.clamp(light / 15f, 0f, 1f);
		float fromLight = IN_DARK + (IN_SUN - IN_DARK) * lit;

		return Math.clamp(fromLight + attention(distance) + flutter(entityId, ticks),
			NARROWEST, WIDEST);
	}

	/**
	 * How much a person being near is worth.
	 *
	 * Full at arm's length and nothing at all across a clearing, with everything
	 * between on a ramp — so a character's eyes widen as somebody walks up to it
	 * rather than the moment they cross a line. The same shape the glance uses,
	 * and for the same reason: a threshold is visible as a threshold.
	 */
	static float attention(float distance) {
		if (distance >= AWARE) return 0f;
		if (distance <= CLOSE) return NOTICED;
		return NOTICED * (1f - (distance - CLOSE) / (AWARE - CLOSE));
	}

	/**
	 * The small unsteadiness of a real pupil.
	 *
	 * Two waves of different lengths rather than one, so it never quite repeats
	 * within the time anybody watches a face, and scattered by the character's own
	 * id so that a crowd does not breathe together.
	 */
	static float flutter(int entityId, float ticks) {
		float scatter = scatter(entityId);
		float seconds = ticks / 20f + scatter * 60f;
		return FLUTTER * 0.5f * ((float) Math.sin(seconds / FLUTTER_TIME * Math.PI * 2)
			+ (float) Math.sin(seconds / (FLUTTER_TIME * 1.7f) * Math.PI * 2));
	}

	/** The same stable scatter the blink and the glance use. */
	static float scatter(int entityId) {
		float value = (entityId * 0.6180339887f) % 1f;
		return value < 0 ? value + 1f : value;
	}
}
