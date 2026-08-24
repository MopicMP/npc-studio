package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Where a participant is actually drawn, which is not where it stands.
 *
 * <h2>Why the difference exists at all</h2>
 *
 * A scene does not move anybody. It changes the numbers on the render state just
 * before the character is drawn — see {@code Staging} — so the entity goes on
 * standing exactly where it always stood while the picture of it is somewhere
 * else entirely. That is the right arrangement: it means a scene can be scrubbed
 * backwards and forwards without shoving anything through a wall, and it means
 * closing the scene puts everybody back.
 *
 * <h2>And why every handle got it wrong</h2>
 *
 * Because they all asked the entity. The rings, the outlines and the arrows were
 * placed from {@code who.position()} and {@code yBodyRot}, which is where the
 * character stands and not where it is drawn — so the moment a scene moved
 * anybody, every handle stayed behind at the old place and every click landed
 * there too. Scale was worse: nothing anywhere took it into account, so a
 * character somebody had made larger had its bones caught at the size the model
 * would have been if nobody had touched it.
 *
 * One answer, asked here, used by all of them.
 *
 * @param at    where the character is drawn standing, in blocks
 * @param yaw   which way its body faces, in degrees
 * @param scale how much larger than the model it is drawn
 */
public record Placed(Vec3 at, float yaw, float scale) {

	public Placed {
		// A scale of nought is a character drawn as a point, and everything measured
		// against it divides by it. A scene is free to say it; nothing downstream is
		// obliged to fall over.
		if (!(scale > 1e-4f)) scale = 1e-4f;
	}

	/** Where a character stands and how it faces, ignoring any scene. */
	public static Placed of(Entity who) {
		return new Placed(who.position(), yawOf(who), scaleOf(who));
	}

	/**
	 * Where this character is drawn this frame, scene and all.
	 *
	 * The fallbacks are the entity's own, exactly as playback's are: a scene
	 * animates what it mentions and leaves everything else where it was, so a
	 * participant whose position is never keyed is drawn where it stands.
	 */
	public static Placed drawn(Entity who) {
		if (who == null) return null;
		Placed standing = of(who);
		Playing.Sample sample = Playing.sampleOf(who, partial());
		if (sample == null) return standing;

		return new Placed(
			new Vec3(
				sample.value(Channels.X, (float) standing.at().x),
				sample.value(Channels.Y, (float) standing.at().y),
				sample.value(Channels.Z, (float) standing.at().z)),
			sample.value(Channels.YAW, standing.yaw()),
			sample.value(Channels.SCALE, standing.scale()));
	}

	/** The same, at the cursor rather than at whatever this frame happens to be. */
	public static Placed atCursor(Entity who, String role) {
		if (who == null) return null;
		Placed standing = of(who);
		var scene = Playing.scene();
		if (scene == null || role == null || role.isEmpty()) return standing;

		double time = Playing.head().at();
		return new Placed(
			new Vec3(
				scene.valueAt(role, Channels.X, time, (float) standing.at().x),
				scene.valueAt(role, Channels.Y, time, (float) standing.at().y),
				scene.valueAt(role, Channels.Z, time, (float) standing.at().z)),
			scene.valueAt(role, Channels.YAW, time, standing.yaw()),
			scene.valueAt(role, Channels.SCALE, time, standing.scale()));
	}

	private static float yawOf(Entity who) {
		return who instanceof LivingEntity living ? living.yBodyRot : who.getYRot();
	}

	/**
	 * How much larger than the model a character is drawn.
	 *
	 * <h2>The other three sixteenths</h2>
	 *
	 * A character is not drawn at its model's size. {@code AvatarRenderer} scales
	 * everything by {@code 0.9375} — fifteen sixteenths, the constant that has made
	 * a player model fit a 1.8-block hitbox since the beginning — and every handle
	 * in this mod was missing it.
	 *
	 * That is a sixteenth of the height, and it lands entirely on top: the model's
	 * offset from the character's own point is measured upwards from the feet, so
	 * an unscaled reading puts the neck twelve centimetres too high and the top of
	 * the head thirteen. Which is exactly what "the outlines are above where they
	 * should be" was.
	 *
	 * Read out of the renderer's bytecode rather than remembered, along with the
	 * order it goes in: the scale is applied inside the lift onto the feet, so the
	 * whole offset is multiplied and nothing is added afterwards.
	 */
	private static final float AVATAR = 0.9375f;

	private static float scaleOf(Entity who) {
		return who instanceof LivingEntity living ? living.getScale() * AVATAR : 1f;
	}

	private static float partial() {
		var client = Minecraft.getInstance();
		return client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
	}
}
