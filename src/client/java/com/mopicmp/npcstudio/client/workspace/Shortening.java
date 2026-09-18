package com.mopicmp.npcstudio.client.workspace;

/**
 * Cutting a name to the room there is for it.
 *
 * <h2>Why the arithmetic is here and not next to the drawing</h2>
 *
 * Because it is arithmetic against two edges and it fails silently in both
 * directions. Cut a character too few and the name still runs under the number
 * beside it, which is the fault this exists to stop; cut one too many and a name
 * that would have fitted is shortened for nothing. Neither throws, neither shows
 * up in review, and both are only visible in a row of a list on somebody else's
 * screen with somebody else's names in it.
 *
 * Separated from the font so it can be checked without a game running. The font is
 * a function from a letter to a width, and that is all this needs to know about
 * it — which also means the checking can use widths that make the boundary cases
 * obvious rather than whatever the game's font happens to do.
 */
public final class Shortening {

	private Shortening() { }

	/** How wide one letter is. The whole of what this needs from a font. */
	@FunctionalInterface
	public interface Widths {
		int of(char letter);
	}

	/** The mark left where a name was cut. */
	public static final String MARK = "…";

	/**
	 * The name, or as much of it as fits with the mark on the end.
	 *
	 * <h2>Why letter by letter rather than by proportion</h2>
	 *
	 * The fonts here are not fixed width and in Russian they are not close to it, so
	 * cutting by a count worked out from an average overshoots on one name and
	 * undershoots on the next. Walking the letters is exact and a name is short.
	 *
	 * <h2>Why the mark is paid for first</h2>
	 *
	 * Because it is going to be drawn. Fitting letters into the whole room and then
	 * adding the mark is how a shortened name ends up wider than the name it
	 * replaced, which is the one outcome that makes the whole thing pointless.
	 */
	public static String cut(String name, int room, Widths widths, int markWidth) {
		if (name == null || room <= 0) return "";

		int whole = 0;
		for (int i = 0; i < name.length(); i++) whole += widths.of(name.charAt(i));
		if (whole <= room) return name;

		// Not even the mark fits. Answering with the mark alone would draw past the
		// edge, so the honest answer is nothing at all.
		if (markWidth > room) return "";

		StringBuilder kept = new StringBuilder();
		int used = markWidth;
		for (int i = 0; i < name.length(); i++) {
			int wide = widths.of(name.charAt(i));
			if (used + wide > room) break;
			used += wide;
			kept.append(name.charAt(i));
		}
		return kept + MARK;
	}
}
