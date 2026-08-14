package com.mopicmp.npcstudio.entity;

/**
 * What a character's face is doing, as an author asked for it.
 *
 * <h2>Why this is numbers about eyes rather than a picture</h2>
 *
 * Because there is nothing to draw with. A face is eight pixels square and its
 * eyes are two; a mod that shipped drawings of expressions would be shipping
 * somebody else's character over the top of the one that was painted. Every
 * expression here is therefore made from the same four things the eyes already
 * do — how far the lids rest, how wide the pupils are, where the gaze sits, how
 * often it blinks — applied to whatever the artist drew.
 *
 * That constraint is not a compromise. It is why a green-eyed character stays
 * green-eyed when it is angry, and why this works on a skin nobody here has
 * seen.
 *
 * <h2>Why the brows are the numbers that matter</h2>
 *
 * Everything the eyes do is a fraction of a pixel. The brows move a whole one,
 * against a flat forehead, and they are what a person reads an expression from
 * in the first place — surprise, anger, doubt and worry are all brows, with much
 * the same eyes underneath. So the brow numbers here are bold where the rest are
 * cautious, and they are the reason these faces are legible at all rather than
 * merely different.
 *
 * <h2>Why the rest of the numbers are small</h2>
 *
 * Everything else on this page is between a fifth and a third of the available
 * travel. On a face this size the distance between "sad" and "unwell" is about
 * one pixel, and the distance between "surprised" and "possessed" is about the
 * same. An expression that is legible in a screenshot is usually too much in
 * motion; these are meant to be felt rather than spotted.
 *
 * <h2>The one that is not like the others</h2>
 *
 * {@link #WINK} is a movement rather than a state: one eye, once, and then it is
 * over. It lives here because an author asks for it the same way they ask for
 * the rest, but everything else on this page is a face held until something
 * changes it.
 */
public enum Expression {

	/** Whatever the character was doing anyway. Nothing is overridden. */
	NEUTRAL(0f, 0f, 0f, 1f, false, 0f, 0f, false),

	/**
	 * Happy: eyes a little narrowed, brows soft and lifted, a touch faster blinking.
	 *
	 * A smile is a mouth, and we have no mouth to work with. What a smile does to
	 * the <em>eyes</em> is narrow them from below — the thing that separates a
	 * real smile from a polite one — so that is what this is. The brows come up a
	 * little and their inner ends with them, which is what stops narrowed eyes from
	 * reading as a squint.
	 */
	HAPPY(0.22f, 0.05f, 0f, 1.15f, false, 0.15f, 0.15f, false),

	/**
	 * Sad: lids heavy, gaze down, blinking slower — and the inner ends of the brows
	 * lifted, which is the whole expression.
	 *
	 * Nothing else here is doing the work. Heavy lids on their own are tiredness
	 * and a lowered gaze on its own is deference; the brow that slopes up towards
	 * the middle of the forehead is what a person calls sad, and it is the one
	 * shape on a face that means nothing else.
	 */
	SAD(0.3f, -0.1f, -0.7f, 0.75f, false, 0.1f, 0.9f, false),

	/**
	 * Angry: brows down and knotted, lids down hard, pupils small, gaze level.
	 *
	 * The narrowed pupil is what keeps it from being fear — wide eyes are fear, and
	 * getting those two the wrong way round makes a villain look frightened of the
	 * player. But the brows are what make it anger rather than concentration: down,
	 * and with the inner ends lower still, which is the knot of a frown.
	 */
	ANGRY(0.35f, -0.45f, 0f, 0.6f, false, -0.7f, -0.9f, false),

	/** Surprised: brows straight up, eyes wide, pupils wide, blinking held off. */
	SURPRISED(-0.15f, 0.8f, 0.25f, 0.35f, false, 1f, 0f, false),

	/**
	 * Scared: wide like surprise, but the gaze will not settle and the brows are
	 * both up and slanted.
	 *
	 * The slant is the difference. Surprise is brows up and level — something
	 * happened. Fear is brows up and pulled together at the middle, which is the
	 * same lift with worry in it.
	 */
	SCARED(-0.1f, 1f, 0f, 1.8f, false, 0.85f, 0.45f, false),

	/**
	 * Thinking: one brow up, gaze up and away, the other lid lowered a little.
	 *
	 * The raised eyebrow, which is the most recognisable thing a face can do with
	 * eight pixels and the only expression here that is deliberately lopsided. A
	 * symmetric version of this is not doubt — it is mild surprise.
	 */
	THINKING(0.2f, -0.1f, 0.6f, 0.8f, false, 0.6f, 0f, true),

	/** Sleepy: lids most of the way down, brows slack and low, slow heavy blinks. */
	SLEEPY(0.5f, 0.15f, -0.35f, 0.5f, false, -0.35f, 0.2f, false),

	/** A wink: one eye, once, with the brow above it going up to say it was meant. */
	WINK(0f, 0f, 0f, 1f, true, 0.4f, 0f, true);

	/** How far the lids rest below open, on top of anything the world is doing. */
	private final float lids;

	/** How much wider than drawn the pupils are, −1 to 1. */
	private final float pupils;

	/** Where the gaze sits upright: −1 down, 1 up. */
	private final float look;

	/** How much more or less often the eyes blink. */
	private final float blinks;

	/** Whether this is a wink rather than a face held. */
	private final boolean winks;

	/** How far the brows are lifted from where the skin drew them, −1 to 1. */
	private final float browRaise;

	/**
	 * How far the inner ends of the brows are lifted against the outer, −1 to 1.
	 *
	 * Positive is inner ends up, which is worry and sadness; negative is the knot
	 * of a frown. It pivots rather than lifting, so a slant on its own leaves the
	 * brow at the height it was drawn.
	 */
	private final float browSlant;

	/**
	 * Whether only one brow does it.
	 *
	 * Which one is not decided here. It is the character's own side — the same one
	 * it winks with — so that a village of sceptics is not a village raising the
	 * identical eyebrow.
	 */
	private final boolean browOdd;

	Expression(float lids, float pupils, float look, float blinks, boolean winks,
			float browRaise, float browSlant, boolean browOdd) {
		this.lids = lids;
		this.pupils = pupils;
		this.look = look;
		this.blinks = blinks;
		this.winks = winks;
		this.browRaise = browRaise;
		this.browSlant = browSlant;
		this.browOdd = browOdd;
	}

	public float lids() {
		return lids;
	}

	public float pupils() {
		return pupils;
	}

	public float look() {
		return look;
	}

	public float blinks() {
		return blinks;
	}

	public boolean winks() {
		return winks;
	}

	public float browRaise() {
		return browRaise;
	}

	public float browSlant() {
		return browSlant;
	}

	public boolean browOdd() {
		return browOdd;
	}

	/**
	 * The expression of that name, or {@link #NEUTRAL} for anything unrecognised.
	 *
	 * Never throws and never refuses. A dialogue written against a later version of
	 * the mod, or with a typo in it, should carry on with an ordinary face rather
	 * than stop a conversation — the cost of the wrong face is nothing, and the
	 * cost of a broken conversation is the whole scene.
	 */
	public static Expression named(String name) {
		if (name == null || name.isBlank()) return NEUTRAL;
		for (Expression expression : values()) {
			if (expression.name().equalsIgnoreCase(name.trim())) return expression;
		}
		return NEUTRAL;
	}

	/** Whether this expression changes anything at all about a face. */
	public boolean shows() {
		return this != NEUTRAL;
	}
}
