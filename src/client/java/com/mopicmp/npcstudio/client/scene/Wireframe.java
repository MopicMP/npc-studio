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
 * that is the whole vocabulary — so a line is a rectangle a pixel across, walked
 * from one end to the other. Doing it here rather than in the world's own line
 * renderer is what makes a handle inside a character still a handle: the world was
 * drawn before any of this, so anything painted now is painted on top of it and
 * cannot be buried in whatever it is attached to.
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

	/** One straight piece, in panel coordinates. */
	public static void segment(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		int steps = (int) Math.ceil(Math.hypot(dx, dy));
		if (steps < 0 || steps > LONGEST) return;
		for (int i = 0; i <= steps; i++) {
			double along = steps == 0 ? 0 : (double) i / steps;
			int x = (int) Math.round(from[0] + dx * along);
			int y = (int) Math.round(from[1] + dy * along);
			graphics.fill(x - 1, y - 1, x + 2, y + 2, SHADOW);
			graphics.fill(x, y, x + 1, y + 1, colour);
		}
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
}
