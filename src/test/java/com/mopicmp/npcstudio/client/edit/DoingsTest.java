package com.mopicmp.npcstudio.client.edit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.edit.Doings.Placing;

/**
 * The two comparisons undo rests on, checked without a world.
 *
 * Both decide the same thing — whether something has been touched since — and
 * getting either wrong makes undo look flaky rather than wrong: it would refuse
 * in one particular place and work everywhere else, which is the hardest kind of
 * fault to report and the easiest to blame on the network.
 */
class DoingsTest {

	@Nested
	@DisplayName("two angles are the same one")
	class Angles {

		@Test
		@DisplayName("across the wrap, where the obvious version fails")
		void acrossTheWrap() {
			// 179 and -179 are two degrees apart. abs(a - b) % 360 says 358, which is
			// how the first version of this refused to undo any character facing very
			// nearly south — the ordinary case — and nowhere else.
			assertTrue(Doings.sameAngle(179.5f, -179.5f));
			assertTrue(Doings.sameAngle(-179.5f, 179.5f));
			assertTrue(Doings.sameAngle(180f, -180f));
		}

		@Test
		@DisplayName("the same angle written differently")
		void sameTurnRound() {
			assertTrue(Doings.sameAngle(0f, 360f));
			assertTrue(Doings.sameAngle(90f, -270f));
			assertTrue(Doings.sameAngle(45f, 405f));
		}

		@Test
		@DisplayName("a real turn is not mistaken for rounding")
		void realTurn() {
			assertFalse(Doings.sameAngle(0f, 90f));
			assertFalse(Doings.sameAngle(179f, -179f + 90f));
			assertFalse(Doings.sameAngle(10f, 15f));
		}

		@Test
		@DisplayName("rounding on the way back is not mistaken for a turn")
		void rounding() {
			// What comes back off the wire is not what was sent: angles travel as a
			// byte of a turn, which is about one and a half degrees a step.
			assertTrue(Doings.sameAngle(90f, 90.4f));
			assertTrue(Doings.sameAngle(90f, 89.6f));
		}
	}

	@Nested
	@DisplayName("two places are the same one")
	class Places {

		@Test
		@DisplayName("within the rounding the wire does")
		void withinRounding() {
			assertTrue(Doings.samePlace(10, 64, -3, 10.03, 64.02, -3.01));
		}

		@Test
		@DisplayName("a step aside is a move")
		void aStepAside() {
			assertFalse(Doings.samePlace(10, 64, -3, 11, 64, -3));
			assertFalse(Doings.samePlace(10, 64, -3, 10, 65, -3));
			assertFalse(Doings.samePlace(10, 64, -3, 10, 64, -2));
		}
	}

	@Nested
	@DisplayName("a gesture worth remembering")
	class Worth {

		@Test
		@DisplayName("a grab that let go where it started is not an entry")
		void nothingHappened() {
			// Grabbing a handle and putting it back is not an edit, and recording it
			// would mean the first Ctrl+Z after a fumble does nothing visible — which
			// reads as undo being broken.
			assertFalse(new Placing(7, 1, 2, 3, 90f, 1, 2, 3, 90f).anything());
		}

		@Test
		@DisplayName("a move is, and so is a turn on the spot")
		void somethingHappened() {
			assertTrue(new Placing(7, 1, 2, 3, 90f, 1.5, 2, 3, 90f).anything());
			assertTrue(new Placing(7, 1, 2, 3, 90f, 1, 2, 3, 91f).anything());
		}
	}
}
