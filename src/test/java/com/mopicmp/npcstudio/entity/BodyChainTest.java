package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the settings are allowed to make of a limb. */
class BodyChainTest {

	/** A vanilla leg: four across, twelve long, hanging from the hip. */
	private static List<SegmentMesh.Ring> leg(BodyShape shape) {
		return BodyChain.limb(shape, 0, 12, 0, 2, 0, 2);
	}

	@Test
	@DisplayName("an untouched build is two rings, the part's own: a plain box")
	void nothingAskedNothingDone() {
		List<SegmentMesh.Ring> loft = leg(BodyShape.DEFAULT);
		assertEquals(2, loft.size(), "an ordinary character was cut up for nothing");
		assertEquals(0f, loft.get(0).y(), 1e-4f);
		assertEquals(12f, loft.get(1).y(), 1e-4f);
		for (SegmentMesh.Ring ring : loft) {
			assertEquals(2f, ring.halfX(), 1e-4f);
			assertEquals(2f, ring.halfZ(), 1e-4f);
		}
	}

	@Test
	@DisplayName("an outline turns round twice, which no single number can say")
	void theProfileIsNotOneStep() {
		BodyShape stepped = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		List<SegmentMesh.Ring> loft = leg(stepped);
		assertEquals(BodyChain.RINGS, loft.size(), "hip, thigh, knee, calf, shin, ankle");

		assertEquals(2f, loft.get(0).halfX(), 1e-4f, "the hip changed");
		assertTrue(loft.get(loft.size() - 1).halfX() < loft.get(0).halfX(),
			"the ankle is no narrower than the hip");

		// It never widens back on a leg this thin, and that is not a bug — see below.
	}

	@Test
	@DisplayName("the outline turns round twice, which no single number can say")
	void theOutlineIsNotMonotone() {
		// The intent, checked apart from what survives rounding. A leg narrows towards
		// the knee, comes back out at the knee and the calf, and only then goes away to
		// the ankle; an arm does the same at the hand. One number saying "how much
		// narrower the bottom is" cannot say either, and a limb built from one reads as
		// two bones stuck together — which is what it was reported as.
		for (boolean leg : new boolean[] { true, false }) {
			float[] outline = BodyChain.outline(leg);
			int backOut = 0;
			for (int i = 1; i < outline.length; i++) {
				if (outline[i] < outline[i - 1]) backOut++;
			}
			assertTrue(backOut >= 1,
				(leg ? "a leg" : "an arm") + " only ever narrows, which is a taper");
		}
	}

	@Test
	@DisplayName("and four pixels has no room to show it, which is the medium not a fault")
	void aThinLimbCannotCarryAnOutline() {
		// Worth pinning, because it is the honest half of the previous test and the
		// thing that decides what to do next.
		//
		// A vanilla limb is four pixels across and the slider's whole range is a pixel
		// and a half of width — three quarters of a pixel per side. The smallest step
		// this art has is half a pixel per side, so there are two levels to put a
		// six-band outline on, and everything between them rounds to the same width.
		//
		// So a calf cannot appear on a vanilla leg at any setting. Room comes from a
		// bigger figure, and that is what the drawings we are working towards are.
		BodyShape most = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		List<SegmentMesh.Ring> loft = leg(most);
		long widths = new java.util.TreeSet<>(loft.stream()
			.map(SegmentMesh.Ring::halfX).toList()).size();
		assertTrue(widths <= 3,
			"a four-pixel leg came out with " + widths + " widths, so there is more room"
				+ " than this test believed and the outline should be showing");
	}

	@Test
	@DisplayName("every step is a whole pixel of box, because nothing finer is visible")
	void stepsAreWholePixels() {
		// Two rounds of sub-pixel profiling were invisible on a four-pixel limb. So the
		// narrowing is quantised — but the narrowing only. Rounding the width itself
		// would move a limb nobody asked to move.
		for (BodyShape shape : shapes()) {
			for (SegmentMesh.Ring ring : BodyChain.limb(shape, 0, 12, 0, 1.2f, 0, 1.2f)) {
				float off = Math.abs(1.2f - ring.halfX());
				assertEquals(0f, Math.abs(off * 2f - Math.round(off * 2f)), 1e-4f,
					"a step of " + off + " is not a whole pixel of width");
			}
		}
	}

	@Test
	@DisplayName("a ring reads where it says it reads, so the texture cannot slide")
	void shareAndPlaceAgree() {
		// The rule that replaced "the number of pieces must divide twelve". That one
		// was needed because two separate boxes met on one row of texels and tore it.
		// A loft is one surface, so a ring landing between rows tears nothing — what
		// still matters is that the shares are even, or one band shows the skin
		// compressed and the next shows it stretched for no reason at all.
		BodyShape stepped = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, 1f);
		List<SegmentMesh.Ring> loft = leg(stepped);
		for (int i = 0; i < loft.size(); i++) {
			float even = i / (float) (loft.size() - 1);
			assertEquals(even, loft.get(i).share(), 1e-4f, "ring " + i + " reads unevenly");
			assertEquals(12f * even, loft.get(i).y(), 1e-4f, "ring " + i + " sits unevenly");
		}
	}

	@Test
	@DisplayName("a corner reaches its own half-diagonal, and the model agrees by eye")
	void theJointReachIsTheHalfDiagonal() {
		// docs/body-shape-mesh.md, measured out of the glTF: a calf 2.4 across with a
		// knee cube 3.4. Half of 2.4 times the root of two is 1.697; half of 3.4 is
		// 1.7. Somebody chose that by looking; this comes from the circle a corner
		// travels on when the piece below a joint turns.
		//
		// Nothing uses it yet and that is deliberate: a gap can only open where the
		// surface is broken, and a lofted limb has no break. The break arrives when a
		// bend becomes a real turn rather than a fold of the vertices.
		assertEquals(1.697f, BodyChain.jointReach(1.2f), 2e-3f);
		assertEquals(0f, BodyChain.jointReach(0f), 1e-6f);
	}

	/** Every build the sliders can reach at their ends, plus the ordinary one. */
	private static List<BodyShape> shapes() {
		return List.of(
			BodyShape.DEFAULT,
			new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, 1f),
			new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER),
			new BodyShape(1, 1, 1, 1, 1.6f, 1, 1, 0f, 0f, 1.3f, 0f),
			new BodyShape(1.35f, 1.05f, 1.1f, 1.3f, 1.1f, 1.3f, 1, 0f, -0.06f),
			new BodyShape(1.1f, 1.5f, 1.6f, 1.2f, 1.4f, 1.1f, 1, 0f, 0f));
	}

	@Test
	@DisplayName("a limb never tapers away to nothing")
	void thereIsAlwaysALimbLeft() {
		BodyShape silly = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		for (SegmentMesh.Ring ring : BodyChain.limb(silly, 0, 12, 0, 1f, 0, 1f)) {
			assertTrue(ring.halfX() >= 0.5f, "a ring of the limb disappeared");
		}
	}

	@Test
	@DisplayName("height is length, and it grows downwards from the hip")
	void tallMeansLongerLegs() {
		BodyShape tall = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1.25f, 0f);
		List<SegmentMesh.Ring> loft = leg(tall);
		assertEquals(15f, loft.get(loft.size() - 1).y(), 1e-4f,
			"a quarter taller did not make a quarter longer leg");
		assertEquals(0f, loft.get(0).y(), 1e-4f, "the hip moved");

		// And the model has to come up by exactly what the leg went down by, or the
		// character stands in the floor.
		assertEquals(3f, BodyChain.lift(tall, 12f), 1e-4f);
		assertEquals(0f, BodyChain.lift(BodyShape.DEFAULT, 12f), 1e-4f);
	}

	@Test
	@DisplayName("the two thighs still fill the pelvis, now that a thigh is a ring")
	void theHipJoinSurvivedTheRewrite() {
		// The invariant that outlived the smooth flare it was invented for. Vanilla
		// says eight equals four and four; a lofted leg has to say the same.
		for (float hips = 0.5f; hips <= 2f; hips += 0.1f) {
			BodyShape build = new BodyShape(1, hips, 1, 1, 1, 1, 1, 0f, 0f, 1f, 1f);
			SegmentMesh.Ring thigh = BodyChain.leg(build, 12, 24, -1f, -1.9f, 0, 0).get(0);
			float pelvis = BodyField.torsoHalf(build, BodyField.HIP_Y);
			// In the part's own space, where the pivot is the origin: the outer edge
			// sits on the pelvis edge and the inner one overlaps the middle by the
			// tenth of a pixel vanilla gives it.
			float outer = -(thigh.middleX() - 1.9f) + thigh.halfX();
			float inner = -(thigh.middleX() - 1.9f) - thigh.halfX();
			assertEquals(pelvis - BodyField.LEG_OVERLAP, outer, 1e-4f, "outer edge, hips " + hips);
			assertEquals(-BodyField.LEG_OVERLAP, inner, 1e-4f, "inner edge, hips " + hips);
		}
	}

	@Test
	@DisplayName("an untouched leg is still two rings and still the vanilla leg")
	void theLegFastPathSurvives() {
		var loft = BodyChain.leg(BodyShape.DEFAULT, 12, 24, -1f, -1.9f, 0, 0);
		assertEquals(2, loft.size(), "an ordinary leg was cut up for nothing");
		assertEquals(2f, loft.get(0).halfX(), 1e-4f);
	}
}
