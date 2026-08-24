package com.mopicmp.npcstudio.foe;

/**
 * Hearing: how much racket somebody is making, and how far it carries.
 *
 * <h2>Why hearing is not just sight without a cone</h2>
 *
 * Because it answers a different question. Sight tells you <em>who and where</em>;
 * hearing tells you <em>something, over there</em>. A guard who hears footsteps
 * behind a wall does not know what made them and cannot fight it — he turns
 * towards it, and waits, and looks.
 *
 * So sound alone never reaches certainty. It fills the alarm to just short of it
 * and stops, which is what {@link #CEILING} is for. Being sure needs eyes on
 * you, and that single rule is what makes hiding worth doing: you can be heard
 * and still not be found.
 *
 * <h2>Not the game's sound system</h2>
 *
 * Deliberately. Minecraft's sounds are a client-side affair mostly, they fire
 * for footsteps on some surfaces and not others, and hooking them would tie how
 * alert a guard is to which block somebody is standing on. What matters for
 * being caught is what you are <em>doing</em> — creeping, walking, running — and
 * that is known on the server, exactly, every tick, for nothing.
 */
public final class Noise {

	/**
	 * The most alarm that sound alone can raise.
	 *
	 * Just under certain. See {@link Alarm} — becoming ALERT needs a full meter, so
	 * a character who can only hear you stays suspicious for as long as you keep
	 * making noise, which is the state where you are being hunted but not caught.
	 */
	public static final float CEILING = 0.9f;

	/**
	 * How loud each way of moving is.
	 *
	 * Running is nearly as good as being seen; walking is a third of that; creeping
	 * is almost nothing but not quite nothing, because a stealth that is perfect is
	 * a stealth with no tension in it. Standing still is silent, and that is
	 * checked against actual movement rather than against the sprint flag — a
	 * player holding the sprint key against a wall is not sprinting anywhere.
	 */
	private static final float RUNNING = 0.9f;
	private static final float WALKING = 0.3f;
	private static final float CREEPING = 0.04f;

	/** Water is loud, and it is the one surface worth a special case. */
	private static final float SPLASHING = 0.25f;

	/** Below this much movement in a tick, somebody is standing still. */
	private static final double STIRRING = 0.005;

	private Noise() { }

	/**
	 * How much noise somebody is making, nought to one.
	 *
	 * @param speed     how far they moved this tick, horizontally, in blocks
	 * @param sneaking  whether they are crouched
	 * @param sprinting whether they are running
	 * @param inWater   whether they are in water
	 */
	public static float loudness(double speed, boolean sneaking, boolean sprinting,
			boolean inWater) {
		if (speed < STIRRING) return 0;
		float made = sneaking ? CREEPING : sprinting ? RUNNING : WALKING;
		if (inWater) made = Math.max(made, SPLASHING) + SPLASHING;
		return Math.clamp(made, 0f, 1f);
	}

	/**
	 * How far sound is worth listening to, in blocks.
	 *
	 * Further than sight, and that is not a mistake: you hear the room next door
	 * and you cannot see it. It is the ceiling that keeps this from making guards
	 * omniscient, not a short range.
	 */
	public static final double EARSHOT = 32;

	/**
	 * How strongly a noise of this loudness carries this far.
	 *
	 * Falls off with the square root of distance rather than with distance, which
	 * is deliberately generous in the middle: sound does not politely halve at
	 * double the range, and a linear fall made everything beyond ten blocks
	 * inaudible in practice.
	 */
	public static float heard(double distance, float loudness) {
		return heard(distance, loudness, EARSHOT);
	}

	/**
	 * The same, for a noise that carries its own distance.
	 *
	 * Footsteps all carry the same way, so the constant serves them. Everything
	 * else in the world does not: the game itself says an ordinary sound reaches
	 * sixteen blocks and a loud one reaches proportionally further, which is how
	 * dynamite crosses a valley and a pressure plate does not cross a room. That
	 * distance belongs to the noise, so it is carried with it.
	 */
	public static float heard(double distance, float loudness, double earshot) {
		if (loudness <= 0 || earshot <= 0 || distance >= earshot) return 0;
		double carried = distance <= 1 ? 1 : 1 - Math.sqrt(distance / earshot);
		float reaching = (float) (loudness * carried);
		return reaching < FLOOR ? 0 : Math.clamp(reaching, 0f, 1f);
	}

	/**
	 * Below this, a sound is not heard at all rather than heard a little.
	 *
	 * <h2>The fault this closes</h2>
	 *
	 * The alarm has no leak in it: anything above nothing accumulates. So a sound
	 * too faint to mean anything still added up, and crouch-walking four blocks
	 * behind somebody made her suspicious after two and a half seconds — and, given
	 * long enough, would have taken her to the ceiling. A stealth where standing
	 * still is the only quiet thing is not a stealth.
	 *
	 * Real hearing has a threshold and this is it. Set so that creeping is audible
	 * only at about arm's length: sneaking past somebody at a distance is silent,
	 * sneaking past them close is a gamble, and neither is decided by how long you
	 * are prepared to wait.
	 */
	private static final float FLOOR = 0.03f;
}
