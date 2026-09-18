package com.mopicmp.npcstudio.puppet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Putting one variant of a slot where the last one was.
 *
 * <h2>Why this is worth arithmetic when the rest is done by hand</h2>
 *
 * Because it is the one part of the placing that has an answer in the pictures
 * themselves. Two variants of one slot are nearly the same drawing, so there is exactly
 * one offset where they lie on each other and it can be found. Everything else — a hat
 * against a head, hair against a shoulder — has no answer in the pictures at all, and
 * this class must never be asked for one.
 */
class FittingTest {

	/**
	 * A mask with a filled rectangle in it, which stands in for a drawing.
	 *
	 * Shapes rather than real pictures because shape is all that is compared: two
	 * variants may differ in every colour and still be the same eyes in the same place.
	 */
	private static boolean[] box(int wide, int high, int x, int y, int w, int h) {
		boolean[] mask = new boolean[wide * high];
		for (int row = y; row < y + h; row++) {
			for (int col = x; col < x + w; col++) {
				if (row >= 0 && row < high && col >= 0 && col < wide) mask[row * wide + col] = true;
			}
		}
		return mask;
	}

	@Test
	@DisplayName("two variants trimmed the same way need no moving at all")
	void identicalTrimsMeetWhereTheyAre() {
		boolean[] one = box(20, 20, 5, 5, 8, 8);
		boolean[] other = box(20, 20, 5, 5, 8, 8);
		assertArrayEquals(new int[] { 0, 0 }, Fitting.meet(one, 20, 20, other, 20, 20));
	}

	@Test
	@DisplayName("a variant trimmed tighter is put back where the drawing was")
	void aTighterTrimIsPutBack() {
		// The case this exists for. One variant was exported with three pixels of air
		// around it and the other without; started at the same corner the second sits
		// three pixels up and left of where its eyes belong.
		boolean[] roomy = box(20, 20, 3, 3, 8, 8);
		boolean[] tight = box(8, 8, 0, 0, 8, 8);

		int[] by = Fitting.meet(roomy, 20, 20, tight, 8, 8);
		assertArrayEquals(new int[] { 3, 3 }, by);
	}

	@Test
	@DisplayName("the shape decides it, never the colours — there are none to read")
	void onlyTheShapeIsCompared() {
		// Stated as a test because it is a promise about the method's arguments: it is
		// handed two masks and nothing else, so a variant that is a different colour in
		// every pixel is found exactly as well as one that is identical.
		boolean[] fixed = box(16, 16, 4, 6, 6, 4);
		boolean[] moving = box(16, 16, 2, 4, 6, 4);
		assertArrayEquals(new int[] { 2, 2 }, Fitting.meet(fixed, 16, 16, moving, 16, 16));
	}

	@Test
	@DisplayName("a picture with nothing to go on stays where it was put")
	void nothingToGoOnMeansNoMovement() {
		// Two blank masks agree equally everywhere. The tie has to break towards no
		// movement, or the answer would be whichever corner the search happened to start
		// at — right in a test, and a picture that drifts on somebody's screen.
		boolean[] empty = new boolean[12 * 12];
		assertArrayEquals(new int[] { 0, 0 }, Fitting.meet(empty, 12, 12, empty, 12, 12));
	}

	@Test
	@DisplayName("a picture that belongs far away is left alone, not dragged as far as it may go")
	void theReachIsABound() {
		// I expected this to travel the full reach and stop at the edge of it. It does
		// not, and the real behaviour is the better one: nothing within reach overlaps at
		// all, every offset scores nothing, and the tie leaves the picture where the
		// person put it.
		//
		// Dragging it twenty-four pixels towards something it can never meet would be
		// worse than useless — it would look like the tool had an opinion.
		boolean[] fixed = box(80, 80, 70, 70, 6, 6);
		boolean[] moving = box(80, 80, 2, 2, 6, 6);

		int[] by = Fitting.meet(fixed, 80, 80, moving, 80, 80);
		assertArrayEquals(new int[] { 0, 0 }, by,
			"out of reach means untouched: " + by[0] + "," + by[1]);
		// And the bound is still a bound: the search never looks further than this, which
		// is what keeps it a cost anybody can pay per variant.
		assertEquals(24, Fitting.REACH);
	}
}
