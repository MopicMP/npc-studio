package com.mopicmp.npcstudio.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.skin.FaceReading;

/**
 * Faces built by hand, because the reading is a heuristic.
 *
 * There is no authority to check this against — a skin is whatever somebody
 * painted — so the tests are the cases that matter in practice: an ordinary
 * face, a face with brows, a face with nothing on it at all, and a face whose
 * markings are not eyes. The last two are the ones worth having: the cost of
 * being wrong there is a rectangle of forehead stamped over someone's design.
 */
class FaceReadingTest {

	private static final int SKIN = 0xFFC49A6C;
	private static final int EYE = 0xFF2B1A0F;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int CLEAR = 0x00000000;

	/** A blank 64×64 skin with a plain face. */
	private static int[][] plainFace() {
		int[][] pixels = new int[64][64];
		for (int y = 8; y < 16; y++) {
			for (int x = 8; x < 16; x++) pixels[y][x] = SKIN;
		}
		return pixels;
	}

	private static FaceReading read(int[][] pixels) {
		return FaceReading.of((x, y) -> pixels[y][x]);
	}

	/** Two eyes where eyes go, and nothing else. */
	private static int[][] withEyes(int[][] pixels) {
		for (int x = 9; x <= 10; x++) {
			pixels[12][x] = WHITE;
			pixels[13][x] = EYE;
		}
		for (int x = 13; x <= 14; x++) {
			pixels[12][x] = WHITE;
			pixels[13][x] = EYE;
		}
		return pixels;
	}

	@Test
	void anOrdinaryFaceHasItsEyesFound() {
		FaceReading reading = read(withEyes(plainFace()));

		assertTrue(reading.hasEyes(), "two markings level with each other are eyes");
		assertEquals(12, reading.eyeTop());
		assertEquals(14, reading.eyeBottom());
		assertFalse(reading.hasBrows(), "nothing was painted above them");
	}

	/**
	 * A whole face, not just eyes — which is what the first version of this test
	 * was missing and what let the bug through.
	 *
	 * Eyes, shading down the nose and a mouth. All three are marks on both halves
	 * of the face, so a rule that only asked for that came back with a band four
	 * rows deep running from the eyes to the chin, and the eyelid covered it all.
	 */
	@Test
	void aNoseAndAMouthAreNotEyes() {
		int[][] pixels = withEyes(plainFace());
		int shadow = 0xFF96704F;
		pixels[14][11] = shadow;
		pixels[14][12] = shadow;
		for (int x = 10; x <= 13; x++) pixels[15][x] = shadow;

		FaceReading reading = read(pixels);

		assertTrue(reading.hasEyes());
		assertEquals(12, reading.eyeTop(), "the eyes start where the eyes are");
		assertEquals(14, reading.eyeBottom(), "and stop before the nose, not after the mouth");
	}

	/** Whatever is found, an eyelid never covers more than an eye's worth. */
	@Test
	void theEyeBandIsNeverTallerThanTwoRows() {
		int[][] pixels = plainFace();
		int mark = 0xFF2B1A0F;
		for (int y = 10; y <= 15; y++) {
			pixels[y][9] = mark;
			pixels[y][14] = mark;
		}

		FaceReading reading = read(pixels);

		assertTrue(reading.eyeBottom() - reading.eyeTop() <= 2,
			"a lid the height of a face is the one failure nobody could miss");
	}

	@Test
	void aBareFaceIsLeftAlone() {
		FaceReading reading = read(plainFace());

		assertFalse(reading.hasEyes(),
			"a face with nothing on it must not be blinked — there is nothing to shut");
	}

	/**
	 * One marking on one side is not a pair of eyes.
	 *
	 * A scar, a patch, a stray pixel. Blinking on the strength of it would put a
	 * lid over somebody's deliberate design.
	 */
	@Test
	void aMarkingOnOneSideIsNotEyes() {
		int[][] pixels = plainFace();
		pixels[12][9] = EYE;
		pixels[13][9] = EYE;

		assertFalse(read(pixels).hasEyes());
	}

	@Test
	void browsAboveEyesAreFound() {
		int[][] pixels = withEyes(plainFace());
		for (int x = 9; x <= 10; x++) pixels[11][x] = EYE;
		for (int x = 13; x <= 14; x++) pixels[11][x] = EYE;

		FaceReading reading = read(pixels);

		assertTrue(reading.hasEyes());
		assertTrue(reading.hasBrows(), "a line above the eyes on both sides is a pair of brows");
		assertEquals(11, reading.browTop());
		// The brow ends where the eye begins, rather than overlapping it by a row.
		// It used to claim row 12 as well, which is the top of the eye — harmless
		// while nothing was drawn on brows, and wrong the moment anything is.
		//
		// Said as an edge rather than as the last row, the same way the eye says it:
		// a brow that is picked up and put down a pixel higher is a rectangle, and a
		// rectangle is bounded by edges. That the two numbers below are equal is the
		// whole statement — the brow stops exactly where the eye starts.
		assertEquals(12, reading.browBottom());
		assertEquals(12, reading.eyeTop(), "and the lid still starts at the eye");
		assertEquals(14, reading.eyeBottom());
	}

	/**
	 * A brow marked by hand comes back with its columns, not merely its rows.
	 *
	 * The distinction the brow animation turns on. Rows are enough to say a brow is
	 * there; picking one up and putting it down a pixel higher needs to know where
	 * it starts and stops across the face, and a reading that knows only the band
	 * has to leave the brows alone. So {@link FaceReading#canMoveBrows()} is the
	 * question actually asked before anything is drawn, and this is what it means.
	 */
	@Test
	void aMarkedBrowCanBePickedUp() {
		// Face columns 1..2 and their mirror, brows on row 3, eyes on rows 4 and 5.
		long eyes = 0;
		long whites = 0;
		long brows = 0;
		for (int y = 4; y <= 5; y++) {
			eyes = com.mopicmp.npcstudio.entity.EyeMap.with(eyes, 2, y, true);
			eyes = com.mopicmp.npcstudio.entity.EyeMap.with(eyes, 5, y, true);
			whites = com.mopicmp.npcstudio.entity.EyeMap.with(whites, 1, y, true);
			whites = com.mopicmp.npcstudio.entity.EyeMap.with(whites, 6, y, true);
		}
		for (int x : new int[] { 1, 2, 5, 6 }) {
			brows = com.mopicmp.npcstudio.entity.EyeMap.with(brows, x, 3, true);
		}

		FaceReading reading = FaceReading.of(
			new com.mopicmp.npcstudio.entity.EyeMap(eyes, whites, brows, true));

		assertTrue(reading.canMoveBrows(), "a brow with columns is a brow that can move");
		// Face column 1 is skin column 9, and the brow ends after column 2.
		assertEquals(9, reading.browOuter(), "the outer end of the brow");
		assertEquals(11, reading.browInner(), "and the column just inside it, which is bare");
		assertEquals(11, reading.browTop());
		assertEquals(12, reading.browBottom(), "one row tall, said as edges");
	}

	/** And a face whose brows nobody marked is left exactly as it was drawn. */
	@Test
	void anUnmarkedBrowIsNotMoved() {
		long eyes = 0;
		long whites = 0;
		for (int y = 4; y <= 5; y++) {
			eyes = com.mopicmp.npcstudio.entity.EyeMap.with(eyes, 2, y, true);
			eyes = com.mopicmp.npcstudio.entity.EyeMap.with(eyes, 5, y, true);
			whites = com.mopicmp.npcstudio.entity.EyeMap.with(whites, 1, y, true);
			whites = com.mopicmp.npcstudio.entity.EyeMap.with(whites, 6, y, true);
		}

		FaceReading reading = FaceReading.of(
			new com.mopicmp.npcstudio.entity.EyeMap(eyes, whites, 0L, true));

		assertTrue(reading.hasEyes(), "the eyes are still found");
		assertFalse(reading.canMoveBrows(), "and the forehead is left alone");
	}

	/**
	 * A fringe, a brow and a two-row eye, which is what real skins look like.
	 *
	 * Straight off a screenshot. Counting down from the top of the band put the
	 * lid on the eyebrows — covering something that was not an eye while the eye
	 * stared out from underneath it. The eyes are the bottom of the band, always:
	 * nothing on a face sits below them and looks like a pair.
	 */
	@Test
	void theLidFindsTheEyeUnderABrowAndAFringe() {
		int[][] pixels = plainFace();
		int[] pair = { 9, 10, 13, 14 };
		for (int x : pair) {
			pixels[11][x] = HAIR;    // fringe
			pixels[12][x] = EYE;     // brow
			pixels[13][x] = WHITE;   // eye, upper half
			pixels[14][x] = EYE;     // eye, lower half
		}

		FaceReading reading = read(pixels);

		assertTrue(reading.hasEyes());
		assertEquals(13, reading.eyeTop(), "the lid starts at the eye, not at the brow");
		assertEquals(15, reading.eyeBottom(), "and covers both of its rows");
	}

	/** A skin prepared for the eye pack is recognisable, and says so. */
	@Test
	void aPreparedSkinIsRecognised() {
		int[][] pixels = withEyes(plainFace());
		assertFalse(read(pixels).prepared());

		for (int y = 4; y < 8; y++) {
			for (int x = 4; x < 8; x++) pixels[y][x] = WHITE;
		}
		assertTrue(read(pixels).prepared(), "something painted in the spare corner");
	}

	/** A fully transparent face has no complexion to compare against. */
	@Test
	void aTransparentFaceIsRefusedRatherThanGuessedAt() {
		int[][] pixels = new int[64][64];
		for (int y = 8; y < 16; y++) {
			for (int x = 8; x < 16; x++) pixels[y][x] = CLEAR;
		}

		assertFalse(read(pixels).hasEyes());
	}

	// ------------------------------------------- the three that reached players

	private static final int HAIR = 0xFF3A2410;

	/**
	 * A fringe covers the corners the complexion used to be measured from.
	 *
	 * This is most of why the eyes worked on roughly half of skins and not the
	 * rest. The old reading took the face's own colour from four corners, two of
	 * which are hair on any character with a fringe — so "skin colour" came out a
	 * blend of hair and jaw, a colour the face is nowhere, and then every skin
	 * pixel counted as a marking. A face that is markings all over is not a pair
	 * of eyes, so the whole face was thrown out.
	 */
	@Test
	void aFringeDoesNotHideTheEyes() {
		int[][] pixels = withEyes(plainFace());
		for (int y = 8; y <= 10; y++) {
			for (int x = 8; x < 16; x++) pixels[y][x] = HAIR;
		}

		FaceReading reading = read(pixels);

		assertTrue(reading.hasEyes(), "the eyes are still there under the fringe");
		assertEquals(12, reading.eyeTop(), "and the lid goes over them, not over the hair");
	}

	/**
	 * A skin drawn at twice the size still has its eyes found.
	 *
	 * The second reason, and it was a clean miss rather than a misjudgement. Only
	 * one pixel of each sixty-fourth was ever looked at, and on a detailed skin an
	 * eye can be a line one pixel wide — so three times out of four the pixel
	 * sampled was the cheek beside the eye and the face came back blank.
	 */
	@Test
	void aSkinAtTwiceTheSizeStillBlinks() {
		int size = 128;
		int[][] big = new int[size][size];
		for (int y = 16; y < 32; y++) {
			for (int x = 16; x < 32; x++) big[y][x] = SKIN;
		}
		// Eyes drawn as a single row of single pixels, deliberately in the corner of
		// each block that the old sampling never looked at.
		for (int x : new int[] { 19, 21, 27, 29 }) {
			big[27][x] = EYE;
		}

		FaceReading reading = FaceReading.of(new FaceReading.Pixels() {
			@Override
			public int at(int x, int y) {
				return big[y * 2][x * 2];
			}

			@Override
			public int unlike(int x, int y, int reference) {
				int furthest = big[y * 2][x * 2];
				int worst = -1;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int colour = big[y * 2 + dy][x * 2 + dx];
						if ((colour >>> 24) < 128) continue;
						int away = Math.abs(((colour >> 16) & 0xFF) - ((reference >> 16) & 0xFF))
							+ Math.abs(((colour >> 8) & 0xFF) - ((reference >> 8) & 0xFF))
							+ Math.abs((colour & 0xFF) - (reference & 0xFF));
						if (away > worst) {
							worst = away;
							furthest = colour;
						}
					}
				}
				return furthest;
			}
		});

		assertTrue(reading.hasEyes(), "a one-pixel eye on a 128-wide skin is still an eye");
	}

	/**
	 * The eyes win over anything higher up the face that also looks like a pair.
	 *
	 * The third reason. The first band found was kept, so a hairline that happened
	 * to have a gap in the middle beat the eyes simply by being above them — and
	 * the lid then came down over the hair while the eyes carried on staring.
	 */
	@Test
	void eyesBeatSomethingHigherUpTheFace() {
		int[][] pixels = withEyes(plainFace());
		// A pair of dark marks near the hairline, with a gap between them: exactly
		// the shape the search is looking for, in the wrong place.
		for (int x : new int[] { 8, 9, 14, 15 }) pixels[10][x] = HAIR;

		FaceReading reading = read(pixels);

		assertTrue(reading.hasEyes());
		assertTrue(reading.eyeTop() >= 12,
			"the lid landed at row " + reading.eyeTop() + " rather than on the eyes");
	}
}
