package com.mopicmp.npcstudio.client.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which direction the hand is pointing, checked without a window.
 *
 * This is the whole of what the ring decides, and it is the part that fails
 * quietly: half a step out and every icon is chosen from its edge instead of its
 * middle, which shows up as the ring "sometimes picking the wrong one" and never
 * as anything you can see in a screenshot.
 */
class RingTest {

	/** Comfortably outside the dead middle and inside the reach. */
	private static final double OUT = 40;

	/** Points at the given angle in degrees, measured from straight up, clockwise. */
	private static int pointing(double degrees, int count) {
		double radians = Math.toRadians(degrees - 90);
		return Ring.directionAt(Math.cos(radians) * OUT, Math.sin(radians) * OUT, count);
	}

	@Nested
	@DisplayName("pointing straight at an icon")
	class AtAnIcon {

		@Test
		@DisplayName("four directions land on their own")
		void four() {
			assertEquals(0, pointing(0, 4));
			assertEquals(1, pointing(90, 4));
			assertEquals(2, pointing(180, 4));
			assertEquals(3, pointing(270, 4));
		}

		@Test
		@DisplayName("eight directions land on their own")
		void eight() {
			for (int which = 0; which < 8; which++) {
				assertEquals(which, pointing(which * 45.0, 8), "direction " + which);
			}
		}

		@Test
		@DisplayName("an odd count still lands on its own")
		void five() {
			for (int which = 0; which < 5; which++) {
				assertEquals(which, pointing(which * 72.0, 5), "direction " + which);
			}
		}
	}

	@Nested
	@DisplayName("near the edges between two")
	class Edges {

		@Test
		@DisplayName("just inside a wedge belongs to that wedge")
		void justInside() {
			// The boundary between 0 and 1 with four directions is at 45.
			assertEquals(0, pointing(44, 4));
			assertEquals(1, pointing(46, 4));
		}

		@Test
		@DisplayName("the wrap at the top is not a hole")
		void theWrap() {
			// Straight up is the middle of direction 0, and the arithmetic reaches it
			// from below through 360. This is where the obvious version leaves a gap.
			assertEquals(0, pointing(359, 4));
			assertEquals(0, pointing(1, 4));
			assertEquals(0, pointing(360, 4));

			// The boundary between the last direction and the first sits at 315 with
			// four of them, so 314 is the last one and 316 is already the first. Worth
			// pinning both sides: this is the one edge the arithmetic reaches by
			// wrapping rather than by counting up, and it is where a hole would be.
			assertEquals(3, pointing(314, 4));
			assertEquals(0, pointing(316, 4));
		}

		@Test
		@DisplayName("every angle right the way round chooses something")
		void nothingIsUnreachable() {
			// A hole would be a direction that cannot be picked from some angle, which
			// nobody would ever report as anything but "it feels unreliable".
			for (int count = 2; count <= Ring.MOST; count++) {
				for (int degrees = 0; degrees < 360; degrees++) {
					int which = pointing(degrees, count);
					assertEquals(true, which >= 0 && which < count,
						"count " + count + " at " + degrees + " gave " + which);
				}
			}
		}
	}

	@Nested
	@DisplayName("where nothing is chosen")
	class Nowhere {

		@Test
		@DisplayName("the middle means no")
		void theMiddle() {
			// The cursor is in the middle the instant the ring opens, so a middle that
			// chose something would choose it before the hand had moved.
			assertEquals(-1, Ring.directionAt(0, 0, 8));
			assertEquals(-1, Ring.directionAt(5, 5, 8));
		}

		@Test
		@DisplayName("far away is aimed at the world, not at the ring")
		void farAway() {
			assertEquals(-1, Ring.directionAt(400, 0, 8));
			assertEquals(-1, Ring.directionAt(0, -400, 8));
		}

		@Test
		@DisplayName("an empty ring chooses nothing rather than dividing by zero")
		void empty() {
			assertEquals(-1, Ring.directionAt(40, 0, 0));
		}
	}

	@Nested
	@DisplayName("the word under the ring")
	class TheLabel {

		@Test
		@DisplayName("never touches the button above it, at any count")
		void clearOfTheButtons() {
			// The fault this replaces was found by drawing the ring at its real size and
			// looking at it: with an even count one button always points straight down,
			// and the label sat four pixels under it, reading as part of the button
			// rather than as the name of the choice. Looking is a check that only
			// happens when somebody thinks to do it, so it is asked of the arithmetic
			// now, for every count the ring can hold.
			for (int count = 1; count <= Ring.MOST; count++) {
				assertTrue(Ring.labelClearance(count) >= 6,
					"only " + Ring.labelClearance(count) + " pixels clear with " + count);
			}
		}

		@Test
		@DisplayName("the tightest case is an even count, which is the one that broke")
		void theEvenCountsAreTheTightOnes() {
			// Stated rather than assumed. An even count puts a button at the bottom of
			// the circle; an odd one puts a gap there. If that ever stops being true the
			// reasoning above has changed and this should be read again.
			for (int even = 2; even <= Ring.MOST; even += 2) {
				assertTrue(Ring.labelClearance(even) <= Ring.labelClearance(even - 1),
					"odd " + (even - 1) + " should have at least as much room as " + even);
			}
		}
	}
}
