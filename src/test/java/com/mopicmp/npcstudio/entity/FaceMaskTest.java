package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a face marked at any size survives being written down and read back.
 *
 * The mask is the only thing in the mod a person makes by hand and cannot make
 * again quickly — an afternoon of marking is an afternoon — so the properties
 * worth testing are all about not losing it: a round trip that changes nothing, a
 * file that will not parse costing one costume rather than a world, and the size
 * and the layer travelling with the mask so a face made on a 2048-wide skin is
 * not silently read as an eight-pixel one.
 */
class FaceMaskTest {

	private static long[][] empty(int size) {
		return new long[FaceMask.PARTS][FaceMask.words(size)];
	}

	private static void set(long[] mask, int size, int x, int y) {
		int bit = y * size + x;
		mask[bit >>> 6] |= 1L << (bit & 63);
	}

	/** What goes in comes out, at every size a skin can be. */
	@Test
	@DisplayName("a mask written down and read back is the same mask")
	void theRoundTripChangesNothing() {
		for (int size = 8; size <= FaceMask.LARGEST; size *= 2) {
			long[][] masks = empty(size);
			// Something on every kind and both layers, in a shape nothing could
			// produce by accident.
			for (int y = size / 4; y < size / 2; y++) {
				for (int x = size / 8; x < size / 3; x++) {
					set(masks[FaceMask.part(FaceMask.Kind.EYE, FaceMask.Layer.FACE)], size, x, y);
				}
			}
			set(masks[FaceMask.part(FaceMask.Kind.WHITE, FaceMask.Layer.OVER)], size,
				size - 1, size - 1);
			set(masks[FaceMask.part(FaceMask.Kind.BROW, FaceMask.Layer.FACE)], size, 0, 0);
			set(masks[FaceMask.part(FaceMask.Kind.LASH, FaceMask.Layer.OVER)], size,
				size / 2, size / 2);

			FaceMask was = new FaceMask(size, masks, true);
			FaceMask back = FaceMask.decode(was.encode(), true);
			assertEquals(was, back, "a mask of " + size + " did not survive the trip");
			assertEquals(size, back.size(), "and the size has to come back with it");
			assertTrue(back.is(FaceMask.Kind.LASH, FaceMask.Layer.OVER, size / 2, size / 2),
				"a lash on the outer layer is still a lash on the outer layer");
			assertFalse(back.is(FaceMask.Kind.LASH, FaceMask.Layer.FACE, size / 2, size / 2),
				"and has not slid down to the face");
		}
	}

	/**
	 * The layer is part of the answer, not a note about it.
	 *
	 * HD skins commonly draw an iris on the outer layer above a sclera on the face,
	 * and a mask that could not tell them apart could not be drawn from: the two
	 * layers are different geometry at different depths, so an iris moved on the
	 * wrong one slides along underneath its own outline.
	 */
	@Test
	@DisplayName("the same pixel can be one thing on one layer and another on the other")
	void layersAreKeptApart() {
		int size = 16;
		long[][] masks = empty(size);
		set(masks[FaceMask.part(FaceMask.Kind.WHITE, FaceMask.Layer.FACE)], size, 5, 6);
		set(masks[FaceMask.part(FaceMask.Kind.EYE, FaceMask.Layer.OVER)], size, 5, 6);

		FaceMask mask = new FaceMask(size, masks, true);

		assertTrue(mask.is(FaceMask.Kind.WHITE, FaceMask.Layer.FACE, 5, 6));
		assertTrue(mask.is(FaceMask.Kind.EYE, FaceMask.Layer.OVER, 5, 6));
		assertFalse(mask.is(FaceMask.Kind.EYE, FaceMask.Layer.FACE, 5, 6),
			"the iris is on the layer above, not on the face");
		assertEquals(mask, FaceMask.decode(mask.encode(), true), "and that survives the file");
	}

	/**
	 * A mask stays small however large the face it is on.
	 *
	 * The whole reason for run lengths. A face at two hundred and fifty-six across
	 * is sixty-five thousand bits eight times over, and it has to sit in a save file
	 * and travel to every client — so what matters is not the size of the grid but
	 * the number of blobs on it, and a person marks a handful. Seven of the eight
	 * masks are usually empty, and an empty mask is one number.
	 */
	@Test
	@DisplayName("a hand-marked face encodes to a few hundred bytes at any size")
	void aMarkedFaceStaysSmall() {
		int size = FaceMask.LARGEST;
		long[][] masks = empty(size);
		long[] eyes = masks[FaceMask.part(FaceMask.Kind.EYE, FaceMask.Layer.FACE)];
		// Two eyes, as solid rectangles, which is what a person actually paints.
		for (int y = size * 3 / 8; y < size / 2; y++) {
			for (int x = size / 8; x < size * 3 / 8; x++) {
				set(eyes, size, x, y);
				set(eyes, size, size - 1 - x, y);
			}
		}

		String written = new FaceMask(size, masks, true).encode();
		assertTrue(written.length() < 2000,
			"two eyes on the largest face came to " + written.length() + " characters");
	}

	/**
	 * A fine mask still answers the question everything else asks.
	 *
	 * Any cell marked inside a block marks the block, rather than most of them: a
	 * lid that covers a row too many is a small ugliness and a lid that misses the
	 * eye is a character that never blinks.
	 */
	@Test
	@DisplayName("a fine mask reduces to the eighths everything else speaks")
	void aFineMaskReducesToEighths() {
		int size = 32;
		long[][] masks = empty(size);
		// One cell, in the block that is face column 2, row 3.
		set(masks[FaceMask.part(FaceMask.Kind.EYE, FaceMask.Layer.OVER)], size, 9, 13);

		EyeMap coarse = new FaceMask(size, masks, true).reduce();

		assertTrue(coarse.isEye(2, 3), "the block the cell is in is marked");
		assertFalse(coarse.isEye(2, 4), "and no other");
		assertEquals(1, EyeMap.count(coarse.eyes()), "exactly one block");
		assertTrue(coarse.authored(), "and it is still a person's answer");
	}

	/**
	 * A lash folds in with the brow when the answer is squeezed into eighths.
	 *
	 * A loss, and a deliberate one: the coarse map has no room for the distinction
	 * and predates the need for it. Better a lash counted as a brow than a lash
	 * dropped on the floor, since both are marks above the eye that the blink has
	 * to know about.
	 */
	@Test
	@DisplayName("a lash reduces into the brow, because eighths have no word for it")
	void aLashFoldsIntoTheBrow() {
		int size = 16;
		long[][] masks = empty(size);
		set(masks[FaceMask.part(FaceMask.Kind.LASH, FaceMask.Layer.FACE)], size, 4, 8);

		EyeMap coarse = new FaceMask(size, masks, true).reduce();
		assertTrue(coarse.isBrow(2, 4), "the lash is somewhere in the coarse answer");
	}

	/** An ordinary eight-by-eight map goes in and comes out unchanged. */
	@Test
	@DisplayName("an ordinary map is a fine mask that happens to be coarse")
	void anOrdinaryMapIsUnchanged() {
		EyeMap map = new EyeMap(0x1234_5678_9ABC_DEF0L, 0xFF00FF00L, 0x0F0F0F0FL, true);
		FaceMask mask = FaceMask.of(map);

		assertEquals(EyeMap.SIZE, mask.size());
		assertEquals(map, mask.reduce(), "reducing something already coarse changes nothing");
		assertEquals(mask, FaceMask.decode(mask.encode(), true));
	}

	/**
	 * A mask from before lashes and layers existed still means what it meant.
	 *
	 * Somebody who marked a wardrobe under the old build keeps every costume of it:
	 * three groups rather than eight is eye, white and brow, all on the face.
	 */
	@Test
	@DisplayName("a mask written by the old build is read as the old build meant it")
	void theOldFormStillReads() {
		EyeMap map = new EyeMap(0x00000000_0000FF00L, 0x00000000_00FF0000L, 0xFFL, true);
		// The shape the old encoder produced: size, then exactly three groups.
		String old = "8:" + runs(map.eyes()) + "/" + runs(map.whites()) + "/" + runs(map.brows());

		FaceMask back = FaceMask.decode(old, true);

		assertEquals(map, back.reduce(), "the old three masks came back changed");
		assertTrue(back.is(FaceMask.Kind.EYE, FaceMask.Layer.FACE, 0, 1),
			"and they are on the face, which is where they used to be");
	}

	private static String runs(long mask) {
		StringBuilder text = new StringBuilder();
		boolean on = false;
		int at = 0;
		while (at < 64) {
			int run = 0;
			while (at + run < 64 && ((mask >>> (at + run)) & 1L) == (on ? 1L : 0L)) run++;
			if (text.length() > 0) text.append('.');
			text.append(Integer.toHexString(run));
			at += run;
			on = !on;
		}
		return text.toString();
	}

	/**
	 * Nonsense in the file is a face nobody marked, not an exception.
	 *
	 * One costume whose mask somebody hand-edited into rubbish should cost that
	 * costume its blink. It must not cost the world its wardrobe, and it must not
	 * stop the world loading.
	 */
	@Test
	@DisplayName("an unreadable mask is an unmarked face rather than a crash")
	void rubbishIsHarmless() {
		assertSame(FaceMask.NONE, FaceMask.decode(null, true));
		assertSame(FaceMask.NONE, FaceMask.decode("", true));
		assertTrue(FaceMask.decode("not a mask", true).isNone());
		assertTrue(FaceMask.decode("10:", true).isNone());
		assertTrue(FaceMask.decode("zz:1/2/3", true).isNone());
		assertTrue(FaceMask.decode("10:1.2/3/4/5", true).isNone(), "neither three groups nor eight");

		// Runs that claim more bits than the face has are cut off at its edge rather
		// than running past the end of the array. Eight squared is sixty-four bits
		// and this asks for two hundred and fifty-five of them.
		FaceMask overrun = FaceMask.decode("8:0.ff//", true);
		assertFalse(overrun.isNone(), "the bits that do fit are still set");
		assertTrue(overrun.is(FaceMask.Kind.EYE, FaceMask.Layer.FACE, 7, 7),
			"right up to the last one");
	}

	/** A size that could not have come from a skin is rounded to one that could. */
	@Test
	@DisplayName("a size is a power of two from eight up, whatever the file says")
	void sizesAreSizesSkinsCanBe() {
		assertEquals(8, new FaceMask(1, null, false).size());
		assertEquals(8, new FaceMask(15, null, false).size());
		assertEquals(16, new FaceMask(16, null, false).size());
		assertEquals(32, new FaceMask(63, null, false).size());
		assertEquals(FaceMask.LARGEST, new FaceMask(99999, null, false).size());
	}

	/** Nothing marked is nothing to store. */
	@Test
	@DisplayName("an unmarked face writes nothing at all")
	void anUnmarkedFaceIsEmpty() {
		assertTrue(FaceMask.NONE.isNone());
		assertEquals("", FaceMask.NONE.encode());
		assertTrue(new FaceMask(64, null, true).isNone());
	}
}
