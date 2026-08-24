package com.mopicmp.npcstudio.client.scene;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.model.Gizmo;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.scene.Channels;
import com.mopicmp.npcstudio.scene.Key;
import com.mopicmp.npcstudio.scene.Role;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Rings on a character's bone, to turn it by hand.
 *
 * <h2>Why this is not the modelling gizmo</h2>
 *
 * The modelling gizmo is written in terms of two things and neither is a bone.
 * Its frame is a placed {@code ModelObject} — where a point of a model lands in
 * the world, which way an axis points once the object is turned. Its grip holds
 * a list of boxes of a model document, because boxes are what it edits. Making
 * those two generic is the right change and it runs through the one part of this
 * mod that took longest to get right; half-done, it leaves the modelling window
 * subtly broken, which is worse than a second small class.
 *
 * So the hard part is shared and the rest is not: {@link Gizmo#onScreen} does
 * the projection — the camera's own basis, its own field of view, and the
 * behind-the-camera case that a naive projection gets plausibly and completely
 * wrong — and everything here is the two dozen lines that are actually about
 * bones.
 *
 * <h2>Where a bone is, and how that was worked out</h2>
 *
 * Not from remembered numbers. The humanoid model was reworked in this version —
 * a mesh in the jar has a waist and an inner body and offsets that do not match
 * any earlier one — so a rest pose written down from memory would put these rings
 * somewhere near the character and be impossible to argue with afterwards.
 *
 * The pivots are read from the game's own baked player layer, so they are
 * whatever this version says they are. The transform from there to the world was
 * read out of {@code LivingEntityRenderer} rather than recalled: it turns by
 * {@code 180 - bodyRot} about Y, scales by {@code -1, -1, 1}, and translates
 * down by {@code 1.501} — which composes to
 * {@code world = position + rotY(180 - bodyRot) · (-x/16, 1.501 - y/16, z/16)}.
 * Checked against three heights it has to produce and does: the neck at 1.501,
 * the shoulders at 1.376, the hips at 0.751.
 *
 * <h2>What is still approximate</h2>
 *
 * The rest pose, not the posed one. A character whose build has been widened, or
 * whose arm is already raised, has that bone somewhere other than where its ring
 * is drawn. The ring still turns the right bone by the right amount — it is where
 * the ring sits that drifts, and only for a character that has been built away
 * from the ordinary. Following the posed position means capturing the bone's
 * matrix during rendering, which is a hook rather than a calculation, and it goes
 * in when there is a build to see it wrong on.
 */
public final class BoneHandles {

	/** How big a ring is, as a share of half the frame's height. The gizmo's own. */
	private static final double SPAN = 0.17;

	/** How near the mouse must come, in interface pixels. The gizmo's own. */
	private static final double GRAB = 9;

	/** How many straight pieces a ring is walked as. */
	private static final int ROUND = 32;

	private static final int[] COLOUR = { 0xFFFF5555, 0xFF55DD66, 0xFF5599FF };
	private static final int LIT = 0xFFFFD54A;

	/** Which bone is being posed. Empty means the handles are not showing. */
	private static String bone = "";

	private BoneHandles() { }

	public static String bone() {
		return bone;
	}

	public static void pose(String named) {
		if (holding()) return;
		bone = named == null ? "" : named;
	}

	// ------------------------------------------------------------- where a bone is

	/**
	 * Where a bone is, in the model's own pixels.
	 *
	 * Where it was last drawn if it has been drawn, which is the honest answer: a
	 * character built broader, or mid-stride, or with an arm already raised has its
	 * shoulder somewhere other than where a default character standing still would.
	 * The rest pose is the fallback and only reaches on the first frame after a
	 * scene opens, when the answer is where the bone is about to be anyway.
	 */
	private static float[] pivotOf(String role, String named) {
		float[] drawn = Posing.placeOf(role, named);
		return drawn != null ? drawn : Posing.restOf(named);
	}

	/**
	 * Where a point of the model lands in the world.
	 *
	 * Through {@link BonePicking}, which is the one place the composition is
	 * written down — it used to be written here as well, minus the character's own
	 * size, which is exactly how the rings came to sit on a resized character as
	 * though nobody had resized it.
	 */
	private static Vec3 world(Placed placed, float px, float py, float pz) {
		return BonePicking.intoWorld(placed,
			new Vec3(px / 16.0, py / 16.0, pz / 16.0));
	}

	/** A direction of the model, in the world. The same turn, without the offsets. */
	private static Vec3 direction(float yaw, double dx, double dy, double dz) {
		return turned(-dx, -dy, dz, 180 - yaw).normalize();
	}

	private static Vec3 turned(double x, double y, double z, double degrees) {
		double angle = Math.toRadians(degrees);
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return new Vec3(x * cos + z * sin, y, -x * sin + z * cos);
	}

	// ------------------------------------------------------------------ the rings

	/** One ring, in the world: what it turns about and the plane it lies in. */
	private record Hoop(int axis, Vec3 centre, Vec3 spin, Vec3 across, Vec3 up, double radius) { }

	/**
	 * The three rings, or nothing when there is nothing to turn.
	 *
	 * Each is drawn about one of the model's own axes expressed in the world, which
	 * is what makes the sum come out simple later: a turn about the ring's axis is
	 * exactly a change to that bone's own Euler number, with no conversion and no
	 * sign to work out twice.
	 */
	private static List<Hoop> hoops() {
		if (bone.isEmpty()) return List.of();
		// Only while the workspace is the thing on screen. These are an instrument
		// rather than a property of the character, and the modelling window is a
		// different screen with its own handles — two sets of rings answering to one
		// mouse is the sort of thing nobody can debug from a screenshot.
		if (!com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()) return List.of();
		Entity who = Workspace.selection();
		Scene scene = Playing.scene();
		if (who == null || scene == null) return List.of();
		String role = roleOf(scene, who);
		if (role.isEmpty()) return List.of();

		Placed placed = Placed.drawn(who);
		float yaw = placed.yaw();

		// The bone's own origin, taken through the very matrix the outline and the
		// picking use. It used to be the part's own offset alone, which leaves out
		// the root and every rotation above it — near enough on a character standing
		// still and visibly adrift on one that is not.
		Vec3 centre;
		Posing.Shape shape = Posing.shapeOf(role, bone);
		if (shape != null) {
			var origin = shape.place().transformPosition(new org.joml.Vector3f());
			centre = BonePicking.intoWorld(placed, new Vec3(origin.x, origin.y, origin.z));
		} else {
			float[] pivot = pivotOf(role, bone);
			if (pivot == null) return List.of();
			centre = world(placed, pivot[0], pivot[1], pivot[2]);
		}

		var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		double away = camera.position().distanceTo(centre);
		double radius = away * Math.tan(Math.toRadians(camera.getFov()) / 2) * SPAN * Gizmo.sizing();
		if (radius <= 0) return List.of();

		// The bone's own axes rather than the character's. A part turns as
		// Rz · Ry · Rx, so only the Z ring lies on a raw axis; the other two lie
		// where the turns before them have put them. See {@link Posing#axesOf}.
		Vec3[] axes = new Vec3[3];
		for (int i = 0; i < 3; i++) {
			if (shape != null) {
				float[] own = shape.axes();
				axes[i] = direction(yaw, own[i * 3], own[i * 3 + 1], own[i * 3 + 2]);
			} else {
				axes[i] = direction(yaw, i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0);
			}
		}

		List<Hoop> found = new ArrayList<>(3);
		for (int axis = 0; axis < 3; axis++) {
			found.add(new Hoop(axis, centre, axes[axis],
				axes[(axis + 1) % 3], axes[(axis + 2) % 3], radius));
		}
		return found;
	}

	private static Vec3 pointOn(Hoop hoop, double angle) {
		return hoop.centre()
			.add(hoop.across().scale(Math.cos(angle) * hoop.radius()))
			.add(hoop.up().scale(Math.sin(angle) * hoop.radius()));
	}

	private static String roleOf(Scene scene, Entity who) {
		String id = who.getUUID().toString();
		for (Role role : scene.cast()) {
			if (role.bound().equals(id)) return role.name();
		}
		return "";
	}

	/**
	 * What the workspace's selection is called in the open scene, or empty.
	 *
	 * Public because everything that wants to touch a bone needs it and the lookup
	 * is one loop over a handful of names — copied into three places it would be
	 * three chances to disagree about who is being posed.
	 */
	public static String role() {
		Scene scene = Playing.scene();
		Entity who = Workspace.selection();
		return scene == null || who == null ? "" : roleOf(scene, who);
	}

	// ------------------------------------------------------------------- the drag

	private static int held = -1;

	/**
	 * Where the ring's middle was on the screen when the button went down.
	 *
	 * <h2>Why it is frozen and not asked for again</h2>
	 *
	 * Because the middle moves with what is being turned. That was harmless while a
	 * ring sat on a pivot nothing could shift; it stopped being harmless the moment
	 * the torso learned to lean, because leaning moves the torso's own pivot to the
	 * hips — so every small drag moved the point the next small drag was measured
	 * from, and each fed the other. That is a feedback loop, and what it looks like
	 * is a rotation that jerks.
	 *
	 * The same lesson the modelling gizmo learned about its arms, in the same words,
	 * for the same reason. Freezing it also means a ring cannot escape the hand
	 * holding it: the angle is read against where the ring <em>was</em>, which is
	 * where the hand started.
	 */
	private static double heldX;
	private static double heldY;

	private static double lastAngle;
	private static double spun;
	private static float began;
	private static float sign;
	private static int hovered = -1;

	public static boolean holding() {
		return held >= 0;
	}

	public static int hovered() {
		return held >= 0 ? held : hovered;
	}

	/** Notices which ring the mouse is over. Display coordinates. */
	public static void hover(double mouseX, double mouseY) {
		hovered = held >= 0 ? held : nearest(mouseX, mouseY);
	}

	private static int nearest(double mouseX, double mouseY) {
		int closest = -1;
		double best = GRAB;
		for (Hoop hoop : hoops()) {
			double away = toHoop(hoop, mouseX, mouseY);
			if (away < best) {
				best = away;
				closest = hoop.axis();
			}
		}
		return closest;
	}

	/**
	 * How far the mouse is from a ring, on the display.
	 *
	 * Walked as a many-sided polygon rather than solved as the ellipse it projects
	 * to. The same choice the modelling gizmo made and for the same reason: the
	 * ellipse has no answer for a ring half of which is behind the camera, and this
	 * one does.
	 */
	private static double toHoop(Hoop hoop, double mouseX, double mouseY) {
		double nearest = Double.MAX_VALUE;
		double[] first = null;
		double[] previous = null;
		for (int step = 0; step <= ROUND; step++) {
			double[] here = Gizmo.onScreen(pointOn(hoop, step * 2 * Math.PI / ROUND));
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

	private static double toSegment(double x, double y, double[] from, double[] to) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared < 1e-6) return Math.hypot(x - from[0], y - from[1]);
		double t = Math.clamp(((x - from[0]) * dx + (y - from[1]) * dy) / lengthSquared, 0, 1);
		return Math.hypot(x - (from[0] + dx * t), y - (from[1] + dy * t));
	}

	/**
	 * Takes hold of a ring. True when one was under the mouse.
	 *
	 * The angle it started at and the value the bone already had are both frozen
	 * here, for the reason the modelling gizmo froze its own: measuring each small
	 * step against something that is itself moving is a feedback loop, and it shows
	 * up as the bone creeping during a long turn.
	 */
	public static boolean grab(double mouseX, double mouseY) {
		int axis = nearest(mouseX, mouseY);
		if (axis < 0) return false;

		for (Hoop hoop : hoops()) {
			if (hoop.axis() != axis) continue;
			double[] middle = Gizmo.onScreen(hoop.centre());
			if (middle == null) return false;
			// Too near the middle and the angle is noise: a hand that shakes by a
			// pixel would spin the bone.
			if (Math.hypot(mouseX - middle[0], mouseY - middle[1]) < 4) return false;

			held = axis;
			spun = 0;
			heldX = middle[0];
			heldY = middle[1];
			lastAngle = Math.toDegrees(Math.atan2(mouseY - middle[1], mouseX - middle[0]));
			began = valueNow(axis);
			// Which way the ring's own axis faces, so a turn clockwise on the screen
			// turns the bone clockwise as seen whichever end is towards the camera.
			Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
			sign = hoop.spin().dot(eye.subtract(hoop.centre())) >= 0 ? 1 : -1;
			return true;
		}
		return false;
	}

	/**
	 * Turns the bone by however far the mouse has gone round.
	 *
	 * Added up a step at a time rather than read off the angle now, because the
	 * angle now wraps: drag past the top of the ring and a straight subtraction
	 * says the bone spun the other way. Small steps are unambiguous, and adding
	 * them lets a turn go round as many times as somebody has patience for — which
	 * is the wheel, and the reason the format keeps angles continuous at all.
	 */
	public static void drag(double mouseX, double mouseY) {
		if (held < 0) return;

		// Against where the ring was, not where it is. See {@link #heldX}.
		double now = Math.toDegrees(Math.atan2(mouseY - heldY, mouseX - heldX));
		double step = now - lastAngle;
		while (step > 180) step -= 360;
		while (step < -180) step += 360;
		lastAngle = now;
		spun += step;
		put(held, began + (float) (-spun * sign));
	}

	public static void release() {
		held = -1;
		spun = 0;
	}

	private static String channelOf(int axis) {
		return Channels.of(bone, switch (axis) {
			case 0 -> Channels.TURN_X;
			case 1 -> Channels.TURN_Y;
			default -> Channels.TURN_Z;
		});
	}

	private static float valueNow(int axis) {
		Scene scene = Playing.scene();
		Entity who = Workspace.selection();
		if (scene == null || who == null) return 0;
		String role = roleOf(scene, who);
		return role.isEmpty() ? 0
			: scene.valueAt(role, channelOf(axis), Playing.head().at(), 0);
	}

	private static void put(int axis, float degrees) {
		Scene scene = Playing.scene();
		Entity who = Workspace.selection();
		if (scene == null || who == null) return;
		String role = roleOf(scene, who);
		if (role.isEmpty()) return;
		Scenes.keep(Playing.openName(), scene.keyed(role, channelOf(axis),
			Key.at(Playing.head().tick(), degrees)));
	}

	// ---------------------------------------------------------------- the drawing

	/**
	 * The rings, drawn over the world rather than in it.
	 *
	 * Over, so that a handle inside a character is still a handle. That was learned
	 * on the modelling arrows, which spent a while disappearing into whatever they
	 * were attached to.
	 */
	public static void draw(GuiGraphicsExtractor graphics, int originX, int originY) {
		int lit = hovered();
		for (Hoop hoop : hoops()) {
			int colour = hoop.axis() == lit ? LIT : COLOUR[hoop.axis()];
			double[] previous = null;
			double[] first = null;
			for (int step = 0; step <= ROUND; step++) {
				double[] here = at(pointOn(hoop, step * 2 * Math.PI / ROUND), originX, originY);
				if (step == 0) first = here;
				if (previous != null && here != null) segment(graphics, previous, here, colour);
				previous = here;
			}
			if (previous != null && first != null) segment(graphics, previous, first, colour);
		}
	}

	/**
	 * The outline of one bone, so that what a click will choose is visible first.
	 *
	 * Twelve edges of the box the ray is actually tested against, drawn from the
	 * same numbers — so if the outline is in the wrong place the picking is wrong
	 * in exactly the same way, and there is nothing to reconcile. That is the same
	 * rule the modelling handles follow, learned when what was drawn and what
	 * answered a click came apart twice.
	 */
	public static void outline(GuiGraphicsExtractor graphics, int originX, int originY,
			Entity who, String role, String named, int colour) {
		if (named == null || named.isEmpty()) return;
		// One outline per band. A folded limb is several, and drawn as one they would
		// describe a box the arm is no longer inside.
		for (Vec3[] corners : BonePicking.corners(who, role, named)) {
			for (int[] edge : BonePicking.EDGES) {
				double[] from = at(corners[edge[0]], originX, originY);
				double[] to = at(corners[edge[1]], originX, originY);
				if (from != null && to != null) segment(graphics, from, to, colour);
			}
		}
	}

	/** The colour an outline is drawn in: what a click would take, and what it has. */
	public static final int UNDER_MOUSE = 0xFFFFD54A;
	public static final int CHOSEN = 0xFF4FC3F7;

	private static double[] at(Vec3 point, int originX, int originY) {
		return Wireframe.at(point, originX, originY);
	}

	/** One piece of a ring: a dark line with a bright one on top, so it reads over anything. */
	private static void segment(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour) {
		Wireframe.segment(graphics, from, to, colour);
	}
}
