package com.mopicmp.npcstudio.dialogue;

/**
 * How a document's lines are put in front of a player.
 *
 * <h2>Why these were constants, and why they cannot stay constants</h2>
 *
 * Every number here already existed. The bar was thirty-four pixels tall and one
 * row of text by construction; a line came down after ten seconds; it came down
 * again if the player wandered twelve blocks; the typing ran at whatever the
 * player's own settings said. All of them were chosen once, by one person, for one
 * scene, and written into the code as though they were facts about dialogue rather
 * than opinions about a scene.
 *
 * They are opinions, and different conversations hold different ones. A shopkeeper
 * wants a line that goes away by itself; a confession at the end of a quest wants
 * one that does not. So they belong to the document, which is the thing that knows
 * which kind of conversation this is.
 *
 * <h2>Why to the document and not to the line</h2>
 *
 * Because a set of these on every line is the same set typed forty times, and the
 * forty-first is the one that disagrees. What varies line by line is already
 * covered by {@link Presentation} — subtitle, cutscene, full screen — which is one
 * decision rather than seven.
 *
 * It is written so that a line may overrule it later without the file changing
 * shape: every field is a plain value with a stated default, and a line-level
 * version would be the same record with the fields optional. Nothing here assumes
 * there is only one of these in play.
 *
 * <h2>Why the sizes are shares and lines rather than pixels</h2>
 *
 * Because pixels are not a size anybody sees. A player at a different interface
 * scale, or on a different monitor, is looking at a different number of them — so a
 * bar written as three hundred pixels is a bar that is two-thirds of one screen and
 * the whole of another, and the author who set it saw neither. A share of the width
 * is the same picture everywhere, and a height in rows is what the author actually
 * means, which is how much they may write before it stops fitting.
 */
public record Manner(
		float width,
		int lines,
		boolean holdsPlayer,
		double range,
		int idleTicks,
		float pace,
		boolean breathes) {

	/**
	 * How things were before any of this could be said, to the number.
	 *
	 * This is not a set of tasteful defaults; it is the old constants, gathered. That
	 * matters more than taste: every dialogue already written is read back with this,
	 * so a map that worked yesterday looks today exactly as it looked yesterday, and
	 * nobody has to go and find out what changed.
	 */
	public static final Manner ORDINARY =
		new Manner(1f, 1, false, 12.0, 200, 0f, true);

	/** Narrower than this and a line is a column of single words. */
	public static final float NARROWEST = 0.2f;

	/** As many rows as anybody can read off a bar before it stops being a bar. */
	public static final int MOST_LINES = 8;

	/**
	 * The pace that means "whatever the player set".
	 *
	 * Nought rather than a number, because the player's own setting is a preference
	 * about reading and the document's is a decision about drama, and the two are not
	 * the same kind of thing. A document that says nothing must not quietly overrule
	 * somebody who has already told the game they read slowly.
	 */
	public static final float PLAYER_PACE = 0f;

	/** Fast enough to be worth a number at all, slow enough to be readable. */
	public static final float SLOWEST = 4f;
	public static final float FASTEST = 400f;

	/**
	 * Nought in either of the two "when does it go away" fields means never.
	 *
	 * A distinct value rather than a very large one. A line that stays until it is
	 * answered is a thing scenes want, and expressing it as "a hundred thousand
	 * ticks" would be a lie that eventually comes true in front of somebody.
	 */
	public static final int STAYS = 0;

	public Manner {
		// Everything is clamped rather than refused, because these arrive from a file
		// as well as from the editor, and a conversation must not fail to open because
		// a number in it is silly. Silly is corrected; the scene still plays.
		width = Float.isFinite(width) ? Math.clamp(width, NARROWEST, 1f) : 1f;
		lines = Math.clamp(lines, 1, MOST_LINES);
		range = Double.isFinite(range) ? Math.max(0, range) : ORDINARY.range();
		idleTicks = Math.max(STAYS, idleTicks);
		if (!Float.isFinite(pace) || pace < 0) pace = PLAYER_PACE;
		else if (pace != PLAYER_PACE) pace = Math.clamp(pace, SLOWEST, FASTEST);
	}

	/** Whether the line stays up until something takes it away. */
	public boolean staysUp() {
		return idleTicks == STAYS;
	}

	/** Whether walking away leaves the line where it is. */
	public boolean survivesDistance() {
		return range <= 0;
	}

	/**
	 * How fast to type, given what the player asked for.
	 *
	 * The one place the two settings meet, so that "the document said nothing" is
	 * decided once rather than wherever somebody happens to need the number.
	 */
	public float paceOr(float playerPace) {
		return pace == PLAYER_PACE ? playerPace : pace;
	}

	// The withers exist for the editor, which changes one thing at a time and must
	// leave the rest alone.

	public Manner withWidth(float value) {
		return new Manner(value, lines, holdsPlayer, range, idleTicks, pace, breathes);
	}

	public Manner withLines(int value) {
		return new Manner(width, value, holdsPlayer, range, idleTicks, pace, breathes);
	}

	public Manner withHoldsPlayer(boolean value) {
		return new Manner(width, lines, value, range, idleTicks, pace, breathes);
	}

	public Manner withRange(double value) {
		return new Manner(width, lines, holdsPlayer, value, idleTicks, pace, breathes);
	}

	public Manner withIdleTicks(int value) {
		return new Manner(width, lines, holdsPlayer, range, value, pace, breathes);
	}

	public Manner withPace(float value) {
		return new Manner(width, lines, holdsPlayer, range, idleTicks, value, breathes);
	}

	public Manner withBreathes(boolean value) {
		return new Manner(width, lines, holdsPlayer, range, idleTicks, pace, value);
	}
}
