package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That a face's eight-by-eight mask says what it is asked and forgets nothing. */
class EyeMapTest {

	private static long of(int... spots) {
		long map = 0;
		for (int i = 0; i + 1 < spots.length; i += 2) {
			map = EyeMap.with(map, spots[i], spots[i + 1], true);
		}
		return map;
	}

	@Test
	@DisplayName("every one of the sixty-four pixels has its own bit")
	void everyPixelIsItsOwn() {
		for (int y = 0; y < EyeMap.SIZE; y++) {
			for (int x = 0; x < EyeMap.SIZE; x++) {
				long one = EyeMap.with(0L, x, y, true);
				assertEquals(1, EyeMap.count(one), "marking one pixel marked " + EyeMap.count(one));
				assertTrue(EyeMap.at(one, x, y), "the marked pixel did not come back");
			}
		}
	}

	@Test
	@DisplayName("marking outside the face changes nothing rather than wrapping round")
	void outsideTheFaceIsIgnored() {
		long map = of(2, 4, 5, 4);
		for (int[] outside : new int[][] { { -1, 4 }, { 8, 4 }, { 2, -1 }, { 2, 8 }, { 99, 99 } }) {
			assertEquals(map, EyeMap.with(map, outside[0], outside[1], true),
				"a mark outside the face was taken");
			assertFalse(EyeMap.at(map, outside[0], outside[1]),
				"a pixel outside the face reported as marked");
		}
	}

	@Test
	@DisplayName("clearing a pixel leaves its neighbours alone")
	void clearingIsLocal() {
		long map = of(2, 4, 3, 4, 5, 4);
		long fewer = EyeMap.with(map, 3, 4, false);
		assertEquals(2, EyeMap.count(fewer));
		assertTrue(EyeMap.at(fewer, 2, 4) && EyeMap.at(fewer, 5, 4));
		assertFalse(EyeMap.at(fewer, 3, 4));
	}

	/** A pair of eyes at the usual place: two blocks either side of the middle. */
	@Test
	@DisplayName("a mirrored pair reads as perfectly symmetric")
	void aPairIsSymmetric() {
		long pair = of(2, 4, 5, 4);
		assertEquals(pair, EyeMap.mirrored(pair), "mirroring a symmetric pair changed it");
		assertEquals(1f, EyeMap.symmetry(pair), 1e-6f);

		long lopsided = of(2, 4, 5, 5);
		assertTrue(EyeMap.symmetry(lopsided) < 0.5f,
			"a lopsided pair should not read as symmetric");
	}

	@Test
	@DisplayName("mirroring twice is doing nothing")
	void mirroringTwiceIsIdentity() {
		long odd = of(0, 0, 1, 3, 6, 4, 7, 7, 3, 5);
		assertEquals(odd, EyeMap.mirrored(EyeMap.mirrored(odd)));
	}

	@Test
	@DisplayName("the rows a map touches are its first and its last")
	void rowsAreFirstAndLast() {
		assertArrayEquals(new int[] { 3, 5 }, EyeMap.rows(of(2, 3, 5, 4, 2, 5)));
		assertEquals(null, EyeMap.rows(0L), "an empty map has no rows");
	}

	@Test
	@DisplayName("a face nobody marked is empty and unauthored")
	void noneIsEmpty() {
		assertTrue(EyeMap.NONE.isNone());
		assertFalse(EyeMap.NONE.authored());
		assertFalse(EyeMap.NONE.isEye(4, 4) || EyeMap.NONE.isBrow(4, 4));
	}
}
