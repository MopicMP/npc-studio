package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The geometry of being seen.
 *
 * <h2>Why arithmetic nobody can see is worth this much testing</h2>
 *
 * Because its failures are not crashes, they are moods. A cone a few degrees too
 * wide is a guard who is tiring to sneak past; a falloff that is linear instead
 * of squared is a guard with excellent peripheral vision. Neither throws, neither
 * logs, and neither can be pointed at from inside the game — the report comes
 * back as "it feels wrong", which is a fortnight of guessing unless the numbers
 * were pinned down first.
 */
class SightTest {

	private static final Sight EYES = Sight.ORDINARY;

	@Test
	@DisplayName("straight ahead and close is seen completely")
	void aheadIsFull() {
		assertEquals(1f, EYES.strength(6, 0), 1e-4);
	}

	@Test
	@DisplayName("past the range nothing is seen at all")
	void beyondRangeIsNothing() {
		assertEquals(0f, EYES.strength(EYES.range() + 0.1, 0), 1e-4);
	}

	@Test
	@DisplayName("outside the cone nothing is seen, however close")
	void outsideTheConeIsNothing() {
		// Sixty-one degrees off a hundred-and-twenty-degree cone is one degree past
		// the edge, and the edge has to be an edge.
		assertEquals(0f, EYES.strength(8, 61), 1e-4);
		assertEquals(0f, EYES.strength(8, 180), 1e-4, "and directly behind, plainly");
	}

	@Test
	@DisplayName("somebody at your shoulder is noticed whatever you are facing")
	void closeEnoughIsAlwaysSeen() {
		// Without this a character breathing on you is invisible because he happens
		// to be facing a wall, which reads as broken rather than as sneaky.
		assertEquals(1f, EYES.strength(1, 180), 1e-4);
		assertEquals(1f, EYES.strength(EYES.near() - 0.01, 179), 1e-4);
	}

	@Test
	@DisplayName("the near half of the range is full sight, and the far half tapers")
	void distanceTapersOnlyFarOut() {
		// Ten blocks in clear view is not half-seen. What the taper is for is the far
		// edge, where a figure is a shape that might be a figure.
		assertEquals(1f, EYES.strength(EYES.range() / 2, 0), 1e-4);
		float far = EYES.strength(EYES.range() * 0.75, 0);
		assertTrue(far > 0.4f && far < 0.6f, "three quarters out came to " + far);
	}

	@Test
	@DisplayName("the corner of the eye is genuinely poor, not nominally worse")
	void angleFallsOffSquared() {
		// The bug this pins: with straight-line falloff, fifty-five degrees off —
		// all but out of the corner of the eye — still came in at nearly a tenth,
		// which is enough to notice somebody through.
		float corner = EYES.strength(5, 55);
		assertTrue(corner < 0.02f, "at fifty-five degrees off it came to " + corner);

		// And halfway out is a quarter, not a half. That is the whole difference.
		assertEquals(0.25f, EYES.strength(5, 30), 1e-3);
	}

	@Test
	@DisplayName("strength never leaves nought and one")
	void alwaysInRange() {
		for (double distance = 0; distance < 40; distance += 0.7) {
			for (double angle = 0; angle <= 180; angle += 3) {
				float seen = EYES.strength(distance, angle);
				assertTrue(seen >= 0 && seen <= 1,
					"at " + distance + " blocks, " + angle + " degrees: " + seen);
			}
		}
	}

	@Test
	@DisplayName("the turn between two headings wraps the short way round")
	void turnWraps() {
		// The classic: facing 179 and a target at -179 are two degrees apart, and
		// subtraction alone says three hundred and fifty-eight.
		assertEquals(2, Sight.turnBetween(179, -179), 1e-6);
		assertEquals(2, Sight.turnBetween(-179, 179), 1e-6);
		assertEquals(0, Sight.turnBetween(90, 90), 1e-6);
		assertEquals(180, Sight.turnBetween(0, 180), 1e-6);
		assertTrue(Sight.turnBetween(0, 350) <= 180);
	}

	@Test
	@DisplayName("yaw is measured the game's way and not one of the five plausible others")
	void yawMatchesTheGame() {
		// Minecraft measures yaw from south, turning towards west. Every other
		// arrangement of these three symbols looks equally right at a call site.
		assertEquals(0, Sight.yawTo(0, 0, 0, 1), 1e-6, "due south is nought");
		assertEquals(90, Sight.yawTo(0, 0, -1, 0), 1e-6, "due west is ninety");
		assertEquals(-90, Sight.yawTo(0, 0, 1, 0), 1e-6, "due east is minus ninety");
		assertEquals(180, Math.abs(Sight.yawTo(0, 0, 0, -1)), 1e-6, "due north is half a turn");
	}

	@Test
	@DisplayName("a character facing a target sees it, and turning away loses it")
	void facingAndAway() {
		// The two pieces used together, which is how Watch will use them, so that a
		// mistake in either is caught here rather than in the game.
		double towards = Sight.yawTo(0, 0, 0, 10);
		assertEquals(1f, EYES.strength(10, Sight.turnBetween(0f, towards)), 1e-4);
		assertEquals(0f, EYES.strength(10, Sight.turnBetween(180f, towards)), 1e-4);
	}
}
