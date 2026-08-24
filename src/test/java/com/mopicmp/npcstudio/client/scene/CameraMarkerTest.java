package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * Which way the scene's camera is pointing, and where its shot lands.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * Because the whole marker is one sign away from being convincingly wrong. Yaw
 * nought faces south in this game, which is {@code +Z}, and somebody facing south
 * has east on their <em>left</em> — so the right-hand vector at yaw nought is
 * {@code -X} and not {@code +X}. Get that backwards and every still picture of the
 * cone is correct, every symmetric shot is correct, and the marker is mirrored
 * only when the camera turns. That reads as a rendering glitch rather than as
 * arithmetic, and it is the same mistake this mod has already made twice — once
 * on the eyes and once on the collision boxes.
 *
 * The angle is the other half. A camera's lens is the vertical angle, because
 * that is what the game's own {@code Camera.getFov} means and what the mixin feeds
 * it into; the width comes from the frame's shape. Drawing a cone from a
 * horizontal reading would put the corners in the wrong place by the aspect ratio
 * squared, which on an ordinary window is nearly a factor of three.
 */
class CameraMarkerTest {

	private static final double SLACK = 1e-9;

	private static void near(Vec3 wanted, Vec3 got, String what) {
		assertEquals(wanted.x, got.x, 1e-9, what + " x");
		assertEquals(wanted.y, got.y, 1e-9, what + " y");
		assertEquals(wanted.z, got.z, 1e-9, what + " z");
	}

	@Test
	@DisplayName("yaw nought faces south, and south's right hand points west")
	void southFacesRightAtWest() {
		CameraMarker.Aim aim = CameraMarker.aimOf(Vec3.ZERO, 0, 0);
		near(new Vec3(0, 0, 1), aim.forward(), "forward");
		// The one that has been got wrong before. Facing south, east (+X) is on the
		// left, so right is -X.
		near(new Vec3(-1, 0, 0), aim.right(), "right");
		near(new Vec3(0, 1, 0), aim.up(), "up");
	}

	@Test
	@DisplayName("a quarter turn faces west, and keeps its own right hand")
	void aQuarterTurn() {
		CameraMarker.Aim aim = CameraMarker.aimOf(Vec3.ZERO, 90, 0);
		near(new Vec3(-1, 0, 0), aim.forward(), "forward");
		near(new Vec3(0, 0, -1), aim.right(), "right");
		near(new Vec3(0, 1, 0), aim.up(), "up");
	}

	@Test
	@DisplayName("looking straight down keeps the horizon level and tips the up")
	void straightDown() {
		CameraMarker.Aim aim = CameraMarker.aimOf(Vec3.ZERO, 0, 90);
		near(new Vec3(0, -1, 0), aim.forward(), "forward");
		// A camera has no roll, so its right stays in the world's flat plane however
		// far it is pitched — only the up tips over. Pointed at the floor while
		// facing south, the top of the frame is towards the south.
		near(new Vec3(-1, 0, 0), aim.right(), "right");
		near(new Vec3(0, 0, 1), aim.up(), "up");
	}

	@Test
	@DisplayName("the three directions stay square to each other at any angle")
	void staysSquare() {
		// Awkward numbers on purpose: a composition that only works at right angles
		// passes the three tests above and nothing else.
		for (float yaw : new float[] { -167, -33, 12.5f, 91, 214 }) {
			for (float pitch : new float[] { -71, -20, 0, 37.5f, 88 }) {
				CameraMarker.Aim aim = CameraMarker.aimOf(new Vec3(3, 70, -8), yaw, pitch);
				assertEquals(1, aim.forward().length(), 1e-9, "forward is a unit");
				assertEquals(1, aim.right().length(), 1e-9, "right is a unit");
				assertEquals(1, aim.up().length(), 1e-9, "up is a unit");
				assertEquals(0, aim.forward().dot(aim.right()), 1e-9, "forward against right");
				assertEquals(0, aim.forward().dot(aim.up()), 1e-9, "forward against up");
				assertEquals(0, aim.right().dot(aim.up()), 1e-9, "right against up");
				// No roll: the right hand never leaves the flat plane.
				assertEquals(0, aim.right().y, 1e-9, "right stays level");
			}
		}
	}

	@Test
	@DisplayName("the lens is the vertical angle and the window makes it wide")
	void theLensIsVertical() {
		// Ninety degrees is the one angle where the arithmetic can be checked by
		// hand: half of it is forty-five, whose tangent is one, so at one block away
		// the shot is one block from the middle to the top.
		CameraMarker.Aim aim = CameraMarker.aimOf(Vec3.ZERO, 0, 0);
		Vec3[] square = CameraMarker.corners(aim, 90, 1.0, 1.0);
		assertEquals(1, square[0].y, 1e-9, "a block up at a block away");
		assertEquals(1, square[0].x, 1e-9, "and a block across in a square window");

		// A window twice as wide is twice as wide a shot at the same lens, and
		// exactly as tall. That is what the game does with its own field of view and
		// it is why the width is not a second setting.
		Vec3[] wide = CameraMarker.corners(aim, 90, 2.0, 1.0);
		assertEquals(1, wide[0].y, 1e-9, "the height does not move");
		assertEquals(2, wide[0].x, 1e-9, "the width follows the frame");
	}

	@Test
	@DisplayName("the corners run round the rectangle rather than across it")
	void theCornersAreInOrder() {
		// Drawn by joining each to the next and closing, so an order that crosses
		// the middle gives a bow tie. Top left, top right, bottom right, bottom left.
		CameraMarker.Aim aim = CameraMarker.aimOf(new Vec3(0, 64, 0), 0, 0);
		Vec3[] at = CameraMarker.corners(aim, 70, 16 / 9.0, 4);

		assertTrue(at[0].y > at[3].y, "the first corner is above the last");
		assertTrue(at[1].y > at[2].y, "the second is above the third");
		assertEquals(at[0].y, at[1].y, SLACK, "the top edge is level");
		assertEquals(at[2].y, at[3].y, SLACK, "the bottom edge is level");
		// Facing south, the frame's right is west, so the second corner is the
		// smaller X. Which is the same sign the direction test pins, arriving by a
		// different road.
		assertTrue(at[1].x < at[0].x, "the frame's right is the world's west");
		assertEquals(at[0].x, at[3].x, SLACK, "the left edge is straight");
	}

	@Test
	@DisplayName("the marker is sized by its frame, not by its length")
	void theMarkerStaysSmall() {
		// What went wrong: the length was sized like a handle and tripled, and the
		// rectangle at the end of it is the length times the tangent of half the lens
		// — so at seventy degrees the marker came out nearly four handles across and
		// covered most of the display. It was reported, correctly, as an enormous
		// camera.
		//
		// One handle. Everything below is in these, so the numbers read directly as
		// "how many arrows across is this thing".
		double unit = 1;

		// An ordinary lens: the far rectangle stands about seven tenths of a handle
		// above the middle, which is the whole point of choosing it that way round.
		double away = CameraMarker.reach(unit, 70);
		double halfUp = away * Math.tan(Math.toRadians(70) / 2);
		assertEquals(0.7, halfUp, 1e-6, "the frame is what is sized");
		assertTrue(away <= unit * 1.5, "and the cone stays about a handle long");

		// The old sum, for the size of the difference: three handles of length at the
		// same lens put the rectangle two handles up and nearly four across.
		assertTrue(3 * Math.tan(Math.toRadians(70) / 2) > 2 * halfUp,
			"the old marker was more than twice as tall as the new one");
	}

	@Test
	@DisplayName("an extreme lens is clamped rather than allowed to fill the screen")
	void extremeLensesAreClamped() {
		double unit = 1;
		// A fisheye wants a cone an eighth of a handle long, which is a rectangle with
		// nothing behind it; a telephoto wants one eight handles long, which runs off
		// the display. Both are held to something still recognisable as a camera.
		assertEquals(0.35, CameraMarker.reach(unit, 160), 1e-6, "a fisheye");
		assertEquals(2.5, CameraMarker.reach(unit, 10), 1e-6, "a telephoto");

		// And the shape still says which is which: the fisheye's frame is far taller
		// than its cone is long, and the telephoto's is far shorter.
		assertTrue(CameraMarker.reach(unit, 160) * Math.tan(Math.toRadians(160) / 2)
			> CameraMarker.reach(unit, 10) * Math.tan(Math.toRadians(10) / 2));
	}

	@Test
	@DisplayName("the marker keeps its size on the display as the camera goes away")
	void itKeepsItsSizeOnScreen() {
		// The handle is worked out from the distance, so everything measured in
		// handles grows in the world exactly as fast as it shrinks on the screen.
		// Pinned because the whole reason for measuring this way is easy to undo by
		// writing one length in blocks.
		for (double unit : new double[] { 0.1, 1, 7.5, 120 }) {
			assertEquals(CameraMarker.reach(1, 70) * unit,
				CameraMarker.reach(unit, 70), 1e-9, "at a handle of " + unit);
		}
	}

	@Test
	@DisplayName("the shot sits in front of the camera and nowhere else")
	void theShotIsAhead() {
		Vec3 eye = new Vec3(12.5, 70, -30.25);
		CameraMarker.Aim aim = CameraMarker.aimOf(eye, 214, -37.5f);
		for (Vec3 corner : CameraMarker.corners(aim, 70, 16 / 9.0, 6)) {
			assertTrue(corner.subtract(eye).dot(aim.forward()) > 0,
				"a corner behind the camera would draw the cone through the lens");
		}
	}
}
