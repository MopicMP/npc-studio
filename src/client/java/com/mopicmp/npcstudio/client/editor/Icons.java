package com.mopicmp.npcstudio.client.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The little pictures on the editor's buttons.
 *
 * <h2>Why they are written out as pictures</h2>
 *
 * Because the alternative is words, and words do not fit. A toolbar of twenty
 * things with names on them is four hundred pixels of Russian nouns across the
 * top of a drawing, and the drawing is the thing you came for. It was tried and
 * it is what the side panel had to be a side panel for.
 *
 * They are drawn rather than shipped, like everything else in this editor, so
 * there is still nothing in the jar but code. Nine by nine is enough for a
 * pencil, a bucket and an eye to be told apart at a glance and small enough that
 * writing one out by hand is a minute's work — which is why they are written out
 * by hand rather than assembled from rectangles in Java.
 *
 * A {@code #} is the ink, a {@code +} is the accent colour, and a space is
 * nothing. Every icon is the same size, so the toolbar can lay itself out without
 * asking any of them how big they are.
 */
public final class Icons {

	/** How many cells across every icon is. */
	public static final int SPAN = 9;

	private Icons() { }

	/** The iris: what a glance moves. Its pupil is the accent. */
	public static final String[] EYE = {
		"         ",
		"  #####  ",
		" #     # ",
		"#  +++  #",
		"#  +++  #",
		"#  +++  #",
		" #     # ",
		"  #####  ",
		"         "
	};

	/** The sclera: the same shape with nothing in it. */
	public static final String[] WHITE = {
		"         ",
		"  #####  ",
		" #+++++# ",
		"#+++++++#",
		"#+++ +++#",
		"#+++++++#",
		" #+++++# ",
		"  #####  ",
		"         "
	};

	/** The brow: a stroke above an eye, well clear of it. */
	public static final String[] BROW = {
		"         ",
		"   ###   ",
		" ##   ## ",
		"##     ##",
		"         ",
		"  #####  ",
		" #     # ",
		"  #####  ",
		"         "
	};

	/** The lash: the lid's own edge, with the hairs on it. */
	public static final String[] LASH = {
		"         ",
		"#       #",
		" #  #  # ",
		"  # # #  ",
		"   ###   ",
		"#########",
		"  #####  ",
		"         ",
		"         "
	};

	/** The pencil: one cell at a time. */
	public static final String[] PENCIL = {
		"      ###",
		"     ####",
		"    ###+#",
		"   ###+# ",
		"  ###+#  ",
		" ###+#   ",
		"#####    ",
		"###      ",
		"#        "
	};

	/** The box: drag out a rectangle. */
	public static final String[] RECT = {
		"         ",
		" ####### ",
		" #     # ",
		" #     # ",
		" #     # ",
		" #     # ",
		" #     # ",
		" ####### ",
		"         "
	};

	/** The bucket: everything of this colour that touches this. */
	public static final String[] FILL = {
		"    #    ",
		"   ###   ",
		"  ##+##  ",
		" ##+++## ",
		"##+++++##",
		" ##+++## ",
		"  #####  ",
		"       ##",
		"       ##"
	};

	/**
	 * The wand: everything of this colour anywhere on the face.
	 *
	 * Deliberately the bucket's shape with the connection broken — scattered dots
	 * rather than one pool — because the pair are two answers to one question and
	 * the picture is where the difference has to be said first.
	 */
	public static final String[] WAND = {
		"##     ##",
		"##  +  ##",
		"   +++   ",
		" +++++++ ",
		"   +++   ",
		"##  +  ##",
		"##     ##",
		"         ",
		"         "
	};

	/** The hand: move the picture about. */
	public static final String[] HAND = {
		"  # # #  ",
		" ### ### ",
		" ### ### ",
		"#########",
		"#########",
		" ####### ",
		"  #####  ",
		"   ###   ",
		"         "
	};

	/** The mirror: one side follows the other. */
	public static final String[] MIRROR = {
		"    #    ",
		" ## # ## ",
		"### # ###",
		"#### ####",
		"### # ###",
		" ## # ## ",
		"    #    ",
		"         ",
		"         "
	};

	/** Show or hide what has been marked. */
	public static final String[] MARKS = {
		"         ",
		"  #####  ",
		" ##   ## ",
		"##  +  ##",
		"#  +++  #",
		"##  +  ##",
		" ##   ## ",
		"  #####  ",
		"         "
	};

	/** Undo. */
	public static final String[] UNDO = {
		"         ",
		"  ###    ",
		" #       ",
		"#  ####  ",
		"# #    # ",
		"#      # ",
		" #    #  ",
		"  ####   ",
		"         "
	};

	/** Fit the whole face into the window. */
	public static final String[] FIT = {
		"##     ##",
		"#       #",
		"         ",
		"   ###   ",
		"   ###   ",
		"   ###   ",
		"         ",
		"#       #",
		"##     ##"
	};

	/** Closer, and further away. */
	public static final String[] IN = {
		"         ",
		"    #    ",
		"    #    ",
		"    #    ",
		" ####### ",
		"    #    ",
		"    #    ",
		"    #    ",
		"         "
	};

	public static final String[] OUT = {
		"         ",
		"         ",
		"         ",
		"         ",
		" ####### ",
		"         ",
		"         ",
		"         ",
		"         "
	};

	/** The face itself: the near layer, on its own. */
	public static final String[] LAYER_FACE = {
		"         ",
		" +++++   ",
		" +++++   ",
		" +++++   ",
		" +++++#  ",
		" +++++#  ",
		"   ##### ",
		"   ##### ",
		"         "
	};

	/** The outer layer: the far one, on its own. */
	public static final String[] LAYER_OVER = {
		"         ",
		" #####   ",
		" #####   ",
		" #####   ",
		" #####+  ",
		" #####+  ",
		"   ++++++",
		"   ++++++",
		"         "
	};

	/** Both, laid over each other, which is what a player sees. */
	public static final String[] LAYER_BOTH = {
		"         ",
		" +++++   ",
		" +++++   ",
		" +++++   ",
		" ++++++  ",
		" ++++++  ",
		"   ++++++",
		"   ++++++",
		"         "
	};

	/** Where the outer layer covers the face, shown on the picture. */
	public static final String[] EDGES = {
		"#  #  #  ",
		" #  #  # ",
		"  #  #  #",
		"#  #  #  ",
		" #  #  # ",
		"  #  #  #",
		"#  #  #  ",
		" #  #  # ",
		"  #  #  #"
	};

	/**
	 * Draws one icon, as large as the given box allows.
	 *
	 * Whole cells only. An icon drawn at two and a half times size has some strokes
	 * a pixel thicker than others, and on a row of them that raggedness is what the
	 * eye lands on rather than the pictures.
	 */
	public static void draw(GuiGraphicsExtractor graphics, String[] icon,
			int left, int top, int width, int height, int ink, int accent) {
		int scale = Math.max(1, Math.min(width, height) / SPAN);
		int span = SPAN * scale;
		int x0 = left + (width - span) / 2;
		int y0 = top + (height - span) / 2;

		for (int y = 0; y < SPAN && y < icon.length; y++) {
			String row = icon[y];
			for (int x = 0; x < SPAN && x < row.length(); x++) {
				char at = row.charAt(x);
				if (at == ' ') continue;
				int colour = at == '+' ? accent : ink;
				graphics.fill(x0 + x * scale, y0 + y * scale,
					x0 + (x + 1) * scale, y0 + (y + 1) * scale, colour);
			}
		}
	}
}
