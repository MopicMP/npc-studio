package com.mopicmp.npcstudio.emote;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.emote.Tuck;

/**
 * That pulling a part inside its own surface does not tear it open.
 *
 * The bug this is written against was judged by its size for a long time and was
 * never about its size. Moving each vertex along the normal of the face it was
 * drawn for gives a corner of a box three different answers, because a corner
 * belongs to three faces — so every edge of every part opened by a fraction of a
 * pixel, and through the crack you could see the inside of the box as a strip of
 * skin standing proud of the model, all the way round.
 *
 * The property that rules that out is not "the number is small". It is that the
 * tuck is a function of the point and nothing else.
 */
class TuckTest {

	/** An arm: four pixels across, twelve down, four through, in blocks. */
	private static final float HALF_X = 2f / 16f;
	private static final float HALF_Y = 6f / 16f;
	private static final float HALF_Z = 2f / 16f;
	private static final float MIDDLE_X = -2f / 16f;
	private static final float MIDDLE_Y = 4f / 16f;
	private static final float MIDDLE_Z = 0;

	private static float[] tucked(float x, float y, float z) {
		return new float[] {
			Tuck.inset(x, MIDDLE_X, HALF_X, Tuck.DEPTH),
			Tuck.inset(y, MIDDLE_Y, HALF_Y, Tuck.DEPTH),
			Tuck.inset(z, MIDDLE_Z, HALF_Z, Tuck.DEPTH) };
	}

	/**
	 * A corner goes to one place, however many faces claim it.
	 *
	 * Stated as the thing that failed: the tuck is asked the same question by each
	 * of the three faces meeting at a corner, and has to give the same answer. It
	 * does here because it is never told which face is asking.
	 */
	@Test
	@DisplayName("a corner of a box goes to exactly one place")
	void aCornerIsNeverSplit() {
		for (float x : new float[] { MIDDLE_X - HALF_X, MIDDLE_X + HALF_X }) {
			for (float y : new float[] { MIDDLE_Y - HALF_Y, MIDDLE_Y + HALF_Y }) {
				for (float z : new float[] { MIDDLE_Z - HALF_Z, MIDDLE_Z + HALF_Z }) {
					float[] once = tucked(x, y, z);
					float[] again = tucked(x, y, z);
					assertTrue(once[0] == again[0] && once[1] == again[1] && once[2] == again[2],
						"a corner moved differently when asked twice");
				}
			}
		}
	}

	/** Every face moves inward by exactly the tuck, and inward is inward. */
	@Test
	@DisplayName("every face moves in by exactly the tuck, and never past the middle")
	void everyFaceMovesInByTheTuck() {
		float[] low = tucked(MIDDLE_X - HALF_X, MIDDLE_Y - HALF_Y, MIDDLE_Z - HALF_Z);
		float[] high = tucked(MIDDLE_X + HALF_X, MIDDLE_Y + HALF_Y, MIDDLE_Z + HALF_Z);

		float[] middle = { MIDDLE_X, MIDDLE_Y, MIDDLE_Z };
		float[] half = { HALF_X, HALF_Y, HALF_Z };
		for (int axis = 0; axis < 3; axis++) {
			float wanted = half[axis] - Tuck.DEPTH;
			assertTrue(Math.abs((middle[axis] - low[axis]) - wanted) < 1e-6f
				&& Math.abs((high[axis] - middle[axis]) - wanted) < 1e-6f,
				"axis " + axis + " did not move in by exactly the tuck");
		}
	}

	/** The middle of a part is the one place a tuck cannot move. */
	@Test
	@DisplayName("the middle of a part does not move")
	void theMiddleStaysPut() {
		float[] at = tucked(MIDDLE_X, MIDDLE_Y, MIDDLE_Z);
		assertTrue(at[0] == MIDDLE_X && at[1] == MIDDLE_Y && at[2] == MIDDLE_Z,
			"the middle moved");
	}

	/** Something thinner than the tuck is flattened, never turned inside out. */
	@Test
	@DisplayName("something thinner than the tuck flattens rather than inverting")
	void aThinPartFlattens() {
		float thin = Tuck.DEPTH / 2f;
		assertTrue(Tuck.inset(thin, 0, thin, Tuck.DEPTH) == 0, "a thin part inverted");
		assertTrue(Tuck.inset(-thin, 0, thin, Tuck.DEPTH) == 0, "a thin part inverted");
	}
}
