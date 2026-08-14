package com.mopicmp.npcstudio.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.entity.EyeMap;

/**
 * That the eye slides inside its own socket and nothing is invented.
 *
 * A glance cannot be checked by looking at it: it is one pixel, it lasts a
 * second or two, and the failures worth guarding against — an eye drawn out on
 * the cheek, a socket left half bare, one eye following and the other not — read
 * as a character squinting rather than as something broken.
 *
 * The first version of this passed its own tests and looked awful in game,
 * because what it did was correct and what it drew was flat: the whole eye
 * painted one colour with an iris stamped on it. So these ask about the drawing,
 * not only about the arithmetic — that what is shown is the eye's own pixels,
 * one for one, and that the socket is exactly covered.
 */
class GlanceTest {

	/**
	 * The face nearly every skin draws: a white outside and an iris inside it.
	 *
	 * Face columns 1 and 6 are the whites, 2 and 5 the irises, on rows 4 and 5 —
	 * which is what a person marked on twenty-four of the thirty-eight faces in
	 * {@code test/Eyes/faces.txt} and something close to it on most of the rest.
	 */
	private static FaceReading ordinary() {
		long eyes = 0;
		long whites = 0;
		for (int y = 4; y <= 5; y++) {
			eyes = EyeMap.with(EyeMap.with(eyes, 2, y, true), 5, y, true);
			whites = EyeMap.with(EyeMap.with(whites, 1, y, true), 6, y, true);
		}
		return FaceReading.of(new EyeMap(eyes, whites, 0, true));
	}

	/** The socket this eye lives in, in model x. */
	private static float[] socket(FaceReading face, boolean rightSide) {
		return rightSide
			? new float[] { face.eyeOuter() - 12f, face.eyeInner() - 12f }
			: new float[] { 24f - face.eyeInner() - 12f, 24f - face.eyeOuter() - 12f };
	}

	/**
	 * Whatever is drawn covers the socket exactly: no gap, no spill.
	 *
	 * The property that stops both of the ugly failures at once. A gap shows the
	 * eye's own pixels underneath in the wrong place; a spill puts an eye on
	 * somebody's cheek.
	 */
	@Test
	@DisplayName("the socket is covered exactly, at every fraction of a glance")
	void theSocketIsCoveredExactly() {
		FaceReading face = ordinary();

		for (int step = -20; step <= 20; step++) {
			float aside = step / 20f;
			for (boolean right : new boolean[] { true, false }) {
				Glance glance = Glance.of(face, aside, 0f, 0f, right);
				if (glance == null) continue;

				float[] eye = socket(face, right);
				float low = Math.min(glance.showFrom(), glance.fillFrom());
				float high = Math.max(glance.showTo(), glance.fillTo());

				assertEquals(eye[0], low, 1e-4, "a strip of the socket was left uncovered at " + aside);
				assertEquals(eye[1], high, 1e-4, "the eye was drawn outside its socket at " + aside);
				assertEquals(eye[1] - eye[0],
					(glance.showTo() - glance.showFrom()) + (glance.fillTo() - glance.fillFrom()),
					1e-4, "the two pieces overlap or leave a gap at " + aside);
			}
		}
	}

	/**
	 * What is drawn is the eye's own pixels, at the size they were drawn.
	 *
	 * This is the difference between a glance and a repaint. One texel per pixel,
	 * always — a quad that sampled a wider or narrower range would squash the
	 * drawing, and squashing is how the flat version got its dead look.
	 */
	@Test
	@DisplayName("the eye is copied one texel per pixel, never stretched")
	void theDrawingIsNeverStretched() {
		FaceReading face = ordinary();

		for (int step = -20; step <= 20; step++) {
			Glance glance = Glance.of(face, step / 20f, 0f, 0f, true);
			if (glance == null) continue;

			// The texels sampled run from showU across the width drawn, so equal
			// widths mean one texel per pixel. And they must be texels of this eye.
			float width = glance.showTo() - glance.showFrom();
			assertTrue(glance.showAt() >= face.eyeOuter() - 1e-4
				&& glance.showAt() + width <= face.eyeInner() + 1e-4,
				"the copy sampled from outside the eye: " + glance.showAt() + " for " + width);
		}
	}

	/**
	 * Looking right moves the eye right, and by no more than a pixel.
	 *
	 * Direction is the one thing here that was reasoned out rather than seen — a
	 * character's right is model −x, because vanilla hangs the right arm at −5 —
	 * so it is worth a test that would fail loudly if it were backwards.
	 */
	@Test
	@DisplayName("looking right moves the eye towards the character's right")
	void theEyeGoesTheRightWay() {
		FaceReading face = ordinary();

		Glance right = Glance.of(face, 1f, 0f, 0f, true);
		assertNotNull(right, "the right eye can look right");
		float[] eye = socket(face, true);
		assertEquals(eye[0], right.showFrom(), 1e-4,
			"the drawing should have slid to the outer edge of its own socket");
		assertTrue(right.fillFrom() >= right.showTo() - 1e-4,
			"and the sclera should show at the edge it left");

		Glance left = Glance.of(face, -1f, 0f, 0f, false);
		assertNotNull(left, "and the left eye can look left");
	}

	/**
	 * At rest the eyes are converged, so one of them is already where it is going.
	 *
	 * Not a defect: the still eye is already on the correct side of its own
	 * socket, and moving it would take it onto the bridge of the nose. An earlier
	 * version did exactly that.
	 */
	@Test
	@DisplayName("on an ordinary face one eye moves and the other is already there")
	void onlyOneEyeHasAnywhereToGo() {
		FaceReading face = ordinary();

		assertNotNull(Glance.of(face, 1f, 0f, 0f, true), "the right eye can look right");
		assertNull(Glance.of(face, 1f, 0f, 0f, false), "the left eye is already looking right");
		assertNull(Glance.of(face, -1f, 0f, 0f, true), "the right eye is already looking left");
		assertNotNull(Glance.of(face, -1f, 0f, 0f, false), "the left eye can look left");
	}

	/** The movement is continuous: no jump between one fraction and the next. */
	@Test
	@DisplayName("the eye slides rather than jumping")
	void theMovementIsSmooth() {
		FaceReading face = ordinary();
		float[] eye = socket(face, true);
		float last = eye[1];

		for (int step = 0; step <= 100; step++) {
			Glance glance = Glance.of(face, step / 100f, 0f, 0f, true);
			float at = glance == null ? eye[1] : glance.showTo();
			assertTrue(Math.abs(at - last) < 0.1f,
				"the eye jumped " + Math.abs(at - last) + " of a pixel at " + (step / 100f));
			last = at;
		}
		assertEquals(eye[1] - 1f, last, 1e-4, "a full glance should travel exactly one pixel");
	}

	/** A wider eye has room for both to move. */
	@Test
	@DisplayName("a three-pixel eye moves both eyes")
	void aWiderEyeMovesBoth() {
		long eyes = EyeMap.with(EyeMap.with(0L, 2, 4, true), 5, 4, true);
		long whites = 0;
		for (int x : new int[] { 1, 3, 4, 6 }) whites = EyeMap.with(whites, x, 4, true);
		FaceReading face = FaceReading.of(new EyeMap(eyes, whites, 0, true));

		assertNotNull(Glance.of(face, 1f, 0f, 0f, true), "the right eye has room");
		assertNotNull(Glance.of(face, 1f, 0f, 0f, false), "and so does the left");
	}

	/** Each eye is patched with its own white, not with the other one's. */
	@Test
	@DisplayName("each eye is filled from its own white")
	void eachEyeUsesItsOwnWhite() {
		FaceReading face = ordinary();
		Glance near = Glance.of(face, 1f, 0f, 0f, true);
		Glance far = Glance.of(face, -1f, 0f, 0f, false);

		assertEquals(9, near.white(), "the right eye's white is face column 1");
		assertEquals(14, far.white(), "and the left eye's is column 6, not the right eye's");
	}

	/** A face whose white nobody could find does not glance at all. */
	@Test
	@DisplayName("without a white there is nothing behind the eye to show")
	void noWhiteMeansNoGlance() {
		long eyes = EyeMap.with(EyeMap.with(0L, 2, 4, true), 5, 4, true);
		FaceReading face = FaceReading.of(new EyeMap(eyes, 0L, 0L, true));

		assertTrue(!face.canGlance(), "an eye with no white cannot glance");
		assertNull(Glance.of(face, 1f, 0f, 0f, true));
		assertNull(Glance.of(face, -1f, 0f, 0f, false));
	}

	/** Looking straight ahead draws nothing, so the skin shows through untouched. */
	@Test
	@DisplayName("looking ahead draws nothing at all")
	void aheadDrawsNothing() {
		assertNull(Glance.of(ordinary(), 0f, 0f, 0f, true));
		assertNull(Glance.of(ordinary(), 0f, 0f, 0f, false));
	}

	/**
	 * A widening pupil eats into the white beside it, and no further.
	 *
	 * The pupil is not a shape drawn on the eye — it is where the line between the
	 * two columns falls. So the test is about that line: which way it moves, and
	 * that it stops at the edge of the socket rather than carrying on across a
	 * cheek.
	 */
	@Test
	@DisplayName("a widening pupil grows into its own white and stops there")
	void aPupilGrowsIntoItsWhite() {
		FaceReading face = ordinary();

		for (boolean right : new boolean[] { true, false }) {
			float[] eye = socket(face, right);
			Glance wide = Glance.of(face, 0f, 0f, 1f, right);
			assertNotNull(wide, "a widened pupil is something to draw");
			assertTrue(wide.resizes(), "and it has a width");
			assertTrue(wide.pupil().from() >= eye[0] - 1e-4 && wide.pupil().to() <= eye[1] + 1e-4,
				"the pupil was drawn from " + wide.pupil().from() + " to " + wide.pupil().to()
					+ ", outside a socket running " + eye[0] + " to " + eye[1]);
			assertEquals(eye[1] - eye[0], wide.pupil().to() - wide.pupil().from(), 1e-4,
				"a fully widened pupil should be the whole eye: iris where the white was");
		}
	}

	/** Which column is painted says whether the pupil is growing or shrinking. */
	@Test
	@DisplayName("growing paints iris over white, shrinking paints white over iris")
	void theRightColumnIsPainted() {
		FaceReading face = ordinary();

		assertEquals(face.irisOuter(), Glance.of(face, 0f, 0f, 0.5f, true).pupil().column(),
			"a pupil is always painted with the iris");
		assertEquals(face.whiteAt(), Glance.of(face, 0f, 0f, -0.5f, true).erase().column(),
			"and what it used to fill is painted out with the white");
	}

	/**
	 * The two pupils are the same size, and each is the mirror of the other.
	 *
	 * The property that matters and the one a person would notice instantly: a
	 * character with one pupil bigger than the other does not look thoughtful, it
	 * looks concussed. Since the far eye is worked out by mirroring columns about
	 * the middle of the face, this is exactly where an off-by-one would land.
	 */
	@Test
	@DisplayName("both pupils are the same size and mirror each other")
	void thePupilsMatch() {
		FaceReading face = ordinary();

		for (float size : new float[] { -0.4f, 0.3f, 0.7f, 1f }) {
			Glance near = Glance.of(face, 0f, 0f, size, true);
			Glance far = Glance.of(face, 0f, 0f, size, false);
			assertNotNull(near);
			assertNotNull(far);

			assertEquals(near.pupil().to() - near.pupil().from(),
				far.pupil().to() - far.pupil().from(), 1e-4,
				"one pupil came out a different size from the other at " + size);
			assertEquals(near.pupil().bottom() - near.pupil().top(),
				far.pupil().bottom() - far.pupil().top(), 1e-4,
				"one pupil came out a different height from the other at " + size);
			// Mirrored about the middle of the face, which is model x nought.
			assertEquals(-near.pupil().to(), far.pupil().from(), 1e-4,
				"the far pupil is not where the near one's reflection would be, at " + size);
		}
	}

	/**
	 * The pupil rides along with a glance rather than staying behind.
	 *
	 * Measured by its middle rather than its edge, because the socket clips it: an
	 * eye turned all the way has its iris hard against the far wall, so the edge
	 * cannot travel the full pixel even though the pupil has.
	 */
	@Test
	@DisplayName("a widened pupil moves with the eye it belongs to")
	void thePupilFollowsTheGlance() {
		FaceReading face = ordinary();
		Glance still = Glance.of(face, 0f, 0f, 0.4f, true);
		Glance moved = Glance.of(face, 1f, 0f, 0.4f, true);

		float before = (still.pupil().from() + still.pupil().to()) / 2f;
		float after = (moved.pupil().from() + moved.pupil().to()) / 2f;
		assertTrue(after < before - 0.1f,
			"looking right should carry the pupil right: " + before + " to " + after);

		float[] eye = socket(face, true);
		assertTrue(moved.pupil().from() >= eye[0] - 1e-4 && moved.pupil().to() <= eye[1] + 1e-4,
			"and it must stay inside the socket while doing it");
	}

	/**
	 * A shrinking pupil leaves something of itself, always.
	 *
	 * The one failure here that nobody would catch in a screenshot: an eye whose
	 * pupil has closed to nothing is a blank white oval, which does not read as
	 * bright light — it reads as a character with its eyes removed.
	 */
	@Test
	@DisplayName("a shrinking pupil never eats the whole iris")
	void aShrinkingPupilLeavesSomething() {
		FaceReading face = ordinary();

		for (boolean right : new boolean[] { true, false }) {
			Glance narrow = Glance.of(face, 0f, 0f, -0.5f, right);
			assertNotNull(narrow, "a narrowed pupil is something to draw");
			assertTrue(narrow.pupil().any(), "and something is left of it");
			assertTrue(narrow.pupil().to() - narrow.pupil().from() >= 0.5f - 1e-4,
				"the pupil kept only " + (narrow.pupil().to() - narrow.pupil().from())
					+ " of a one-pixel iris, which is a blank eye");

			float[] eye = socket(face, right);
			assertTrue(narrow.pupil().from() >= eye[0] - 1e-4 && narrow.pupil().to() <= eye[1] + 1e-4,
				"and it stayed inside the socket");
		}
	}

	/** Nothing to move and nothing to resize is nothing to draw. */
	@Test
	@DisplayName("a still eye with an unchanged pupil draws nothing")
	void nothingToDoDrawsNothing() {
		assertNull(Glance.of(ordinary(), 0f, 0f, 0f, true));
		assertNull(Glance.of(ordinary(), 0f, 0f, 0f, false));
	}

	/**
	 * A closing pupil shrinks in both directions, not only sideways.
	 *
	 * The first version moved one edge, so a pupil closing in bright light became a
	 * thin column the full height of the eye — a cat's slit rather than a small
	 * pupil. Nothing about a pupil is taller than it is wide.
	 */
	@Test
	@DisplayName("a closing pupil shrinks upright as well as sideways")
	void aPupilClosesInBothDirections() {
		FaceReading face = ordinary();
		Glance open = Glance.of(face, 0f, 0f, 0.02f, true);
		Glance shut = Glance.of(face, 0f, 0f, -0.5f, true);

		float wasTall = open.erase().bottom() - open.erase().top();
		float isTall = shut.pupil().bottom() - shut.pupil().top();
		float isWide = shut.pupil().to() - shut.pupil().from();

		assertTrue(isTall < wasTall - 0.1f,
			"the pupil kept its full height of " + wasTall + " while closing");
		assertTrue(isTall > 0.1f, "and it must not close to a line: " + isTall);
		assertTrue(isWide > 0.1f, "nor to a column: " + isWide);
	}

	/**
	 * Upwards there is nowhere to grow, and the pupil does not pretend otherwise.
	 *
	 * Measured on the faces marked by hand: the iris fills the whole height of its
	 * own eye, every time. So a widening pupil widens, and a rule that also grew it
	 * upwards would be drawing an iris on an eyelid.
	 */
	@Test
	@DisplayName("a widening pupil does not grow out of the top of the eye")
	void aWidePupilStaysInsideItsRows() {
		FaceReading face = ordinary();
		Glance wide = Glance.of(face, 0f, 0f, 1f, true);

		assertTrue(wide.pupil().top() >= face.eyeTop() - 16f - 1e-4,
			"the pupil was drawn above the eye");
		assertTrue(wide.pupil().bottom() <= face.eyeBottom() - 16f + 1e-4,
			"the pupil was drawn below the eye");
	}

	/** What is painted out is exactly the rectangle the artist drew the iris in. */
	@Test
	@DisplayName("the old pupil is painted out where it actually was")
	void theOldPupilIsCoveredExactly() {
		FaceReading face = ordinary();
		Glance narrow = Glance.of(face, 0f, 0f, -0.4f, true);

		assertEquals(face.irisOuter() - 12f, narrow.erase().from(), 1e-4);
		assertEquals(face.irisInner() - 12f, narrow.erase().to(), 1e-4);
		assertEquals(face.irisTop() - 16f, narrow.erase().top(), 1e-4);
		assertEquals(face.irisBottom() - 16f, narrow.erase().bottom(), 1e-4);
	}

	/**
	 * Looking down puts the pupil in the lower part of its own eye.
	 *
	 * Not by moving it — there is nowhere to move to, the iris being as tall as the
	 * eye — but by giving up the top of itself. That is what a pixel artist draws,
	 * and it is the only thing that fits inside two rows.
	 */
	@Test
	@DisplayName("looking down puts the pupil in the lower half of the eye")
	void lookingDownLowersThePupil() {
		FaceReading face = ordinary();
		Glance down = Glance.of(face, 0f, -1f, 0f, true);
		Glance up = Glance.of(face, 0f, 1f, 0f, true);

		assertNotNull(down, "looking down is something to draw");
		assertNotNull(up, "and so is looking up");

		assertTrue(down.pupil().top() > up.pupil().top() + 0.1f,
			"looking down should start the pupil lower than looking up does");
		assertTrue(up.pupil().bottom() < down.pupil().bottom() - 0.1f,
			"and end it higher");

		// Both stay inside the eye, and neither closes to a line.
		for (Glance look : new Glance[] { down, up }) {
			assertTrue(look.pupil().top() >= face.eyeTop() - 16f - 1e-4
				&& look.pupil().bottom() <= face.eyeBottom() - 16f + 1e-4,
				"the pupil left the eye while looking up or down");
			assertTrue(look.pupil().bottom() - look.pupil().top() > 0.1f,
				"a pupil looking up or down still has to be a pupil");
		}
	}

	/** Straight ahead, the pupil is the height the artist drew it. */
	@Test
	@DisplayName("looking level leaves the pupil the height it was drawn")
	void levelLeavesTheHeightAlone() {
		FaceReading face = ordinary();
		Glance level = Glance.of(face, 0f, 0f, 0.5f, true);

		assertEquals(face.irisTop() - 16f, level.pupil().top(), 1e-4);
		assertEquals(face.irisBottom() - 16f, level.pupil().bottom(), 1e-4);
	}
}
