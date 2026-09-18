package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.client.model.Gizmo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/**
 * Lines of the world, drawn over the interface's flat rectangles.
 *
 * <h2>Why every handle draws this way</h2>
 *
 * There is no line in this interface. A {@code GuiGraphics} fills rectangles, and
 * that is the whole vocabulary — so a line is a rectangle turned to face the way
 * the line goes. Doing it here rather than in the world's own line renderer is what
 * makes a handle inside a character still a handle: the world was drawn before any
 * of this, so anything painted now is painted on top of it and cannot be buried in
 * whatever it is attached to.
 *
 * <h2>It used to be one rectangle a pixel, and that cost the game</h2>
 *
 * A line was walked from one end to the other, a rectangle laid down at every step.
 * A line across the screen was therefore a thousand rectangles, and four points of a
 * route with the posts and crosses that go with them is thirty lines — tens of
 * thousands of interface elements in a frame, every frame.
 *
 * It was reported twice before anybody connected the two. First as a crash: the
 * interface is built as a tree, an element overlapping another goes in a layer above
 * it, and a thousand overlapping rectangles is a thousand layers, which the recursive
 * draw walks until the stack runs out. Then, once that was cured by making them not
 * overlap, as the frame rate falling from seventy to five when you looked at a route.
 * The layering was a symptom; this was the disease.
 *
 * The fix was already in this project, in {@code model/Handles}, which draws an arrow
 * shaft by turning the matrix and filling one rectangle. Two fills a line rather than
 * two thousand — the shadow and the line — and nothing else about it changes.
 *
 * Every line is two: a dark one a pixel wider underneath and a bright one on top.
 * Without the dark one a red ring over a red brick wall is invisible, which is the
 * one place a handle absolutely may not be.
 *
 * <h2>Why it is one class and was three</h2>
 *
 * The rings, the arrows and the camera each carried their own copy of this, and
 * the copies had already drifted: one refused a segment longer than four hundred
 * pixels and one allowed four thousand, so a ring seen from close up quietly lost
 * pieces of itself while an arrow at the same distance did not. Two behaviours for
 * one drawing is a thing nobody can see is wrong until they are looking for it.
 */
public final class Wireframe {

	/** The dark line underneath, so a bright one reads over anything. */
	private static final int SHADOW = 0x99101418;

	/**
	 * How long a segment may be before it is refused, in pixels.
	 *
	 * A guard rather than a limit. Projection is well behaved for anything in
	 * front of the camera and unbounded for anything grazing the plane it sits on,
	 * so a point that is technically ahead by a hair produces a segment thousands
	 * of screens long, and walking it a pixel at a time is a frozen game. Well past
	 * any real display and well short of that.
	 */
	private static final int LONGEST = 4000;

	private Wireframe() { }

	/** Where a point of the world lands in a panel, or null when it is behind. */
	public static double[] at(Vec3 point, int originX, int originY) {
		double[] on = Gizmo.onScreen(point);
		return on == null ? null : new double[] { on[0] - originX, on[1] - originY };
	}

	/**
	 * One straight piece, in panel coordinates.
	 *
	 * One rectangle turned to lie along the line, and one wider behind it for the
	 * shadow. Not a rectangle a pixel — see the note on this class for what that
	 * cost, twice.
	 *
	 * The turn is about the near end, so the shaft runs from nought to its length
	 * along the x axis of the turned frame and the thickness sits either side of it.
	 * The same arrangement {@code model/Handles} draws an arrow with.
	 */
	public static void segment(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double length = Math.hypot(dx, dy);
		// Nought is two points on the same pixel, which is a line with no direction and
		// nothing to draw. The ceiling is the old guard, unchanged in meaning: a point
		// grazing the plane the camera sits on projects to something thousands of
		// screens long, and drawing it is a frozen game however cheap each one is.
		if (!(length >= 1) || length > LONGEST) return;

		float angle = (float) Math.atan2(dy, dx);
		int along = (int) Math.round(length);

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) from[0], (float) from[1]);
		graphics.pose().rotate(angle);
		// A pixel of shadow either side of a pixel of line, which is what the walked
		// version drew and is why a bright ring reads over a bright wall.
		graphics.fill(0, -1, along, 2, SHADOW);
		graphics.fill(0, 0, along, 1, colour);
		graphics.pose().popMatrix();
	}

	/**
	 * One straight piece between two places in the world.
	 *
	 * Drawn only when both ends are in front of the camera. Clipping the line at
	 * the plane would be the thorough answer and would be wrong here for the same
	 * reason {@link Gizmo#onScreen} refuses a point behind: half a handle drawn
	 * across the screen, plausibly, is worse than none of it.
	 */
	public static void line(GuiGraphicsExtractor graphics, int originX, int originY,
			Vec3 from, Vec3 to, int colour) {
		double[] a = at(from, originX, originY);
		double[] b = at(to, originX, originY);
		if (a != null && b != null) segment(graphics, a, b, colour);
	}

	/**
	 * A box, as its twelve edges.
	 *
	 * <h2>Why edges and not a filled shape</h2>
	 *
	 * Because the whole reason to draw a box is to see what is inside it, and a
	 * solid one hides exactly that. A wireframe over the world is the same choice
	 * every handle in this file makes, for the same reason: it says where the thing
	 * is without standing in front of it.
	 *
	 * Twelve calls is twenty-four fills, which is nothing — see the note on this
	 * class about what a line used to cost and why it no longer does.
	 *
	 * The corners are given in blocks and the far one is inclusive, so a box from a
	 * block to itself is drawn round the whole of that block rather than collapsing
	 * to a point. That matches what {@code Area.holds} says about the same two
	 * corners, and a picture that disagrees with the test is worse than no picture.
	 */
	public static void box(GuiGraphicsExtractor graphics, int originX, int originY,
			int[] low, int[] high, int colour) {
		double x0 = low[0];
		double y0 = low[1];
		double z0 = low[2];
		double x1 = high[0] + 1.0;
		double y1 = high[1] + 1.0;
		double z1 = high[2] + 1.0;

		for (double y : new double[] { y0, y1 }) {
			line(graphics, originX, originY, new Vec3(x0, y, z0), new Vec3(x1, y, z0), colour);
			line(graphics, originX, originY, new Vec3(x1, y, z0), new Vec3(x1, y, z1), colour);
			line(graphics, originX, originY, new Vec3(x1, y, z1), new Vec3(x0, y, z1), colour);
			line(graphics, originX, originY, new Vec3(x0, y, z1), new Vec3(x0, y, z0), colour);
		}
		for (double[] corner : new double[][] { { x0, z0 }, { x1, z0 }, { x1, z1 }, { x0, z1 } }) {
			line(graphics, originX, originY,
				new Vec3(corner[0], y0, corner[1]), new Vec3(corner[0], y1, corner[1]), colour);
		}
	}
}
