package com.mopicmp.npcstudio.client.workspace;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The camera while the workspace is open.
 *
 * An orbit rather than a body: there is a point it looks at, an angle it looks
 * from and a distance. Everything the mouse does moves one of those three. That
 * is the arrangement every model editor has landed on, and the reason is that
 * the thing being built stays in frame by construction — a free-flying camera
 * loses its subject the moment a hand slips, and finding it again is a chore
 * that has nothing to do with the work.
 *
 * The player's body stays where it was. It is not being driven and it is not
 * being followed; a screen is open, so nothing is walking anywhere.
 */
public final class WorkspaceCamera {

	/** Nearest and furthest the camera may sit from what it is looking at. */
	private static final double NEAR = 0.6;
	private static final double FAR = 64;

	private static boolean active;
	private static CameraType before;

	private static Vec3 focus = Vec3.ZERO;
	private static float yaw;
	private static float pitch = 12;
	private static double distance = 5;

	/** Where the eased move started, and how far along it is. */
	private static Vec3 fromFocus = Vec3.ZERO;
	private static double fromDistance = 5;
	private static float flown = 1;

	private WorkspaceCamera() { }

	public static boolean active() {
		return active;
	}

	/**
	 * Takes the camera, starting from where the player is standing.
	 *
	 * Third person for the length of the session, and not as a cosmetic choice:
	 * in first person the game skips drawing the player, and a camera that has
	 * flown across the room while the body is invisible makes the room look
	 * emptier than it is.
	 */
	public static void take() {
		take(true);
	}

	/**
	 * @param claimSubject whether to frame the nearest character and select it.
	 *                     False for a mode that has nothing to do with characters:
	 *                     the modelling window used to take hold of an NPC on the
	 *                     way in, which is a window quietly doing something about
	 *                     something it is not about.
	 */
	public static void take(boolean claimSubject) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		active = true;
		before = client.options.getCameraType();
		client.options.setCameraType(CameraType.THIRD_PERSON_BACK);

		// Facing the same way the player was, so the view does not jump on the way
		// in. Only the angle comes from the player, though — what the camera turns
		// around is the work, not the person doing it.
		yaw = client.player.getYRot();
		pitch = Math.max(0, client.player.getXRot());
		focus = client.player.getEyePosition();
		distance = 5;
		fromFocus = focus;
		fromDistance = distance;
		flown = 1;

		if (claimSubject) frame(subject(client));
	}

	/**
	 * What the camera should be looking at when the workspace opens.
	 *
	 * The selection if there is one — opening from a character's own settings
	 * means that character is what this session is about. Otherwise the nearest
	 * NPC, because a workspace opened while standing next to somebody was almost
	 * certainly opened about them. The player only wins when there is nobody at
	 * all, and then it is a starting point rather than a subject.
	 */
	private static Entity subject(Minecraft client) {
		Entity chosen = Workspace.selection();
		if (chosen != null) return chosen;
		if (client.level == null) return null;

		Entity nearest = null;
		double closest = Double.MAX_VALUE;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof com.mopicmp.npcstudio.entity.NpcEntity)) continue;
			double away = entity.distanceToSqr(client.player);
			if (away < closest) {
				closest = away;
				nearest = entity;
			}
		}
		// Selecting it too, so the panels on the right are about the same character
		// the camera is pointed at. A view of somebody whose settings are not the
		// ones on screen is worse than no view.
		if (nearest != null) Workspace.select(nearest.getId());
		return nearest;
	}

	public static void release() {
		if (!active) return;
		active = false;
		Minecraft client = Minecraft.getInstance();
		if (before != null) client.options.setCameraType(before);
		before = null;
	}

	// ------------------------------------------------------------- the moves

	public static void orbit(double acrossPixels, double downPixels) {
		yaw += (float) acrossPixels * 0.4f;
		// Not quite to the pole. At exactly ninety the sideways direction is
		// undefined and the view rolls over on itself as the last pixel is crossed.
		pitch = Mth.clamp(pitch + (float) downPixels * 0.4f, -89.5f, 89.5f);
	}

	/**
	 * Slides the point being looked at across the screen.
	 *
	 * Scaled by distance, so a nudge is a nudge whether the camera is on top of a
	 * face or looking at the whole ship. Panning at a fixed rate is what makes a
	 * zoomed-out view feel stuck and a zoomed-in one feel slippery.
	 */
	public static void pan(double acrossPixels, double downPixels) {
		double rate = distance * 0.0022;
		focus = focus.add(right().scale(-acrossPixels * rate)).add(up().scale(downPixels * rate));
		flown = 1;
		fromFocus = focus;
	}

	/**
	 * Turns the camera where it stands, instead of swinging it round the subject.
	 *
	 * The orbit is right for judging a build and wrong for everything else: it
	 * ties the camera to whatever it was last pointed at, so getting from one end
	 * of a ship to the other means orbiting something in between. This looks
	 * about, and because the camera keeps a point it is looking at, looking about
	 * means moving that point rather than moving the camera.
	 */
	public static void look(double acrossPixels, double downPixels) {
		Vec3 eye = position(1f);
		if (eye == null) return;
		yaw += (float) acrossPixels * 0.4f;
		pitch = Mth.clamp(pitch + (float) downPixels * 0.4f, -89.5f, 89.5f);
		settle(eye.add(forward().scale(distance)));
	}

	/**
	 * Flies, in the direction the camera is facing.
	 *
	 * Read from the keyboard directly rather than through the game's key
	 * bindings, because a screen is open and the game releases every binding
	 * while one is. Asking the window what is held down is the only thing that
	 * still answers.
	 */
	public static void fly(double ahead, double across, double up, double rate) {
		if (ahead == 0 && across == 0 && up == 0) return;
		Vec3 eye = position(1f);
		if (eye == null) return;
		Vec3 moved = eye
			.add(forward().scale(ahead * rate))
			.add(right().scale(across * rate))
			.add(0, up * rate, 0);
		settle(moved.add(forward().scale(distance)));
	}

	/** Puts the point being looked at somewhere, without an eased flight to it. */
	private static void settle(Vec3 at) {
		focus = at;
		fromFocus = at;
		fromDistance = distance;
		flown = 1;
	}

	public static void zoom(double notches) {
		distance = Mth.clamp(distance * Math.exp(-notches * 0.16), NEAR, FAR);
		flown = 1;
		fromDistance = distance;
	}

	/** Frames something: the eased move that F does in every editor. */
	public static void frame(Entity entity) {
		if (entity == null) return;
		AABB box = entity.getBoundingBox();
		fromFocus = focus;
		fromDistance = distance;
		focus = box.getCenter();
		distance = Mth.clamp(box.getSize() * 2.4, NEAR, FAR);
		flown = 0;
	}

	public static void tick() {
		if (flown < 1) flown = Math.min(1f, flown + 0.12f);
	}

	// ----------------------------------------------------------- what it sees

	/** The way the camera is looking, as a unit vector. */
	public static Vec3 forward() {
		double y = Math.toRadians(yaw);
		double p = Math.toRadians(pitch);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/**
	 * The camera's own right hand.
	 *
	 * Negated from the obvious form, and the sign is the whole of it. Minecraft's
	 * yaw has the look vector at {@code (-sin y, ·, cos y)}, so at yaw zero the
	 * camera faces south — and somebody facing south has west on their right, not
	 * east. Getting it backwards mirrored three separate things at once: D walked
	 * left, panning fought the mouse, and the pick ray came out reflected about
	 * the middle of the frame, which is why clicking a character near the centre
	 * worked and clicking one at the edge selected nothing.
	 */
	public static Vec3 right() {
		double y = Math.toRadians(yaw);
		return new Vec3(-Math.cos(y), 0, -Math.sin(y));
	}

	public static Vec3 up() {
		double y = Math.toRadians(yaw);
		double p = Math.toRadians(pitch);
		return new Vec3(-Math.sin(y) * Math.sin(p), Math.cos(p), Math.cos(y) * Math.sin(p));
	}

	public static Vec3 focus() {
		return focus;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	public static double distance() {
		return distance;
	}

	/** Where the camera is this frame, or null when it is not ours to place. */
	public static Vec3 position(float partial) {
		if (!active) return null;
		float t = ease(Math.min(1f, flown + partial * 0.12f));
		Vec3 at = fromFocus.lerp(focus, t);
		double away = Mth.lerp(t, fromDistance, distance);
		return at.subtract(forward().scale(away));
	}

	private static float ease(float t) {
		return t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
	}
}
