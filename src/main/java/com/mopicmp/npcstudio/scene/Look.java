package com.mopicmp.npcstudio.scene;

/**
 * How a line on the screen is drawn: everything about it except the words.
 *
 * <h2>Why this is a record beside the cue and not fields on it</h2>
 *
 * Because two of the three kinds of cue have no appearance at all. A sound and a
 * piece of music are a name and a moment; giving them a colour and a typeface
 * would be nine fields that are meaningless in two thirds of the cases, and
 * meaningless fields are the ones that end up half-written by some code path
 * nobody checked. One thing that is present when the cue is a line and absent
 * otherwise says exactly what is true.
 *
 * <h2>Why the position is a fraction and not a pixel</h2>
 *
 * Because the film is the size of the window, and the window is not the size it
 * was yesterday. A title placed at "x = 340" is centred on one machine and off
 * the edge on another, and — worse — moves when somebody changes the interface
 * scale between setting it up and filming. A half is the middle of the frame on
 * every screen there has ever been.
 *
 * <h2>Why the size is a multiplier</h2>
 *
 * The game's font is eight pixels tall and there is no other size of it. What
 * there is, is a matrix — so a size here is how much the frame is scaled while
 * the line is drawn. Whole numbers stay crisp because the glyphs land on pixels;
 * halves are legible and slightly soft, which is a trade worth being allowed to
 * make rather than one worth forbidding.
 *
 * @param font   which typeface, as the game names one; empty is the ordinary one
 * @param size   how much larger than the game's own eight pixels
 * @param colour packed RGB, no alpha — how solid it is belongs to the fade
 * @param x      across the frame, nought at the left edge and one at the right
 * @param y      down the frame, nought at the top and one at the bottom
 * @param align  which part of the line sits at {@code x}
 */
public record Look(String font, float size, int colour, float x, float y,
		Align align, boolean bold, boolean italic, boolean shadow) {

	/** Which part of the line sits at the point it is placed at. */
	public enum Align { LEFT, CENTRE, RIGHT }

	/**
	 * How small and how large a line may be asked to be.
	 *
	 * Under a half the game's font stops having distinct glyphs — it is eight
	 * pixels tall to begin with, and four is a smear. Over eight a single word
	 * crosses the frame, so anything past it is a number nobody can use rather
	 * than a freedom anybody wants.
	 */
	public static final float SMALLEST = 0.5f;
	public static final float LARGEST = 8f;

	/** An ordinary caption: white, middling, across the middle of the frame. */
	public static final Look PLAIN =
		new Look("", 1f, 0xFFFFFF, 0.5f, 0.42f, Align.CENTRE, false, false, true);

	public Look {
		font = font == null ? "" : font.trim();
		size = Math.clamp(size, SMALLEST, LARGEST);
		colour = colour & 0xFFFFFF;
		// Beyond the edges rather than at them, because a line may legitimately be
		// parked just off frame to slide in — and a value that cannot be reached is
		// worse than one that is simply not useful.
		x = Math.clamp(x, -1f, 2f);
		y = Math.clamp(y, -1f, 2f);
		if (align == null) align = Align.CENTRE;
	}

	public Look withFont(String named) {
		return new Look(named, size, colour, x, y, align, bold, italic, shadow);
	}

	public Look withSize(float times) {
		return new Look(font, times, colour, x, y, align, bold, italic, shadow);
	}

	public Look withColour(int rgb) {
		return new Look(font, size, rgb, x, y, align, bold, italic, shadow);
	}

	public Look at(float across, float down) {
		return new Look(font, size, colour, across, down, align, bold, italic, shadow);
	}

	public Look aligned(Align how) {
		return new Look(font, size, colour, x, y, how, bold, italic, shadow);
	}

	public Look withBold(boolean on) {
		return new Look(font, size, colour, x, y, align, on, italic, shadow);
	}

	public Look withItalic(boolean on) {
		return new Look(font, size, colour, x, y, align, bold, on, shadow);
	}

	public Look withShadow(boolean on) {
		return new Look(font, size, colour, x, y, align, bold, italic, on);
	}

	/**
	 * How far left of the placement point the line starts, given how wide it is.
	 *
	 * Here rather than in the drawing because it is the one part of laying a line
	 * out that has an answer somebody can get wrong, and because getting it wrong
	 * is invisible on a centred line — which is the default, and therefore the one
	 * every test would have used.
	 */
	public float offsetOf(float wide) {
		return switch (align) {
			case LEFT -> 0;
			case CENTRE -> -wide / 2;
			case RIGHT -> -wide;
		};
	}
}
