package com.mopicmp.npcstudio.client.wardrobe;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * A costume drawn flat, from the front, out of its own skin.
 *
 * This exists because the obvious answer was wrong. The first thought was to
 * crop the face, the way the tab list does and the way our own speaker icon
 * does — but a costume keeps the head and changes the body. The same character
 * in work clothes, party clothes and everyday clothes is one face and three
 * bodies, so twenty costumes would have given twenty identical pictures and
 * nothing to choose between them.
 *
 * So the whole figure is cut out instead: head, torso, arms and legs, taken
 * from the front faces and laid side by side. Nine rectangles, no model, no
 * three dimensions, and nobody has to draw anything — the picture is already in
 * the skin. What it shows is exactly what costumes differ by.
 *
 * The outer layer goes over the top, because a great many costumes live there
 * entirely: a cloak, a coat, a hat.
 */
public final class PaperDoll {

	/** The figure's own proportions: sixteen across, thirty-two down. */
	public static final int WIDE = 16;
	public static final int TALL = 32;

	/**
	 * One piece: where it sits on the figure, and where it comes from on the skin.
	 *
	 * In sixty-fourths, so a skin at twice or four times the size is read from
	 * the same places — a larger skin is the same layout drawn bigger.
	 */
	private record Piece(int x, int y, int w, int h, int u, int v) { }

	private static final Piece[] BODY = {
		new Piece(4, 0, 8, 8, 8, 8),      // head
		new Piece(4, 8, 8, 12, 20, 20),   // torso
		new Piece(0, 8, 4, 12, 44, 20),   // right arm
		new Piece(12, 8, 4, 12, 36, 52),  // left arm
		new Piece(4, 20, 4, 12, 4, 20),   // right leg
		new Piece(8, 20, 4, 12, 20, 52),  // left leg
	};

	/**
	 * The same figure on a skin from before 1.8, which is half as tall.
	 *
	 * That layout has no left arm and no left leg — the right ones were mirrored
	 * for both sides — and no jacket, sleeves or trousers, only a hat. Drawn from
	 * the full table it sampled rows the picture does not have, which is why seven
	 * of the fifty measured HD skins came out looking like broken textures rather
	 * than like old skins.
	 *
	 * The left limbs are drawn from the right ones. Not mirrored, because a blit
	 * cannot flip and a limb the right way round is a great deal closer to the
	 * truth than a limb made of somebody's cheek.
	 */
	private static final Piece[] BODY_OLD = {
		new Piece(4, 0, 8, 8, 8, 8),      // head
		new Piece(4, 8, 8, 12, 20, 20),   // torso
		new Piece(0, 8, 4, 12, 44, 20),   // right arm
		new Piece(12, 8, 4, 12, 44, 20),  // left arm, from the right one
		new Piece(4, 20, 4, 12, 4, 20),   // right leg
		new Piece(8, 20, 4, 12, 4, 20),   // left leg, from the right one
	};

	private static final Piece[] OVER_OLD = {
		new Piece(4, 0, 8, 8, 40, 8),     // hat, and there is nothing else
	};

	private static final Piece[] OVER = {
		new Piece(4, 0, 8, 8, 40, 8),     // hat
		new Piece(4, 8, 8, 12, 20, 36),   // jacket
		new Piece(0, 8, 4, 12, 44, 36),   // right sleeve
		new Piece(12, 8, 4, 12, 52, 52),  // left sleeve
		new Piece(4, 20, 4, 12, 4, 36),   // right trouser
		new Piece(8, 20, 4, 12, 4, 52),   // left trouser
	};

	private PaperDoll() { }

	/** How wide a figure is when drawn this tall. */
	public static int widthFor(int height) {
		return Math.max(1, height * WIDE / TALL);
	}

	/**
	 * Draws the figure inside a box, as large as fits.
	 *
	 * @param height how tall to draw it; the width follows from the proportions
	 */
	public static void draw(GuiGraphicsExtractor graphics, Identifier skin,
			int left, int top, int height) {
		draw(graphics, skin, left, top, height, 64);
	}

	/**
	 * @param tall how tall the skin is in sixty-fourths: 64, or 32 for the layout
	 *             from before 1.8. A texture is addressed as a fraction of its own
	 *             height, so half the height means twice the fraction, and a blit
	 *             told the wrong one draws the wrong rows
	 */
	public static void draw(GuiGraphicsExtractor graphics, Identifier skin,
			int left, int top, int height, int tall) {
		// Whole pixels only. A figure drawn at two and a half times size has some
		// limbs a pixel wider than others, and on a grid of them the raggedness is
		// what the eye lands on first.
		int scale = Math.max(1, height / TALL);
		int x = left;
		int y = top;

		boolean old = tall <= 32;
		for (Piece piece : old ? BODY_OLD : BODY) blit(graphics, skin, x, y, scale, piece, tall);
		for (Piece piece : old ? OVER_OLD : OVER) blit(graphics, skin, x, y, scale, piece, tall);
	}

	private static void blit(GuiGraphicsExtractor graphics, Identifier skin,
			int left, int top, int scale, Piece piece, int tall) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, skin,
			left + piece.x() * scale, top + piece.y() * scale, piece.u(), piece.v(),
			piece.w() * scale, piece.h() * scale, piece.w(), piece.h(), 64, tall);
	}
}
