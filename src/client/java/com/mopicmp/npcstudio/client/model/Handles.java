package com.mopicmp.npcstudio.client.model;

import com.mopicmp.npcstudio.entity.ModelObject;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The gizmo, drawn over the world instead of in it.
 *
 * <h2>Why it left the world</h2>
 *
 * Because a handle has one job that geometry cannot do: be visible. Drawn as
 * geometry, an arrow is behind whatever is in front of it — inside the box it
 * belongs to, behind the neighbouring plank, gone the moment the camera moves
 * round. Every dodge for that costs something: starting the arms outside the
 * model puts them off to one side of what they edit, and the game offers no
 * entity material that ignores depth.
 *
 * Over the world it is always there, by construction, and there is nothing left
 * to arrange.
 *
 * <h2>And it made the numbers one set instead of two</h2>
 *
 * The arms were already projected onto the screen to be clicked — that is how
 * dragging works and always has been. Drawing them from the same projection
 * means what is drawn and what answers a click cannot come apart, which they
 * have twice.
 *
 * <h2>What is lost</h2>
 *
 * Depth. An arm pointing away from the camera looks the same as one pointing
 * towards it, so the tips are what tell them apart, and a ring is a flat ellipse
 * rather than a hoop. That is the trade every editor with a flat gizmo makes,
 * and it is the right way round: a handle that is always catchable and slightly
 * flat beats one that is beautifully lit and behind a plank.
 */
public final class Handles {

	private static final int SHAFT = 2;
	private static final int HEAD = 11;
	private static final int WING = 4;
	private static final int MIDDLE = 3;
	private static final int RING_STEPS = 40;

	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55DD66;
	private static final int BLUE = 0xFF5599FF;
	private static final int LIT = 0xFFFFD54A;
	private static final int WHITE = 0xFFF2F5F7;
	private static final int SHADOW = 0x99101418;

	private Handles() { }

	/**
	 * Draws whatever the gizmo is showing, in the panel's own coordinates.
	 *
	 * @param originX where the panel's corner is on the display, because everything
	 *                {@link Gizmo} projects is in display coordinates and this draws
	 *                inside a panel that has been translated to its own
	 */
	public static void draw(GuiGraphicsExtractor graphics, int originX, int originY) {
		ModelObject object = Gizmo.subject();
		if (object == null) return;

		Gizmo.Axis lit = Gizmo.hovered();
		boolean resizing = Gizmo.resizing();

		for (Gizmo.Ring ring : Gizmo.rings()) {
			ring(graphics, object, ring, originX, originY, colourOf(ring.axis(), lit));
		}

		double[] middle = null;
		for (Gizmo.Arm arm : Gizmo.arms()) {
			double[] from = at(object, arm.start(), originX, originY);
			float[] tip = arm.tip();
			double[] to = at(object, tip, originX, originY);
			if (from == null || to == null) continue;
			middle = from;
			arm(graphics, from, to, colourOf(arm.axis(), lit), resizing);
		}

		// The little cube everything comes out of, last so it sits over the shafts.
		// It is what says where the thing being edited actually is — three arms with
		// no common point look like three arms, not like a set of axes on a box.
		if (middle != null) {
			int x = (int) Math.round(middle[0]);
			int y = (int) Math.round(middle[1]);
			graphics.fill(x - MIDDLE - 1, y - MIDDLE - 1, x + MIDDLE + 1, y + MIDDLE + 1, SHADOW);
			graphics.fill(x - MIDDLE, y - MIDDLE, x + MIDDLE, y + MIDDLE, WHITE);
		}
	}

	private static int colourOf(Gizmo.Axis axis, Gizmo.Axis lit) {
		if (axis == lit) return LIT;
		return switch (axis) {
			case X -> RED;
			case Y -> GREEN;
			case Z -> BLUE;
		};
	}

	/** A point of the model, on the panel, or null when it is behind the camera. */
	private static double[] at(ModelObject object, float[] point, int originX, int originY) {
		double[] on = Gizmo.onScreen(Gizmo.world(object, point[0], point[1], point[2]));
		if (on == null) return null;
		return new double[] { on[0] - originX, on[1] - originY };
	}

	/**
	 * One arm: a shaft, and a head that says which end is the front.
	 *
	 * The head is a stack of narrowing bars rather than a triangle, because what is
	 * available to draw with is rectangles. Eleven pixels of them at four wide reads
	 * as a point at any angle, which is all it has to do.
	 */
	private static void arm(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour, boolean resizing) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double length = Math.hypot(dx, dy);
		// An arm pointing at the camera has nowhere to go on screen. Drawing it as a
		// blob at the centre would be a handle that cannot be aimed at claiming it can.
		if (length < HEAD + 2) return;

		float angle = (float) Math.atan2(dy, dx);
		graphics.pose().pushMatrix();
		graphics.pose().translate((float) from[0], (float) from[1]);
		graphics.pose().rotate(angle);

		int shaft = (int) Math.round(length) - HEAD;
		graphics.fill(0, -SHAFT / 2 - 1, shaft, SHAFT / 2 + 1 + SHAFT % 2, SHADOW);
		graphics.fill(0, -SHAFT / 2, shaft, SHAFT / 2 + SHAFT % 2, colour);

		if (resizing) {
			// A cube at the end rather than a point: the shape every editor uses for a
			// scale handle, and the gizmo's way of saying which tool is armed before
			// anything is pulled.
			int reach = (int) Math.round(length);
			graphics.fill(reach - WING - 1, -WING - 1, reach + WING + 1, WING + 1, SHADOW);
			graphics.fill(reach - WING, -WING, reach + WING, WING, colour);
		} else {
			for (int step = 0; step < HEAD; step++) {
				int wing = Math.max(1, WING - WING * step / HEAD);
				int x = shaft + step;
				graphics.fill(x, -wing - 1, x + 1, wing + 1, SHADOW);
				graphics.fill(x, -wing, x + 1, wing, colour);
			}
		}
		graphics.pose().popMatrix();
	}

	/**
	 * One ring, walked as a chain of short bars.
	 *
	 * Walked rather than drawn as an ellipse for the same reason the hit test walks
	 * it: the projection of a circle in three dimensions is an ellipse only while
	 * all of it is in front of the camera, and a ring round a box you are standing
	 * inside is not.
	 */
	private static void ring(GuiGraphicsExtractor graphics, ModelObject object, Gizmo.Ring ring,
			int originX, int originY, int colour) {
		double[] before = null;
		double[] first = null;

		for (int step = 0; step <= RING_STEPS; step++) {
			double[] here = at(object, Gizmo.onRing(ring, step * 2 * Math.PI / RING_STEPS),
				originX, originY);
			if (step == 0) first = here;
			if (before != null && here != null) segment(graphics, before, here, colour);
			before = here;
		}
		if (before != null && first != null) segment(graphics, before, first, colour);
	}

	private static void segment(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double length = Math.hypot(dx, dy);
		if (length < 0.5) return;

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) from[0], (float) from[1]);
		graphics.pose().rotate((float) Math.atan2(dy, dx));
		// A pixel longer than it is, so the corners between segments do not show as gaps.
		graphics.fill(0, -SHAFT / 2 - 1, (int) Math.ceil(length) + 1, SHAFT / 2 + 1, SHADOW);
		graphics.fill(0, -SHAFT / 2, (int) Math.ceil(length) + 1, SHAFT / 2 + SHAFT % 2, colour);
		graphics.pose().popMatrix();
	}
}
