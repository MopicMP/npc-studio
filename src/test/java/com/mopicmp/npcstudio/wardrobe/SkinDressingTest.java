package com.mopicmp.npcstudio.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Wearing a costume, checked on the cases that decide the rule.
 *
 * The interesting ones are not "the shirt changed". They are the two the rule
 * has to tell apart without being told: a costume that leaves the head alone,
 * and a costume that deliberately puts something on it.
 */
class SkinDressingTest {

	private static final int WIDTH = 64;
	private static final int CLEAR = 0x00000000;
	private static final int FACE = 0xFFC49A6C;
	private static final int SHIRT = 0xFF2E5FA3;
	private static final int GLASSES = 0xFF101010;

	private static int[] filled(int colour) {
		int[] pixels = new int[WIDTH * WIDTH];
		java.util.Arrays.fill(pixels, colour);
		return pixels;
	}

	private static int[] blank() {
		return new int[WIDTH * WIDTH];
	}

	private static int at(int[] pixels, int x, int y) {
		return pixels[y * WIDTH + x];
	}

	private static void paint(int[] pixels, int x, int y, int colour) {
		pixels[y * WIDTH + x] = colour;
	}

	/** A costume with nothing above the neck leaves the face exactly as it was. */
	@Test
	void anOrdinaryCostumeKeepsTheHead() {
		int[] base = filled(FACE);
		int[] costume = blank();
		paint(costume, 22, 22, SHIRT);

		int[] worn = SkinDressing.wear(base, costume, WIDTH, SkinDressing.Head.AUTOMATIC);

		assertEquals(FACE, at(worn, 10, 10), "the face is untouched");
		assertEquals(SHIRT, at(worn, 22, 22), "and the shirt arrived");
	}

	/**
	 * A costume that paints spectacles reaches the face.
	 *
	 * This is the case the first rule got wrong. "Never touch the head" is right
	 * for most costumes and wrong for exactly the ones somebody took trouble
	 * over — so what a costume paints is what it claims, wherever it is.
	 */
	@Test
	void spectaclesOnACostumeReachTheFace() {
		int[] base = filled(FACE);
		int[] costume = blank();
		paint(costume, 22, 22, SHIRT);
		paint(costume, 9, 12, GLASSES);
		paint(costume, 14, 12, GLASSES);

		int[] worn = SkinDressing.wear(base, costume, WIDTH, SkinDressing.Head.AUTOMATIC);

		assertEquals(GLASSES, at(worn, 9, 12), "the costume claimed this pixel");
		assertEquals(GLASSES, at(worn, 14, 12));
		assertEquals(FACE, at(worn, 11, 12), "and claimed nothing between them");
	}

	/**
	 * A whole skin used as a costume must not bring its face with it.
	 *
	 * This is the case the mask cannot answer — a skin is opaque everywhere, so
	 * read literally it claims the head too, which is the one thing a costume
	 * must never do.
	 */
	@Test
	void awholeSkinUsedAsACostumeLeavesTheHeadBehind() {
		int[] base = filled(FACE);
		int[] other = filled(SHIRT);

		assertTrue(SkinDressing.looksLikeAWholeSkin(other, WIDTH));

		int[] worn = SkinDressing.wear(base, other, WIDTH, SkinDressing.Head.AUTOMATIC);

		assertEquals(FACE, at(worn, 10, 10), "the original face stayed");
		assertEquals(SHIRT, at(worn, 22, 22), "the body came across");
	}

	/** A costume with a few pixels on the head is still a costume. */
	@Test
	void aFewPixelsOnTheHeadDoNotMakeItASkin() {
		int[] costume = blank();
		for (int x = 8; x < 16; x++) paint(costume, x, 12, GLASSES);

		assertFalse(SkinDressing.looksLikeAWholeSkin(costume, WIDTH),
			"one line of spectacles is not a face");
	}

	/** Asked outright, the reading is not consulted. */
	@Test
	void theReadingCanBeOverruled() {
		int[] base = filled(FACE);
		int[] other = filled(SHIRT);

		int[] whole = SkinDressing.wear(base, other, WIDTH, SkinDressing.Head.REPLACE);
		assertEquals(SHIRT, at(whole, 10, 10), "asked for the head, got the head");

		int[] kept = SkinDressing.wear(base, other, WIDTH, SkinDressing.Head.KEEP);
		assertEquals(FACE, at(kept, 10, 10));
	}

	/**
	 * A detailed costume on a plain face, and the detail survives.
	 *
	 * Mixed resolutions are not a special case to be avoided — a 128 costume over
	 * a 64 face is an ordinary thing to want, and resolution is a property of a
	 * picture rather than a category of character. So the smaller grows to meet
	 * the larger: reducing the bigger one would throw away exactly the detail
	 * somebody drew it for.
	 */
	@Test
	void aDetailedCostumeOverAPlainFaceKeepsItsDetail() {
		int[] base = filled(FACE);
		int[] costume = new int[128 * 128];
		// One pixel of shirt, at a place that only exists at the larger size.
		costume[45 * 128 + 45] = SHIRT;

		SkinDressing.Worn worn = SkinDressing.wear(base, 64, costume, 128,
			SkinDressing.Head.AUTOMATIC);

		assertEquals(128, worn.width(), "the result is as detailed as its most detailed part");
		assertEquals(SHIRT, worn.pixels()[45 * 128 + 45], "the fine detail is where it was drawn");
		assertEquals(FACE, worn.pixels()[10 * 128 + 10], "and the face came up with it");
	}

	/** Doubling a picture repeats pixels rather than blending them. */
	@Test
	void enlargingIsPixelDoubling() {
		int[] small = new int[64 * 64];
		small[0] = SHIRT;

		int[] big = SkinDressing.enlarge(small, 64, 128);

		assertEquals(128 * 128, big.length);
		assertEquals(SHIRT, big[0]);
		assertEquals(SHIRT, big[1], "the pixel became two across");
		assertEquals(SHIRT, big[128], "and two down");
		assertEquals(0, big[2], "and no further");
	}

	/**
	 * An old half-height skin is a different layout, not a smaller one.
	 *
	 * Scaling it would line the arms up with the legs, so it is refused with a
	 * reason rather than composited into nonsense.
	 */
	@Test
	void anOldHalfHeightSkinIsRefusedRatherThanMangled() {
		int[] legacy = new int[64 * 32];
		SkinDressing.Worn worn = SkinDressing.wear(filled(FACE), 64, legacy, 64,
			SkinDressing.Head.AUTOMATIC);

		assertNotNull(worn.refused(), "it says why rather than producing rubbish");
		assertEquals(FACE, worn.pixels()[10 * 64 + 10], "and leaves the character as it was");
	}

	/** Neither picture handed in is altered. */
	@Test
	void theOriginalsAreLeftAlone() {
		int[] base = filled(FACE);
		int[] costume = blank();
		paint(costume, 22, 22, SHIRT);

		SkinDressing.wear(base, costume, WIDTH, SkinDressing.Head.AUTOMATIC);

		assertEquals(FACE, at(base, 22, 22), "the base skin was not written over");
		assertEquals(CLEAR, at(costume, 10, 10), "nor the costume");
	}
}
