package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * Getting a click from the screen into a character's own bones.
 *
 * <h2>Why this is worth a test when the picking itself is not</h2>
 *
 * Because the two transforms in the way are the only part anybody can get wrong
 * quietly. A matrix inversion is either right or produces nonsense you can see;
 * a sign in the model-to-world composition produces a character whose left arm
 * answers to clicks on its right, which looks like a bad hit box rather than
 * like arithmetic — and is the mistake that has already been made twice in this
 * mod, once for the eyes and once for the collision boxes.
 *
 * So the composition is pinned against the three heights it is known to have to
 * produce. Those numbers are not guesses: they were read out of
 * {@code LivingEntityRenderer} and checked against the model's own pivots.
 */
class BonePickingTest {

	/** Where a character stands, somewhere with no round numbers in it. */
	private static final Vec3 STANDS = new Vec3(12.5, 64.0, -30.25);

	@Test
	@DisplayName("the model's own origin is the character's neck, at 1.501 above its feet")
	void theOriginIsTheNeck() {
		// Model space has its origin at the neck and counts downwards, which is what
		// the composition exists to undo. A character facing due south, so that the
		// turn is the identity and any sign error shows as a sign error.
		Vec3 neck = BonePicking.intoWorld(STANDS, 0f, 1f, Vec3.ZERO);
		assertEquals(STANDS.y + 1.501, neck.y, 1e-6, "the neck sits 1.501 up");

		// And the two heights the pivots have to land on, in model pixels over
		// sixteen: the shoulders two pixels down, the hips twelve.
		Vec3 shoulders = BonePicking.intoWorld(STANDS, 0f, 1f, new Vec3(0, 2 / 16.0, 0));
		Vec3 hips = BonePicking.intoWorld(STANDS, 0f, 1f, new Vec3(0, 12 / 16.0, 0));
		assertEquals(STANDS.y + 1.376, shoulders.y, 1e-6);
		assertEquals(STANDS.y + 0.751, hips.y, 1e-6);
	}

	@Test
	@DisplayName("a character's own right is the world's, whichever way it is turned")
	void theSidesDoNotSwap() {
		// Vanilla hangs the right arm at model x = -5. A yaw of nought faces south,
		// which is +z, and facing south your right hand points west — so the right
		// arm belongs at -x. Getting this backwards is the failure that reads as a
		// bad hit box rather than as a sign, which is why it is written out longhand
		// rather than asserted from memory.
		Vec3 rightArm = BonePicking.intoWorld(STANDS, 0f, 1f, new Vec3(-5 / 16.0, 2 / 16.0, 0));
		assertTrue(rightArm.x < STANDS.x, "the right arm went to " + rightArm.x);

		// A yaw of ninety faces west, and facing west your right hand points north,
		// which is -z. The arm has to follow the character round rather than staying
		// on one side of the world.
		Vec3 turned = BonePicking.intoWorld(STANDS, 90f, 1f, new Vec3(-5 / 16.0, 2 / 16.0, 0));
		assertTrue(turned.z < STANDS.z, "after turning it went to " + turned.z);
		assertEquals(STANDS.x, turned.x, 1e-6, "and no longer to one side");
	}

	@Test
	@DisplayName("a point taken into the model and back comes out where it started")
	void theTwoWaysAgree() {
		// The picking carries the ray in and the outline carries the corners out, so
		// the two have to be each other's inverse exactly. If they drift, the box a
		// click is tested against and the box drawn round it are different boxes —
		// which is the failure the modelling handles were caught with twice.
		float[] yaws = { 0f, 37f, 90f, 180f, -145f, 359f };
		Vec3[] points = {
			Vec3.ZERO,
			new Vec3(-5 / 16.0, 2 / 16.0, 0),
			new Vec3(0.25, -0.5, 0.125),
			new Vec3(-1.5, 3.25, -0.75) };

		for (float yaw : yaws) {
			for (Vec3 point : points) {
				Vec3 back = BonePicking.intoModel(STANDS, yaw, 1f,
					BonePicking.intoWorld(STANDS, yaw, 1f, point));
				assertEquals(point.x, back.x, 1e-6, "x at yaw " + yaw);
				assertEquals(point.y, back.y, 1e-6, "y at yaw " + yaw);
				assertEquals(point.z, back.z, 1e-6, "z at yaw " + yaw);
			}
		}
	}

	@Test
	@DisplayName("a character somebody has made larger is caught at the size it is drawn")
	void sizeIsCarried() {
		// Nothing anywhere took the character's own scale into account, so every
		// handle and every catch box sat where the model would have been if nobody
		// had resized it. On a character at double size that is half a body out.
		Vec3 plain = BonePicking.intoWorld(STANDS, 0f, 1f, new Vec3(0, -1, 0));
		Vec3 large = BonePicking.intoWorld(STANDS, 0f, 2f, new Vec3(0, -1, 0));

		assertEquals(STANDS.y + 1.501 + 1, plain.y, 1e-6);
		assertEquals(STANDS.y + (1.501 + 1) * 2, large.y, 1e-6, "twice as far up");

		// And the way back divides rather than doing something else that happens to
		// look right at a scale of one.
		Vec3 back = BonePicking.intoModel(STANDS, 0f, 2f, large);
		assertEquals(-1, back.y, 1e-6);
	}

	@Test
	@DisplayName("turning the character turns what is in front of it, not what is above it")
	void heightIsNotTurned() {
		// A body rotation is about the vertical axis and nothing else. Folding the
		// height into the turn is an easy slip and it comes out as limbs sinking into
		// the floor as a character walks round in a circle.
		for (float yaw = -180; yaw <= 180; yaw += 17) {
			Vec3 at = BonePicking.intoWorld(STANDS, yaw, 1f, new Vec3(0.4, 0.6, -0.2));
			assertEquals(STANDS.y + 1.501 - 0.6, at.y, 1e-6, "height moved at yaw " + yaw);
		}
	}
}
