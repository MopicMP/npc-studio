package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.client.model.Gizmo;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.entity.SceneCamera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the scene's camera looks like from outside it.
 *
 * <h2>Why anything has to be drawn at all</h2>
 *
 * Because the camera has no body. It is an entity so that clicking, the arrows
 * and the turning ring work without being taught a second kind of subject — see
 * {@link SceneCamera} — and an entity with no renderer is a perfectly good
 * invisible point. Which is what it was: a thing in the world that could be
 * selected only by finding its name in a list, and that could not be aimed
 * because there was nothing on screen saying where it pointed.
 *
 * <h2>A box and a cone, and what each of them is for</h2>
 *
 * The box is the camera's own bounding box, drawn from the same numbers a click
 * is tested against. That is deliberate and it is the rule the bone outlines
 * follow: if the drawing is in the wrong place then the picking is wrong in
 * exactly the same way, and there is nothing to reconcile.
 *
 * The cone is the shot. Four lines out to the corners of a rectangle and the
 * rectangle closed, at the camera's own angle and the frame's own shape — so
 * pointing it is done by looking at where the cone lands rather than by reading
 * two numbers and imagining them.
 *
 * <h2>What the cone does not say</h2>
 *
 * How far the shot reaches, because a shot does not reach a distance — it reaches
 * whatever is in front of it. The rectangle is put at a distance chosen to keep
 * the cone the same size on the display however far away the camera is, exactly
 * as the handles are sized, because a marker you cannot find across a scene is a
 * marker that does not exist. What is actually in frame is answered by looking
 * through it, which is a button.
 */
public final class CameraMarker {

	/** How long a handle is, as a share of half the frame's height. The handles' own. */
	private static final double SPAN = 0.17;

	/**
	 * How tall the far end of the cone is drawn, in handles.
	 *
	 * <h2>The mistake this replaces</h2>
	 *
	 * The <em>length</em> was sized like a handle and made three times longer,
	 * which sounds modest and is not: the far rectangle's height is the length
	 * times the tangent of half the lens, so at an ordinary seventy degrees a cone
	 * three handles long ends in a rectangle two handles tall and nearly four
	 * wide. That covered most of the display. Nothing about the sum was wrong —
	 * the wrong thing was sizing the one number that does not decide how big the
	 * marker looks.
	 *
	 * So the rectangle is what is sized, and the length follows from it. Seven
	 * tenths of a handle each way is a marker that reads at a glance and sits in
	 * the same family as the arrows beside it.
	 */
	private static final double FRAME = 0.7;

	/**
	 * How long the cone may be, in handles.
	 *
	 * Because the length is worked out backwards from a fixed frame, an extreme
	 * lens sends it to an extreme: a ten-degree telephoto wants a cone eight
	 * handles long, and a hundred-and-sixty-degree fisheye wants one an eighth of
	 * a handle, which is a rectangle with no cone behind it at all. Clamped, both
	 * stay recognisably a camera and the shape still says which lens it is.
	 */
	private static final double SHORTEST = 0.35;
	private static final double LONGEST = 2.5;

	private static final int CHOSEN = 0xFF4FC3F7;
	private static final int RESTING = 0xFFB39DDB;
	private static final int SHOT = 0xFFFFD54A;

	private CameraMarker() { }

	/**
	 * Which way the camera faces, as the three directions a frame is built from.
	 *
	 * Minecraft's own convention and it is worth writing out, because half of it
	 * is counter-intuitive: yaw nought faces south, which is {@code +Z}, and a
	 * yaw of ninety faces west, which is {@code -X}. So somebody facing south has
	 * east on their <em>left</em>, and the right-hand vector at yaw nought is
	 * {@code -X} rather than {@code +X}. Getting that backwards draws a cone that
	 * is correct in every still and mirrored the moment the camera turns.
	 *
	 * @param eye     where the picture is taken from
	 * @param forward where it points
	 * @param right   the frame's own right, with no roll
	 * @param up      the frame's own up
	 */
	public record Aim(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up) { }

	public static Aim aimOf(Vec3 eye, float yaw, float pitch) {
		double yawR = Math.toRadians(yaw);
		double pitchR = Math.toRadians(pitch);
		double cosPitch = Math.cos(pitchR);

		Vec3 forward = new Vec3(
			-Math.sin(yawR) * cosPitch,
			-Math.sin(pitchR),
			Math.cos(yawR) * cosPitch);
		// Level whatever the pitch is: a camera has no roll, so its right stays in
		// the world's horizontal plane and only its up tips.
		Vec3 right = new Vec3(-Math.cos(yawR), 0, -Math.sin(yawR));
		Vec3 up = right.cross(forward);
		return new Aim(eye, forward, right, up);
	}

	/**
	 * The four corners of the shot at a distance, in the world.
	 *
	 * The vertical angle is the camera's own; the horizontal one comes out of the
	 * frame's shape rather than being a second setting, because it is not a second
	 * setting — a film in a wider window is a wider shot at the same lens, which is
	 * what the game itself does with its own field of view.
	 *
	 * Order round the rectangle, so that joining each to the next closes it: top
	 * left, top right, bottom right, bottom left.
	 */
	public static Vec3[] corners(Aim aim, float fov, double aspect, double away) {
		double halfUp = Math.tan(Math.toRadians(fov) / 2) * away;
		double halfAcross = halfUp * aspect;
		Vec3 middle = aim.eye().add(aim.forward().scale(away));
		Vec3 across = aim.right().scale(halfAcross);
		Vec3 above = aim.up().scale(halfUp);
		return new Vec3[] {
			middle.subtract(across).add(above),
			middle.add(across).add(above),
			middle.add(across).subtract(above),
			middle.subtract(across).subtract(above) };
	}

	/**
	 * How far out the far end of the cone goes, given how big a handle is here.
	 *
	 * Worked out backwards from how tall the rectangle should look rather than
	 * chosen directly, because the length is not what decides how big the marker
	 * is — the rectangle is, and it grows with the lens. A wide lens therefore
	 * gets a short stubby cone and a narrow one gets a long thin cone, which is
	 * the shape of the shot and is worth being able to see without reading the
	 * number.
	 *
	 * Taken apart from the drawing so the sum can be checked without a window.
	 *
	 * @param unit how long one handle is here, in blocks
	 * @param fov  the camera's own vertical angle, in degrees
	 */
	public static double reach(double unit, float fov) {
		double lens = Math.tan(Math.toRadians(Math.clamp(fov, 1f, 179f)) / 2);
		if (!(lens > 1e-6)) return unit * LONGEST;
		return Math.clamp(unit * FRAME / lens, unit * SHORTEST, unit * LONGEST);
	}

	// ------------------------------------------------------------------- drawing

	/**
	 * Whether the marker is showing.
	 *
	 * Not while the view is being taken through it. Standing inside a cone and
	 * drawing the cone gives four lines out of the corners of the screen and a
	 * rectangle round everything, which is not a camera — it is a scribble over the
	 * shot somebody has just asked to see.
	 */
	private static boolean showing() {
		if (!com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()) return false;
		return SceneCameras.camera() != null && !SceneCameras.through();
	}

	public static void draw(GuiGraphicsExtractor graphics, int originX, int originY) {
		if (!showing()) return;
		SceneCamera camera = SceneCameras.camera();
		Minecraft client = Minecraft.getInstance();

		boolean chosen = Workspace.selection() == camera;
		int body = chosen ? CHOSEN : RESTING;

		box(graphics, originX, originY, camera.getBoundingBox(), body);

		Vec3 eye = camera.position();
		var viewer = client.gameRenderer.mainCamera();
		// One handle, in blocks, at this distance: the length an arrow of the move
		// gizmo would be drawn. Everything about the marker is measured in these, so
		// it keeps the same size on the display however far off the camera is.
		double unit = viewer.position().distanceTo(eye)
			* Math.tan(Math.toRadians(viewer.getFov()) / 2) * SPAN * Gizmo.sizing();
		double away = reach(unit, camera.fov());
		if (!(away > 1e-6)) return;

		var window = client.getWindow();
		double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
		Aim aim = aimOf(eye, camera.getYRot(), camera.getXRot());
		Vec3[] corners = corners(aim, camera.fov(), aspect, away);

		int shot = chosen ? SHOT : body;
		for (int i = 0; i < corners.length; i++) {
			Wireframe.line(graphics, originX, originY, eye, corners[i], shot);
			Wireframe.line(graphics, originX, originY,
				corners[i], corners[(i + 1) % corners.length], shot);
		}

		// A gable over the top edge, which is the only thing on a rectangle that says
		// which way up it is. Without it a camera pitched over backwards and one
		// pitched forwards draw the same four lines.
		Vec3 middleTop = corners[0].add(corners[1]).scale(0.5);
		Vec3 peak = middleTop.add(aim.up().scale(
			corners[0].distanceTo(corners[3]) * 0.25));
		Wireframe.line(graphics, originX, originY, corners[0], peak, shot);
		Wireframe.line(graphics, originX, originY, corners[1], peak, shot);
	}

	/** The twelve edges of a box. */
	private static void box(GuiGraphicsExtractor graphics, int originX, int originY,
			AABB around, int colour) {
		Vec3[] at = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			at[i] = new Vec3(
				(i & 1) == 0 ? around.minX : around.maxX,
				(i & 2) == 0 ? around.minY : around.maxY,
				(i & 4) == 0 ? around.minZ : around.maxZ);
		}
		// Two corners share an edge when they differ in exactly one bit, which is the
		// whole of a cube's connectivity and shorter than listing twelve pairs.
		for (int from = 0; from < 8; from++) {
			for (int bit = 1; bit <= 4; bit <<= 1) {
				int to = from | bit;
				if (to != from) Wireframe.line(graphics, originX, originY, at[from], at[to], colour);
			}
		}
	}
}
