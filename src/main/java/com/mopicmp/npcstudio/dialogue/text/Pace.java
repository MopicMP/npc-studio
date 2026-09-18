package com.mopicmp.npcstudio.dialogue.text;

/**
 * How much of a line has been said by now.
 *
 * <h2>What was wrong with counting</h2>
 *
 * The line appeared at a fixed number of letters a second, and every letter cost
 * the same. So a full stop cost what a vowel cost, and the text came out as an
 * even mechanical clatter with no shape in it at all — reported plainly: it
 * ignores full stops, dashes and commas, and always runs at one speed.
 *
 * That is not a small ugliness. Punctuation <em>is</em> the timing of speech; it
 * is what the marks are for. A comma is a breath, a full stop is the end of a
 * thought, a dash is a swerve. Typing them out at the speed of the letters around
 * them throws away the one piece of performance already written into the text.
 *
 * <h2>How this counts instead</h2>
 *
 * Every code point costs a number of beats, where one beat is what an ordinary
 * letter costs. An ordinary letter costs one; the letter <em>after</em> a comma
 * costs more, because the pause belongs between them; the letter after a full stop
 * costs more still. Then "how much is showing at time t" is the longest run of
 * beats that fits in the time.
 *
 * After, not on. A pause drawn on the mark itself would hold the sentence up
 * before the mark had appeared, so the reader waits at a place they cannot see and
 * the comma lands late — which reads as stuttering rather than as breathing.
 *
 * <h2>Why it is here, away from the game</h2>
 *
 * Same fence as the rest of this package: no Minecraft, so the whole of the timing
 * can be tested without a screen. That is worth insisting on for this in
 * particular, because a fault in it looks like "the text feels a bit off" — the
 * one kind of report that can never be chased down by looking.
 */
public final class Pace {

	private Pace() { }

	/**
	 * What a letter after a comma costs, over and above being a letter.
	 *
	 * <h2>Where these numbers come from</h2>
	 *
	 * From speech, not from taste. Read aloud, the silence at a comma is roughly a
	 * fifth of a second and at a full stop roughly half — so against typing at about
	 * forty-five letters a second these come out near two and a half beats and six.
	 * They are held as beats rather than as milliseconds precisely so that they scale
	 * with the pace: a line set to type slowly should breathe slowly too, and one set
	 * fast should still be recognisably the same performance rather than a different
	 * one.
	 */
	public static final float COMMA = 2.5f;

	/** The end of a thought, which is the longest ordinary pause there is. */
	public static final float STOP = 6f;

	/**
	 * A dash, which is a swerve rather than a stop.
	 *
	 * Between the two, and closer to a comma. Both the em dash and the en dash count:
	 * which one a writer reaches for is a fact about their keyboard.
	 */
	public static final float DASH = 4f;

	/**
	 * A line break, which is the longest of the lot.
	 *
	 * Longer than a full stop because somebody who pressed return meant more than
	 * somebody who typed a dot — they meant a new breath, a new thought, or a new
	 * speaker.
	 */
	public static final float BREAK = 8f;

	/**
	 * What the code point at this position costs, in beats.
	 *
	 * Given the one before it, because every pause here is a property of the join
	 * rather than of either letter.
	 */
	public static float beatsFor(int before, boolean breathes) {
		if (!breathes || before < 0) return 1f;
		return 1f + switch (before) {
			case '\n' -> BREAK;
			case '.', '!', '?', '…', ':' -> STOP;
			case ',', ';' -> COMMA;
			case '—', '–' -> DASH;
			default -> 0f;
		};
	}

	/**
	 * How many code points of this line are on screen after this long.
	 *
	 * @param breathes false gives exactly the old behaviour, one beat a letter, which
	 *                 is what a document that has said nothing about this gets — and
	 *                 what makes this safe to switch on for everybody's existing maps
	 * @return a count for {@link Words#first}, never past the end of the line
	 */
	public static int shown(String text, float charsPerSecond, long millis, boolean breathes) {
		if (text == null || text.isEmpty()) return 0;
		if (!(charsPerSecond > 0)) return count(text);
		if (millis <= 0) return 0;

		// Beats rather than time, so the whole loop is integer-ish arithmetic against
		// one budget and the pace appears exactly once.
		float budget = millis * charsPerSecond / 1000f;
		int shown = 0;
		int before = -1;
		float spent = 0;

		int at = 0;
		while (at < text.length()) {
			int point = text.codePointAt(at);
			spent += beatsFor(before, breathes);
			// Strictly greater, so the very first letter appears at once rather than
			// after one beat: a line that shows nothing at all for its first instant
			// reads as a line that failed to arrive.
			if (spent > budget) return shown;
			shown++;
			before = point;
			at += Character.charCount(point);
		}
		return shown;
	}

	/**
	 * How long the whole line takes to say.
	 *
	 * Wanted by anything that has to decide how long to leave a line up: a bar that
	 * disappears on a timer shorter than its own text is a bar that takes the sentence
	 * away in the middle of typing it.
	 */
	public static long millisFor(String text, float charsPerSecond, boolean breathes) {
		if (text == null || text.isEmpty() || !(charsPerSecond > 0)) return 0;
		float beats = 0;
		int before = -1;
		int at = 0;
		while (at < text.length()) {
			int point = text.codePointAt(at);
			beats += beatsFor(before, breathes);
			before = point;
			at += Character.charCount(point);
		}
		// Rounded up, not down. This answers "by when is it finished", and the two
		// halves of that question are asked from opposite ends — this decides how long
		// to leave a line up, {@link #shown} decides what is on it — so an answer half
		// a millisecond early is a bar that takes the sentence away with its last
		// letter still arriving. The test caught exactly that.
		return (long) Math.ceil(beats * 1000.0 / charsPerSecond);
	}

	private static int count(String text) {
		return text.codePointCount(0, text.length());
	}
}
