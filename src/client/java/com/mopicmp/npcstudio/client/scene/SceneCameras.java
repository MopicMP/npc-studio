package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.entity.SceneCamera;
import com.mopicmp.npcstudio.scene.Channels;
import com.mopicmp.npcstudio.scene.Key;
import com.mopicmp.npcstudio.scene.Role;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The one camera an open scene has, kept in step with the document.
 *
 * <h2>Two things that look like one</h2>
 *
 * There is a part in the scene called the camera and there is a thing in the
 * world you can click on. They are not the same object and neither is a copy of
 * the other: the part holds the numbers and is what gets saved, and the thing in
 * the world exists so that the arrows, the ring and the click already work.
 *
 * The document is always right. Every tick the thing in the world is put where
 * the part says it is; every drag writes back into the part. There is no state
 * in between the two that could disagree, which is the only arrangement that
 * survives somebody scrubbing the timeline while holding an arrow.
 *
 * <h2>Why looking through it is not a second render</h2>
 *
 * Because it does not have to be. A camera is a place and a direction, and the
 * game already draws the world from a place and a direction — so "look through
 * the camera" is the same picture, not another one. What it costs is that you
 * are then looking through it and no longer at it, which is the trade every 3D
 * editor makes for the same reason.
 *
 * A second view in its own little window is a second render of the world, and
 * that is a much larger thing: see {@code docs/deferred.md}.
 */
public final class SceneCameras {

	private SceneCameras() { }

	/** The one in this client's level, or null. */
	private static SceneCamera standing;

	/** Whether the view is being taken through it. */
	private static boolean through;

	public static SceneCamera camera() {
		return standing;
	}

	public static boolean through() {
		return through && standing != null;
	}

	public static void through(boolean on) {
		through = on;
	}

	/** The scene's camera part, or null when the scene has none. */
	public static Role part() {
		Scene scene = Playing.scene();
		if (scene == null) return null;
		for (Role role : scene.cast()) {
			if (role.kind() == Role.Kind.CAMERA) return role;
		}
		return null;
	}

	/** What the camera's part is called. One per scene, so one name will do. */
	public static final String NAME = "камера";

	/**
	 * Puts one in the world, and points the part at it.
	 *
	 * The part is bound to the entity rather than the other way round because the
	 * binding is how everything else finds it: the pose panel, the handles and the
	 * timeline all go from a selected entity to a part by its id.
	 *
	 * It starts where the view is, which is the only starting point that is never
	 * wrong: somebody who presses this has just been looking at the thing they want
	 * to film. A camera dropped at the origin would be a camera nobody can find.
	 */
	public static void add() {
		Scene scene = Playing.scene();
		Minecraft client = Minecraft.getInstance();
		if (scene == null || client.level == null || part() != null) return;

		Vec3 at = viewpoint();
		Scenes.keep(Playing.openName(), scene
			.with(Role.of(NAME, Role.Kind.CAMERA))
			.keyed(NAME, Channels.X, Key.at(0, (float) at.x))
			.keyed(NAME, Channels.Y, Key.at(0, (float) at.y))
			.keyed(NAME, Channels.Z, Key.at(0, (float) at.z))
			.keyed(NAME, Channels.YAW,
				Key.at(0, com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.yaw()))
			.keyed(NAME, Channels.PITCH,
				Key.at(0, com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.pitch())));
	}

	/** Takes the part out, which takes the camera with it. */
	public static void remove() {
		Scene scene = Playing.scene();
		Role part = part();
		if (scene == null || part == null) return;
		through = false;
		Scenes.keep(Playing.openName(), scene.withoutRole(part.name()));
	}

	/**
	 * Where the workspace's own view is, which is not the frame's.
	 *
	 * Asked of the workspace camera rather than of the frame, because while
	 * somebody is looking through the scene's camera the frame <em>is</em> the
	 * scene's camera — so "put it where I am standing" would put it where it
	 * already is and appear to do nothing.
	 */
	private static Vec3 viewpoint() {
		Vec3 flown = com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.position(1f);
		return flown != null ? flown : Minecraft.getInstance().gameRenderer.mainCamera().position();
	}

	// -------------------------------------------------------------- the numbers

	/**
	 * What the scene says this number is at the cursor, or the fallback.
	 *
	 * Read from the document and never from the entity, even though the entity has
	 * the same numbers on it. The entity is a copy made once a tick from the
	 * document — asking it would be asking last tick's answer, and a box that shows
	 * last tick's answer flickers while anything is being dragged.
	 */
	public static float read(String channel, float unsaid) {
		Scene scene = Playing.scene();
		Role part = part();
		if (scene == null || part == null) return unsaid;
		return scene.valueAt(part.name(), channel, Playing.head().at(), unsaid);
	}

	/** Keys one of the camera's numbers at the cursor. */
	public static void put(String channel, float value) {
		Scene scene = Playing.scene();
		Role part = part();
		if (scene == null || part == null) return;
		Scenes.keep(Playing.openName(), scene.keyed(part.name(), channel,
			Key.at(Playing.head().tick(), value)));
	}

	/** Everything a shot is: where it is taken from, which way, and how wide. */
	public static final String[] SHOT = {
		Channels.X, Channels.Y, Channels.Z, Channels.YAW, Channels.PITCH, Channels.FOV };

	/**
	 * Keys the whole shot where it already is.
	 *
	 * The way to make a camera hold still, and the same button the pose panel has
	 * for the same reason: without it the only way to key a camera is to move it,
	 * so a shot that has to stay put for two seconds cannot be said.
	 */
	public static void keyAll() {
		Scene scene = Playing.scene();
		Role part = part();
		if (scene == null || part == null || standing == null) return;

		int at = Playing.head().tick();
		Scene changed = scene;
		for (String channel : SHOT) {
			changed = changed.keyed(part.name(), channel,
				Key.at(at, read(channel, restingOf(channel))));
		}
		Scenes.keep(Playing.openName(), changed);
	}

	/** Puts the shot where the workspace's view is, at the cursor. */
	public static void putHere() {
		Scene scene = Playing.scene();
		Role part = part();
		if (scene == null || part == null) return;

		Vec3 at = viewpoint();
		int tick = Playing.head().tick();
		Scenes.keep(Playing.openName(), scene
			.keyed(part.name(), Channels.X, Key.at(tick, (float) at.x))
			.keyed(part.name(), Channels.Y, Key.at(tick, (float) at.y))
			.keyed(part.name(), Channels.Z, Key.at(tick, (float) at.z))
			.keyed(part.name(), Channels.YAW,
				Key.at(tick, com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.yaw()))
			.keyed(part.name(), Channels.PITCH,
				Key.at(tick, com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.pitch())));
	}

	/**
	 * How wide and how narrow a lens may be, in degrees of the vertical angle.
	 *
	 * Here rather than in the panel because two things now set it — the slider and
	 * the wheel — and a range written twice is a range that disagrees with itself
	 * the first time one of them is changed.
	 */
	public static final float NARROWEST = 10;
	public static final float WIDEST = 160;

	/**
	 * The lens after so many notches of a wheel.
	 *
	 * A ratio rather than a number of degrees, because a lens is a ratio: two
	 * degrees is the whole difference between a telephoto and a longer one and is
	 * nothing at all at a hundred and sixty. A twelfth each way gives about the
	 * same felt step everywhere along the range.
	 *
	 * Up narrows, as it does on every camera and in every viewport — scrolling
	 * towards the screen is going closer to the subject.
	 *
	 * Pure, so the sum can be checked without a wheel.
	 */
	public static float zoomed(float fov, double notches) {
		return (float) Math.clamp(fov * Math.pow(0.92, notches), NARROWEST, WIDEST);
	}

	/**
	 * Turns the wheel on the lens, and keys it.
	 *
	 * The one control that has to work from inside the shot. Everything else about
	 * a camera is judged by looking at it from outside — where it stands, which way
	 * it faces — and the angle is the opposite: it is a statement about what fits in
	 * the frame, so the only place it can honestly be chosen is with the frame in
	 * front of you. The slider in the panel says the same number and always did;
	 * what was missing was being able to say it while looking.
	 */
	public static void zoom(double notches) {
		if (part() == null) return;
		put(Channels.FOV, zoomed(read(Channels.FOV, restingOf(Channels.FOV)), notches));
	}

	/**
	 * Aims the camera by however far the mouse has been dragged.
	 *
	 * <h2>Why this exists when the arrows and the numbers already do</h2>
	 *
	 * Because aiming a camera by dragging a ring you are standing inside is not
	 * aiming, it is arithmetic. The whole reason to look through a camera is to
	 * decide what is in the frame, and that decision is made by moving the frame
	 * until it is right — so the gesture has to be the ordinary one, held down and
	 * pushed about, with the picture answering.
	 *
	 * The same four tenths of a degree per pixel the workspace's own camera uses,
	 * because a hand that has learned one should not have to learn the other. The
	 * pitch is clamped short of straight up and straight down for the reason every
	 * camera clamps it: at exactly ninety the direction it faces stops deciding
	 * which way up the frame is.
	 */
	public static void look(double acrossPixels, double downPixels) {
		if (part() == null) return;
		float yaw = read(Channels.YAW, restingOf(Channels.YAW)) + (float) acrossPixels * 0.4f;
		float pitch = Math.clamp(
			read(Channels.PITCH, restingOf(Channels.PITCH)) + (float) downPixels * 0.4f,
			-89.5f, 89.5f);
		put(Channels.YAW, yaw);
		put(Channels.PITCH, pitch);
	}

	/**
	 * Flies the camera, along the way it is facing.
	 *
	 * <h2>What is written down, and how often</h2>
	 *
	 * A key at the cursor, every frame that the keys are held. Which sounds
	 * alarming and is not: a key is identified by the tick it sits at, so a hundred
	 * frames of flying at one cursor position write one key over and over rather
	 * than a hundred keys. The document ends up with exactly what a hand-typed
	 * number would have put there.
	 *
	 * Move the cursor first and fly again, and there are two keys and a move
	 * between them — which is not a side effect, it is how a camera move is
	 * authored, and it is the reason this writes to the scene at all rather than
	 * to the entity.
	 */
	public static void fly(double ahead, double across, double up, double rate) {
		if (part() == null || (ahead == 0 && across == 0 && up == 0)) return;
		var aim = CameraMarker.aimOf(net.minecraft.world.phys.Vec3.ZERO,
			read(Channels.YAW, restingOf(Channels.YAW)),
			read(Channels.PITCH, restingOf(Channels.PITCH)));

		Vec3 moved = new Vec3(
			read(Channels.X, restingOf(Channels.X)),
			read(Channels.Y, restingOf(Channels.Y)),
			read(Channels.Z, restingOf(Channels.Z)))
			.add(aim.forward().scale(ahead * rate))
			.add(aim.right().scale(across * rate))
			.add(0, up * rate, 0);

		put(Channels.X, (float) moved.x);
		put(Channels.Y, (float) moved.y);
		put(Channels.Z, (float) moved.z);
	}

	/** What a channel reads as when the scene has never said: whatever it is now. */
	private static float restingOf(String channel) {
		if (standing == null) return 0;
		return switch (channel) {
			case Channels.X -> (float) standing.getX();
			case Channels.Y -> (float) standing.getY();
			case Channels.Z -> (float) standing.getZ();
			case Channels.YAW -> standing.getYRot();
			case Channels.PITCH -> standing.getXRot();
			case Channels.FOV -> standing.fov();
			default -> 0;
		};
	}

	/** The same, for a panel that has to show a number before anybody has set one. */
	public static float resting(String channel) {
		return restingOf(channel);
	}

	/**
	 * Makes the world hold exactly the camera the document describes.
	 *
	 * Called every tick. Adding and removing are both here rather than at the
	 * places that change the scene, because the scene can change without this
	 * client doing anything to it — somebody else may have saved over it — and a
	 * camera left standing after its part is gone is a thing you can select and
	 * cannot get rid of.
	 *
	 * Where it stands is <em>not</em> decided here; see {@link #frame(float)}.
	 */
	public static void tick() {
		Minecraft client = Minecraft.getInstance();
		Role part = part();

		if (part == null || client.level == null) {
			forget();
			return;
		}
		if (standing == null || standing.isRemoved() || standing.level() != client.level) {
			standing = new SceneCamera(SceneCamera.TYPE, client.level);
			// A long way below anything the server hands out, so the two counters
			// cannot meet. A client-side entity sharing an id with a real one is a
			// class of bug that only shows up in a world busy enough to reach it.
			standing.setId(-424242);
			client.level.addEntity(standing);
			bind(part, standing);
		}
	}

	/**
	 * Puts the camera where the document says, this frame.
	 *
	 * <h2>Why a frame and not a tick</h2>
	 *
	 * Because this is a camera, and a camera moving twenty times a second is a
	 * camera that judders. Everything else the scene animates is drawn with a
	 * partial tick — see {@code Placed.drawn} — so a character crossing a deck
	 * moves smoothly while a camera placed on the tick would step behind it. The
	 * cost is the whole point of the shot: the eye is far more forgiving of a
	 * subject that stutters than of a view that does.
	 *
	 * <h2>And why it matters even more while filming</h2>
	 *
	 * A capture does not advance the cursor on the tick at all — it advances it one
	 * frame's worth per written frame, thirty times to the second. Placed on the
	 * tick, the camera would hold still for a frame and a half and then jump, in
	 * every film, at exactly the rate that reads as a broken export.
	 */
	public static void frame(float partial) {
		Role part = part();
		if (part != null) place(part, Playing.now(partial));
	}

	/** Ties the part to the entity, so that everything which looks one up finds it. */
	private static void bind(Role part, SceneCamera camera) {
		Scene scene = Playing.scene();
		if (scene == null) return;
		String id = camera.getUUID().toString();
		if (part.bound().equals(id)) return;
		Scenes.keep(Playing.openName(), scene.with(part.boundTo(id)));
	}

	private static void place(Role part, double at) {
		Scene scene = Playing.scene();
		if (scene == null || standing == null) return;
		String who = part.name();

		double x = scene.valueAt(who, Channels.X, at, (float) standing.getX());
		double y = scene.valueAt(who, Channels.Y, at, (float) standing.getY());
		double z = scene.valueAt(who, Channels.Z, at, (float) standing.getZ());
		float yaw = scene.valueAt(who, Channels.YAW, at, standing.getYRot());
		float pitch = scene.valueAt(who, Channels.PITCH, at, standing.getXRot());

		standing.setPos(x, y, z);
		standing.xOld = x;
		standing.yOld = y;
		standing.zOld = z;
		standing.setYRot(yaw);
		standing.yRotO = yaw;
		standing.setXRot(pitch);
		standing.xRotO = pitch;
		standing.fov(scene.valueAt(who, Channels.FOV, at, standing.fov()));
	}

	/** Takes it out of the world. Nothing is saved, so there is nothing to keep. */
	public static void forget() {
		if (standing != null) {
			standing.discard();
			standing = null;
		}
		through = false;
	}

	/** Whether this is the camera, for the handles to tell it from a character. */
	public static boolean isCamera(Entity who) {
		return who instanceof SceneCamera;
	}
}
