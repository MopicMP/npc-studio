package com.mopicmp.npcstudio.client.dialogue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Where the camera is during a conversation.
 *
 * Two different jobs, and they are different on purpose.
 *
 * A full-screen line only turns the player's own view towards whoever is
 * speaking. Nothing is taken away; the body still owns the camera, and a player
 * who wants to look somewhere else can.
 *
 * A cutscene lifts the camera off the player entirely and flies it to a shot of
 * the NPC. That needs a mixin, because the camera is rebuilt from the entity
 * every frame with nothing to say otherwise — and it needs the player pinned,
 * because a body walking around under a camera that is not attached to it is
 * how someone ends up lost.
 */
public final class DialogueCamera {

	/** How far the shot sits from the NPC, and how far round and up from its facing. */
	private static final double DISTANCE = 3.2;
	private static final double SIDE = 34;
	private static final double HEIGHT = 0.55;

	/** Seconds the flight takes. Long enough to read as a move, short enough not to be a wait. */
	private static final float FLIGHT = 1.1f;

	/** A camera placement for one frame. */
	public record Shot(Vec3 position, float yaw, float pitch) { }

	private static int lookTarget = -1;

	private static int sceneTarget = -1;
	private static Vec3 from = Vec3.ZERO;
	private static float fromYaw;
	private static float fromPitch;
	private static float flown;
	private static boolean leaving;

	private static final float EASE = 0.18f;
	private static final float SETTLED = 0.6f;

	private DialogueCamera() { }

	// ------------------------------------------------- turning to look

	public static void lookAt(int entityId) {
		lookTarget = entityId;
	}

	public static void release() {
		lookTarget = -1;
	}

	// ----------------------------------------------------- the cutscene

	/**
	 * Starts the flight from wherever the camera is now.
	 *
	 * Taking the starting point from the player rather than from a fixed spot is
	 * what makes it a move instead of a cut: the shot begins where the player was
	 * already looking.
	 */
	public static void beginScene(int entityId) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		if (sceneTarget == entityId && !leaving) return;

		sceneTarget = entityId;
		leaving = false;
		flown = 0;
		from = client.player.getEyePosition();
		fromYaw = client.player.getYRot();
		fromPitch = client.player.getXRot();
	}

	/**
	 * Flies back and hands the camera over.
	 *
	 * The return is the same move in reverse rather than a cut, because a scene
	 * that ends by snapping back to the body undoes the impression the flight out
	 * just made.
	 */
	public static void endScene() {
		if (sceneTarget < 0) return;
		leaving = true;
		flown = 0;
	}

	public static boolean inScene() {
		return sceneTarget >= 0;
	}

	/** Called every client tick to move the flight along. */
	public static void tick(Minecraft client) {
		turn(client);

		if (sceneTarget < 0) return;
		if (client.level == null || client.level.getEntity(sceneTarget) == null) {
			sceneTarget = -1;
			return;
		}
		flown = Math.min(1f, flown + 1f / (FLIGHT * 20f));
		if (leaving && flown >= 1f) sceneTarget = -1;
	}

	/**
	 * Where to put the camera this frame, or null to leave it alone.
	 *
	 * @param partial how far into the current tick, so the flight is smooth at any
	 *                frame rate rather than stepping twenty times a second
	 */
	public static Shot shot(float partial) {
		if (sceneTarget < 0) return null;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return null;

		Entity npc = client.level.getEntity(sceneTarget);
		if (npc == null) return null;

		Vec3 framed = framing(npc);
		Vec3 towards = npc.getEyePosition().subtract(framed);
		float wantYaw = (float) (Math.toDegrees(Math.atan2(towards.z, towards.x)) - 90);
		float wantPitch = (float) -Math.toDegrees(Math.atan2(towards.y,
			Math.sqrt(towards.x * towards.x + towards.z * towards.z)));

		float t = ease(Math.min(1f, flown + partial / (FLIGHT * 20f)));
		if (leaving) {
			// On the way back the ends swap, so the same curve carries the camera
			// home without a second set of numbers to keep in step.
			return new Shot(framed.lerp(client.player.getEyePosition(), t),
				Mth.rotLerp(t, wantYaw, client.player.getYRot()),
				Mth.lerp(t, wantPitch, client.player.getXRot()));
		}
		return new Shot(from.lerp(framed, t),
			Mth.rotLerp(t, fromYaw, wantYaw),
			Mth.lerp(t, fromPitch, wantPitch));
	}

	/**
	 * The shot: off to one side of the NPC's own facing, slightly above its eyes.
	 *
	 * Round the side rather than straight on, because a face filling the frame
	 * dead centre reads as a mugshot. Slightly high because looking very slightly
	 * down at someone is the angle a conversation is normally seen from.
	 */
	private static Vec3 framing(Entity npc) {
		// In front, not behind. An entity with yaw y faces (-sin y, cos y), so
		// adding 180 — as this did — put the camera at the back of its head and
		// then turned it round to look at the neck.
		double angle = Math.toRadians(npc.getYRot() + SIDE);
		double x = npc.getX() - Math.sin(angle) * DISTANCE;
		double z = npc.getZ() + Math.cos(angle) * DISTANCE;
		return new Vec3(x, npc.getEyeY() + HEIGHT, z);
	}

	/** Slow at both ends: a flight that starts and stops abruptly reads as a glitch. */
	private static float ease(float t) {
		return t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
	}

	// -------------------------------------------------------- the turn

	private static void turn(Minecraft client) {
		if (lookTarget < 0 || client.player == null || client.level == null) return;

		Entity speaker = client.level.getEntity(lookTarget);
		if (speaker == null) {
			lookTarget = -1;
			return;
		}

		Vec3 towards = speaker.getEyePosition().subtract(client.player.getEyePosition());
		double flat = Math.sqrt(towards.x * towards.x + towards.z * towards.z);
		float wantYaw = (float) (Math.toDegrees(Math.atan2(towards.z, towards.x)) - 90);
		float wantPitch = (float) -Math.toDegrees(Math.atan2(towards.y, flat));

		float yawGap = wrap(wantYaw - client.player.getYRot());
		float pitchGap = wantPitch - client.player.getXRot();

		// Stops once it arrives, so a player who looks away during a long line is
		// not fought with — the turn is an opening move, not a leash.
		if (Math.abs(yawGap) < SETTLED && Math.abs(pitchGap) < SETTLED) {
			lookTarget = -1;
			return;
		}
		client.player.setYRot(client.player.getYRot() + yawGap * EASE);
		client.player.setXRot(client.player.getXRot() + pitchGap * EASE);
	}

	private static float wrap(float degrees) {
		degrees %= 360;
		if (degrees >= 180) degrees -= 360;
		if (degrees < -180) degrees += 360;
		return degrees;
	}
}
