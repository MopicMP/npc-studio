package com.mopicmp.npcstudio.dialogue.text;

/**
 * How a stretch of a line is drawn.
 *
 * <h2>Why this is ours and not the game's {@code Style}</h2>
 *
 * Because the game's has no size. That was read out of the constructor of
 * {@code Style} in 26.2 rather than assumed: it carries colour, shadow colour,
 * four faces, the scramble, click, hover, insertion and a font — and nothing
 * about how big any of it is. Everything in the game that looks larger is the
 * drawing matrix of whoever drew it.
 *
 * Since size is wanted per stretch of text rather than per line, {@code Style}
 * cannot hold what has to be stored, and a second structure holding the sizes
 * beside it would be two accounts of one thing. Two accounts of one thing
 * disagree; it is only a question of when.
 *
 * <h2>Why it lives here, away from Minecraft</h2>
 *
 * Same fence as the rest of {@code dialogue}: nothing in this package imports
 * the game, which is what lets a whole conversation be tested in milliseconds.
 * So a colour is an integer and a font is a name, not an {@code Identifier} —
 * and turning either into something the screen understands happens on the far
 * side of the fence, where the screen already is.
 */
public record Look(
		Integer colour,
		boolean bold,
		boolean italic,
		boolean underlined,
		boolean struck,
		String font,
		float size) {

	/**
	 * Nothing chosen: whatever the screen would have drawn anyway.
	 *
	 * This is the ordinary state and not a fallback. A line nobody has decorated
	 * is every line in every dialogue written so far, and it has to stay cheap —
	 * cheap to compare, cheap to store, and written to the file as a bare string.
	 */
	public static final Look PLAIN = new Look(null, false, false, false, false, null, 1f);

	/** The size of text nobody has resized: one, meaning as the game draws it. */
	public static final float ORDINARY = 1f;

	/**
	 * Normalised on the way in, so that two looks meaning the same thing are the
	 * same look.
	 *
	 * This matters more than it sounds. Runs that carry equal looks are welded
	 * together ({@link Words}), so a look that is {@code PLAIN} in all but the
	 * spelling of "no font" would split a line into two runs that draw
	 * identically — and the file would grow a seam nobody put there and nobody
	 * can see.
	 */
	public Look {
		if (font != null && font.isBlank()) font = null;
		// A size that is not a size is a drawing that never appears, and it would
		// arrive from a file rather than from the editor — which is to say from
		// somebody typing, where nought and minus one are ordinary typing.
		if (!(size > 0) || Float.isInfinite(size)) size = ORDINARY;
	}

	public boolean isPlain() {
		return equals(PLAIN);
	}

	// The withers exist for the editor, which applies one property at a time to a
	// selection and must leave the rest of the look alone. Written out rather than
	// generated because there are seven and they are read far more often than
	// written.

	public Look withColour(Integer value) {
		return new Look(value, bold, italic, underlined, struck, font, size);
	}

	public Look withBold(boolean value) {
		return new Look(colour, value, italic, underlined, struck, font, size);
	}

	public Look withItalic(boolean value) {
		return new Look(colour, bold, value, underlined, struck, font, size);
	}

	public Look withUnderlined(boolean value) {
		return new Look(colour, bold, italic, value, struck, font, size);
	}

	public Look withStruck(boolean value) {
		return new Look(colour, bold, italic, underlined, value, font, size);
	}

	public Look withFont(String value) {
		return new Look(colour, bold, italic, underlined, struck, value, size);
	}

	public Look withSize(float value) {
		return new Look(colour, bold, italic, underlined, struck, font, value);
	}
}
