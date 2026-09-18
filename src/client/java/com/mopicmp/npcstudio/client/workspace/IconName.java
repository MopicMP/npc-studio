package com.mopicmp.npcstudio.client.workspace;

/**
 * Where the name of a hovered icon goes.
 *
 * <h2>Why this is a class of its own rather than four lines in the drawing</h2>
 *
 * Because it is arithmetic against three edges — the top strip, the two upright
 * strips and the far side of the window — and arithmetic against edges is where
 * a label ends up half off the screen in one language and not in another. Kept
 * apart from the screen, it can be checked without a game running, which is the
 * only way this particular fault gets caught before somebody meets it.
 *
 * <h2>Why the name is drawn at all</h2>
 *
 * Nine icons carry the whole window and not one of them said what it was. Icons
 * were chosen over words on purpose — a row of words is as wide as the language
 * it is written in — but the trade was only half made: every icon inside a panel
 * keeps its word in a tooltip, and the hand-drawn strips got nothing. One label,
 * for whichever the hand is on, which is the rule the ring already follows.
 */
public final class IconName {

	private IconName() { }

	/** How tall the little plate is, and how far the text sits inside it. */
	public static final int TALL = 12;
	public static final int PAD = 4;

	/** How wide the plate is for a name of this width. */
	public static int wide(int textWidth) {
		return textWidth + PAD * 2;
	}

	/**
	 * Under a button of the top strip, pulled back in at the far edge.
	 *
	 * The pulling back is not a nicety: the rightmost button is the receiver, its
	 * name is the longest of the five in both languages, and without this it would
	 * be the one label in the window that cannot be read.
	 */
	public static int leftUnderTool(int window, int iconLeft, int textWidth) {
		int wide = wide(textWidth);
		// Never off the right, and never off the left either — a window narrower than
		// the label itself would otherwise push it to a negative x.
		return Math.max(0, Math.min(iconLeft, window - wide));
	}

	/**
	 * Beside a button of an upright strip, on the side the world is.
	 *
	 * Away from the edge rather than over it. A label drawn over its own strip would
	 * cover the icons above and below the one being named, which is the row somebody
	 * is reading down when they need a label at all.
	 */
	public static int leftBesideRail(int window, boolean right, int textWidth) {
		int wide = wide(textWidth);
		return right ? Math.max(0, window - Rail.WIDTH - wide) : Rail.WIDTH + 1;
	}

	/**
	 * Level with the button it names, which is not level with the cursor.
	 *
	 * The buttons start two pixels below the strip, so a top worked out from the
	 * mouse alone is off by two and drifts by up to a whole button. This takes the
	 * same index the click does.
	 */
	public static int topBesideRail(int toolbar, int which) {
		return toolbar + 2 + which * Rail.WIDTH + (Rail.WIDTH - TALL) / 2;
	}

	/** Which button of a strip a point is on, or -1. The one answer, for both uses. */
	public static int railIndex(int toolbar, double py, int count) {
		if (py < toolbar + 2) return -1;
		int which = (int) ((py - toolbar - 2) / Rail.WIDTH);
		return which >= 0 && which < count ? which : -1;
	}
}
