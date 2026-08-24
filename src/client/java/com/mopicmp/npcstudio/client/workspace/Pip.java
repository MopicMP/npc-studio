package com.mopicmp.npcstudio.client.workspace;

import org.joml.Vector2f;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Where a panel's own coordinates land on the actual screen.
 *
 * <h2>The bug this exists for</h2>
 *
 * Almost everything drawn in this interface goes through the pose: a fill, a
 * blit, a line of text are all placed by the matrix, and a panel therefore draws
 * from its own nought-nought without knowing or caring where the dock put it.
 *
 * Three things do not. A figure, a skin and a book are not painted into the
 * frame at all — they are handed to the renderer as a <em>picture in picture</em>,
 * a little scene rendered on its own and dropped into a rectangle, and that
 * rectangle is four plain numbers with no matrix anywhere near them. Hand those
 * a panel's local coordinates and the picture is placed that far from the corner
 * of the window instead of that far from the corner of the panel — and then
 * clipped away by the panel's scissor, which <em>was</em> put through the matrix.
 * The result is not a figure in the wrong place. It is no figure at all, which
 * is a great deal harder to recognise as a coordinate problem.
 *
 * So every picture-in-picture rectangle goes through here first. The whole
 * matrix is applied rather than its two translation numbers, because a matrix
 * that only ever translates today is not a matrix that only ever translates.
 */
public final class Pip {

	private Pip() { }

	/** The point, as the screen sees it. */
	public static int[] at(GuiGraphicsExtractor graphics, int x, int y) {
		Vector2f point = graphics.pose().transformPosition(new Vector2f(x, y));
		return new int[] { Math.round(point.x), Math.round(point.y) };
	}

	/** The rectangle, as the screen sees it: left, top, right, bottom. */
	public static int[] box(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		int[] from = at(graphics, left, top);
		int[] to = at(graphics, right, bottom);
		return new int[] {
			Math.min(from[0], to[0]), Math.min(from[1], to[1]),
			Math.max(from[0], to[0]), Math.max(from[1], to[1]) };
	}
}
