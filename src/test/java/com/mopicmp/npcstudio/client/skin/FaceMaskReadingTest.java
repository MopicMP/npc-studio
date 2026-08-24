package com.mopicmp.npcstudio.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.entity.FaceMask;

/**
 * Reading a hand-marked face at the size it was marked.
 *
 * <h2>What went wrong, so that it cannot go wrong again quietly</h2>
 *
 * The mask was squeezed through an eight-by-eight grid on its way to the
 * drawing. On an ordinary sixty-four-wide skin the face is eight texels across
 * and that grid is exact. On the two-hundred-and-fifty-six-wide skin this was
 * reported against, the face is thirty-two texels across and one cell is four
 * texels by four — so every mark somebody made was rounded to a block of
 * sixteen.
 *
 * Nothing about that was visible as rounding. It came out as skin travelling
 * with the pupil, as a torn drawing wherever the pupil narrowed, as an eyelid
 * the colour of the iris, and as a glance that jumped a third of an eye instead
 * of sliding. Four separate-looking faults, one cause — which is exactly the
 * shape of thing a test has to hold down, because by the time it is visible it
 * no longer looks like arithmetic.
 */
class FaceMaskReadingTest {

	/** The face of the skin this was reported against: 256 wide, so 32 across. */
	private static final int HD = 32;

	private static long[][] blank(int size) {
		return new long[FaceMask.PARTS][FaceMask.words(size)];
	}

	private static void mark(long[][] masks, int size, FaceMask.Kind kind, int x, int y) {
		int bit = y * size + x;
		masks[FaceMask.part(kind, FaceMask.Layer.FACE)][bit >>> 6] |= 1L << (bit & 63);
	}

	/** One eye on the left half: a white column, three of iris, a white column. */
	private static FaceMask anEye(int size, int left, int top, int tall) {
		long[][] masks = blank(size);
		for (int y = top; y < top + tall; y++) {
			mark(masks, size, FaceMask.Kind.WHITE, left, y);
			for (int x = left + 1; x <= left + 3; x++) mark(masks, size, FaceMask.Kind.EYE, x, y);
			mark(masks, size, FaceMask.Kind.WHITE, left + 4, y);
		}
		return new FaceMask(size, masks, true);
	}

	@Test
	@DisplayName("a texel of a large face is a quarter of a unit, not a whole one")
	void aTexelIsNotAUnit() {
		assertEquals(0.25f, FaceReading.of(anEye(HD, 5, 15, 4)).texel(), 1e-5,
			"the face is 32 texels across and eight units wide");
		assertEquals(1f, FaceReading.of(anEye(8, 1, 3, 1)).texel(), 1e-5,
			"on a plain skin a texel and a unit are the same thing, as they always were");
	}

	@Test
	@DisplayName("the eye is read where it was marked, to the texel")
	void theEyeIsWhereItWasMarked() {
		// Marked at columns 5..9 and rows 15..18 of a 32-wide face. In sixty-fourths
		// the face starts at 8, so that is 8 + 5/4 = 9.25 across and 8 + 15/4 = 11.75
		// down. Rounded to cells it would have been 9 and 11 — a texel and a half out
		// in one direction and three quarters in the other, on an eye four texels tall.
		FaceReading face = FaceReading.of(anEye(HD, 5, 15, 4));

		assertEquals(9.25f, face.eyeOuter(), 1e-5);
		assertEquals(10.5f, face.eyeInner(), 1e-5);
		assertEquals(11.75f, face.eyeTop(), 1e-5);
		assertEquals(12.75f, face.eyeBottom(), 1e-5);
	}

	@Test
	@DisplayName("the iris is told from its white, and neither swallows the other")
	void theIrisIsItsOwnStrip() {
		FaceReading face = FaceReading.of(anEye(HD, 5, 15, 4));

		// Three texels of iris, starting one texel in from the eye's outer edge.
		assertEquals(9.5f, face.irisOuter(), 1e-5);
		assertEquals(10.25f, face.irisInner(), 1e-5);
		assertEquals(0.75f, face.irisInner() - face.irisOuter(), 1e-5,
			"three texels, not one cell and not the whole eye");

		// And the column the sclera is borrowed from is a marked white one, which is
		// the outer column here.
		assertEquals(9.25f, face.whiteAt(), 1e-5);
		assertTrue(face.canGlance(), "an eye with a white and an iris can look about");
	}

	@Test
	@DisplayName("the lid borrows its colour from outside the eye, not from inside it")
	void theLidBorrowsBareSkin() {
		FaceReading face = FaceReading.of(anEye(HD, 5, 15, 4));

		// The lid takes one texel starting at the eye's inner edge, which is the
		// first column that is not eye. A whole unit from there is four texels wide
		// and reaches back over the iris — which is what made a blink come down in
		// the colour of somebody's pupil.
		float borrowedFrom = face.eyeInner();
		float borrowedTo = face.eyeInner() + face.texel();

		assertTrue(borrowedFrom >= face.eyeInner(), "it starts outside the eye");
		assertTrue(borrowedTo <= face.eyeInner() + 1f, "and it does not span a whole unit");
		assertEquals(0.25f, borrowedTo - borrowedFrom, 1e-5, "one texel of cheek");
	}

	@Test
	@DisplayName("an eye marked on the right half is mirrored rather than lost")
	void theOtherHalfIsMirrored() {
		// The drawing works from one half of the face and mirrors it, so a face
		// marked only on the far side has to be readable all the same.
		long[][] masks = blank(HD);
		for (int y = 15; y < 19; y++) {
			mark(masks, HD, FaceMask.Kind.WHITE, 26, y);
			for (int x = 23; x <= 25; x++) mark(masks, HD, FaceMask.Kind.EYE, x, y);
		}

		FaceReading face = FaceReading.of(new FaceMask(HD, masks, true));

		assertTrue(face.hasEyes());
		assertTrue(face.eyeInner() > face.eyeOuter(), "it came back as a real socket");
	}

	@Test
	@DisplayName("a face nobody marked reads as nothing rather than as an eye at the origin")
	void anEmptyMaskIsBlank() {
		assertEquals(FaceReading.BLANK, FaceReading.of(FaceMask.NONE));
		assertEquals(FaceReading.BLANK, FaceReading.of((FaceMask) null));
	}
}
