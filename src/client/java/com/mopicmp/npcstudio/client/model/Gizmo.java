package com.mopicmp.npcstudio.client.model;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.entity.ModelObject;
import com.mopicmp.npcstudio.model.Cube;
import com.mopicmp.npcstudio.model.ModelPose;

import org.joml.Vector3fc;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * The arrows: where they are, which one the mouse is on, and how far a drag means.
 *
 * <h2>Three things a handle has to get right</h2>
 *
 * <b>The same size on screen, always.</b> An arrow measured in blocks is a
 * different arrow at every distance: enormous with the camera on top of the box
 * and a few pixels across the room, where it is needed most. So the length is
 * worked out backwards from the display — a fixed share of the frame's height —
 * and turned into world units for whatever distance the camera happens to be at.
 * That is why {@link Arm#length()} is recomputed every frame instead of being a
 * constant.
 *
 * <b>Out of the box, on the side you are looking from.</b> Each arm points to
 * whichever end of its axis faces the camera and starts at that face rather than
 * at the middle. An arm that starts in the middle is half buried in the box it
 * belongs to — the visible stub is what was there before, and it is also why
 * they looked short. Following the camera means walking round a model never
 * leaves you with an arrow inside it.
 *
 * <b>Grabbable along the whole shaft.</b> The first version tested the mouse
 * against the tip alone, so all but a few pixels of what was drawn did nothing
 * when clicked. Distance to the segment, in screen space, is the whole fix.
 *
 * <h2>Why dragging does not use a ray</h2>
 *
 * The obvious way is to cast a ray and intersect it with the axis, and it is the
 * wrong way: the ray and the axis nearly never meet, so it becomes a
 * nearest-approach calculation that goes unstable exactly when the axis points
 * at the camera — which is the moment somebody is most likely to be dragging it.
 *
 * So the arm is projected onto the screen and the mouse is measured along that.
 * A drag of ten pixels along a shaft that is sixty pixels long and worth twelve
 * model pixels moves the box two. It cannot go unstable, because an arm pointing
 * at the camera projects to nothing and refuses to move — the honest answer to
 * "how far along an axis you cannot see did I mean to drag".
 *
 * <h2>Why a grip is frozen when the button goes down</h2>
 *
 * Because the arm moves with the box. Measuring each little drag against a
 * direction that is itself being dragged is a feedback loop, and it shows up as
 * the box creeping sideways during a long pull. The direction on screen and the
 * box as it was are both taken once, at the press, and every position of the
 * mouse afterwards is measured against those.
 */
public final class Gizmo {

	public enum Axis { X, Y, Z }

	/**
	 * What the handles do, which is a mode rather than a modifier.
	 *
	 * It was a modifier first — shift on the same arm — and that is the right
	 * bargain only while there are two jobs. At three it stops working: there is no
	 * second modifier that is not already spoken for, and holding a key down for
	 * the length of a drag is not something you want to be doing while also aiming.
	 * So the three are a mode with a key each, the arrangement every editor with
	 * more than one handle has arrived at.
	 */
	public enum Tool { MOVE, ROTATE, RESIZE }

	/**
	 * One arm, in model pixels, exactly as it is drawn and as it is hit-tested.
	 *
	 * One record for both is the point of it. Drawing from one set of numbers and
	 * clicking against another is how you get handles that do not respond where
	 * they look, and that was the state of these before.
	 *
	 * <h2>Why it carries directions instead of naming an axis</h2>
	 *
	 * Because a box can be turned, and a turned box's own X does not point along
	 * the model's X any more. The arms are worked out in the box's placed frame —
	 * the same matrix the renderer draws it with — so they sit on the box and point
	 * along its own sides however it has been rotated. Naming the axis and adding
	 * along it, which is what this did first, puts the handles where the box would
	 * have been if nobody had turned it: near it at first and further away with
	 * every degree, which is the "the arrows wander off" that was reported.
	 *
	 * {@code along} already carries the sign, so everything drawn from it comes out
	 * right whichever end of the axis is facing the camera. {@code axis} survives
	 * only to say which of the box's three numbers a drag edits.
	 */
	public record Arm(Axis axis, float sign, float[] start, float[] along, float[] across,
			float[] up, float length) {

		/** Where the point of the arrow is, in model pixels. */
		public float[] tip() {
			return new float[] {
				start[0] + along[0] * length,
				start[1] + along[1] * length,
				start[2] + along[2] * length };
		}
	}

	/**
	 * One ring: a circle in the plane its axis is normal to, in the box's own frame.
	 *
	 * {@code across} and {@code up} span that plane, and they are the box's turned
	 * axes for the same reason the arms' are.
	 */
	public record Ring(Axis axis, float[] centre, float[] across, float[] up, float radius) { }

	/**
	 * A drag in progress: what was grabbed, and the measuring stick it is read against.
	 *
	 * Two measuring sticks, because there are two kinds of drag. A pull along an
	 * arm is a line: a point it started from and a direction, in display pixels. A
	 * turn of a ring is a circle: a centre it goes round and the angle it started
	 * at. Both are frozen at the press for the same reason.
	 *
	 * @param start    the box as it was when the button went down
	 * @param fromX    where the mouse was, on the display
	 * @param dirX     the arm's direction on screen, as a unit vector
	 * @param perPixel screen pixels per model pixel along the arm
	 * @param centreX  where the ring's middle is, on the display
	 */
	public record Grip(Tool tool, Axis axis, float sign,
			java.util.List<Modelling.Chosen> where, java.util.List<Cube> start,
			double fromX, double fromY, double dirX, double dirY, double perPixel,
			double centreX, double centreY,
			float anchor, float span) {

		/** How far the mouse has been pulled along the arm, in model pixels. */
		public float pulled(double mouseX, double mouseY) {
			double along = (mouseX - fromX) * dirX + (mouseY - fromY) * dirY;
			return (float) (along / perPixel);
		}

		/** Where the mouse stands round the ring, in degrees, as the screen measures it. */
		public double angleAt(double mouseX, double mouseY) {
			return Math.toDegrees(Math.atan2(mouseY - centreY, mouseX - centreX));
		}
	}

	/**
	 * How long an arm is, as a share of half the frame's height.
	 *
	 * Half the height, because that is what a distance times the tangent of half
	 * the field of view gives you directly. Four tenths of it was too much by a
	 * long way: around a box of one block it drew arms two blocks long and a ring
	 * two blocks across, which does not read as a handle on that box — it reads as
	 * a circle hanging in the air somewhere near it.
	 */
	private static final double SPAN = 0.17;

	/** How far the setting may take that, either way. */
	public static final float SMALLEST = 0.5f;
	public static final float LARGEST = 2.5f;

	private static float sizing = 1f;

	public static float sizing() {
		return sizing;
	}

	public static void sizing(float times) {
		sizing = Math.max(SMALLEST, Math.min(LARGEST, times));
	}

	/**
	 * How near the mouse has to come to a handle to take hold of it, in interface pixels.
	 *
	 * Kept where it was while the arrows themselves were made slimmer. The two are
	 * not the same measurement and there is no reason for them to move together: how
	 * big a thing looks is a question about the picture, and how easily it is caught
	 * is a question about the hand. Tying them means a tidy gizmo you cannot hit.
	 */
	private static final double GRAB = 9;

	/** How many straight pieces a ring is measured as. Enough that the seams do not show. */
	private static final int ROUND = 24;

	/** Which handle the mouse is over, and which one is being pulled. */
	private static Axis hovered;
	private static Grip grip;
	private static Tool tool = Tool.MOVE;

	/**
	 * How far a turn has gone, in degrees, and where the mouse last stood.
	 *
	 * Kept as a running total rather than read off the angle the mouse is at now,
	 * because the angle the mouse is at now wraps: drag past the top of the ring and
	 * a straight subtraction says the box spun the other way round. Adding up the
	 * steps, each one small enough to be unambiguous, lets a turn go round as many
	 * times as somebody has patience for.
	 */
	private static double spun;
	private static double lastAngle;

	private Gizmo() { }

	public static Tool tool() {
		return tool;
	}

	public static void tool(Tool which) {
		if (which != null && grip == null) tool = which;
	}

	// ------------------------------------------------------------ what and where

	/** The copy that was last clicked on, so that a model placed twice is not ambiguous. */
	private static int chosen = -1;

	public static void attach(ModelObject object) {
		chosen = object == null ? -1 : object.getId();
	}

	/**
	 * The object the handles belong to: a placed copy of the model being edited.
	 *
	 * The one that was clicked if it is still standing, and otherwise the nearest.
	 * The difference matters as soon as a model is put down twice: the handles have
	 * to be on the copy that was chosen, or a delete takes the wrong one and a drag
	 * appears to do nothing because it is happening to a box behind you.
	 */
	public static ModelObject subject() {
		if (!Modelling.open()) return null;
		// Walked rather than taken from the sorted list, because this is asked several
		// times a frame — by the arms, by the rings, by the hit test and by the
		// drawing — and sorting every placed object by distance each time to answer
		// "which one was clicked" is a great deal of work for a question that has an
		// id in it.
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null) return null;

		ModelObject first = null;
		for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ModelObject object)) continue;
			if (!object.model().equals(Modelling.name())) continue;
			if (object.getId() == chosen) return object;
			if (first == null) first = object;
		}
		return first;
	}

	/**
	 * Whether handles should be showing at all.
	 *
	 * Only while the editor is open. They are an instrument, not a property of the
	 * object: leaving them standing in the world after the window is closed says
	 * the thing is still being edited when it is not, and there is nothing on
	 * screen that could be used to grab them.
	 */
	private static boolean editing() {
		return ModellingScreen.showing();
	}

	/** The three arms as they stand this frame, or nothing when there is no box to hold. */
	public static List<Arm> arms() {
		ModelObject object = subject();
		Cube cube = Modelling.cube();
		if (object == null || cube == null || tool == Tool.ROTATE || !editing()) return List.of();

		float length = length(object, cube);
		if (length <= 0) return List.of();

		float[] frame = frame();
		// The box's own sides, or the world's. Square axes are what a world direction
		// is once the object's own turn is out of the way, and the object is kept
		// square to the world, so that is the identity.
		float[][] axes = world && worldMatters()
			? new float[][] { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } }
			: axesOf(frame);

		List<Arm> arms = new ArrayList<>(3);
		for (Axis axis : Axis.values()) {
			float[] unit = axes[axis.ordinal()];
			float sign = towards(unit, object, placedMiddle(frame, cube));

			float[] along = { unit[0] * sign, unit[1] * sign, unit[2] * sign };

			// From the middle of the box, with nothing between. The arms used to start
			// at a face and then be pushed further out past whatever was in the way,
			// which was a fix for a problem that no longer exists: they are drawn over
			// the world now rather than in it, so being inside something costs nothing.
			// What it cost was the thing everybody notices — a gizmo standing off to
			// one side of what it edits instead of on it.
			float[] start = placedMiddle(frame, cube);

			float[] across = axes[(axis.ordinal() + 1) % 3];
			float[] up = axes[(axis.ordinal() + 2) % 3];
			arms.add(new Arm(axis, sign, start, along, across, up, length));
		}
		return arms;
	}

	/** The three rings, when it is turning that is being done. */
	public static List<Ring> rings() {
		ModelObject object = subject();
		Cube cube = Modelling.cube();
		if (object == null || cube == null || tool != Tool.ROTATE || !editing()) return List.of();

		float radius = length(object, cube);
		if (radius <= 0) return List.of();

		// Round the point the box turns about, not round the middle of the box. They
		// are usually the same and when they are not, the pivot is the honest answer:
		// it is what the box will actually swing on.
		float[] frame = frame();
		float[][] axes = axesOf(frame);
		// Round the middle of the whole selection, because that is what will turn.
		// A ring drawn round one box of a cone while the cone turns round its own
		// middle is a ring drawn round the wrong thing.
		float[] about = about(Modelling.markedBoxes());
		float[] middle = ModelPose.apply(frame, about[0], about[1], about[2]);

		List<Ring> rings = new ArrayList<>(3);
		for (Axis axis : Axis.values()) {
			rings.add(new Ring(axis, middle,
				axes[(axis.ordinal() + 1) % 3], axes[(axis.ordinal() + 2) % 3], radius));
		}
		return rings;
	}

	/**
	 * The frame the selected box is drawn in: its bone's turns and its own.
	 *
	 * Taken from the same place the renderer takes it, by name and slot, so the
	 * handles cannot end up in a frame the box is not in. An identity when nothing
	 * is selected, which is the harmless answer — the callers have already checked
	 * that there is a box.
	 */
	private static float[] frame() {
		if (!Modelling.open()) return ModelPose.identity();
		for (ModelPose.Placed placed : ModelPose.place(Modelling.model())) {
			if (placed.bone().equals(Modelling.selectedBone())
				&& placed.index() == Modelling.selectedCube()) {
				return placed.matrix();
			}
		}
		return ModelPose.identity();
	}

	/**
	 * Whether the handles follow the box's own sides or the world's.
	 *
	 * <h2>Why both are wanted</h2>
	 *
	 * A box turned forty-five degrees has arms turned forty-five degrees, which is
	 * right when the job is to lengthen that box along its own length and useless
	 * when the job is to shift it a little to one side. Working out which two
	 * diagonal drags add up to "a bit to the left" is arithmetic nobody should be
	 * doing by eye.
	 *
	 * <h2>Where it applies, and where it cannot</h2>
	 *
	 * Moving. A box's corners are written in the frame its folder is in, before its
	 * own turn is applied, so a world direction is exactly a change to those
	 * numbers — the two are the same thing.
	 *
	 * Not resizing and not turning. A face of a turned box does not face along a
	 * world axis, so "make it wider along the world's X" has no answer that is one
	 * number; and an angle written as three Euler numbers is turned in its own
	 * frame by definition. Those two keep the box's own sides whatever this says,
	 * and the button dims to admit it.
	 */
	private static boolean world;

	public static boolean world() {
		return world;
	}

	public static void world(boolean on) {
		if (grip == null) world = on;
	}

	/** Whether the switch means anything for the tool that is armed. */
	public static boolean worldMatters() {
		return tool == Tool.MOVE;
	}

	/** The three axes of a frame, as unit vectors. */
	private static float[][] axesOf(float[] frame) {
		float[] origin = ModelPose.apply(frame, 0, 0, 0);
		float[][] axes = new float[3][];
		for (int axis = 0; axis < 3; axis++) {
			float[] end = ModelPose.apply(frame, axis == 0 ? 1 : 0, axis == 1 ? 1 : 0,
				axis == 2 ? 1 : 0);
			float x = end[0] - origin[0];
			float y = end[1] - origin[1];
			float z = end[2] - origin[2];
			float span = (float) Math.sqrt(x * x + y * y + z * z);
			if (span < 1e-6) span = 1;
			axes[axis] = new float[] { x / span, y / span, z / span };
		}
		return axes;
	}

	/** The middle of the box, where it actually sits. */
	private static float[] placedMiddle(float[] frame, Cube cube) {
		return ModelPose.apply(frame,
			(cube.fromX() + cube.toX()) / 2,
			(cube.fromY() + cube.toY()) / 2,
			(cube.fromZ() + cube.toZ()) / 2);
	}

	/**
	 * Where a box turns, with an answer for boxes that were never given one.
	 *
	 * A pivot of nothing at all is a box that swings round the model's origin, which
	 * for anything not standing at the origin means it takes off across the scene.
	 * That is a correct reading of the document and a useless one to hand somebody
	 * who has just pressed R, so a box with no pivot set turns round its own middle.
	 * Setting a pivot is still a thing that exists and still wins.
	 */
	public static float[] turnsAbout(Cube cube) {
		if (cube.pivotX() != 0 || cube.pivotY() != 0 || cube.pivotZ() != 0) {
			return new float[] { cube.pivotX(), cube.pivotY(), cube.pivotZ() };
		}
		return new float[] {
			(cube.fromX() + cube.toX()) / 2,
			(cube.fromY() + cube.toY()) / 2,
			(cube.fromZ() + cube.toZ()) / 2 };
	}

	/** Which way along a direction the camera is, as a sign. */
	private static float towards(float[] unit, ModelObject object, float[] middle) {
		Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
		Vec3 at = world(object, middle[0], middle[1], middle[2]);
		return worldDirection(unit, object).dot(eye.subtract(at)) >= 0 ? 1 : -1;
	}

	/** A direction of the model, turned the way the object is. */
	public static Vec3 worldDirection(float[] unit, ModelObject object) {
		double yaw = Math.toRadians(object.getYRot());
		double cos = Math.cos(yaw);
		double sin = Math.sin(yaw);
		return new Vec3(unit[0] * cos - unit[2] * sin, unit[1], unit[0] * sin + unit[2] * cos);
	}

	/**
	 * How long an arm should be in model pixels for it to keep its size on screen.
	 *
	 * A distance times the tangent of half the field of view is how much world fits
	 * between the middle of the frame and its top edge, at that distance. Taking a
	 * share of that is a length in blocks that always covers the same share of the
	 * display; the rest is the two conversions into the space the model is drawn
	 * in — the object's own scale, and sixteen pixels to a block.
	 */
	private static float length(ModelObject object, Cube cube) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		float[] placed = placedMiddle(frame(), cube);
		Vec3 middle = world(object, placed[0], placed[1], placed[2]);
		double away = camera.position().distanceTo(middle);
		double half = Math.tan(Math.toRadians(camera.getFov()) / 2);
		double blocks = away * half * SPAN * sizing;
		return (float) (blocks * 16 / Math.max(0.01f, object.modelScale()));
	}

	/** Where a point of the model lands in the world, with the object's turn and scale. */
	public static Vec3 world(ModelObject object, double px, double py, double pz) {
		double scale = object.modelScale() / 16.0;
		// The same turn the renderer applies, and it is applied here as a rotation
		// about the world's Y rather than the entity's, because that is what a yaw is.
		double yaw = Math.toRadians(object.getYRot());
		double cos = Math.cos(yaw);
		double sin = Math.sin(yaw);
		double x = px * scale;
		double y = py * scale;
		double z = pz * scale;
		return object.position().add(x * cos - z * sin, y, x * sin + z * cos);
	}

	// ------------------------------------------------------------- on the display

	/**
	 * Where a point of the world lands on the display, or null when it is behind.
	 *
	 * Behind matters: a point behind the camera projects to a perfectly plausible
	 * position on screen, mirrored, and a handle that answers to a click in the
	 * wrong half of the display is worse than one that does not answer at all.
	 *
	 * Read from the camera the frame was drawn with — its own basis and its own
	 * field of view, not the setting the field of view is calculated from. The
	 * game widens it for speed and narrows it for a spyglass, and arithmetic about
	 * where an arrow ought to be is no use when the picture disagrees.
	 */
	public static double[] onScreen(Vec3 point) {
		Minecraft client = Minecraft.getInstance();
		Camera camera = client.gameRenderer.mainCamera();
		Vec3 towards = point.subtract(camera.position());

		Vector3fc forward = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();

		double ahead = towards.x * forward.x() + towards.y * forward.y() + towards.z * forward.z();
		if (ahead <= 0.05) return null;

		// Right is left negated. The camera hands out a left because that is the
		// vector the view matrix is built from; everything to do with a mouse wants
		// the other one.
		double across = -(towards.x * left.x() + towards.y * left.y() + towards.z * left.z());
		double above = towards.x * up.x() + towards.y * up.y() + towards.z * up.z();

		var window = client.getWindow();
		double wide = window.getGuiScaledWidth();
		double tall = window.getGuiScaledHeight();
		if (wide <= 0 || tall <= 0) return null;

		// The frame's shape, not the interface's: the interface is measured in whole
		// scaled units and rounds down, so at most scales the two differ a little,
		// and the error grows with the distance from the middle of the screen.
		double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
		double half = Math.tan(Math.toRadians(camera.getFov()) / 2);

		double ndcX = (across / ahead) / (half * aspect);
		double ndcY = (above / ahead) / half;
		return new double[] { (ndcX + 1) / 2 * wide, (1 - ndcY) / 2 * tall };
	}

	/** Which handle the mouse is on, or null. Mouse in display coordinates. */
	public static Axis grabbed(double mouseX, double mouseY) {
		ModelObject object = subject();
		if (object == null) return null;

		Axis closest = null;
		double nearest = GRAB;
		for (Arm arm : arms()) {
			double[] from = onScreen(world(object, arm.start()[0], arm.start()[1], arm.start()[2]));
			float[] tip = arm.tip();
			double[] to = onScreen(world(object, tip[0], tip[1], tip[2]));
			if (from == null || to == null) continue;

			// To the whole shaft, not to its point. Testing the tip alone is what made
			// all but a few pixels of what was drawn do nothing when clicked.
			double away = toSegment(mouseX, mouseY, from, to);
			if (away < nearest) {
				nearest = away;
				closest = arm.axis();
			}
		}
		for (Ring ring : rings()) {
			double away = toRing(object, ring, mouseX, mouseY);
			if (away < nearest) {
				nearest = away;
				closest = ring.axis();
			}
		}
		return closest;
	}

	/**
	 * How far the mouse is from a ring, on the display.
	 *
	 * The circle is walked as a two-dozen-sided polygon and the mouse is measured
	 * against each piece. Projecting the circle properly would give an ellipse and
	 * an equation to solve; walking it gives the same answer to within a pixel, and
	 * it keeps working when part of the ring is behind the camera, which is a case
	 * the ellipse does not have.
	 */
	private static double toRing(ModelObject object, Ring ring, double mouseX, double mouseY) {
		double nearest = Double.MAX_VALUE;
		double[] previous = null;
		double[] first = null;
		for (int step = 0; step <= ROUND; step++) {
			float[] point = onRing(ring, step * 2 * Math.PI / ROUND);
			double[] here = onScreen(world(object, point[0], point[1], point[2]));
			if (step == 0) first = here;
			if (previous != null && here != null) {
				nearest = Math.min(nearest, toSegment(mouseX, mouseY, previous, here));
			}
			previous = here;
		}
		if (previous != null && first != null) {
			nearest = Math.min(nearest, toSegment(mouseX, mouseY, previous, first));
		}
		return nearest;
	}

	/** A point on a ring, in model pixels: the circle drawn in the plane its axis is normal to. */
	public static float[] onRing(Ring ring, double angle) {
		float across = (float) (Math.cos(angle) * ring.radius());
		float up = (float) (Math.sin(angle) * ring.radius());
		float[] point = new float[3];
		for (int i = 0; i < 3; i++) {
			point[i] = ring.centre()[i] + ring.across()[i] * across + ring.up()[i] * up;
		}
		return point;
	}

	/** How far a point is from a segment, both on the display. */
	private static double toSegment(double x, double y, double[] from, double[] to) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared < 1e-6) return Math.hypot(x - from[0], y - from[1]);

		double t = ((x - from[0]) * dx + (y - from[1]) * dy) / lengthSquared;
		t = Math.max(0, Math.min(1, t));
		return Math.hypot(x - (from[0] + dx * t), y - (from[1] + dy * t));
	}

	// ------------------------------------------------------------------ the drag

	/** Takes hold of whatever handle is under the mouse. True when one was there. */
	public static boolean grab(double mouseX, double mouseY) {
		Axis axis = grabbed(mouseX, mouseY);
		Cube cube = Modelling.cube();
		ModelObject object = subject();
		if (axis == null || cube == null || object == null) return false;

		for (Arm arm : arms()) {
			if (arm.axis() != axis) continue;
			double[] from = onScreen(world(object, arm.start()[0], arm.start()[1], arm.start()[2]));
			float[] tip = arm.tip();
			double[] to = onScreen(world(object, tip[0], tip[1], tip[2]));
			if (from == null || to == null) return false;

			double dx = to[0] - from[0];
			double dy = to[1] - from[1];
			double onScreenLength = Math.hypot(dx, dy);
			// Under a couple of pixels for the whole arm means it is pointing at the
			// camera. There is no direction left to measure against, so refuse rather
			// than divide by something near zero and send the box to the horizon.
			if (onScreenLength < 2) return false;

			java.util.List<Modelling.Chosen> where = Modelling.markedBoxes();
			java.util.List<Cube> starts = startsOf(where, false);
			// How far the whole selection reaches along this axis, and which end of it
			// stays put. Resizing a group is a scale about the far side, not the same
			// nudge applied to every box in it — see the note on Grip.
			float[] reach = reachOf(starts, axis);
			grip = new Grip(tool, axis, arm.sign(), where, starts,
				mouseX, mouseY,
				dx / onScreenLength, dy / onScreenLength, onScreenLength / arm.length(), 0, 0,
				arm.sign() > 0 ? reach[0] : reach[1], reach[1] - reach[0]);
			return true;
		}

		for (Ring ring : rings()) {
			if (ring.axis() != axis) continue;
			double[] middle = onScreen(world(object,
				ring.centre()[0], ring.centre()[1], ring.centre()[2]));
			if (middle == null) return false;
			// Too close to the middle and the angle the mouse is at is noise; a hand
			// that shakes by a pixel would spin the box.
			if (Math.hypot(mouseX - middle[0], mouseY - middle[1]) < 4) return false;

			// Which way the ring's own axis faces, so a turn clockwise on the screen
			// turns the box clockwise as seen. The axis is the two directions the ring
			// spans, crossed — the box may be turned, so its name is not enough.
			float[] across = ring.across();
			float[] up = ring.up();
			float[] normal = {
				across[1] * up[2] - across[2] * up[1],
				across[2] * up[0] - across[0] * up[2],
				across[0] * up[1] - across[1] * up[0] };

			java.util.List<Modelling.Chosen> where = Modelling.markedBoxes();
			grip = new Grip(Tool.ROTATE, axis, towards(normal, object, ring.centre()),
				where, startsOf(where, true),
				mouseX, mouseY, 0, 0, 0, middle[0], middle[1], 0, 0);
			spun = 0;
			lastAngle = grip.angleAt(mouseX, mouseY);
			return true;
		}
		return false;
	}

	/**
	 * The boxes as they were when the button went down.
	 *
	 * Everything marked, not only the box the handles are on. A drag of a handle is
	 * a drag of the selection — the walls of a room are picked out together and then
	 * moved together, and having to do the same drag four times because only the
	 * last row clicked answers is the thing that makes multiple selection pointless.
	 *
	 * A pivot is written in on the way past when the drag is a turn, and it is the
	 * <em>same</em> point for every box: the middle of the whole selection. A box
	 * turns about its own pivot, so giving them all one pivot and one angle turns
	 * them as one rigid thing. Giving each its own middle instead turns each box
	 * where it stands, which takes a cone apart into two hundred separately spinning
	 * bricks — a correct reading of "turn every selected box" that nobody means.
	 *
	 * One box on its own still turns about itself, because for one box those two
	 * answers are the same point.
	 */
	private static java.util.List<Cube> startsOf(java.util.List<Modelling.Chosen> where,
			boolean turning) {
		float[] about = turning ? about(where) : null;
		java.util.List<Cube> found = new java.util.ArrayList<>();
		for (Modelling.Chosen one : where) {
			Cube box = Modelling.cubeAt(one);
			if (box == null) continue;
			if (about != null) box = box.pivotedAt(about[0], about[1], about[2]);
			found.add(box);
		}
		return found;
	}

	/** How far a set of boxes reaches along one axis: the low end and the high one. */
	private static float[] reachOf(java.util.List<Cube> boxes, Axis axis) {
		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (Cube box : boxes) {
			low = Math.min(low, switch (axis) {
				case X -> box.fromX();
				case Y -> box.fromY();
				case Z -> box.fromZ();
			});
			high = Math.max(high, switch (axis) {
				case X -> box.toX();
				case Y -> box.toY();
				case Z -> box.toZ();
			});
		}
		return boxes.isEmpty() ? new float[] { 0, 0 } : new float[] { low, high };
	}

	/** What a selection turns about: its own middle, or one box's pivot when it is alone. */
	private static float[] about(java.util.List<Modelling.Chosen> where) {
		if (where.size() == 1) {
			Cube only = Modelling.cubeAt(where.get(0));
			if (only != null) return turnsAbout(only);
		}
		float[] middle = Modelling.middleOfSelection();
		return middle == null ? new float[3] : middle;
	}

	/**
	 * How far the box has been turned since the button went down, in degrees.
	 *
	 * Added up a step at a time so a drag can go round more than once, and signed by
	 * which way the axis faces: a ring turned clockwise on the screen should turn
	 * the box clockwise as seen, whether the axis it belongs to points at you or
	 * away. The screen's Y counts downwards, which is where the other sign comes
	 * from.
	 */
	public static float turned(double mouseX, double mouseY) {
		if (grip == null || grip.tool() != Tool.ROTATE) return 0;
		double now = grip.angleAt(mouseX, mouseY);
		double step = now - lastAngle;
		while (step > 180) step -= 360;
		while (step < -180) step += 360;
		lastAngle = now;
		spun += step;
		return (float) (-spun * grip.sign());
	}

	public static Grip grip() {
		return grip;
	}

	public static void release() {
		grip = null;
		spun = 0;
	}

	// -------------------------------------------------------------- what is lit

	public static void hover(Axis axis) {
		hovered = axis;
	}

	/** The arm to light up: the one being pulled, or the one under the mouse. */
	public static Axis hovered() {
		return grip != null ? grip.axis() : hovered;
	}

	/**
	 * Whether the arrows should be wearing their resize heads.
	 *
	 * A cube instead of a point, which is the shape every editor uses for a scale
	 * handle. The head is how the gizmo says which tool is armed before anything is
	 * pulled — the alternative is three identical sets of arrows and a mode you have
	 * to remember you are in.
	 */
	public static boolean resizing() {
		return (grip != null ? grip.tool() : tool) == Tool.RESIZE;
	}
}
