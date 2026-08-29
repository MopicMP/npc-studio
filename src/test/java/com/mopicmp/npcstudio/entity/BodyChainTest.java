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
	@DisplayName("an outline comes back out, which no single number can say")
	void theProfileIsNotOneStep() {
		BodyShape stepped = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		List<SegmentMesh.Ring> loft = leg(stepped);
		assertEquals(BodyChain.RINGS, loft.size(), "hip, thigh, knee, calf, shin, ankle");

		assertEquals(2f, loft.get(0).halfX(), 1e-4f, "the hip changed");
		assertTrue(loft.get(loft.size() - 1).halfX() < loft.get(0).halfX(),
			"the ankle is no narrower than the hip");

		// And it does widen back at the calf, on a vanilla-sized leg, which it could
		// not do while the steps were rounded to whole pixels — there were only three
		// widths to be had and the swell fell between two of them.
		int backOut = 0;
		for (int i = 1; i < loft.size(); i++) {
			if (loft.get(i).halfX() > loft.get(i - 1).halfX()) backOut++;
		}
		assertTrue(backOut >= 1, "a four-pixel leg came out monotone: " + widths(loft));
	}

	/** Every ring's half-width, in order, for a message worth reading. */
	private static String widths(List<SegmentMesh.Ring> loft) {
		StringBuilder out = new StringBuilder();
		for (SegmentMesh.Ring ring : loft) out.append(String.format("%.3f ", ring.halfX()));
		return out.toString().trim();
	}

	@Test
	@DisplayName("the tables come back out too, across and through alike")
	void theOutlineIsNotMonotone() {
		// The intent, checked apart from what survives rounding. A leg narrows towards
		// the knee, comes back out at the knee and the calf, and only then goes away to
		// the ankle; an arm does the same at the hand. One number saying "how much
		// narrower the bottom is" cannot say either, and a limb built from one reads as
		// two bones stuck together — which is what it was reported as.
		for (boolean leg : new boolean[] { true, false }) {
			for (boolean deep : new boolean[] { false, true }) {
				float[] outline = BodyChain.outline(leg, deep);
				assertEquals(BodyChain.RINGS, outline.length, "a table has to cover the rings");
				int backOut = 0;
				for (int i = 1; i < outline.length; i++) {
					if (outline[i] < outline[i - 1]) backOut++;
				}
				assertTrue(backOut >= 1,
					(leg ? "a leg" : "an arm") + (deep ? " through" : " across")
						+ " never comes back out, which is a taper");
			}
		}
	}

	@Test
	@DisplayName("across and through are separate shapes, or a limb is round all the way down")
	void widthAndDepthAreNotTheSameShape() {
		// Measured on the reference: down the Elf's leg the width holds at 2.317 while
		// the depth goes 2.573 to 3.458. A calf bulges backwards, not sideways, and one
		// table for both cannot say so.
		for (boolean leg : new boolean[] { true, false }) {
			assertTrue(!java.util.Arrays.equals(BodyChain.outline(leg, false),
					BodyChain.outline(leg, true)),
				(leg ? "a leg" : "an arm") + " is the same shape across as through");
		}
	}

	@Test
	@DisplayName("four pixels does carry an outline, once nothing is rounded off it")
	void aThinLimbDoesCarryAnOutline() {
		// This test used to assert the opposite, and the opposite was an artefact. The
		// steps were quantised to a whole pixel of width, which left a four-pixel leg
		// exactly three widths to choose from — 4, 3 and 2 — so a twelve-band outline
		// came out as a staircase of three. The reference models never take a
		// whole-pixel step at all; the Elf's largest is 0.58 of a pixel and its
		// smallest is 0.02, on a leg thinner than vanilla's.
		BodyShape most = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		List<SegmentMesh.Ring> loft = leg(most);
		long widths = new java.util.TreeSet<>(loft.stream()
			.map(SegmentMesh.Ring::halfX).toList()).size();
		assertTrue(widths >= 10,
			"a four-pixel leg came out with only " + widths + " widths: " + widths(loft));
	}

	@Test
	@DisplayName("no step is big enough to read as a step")
	void everyStepIsSmallerThanTheEyeCatches() {
		// The bar comes off the reference rather than off taste. The Elf's leg spends
		// its whole range in eight changes, the largest 0.575 of a pixel of width; its
		// arm's largest is 1.101, and that one is the wrist, where a step is meant to
		// be seen. So: nothing on the smooth run of a limb may exceed the largest step
		// the smoothest reference takes.
		for (BodyShape shape : shapes()) {
			List<SegmentMesh.Ring> loft = BodyChain.limb(shape, 0, 12, 0, 2f, 0, 2f);
			for (int i = 1; i < loft.size(); i++) {
				float across = Math.abs(loft.get(i).halfX() - loft.get(i - 1).halfX()) * 2f;
				float through = Math.abs(loft.get(i).halfZ() - loft.get(i - 1).halfZ()) * 2f;
				assertTrue(across <= 0.6f && through <= 0.6f,
					"band " + i + " steps by " + across + " across and " + through
						+ " through, which is a staircase again: " + widths(loft));
			}
		}
	}

	@Test
	@DisplayName("nothing is quantised, because quantising was the fault")
	void widthsAreNotOnAGrid() {
		// The guard against the old line coming back. If every width lands on a half
		// pixel, somebody has rounded again.
		BodyShape most = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		int offGrid = 0;
		for (SegmentMesh.Ring ring : leg(most)) {
			if (Math.abs(ring.halfX() * 2f - Math.round(ring.halfX() * 2f)) > 1e-3f) offGrid++;
		}
		assertTrue(offGrid >= 8, "the widths are back on a grid: " + widths(leg(most)));
	}

	@Test
	@DisplayName("a limb is shaped on its outside and its back, not about its middle")
	void theInnerFaceAndTheFrontAreHeld() {
		// Measured on the reference: down its left leg the inner edge sits at 0.276
		// and stays there while the outer goes 3.30, 2.61, 3.16, 3.02; front to back
		// the front holds at -10.5 and the back does all the moving. The rings used
		// to keep their middle and shrink both ways, which pulled the two legs apart
		// down the inside and put a taper on a shin that has none.
		BodyShape most = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		List<SegmentMesh.Ring> loft = BodyChain.leg(most, 12, 24, 1f, 1.9f, 0, 0);

		float front = loft.get(1).middleZ() - loft.get(1).halfZ();
		float inner = loft.get(1).middleX() - loft.get(1).halfX();
		float wandered = 0;
		for (int i = 1; i < loft.size(); i++) {
			SegmentMesh.Ring ring = loft.get(i);
			assertEquals(front, ring.middleZ() - ring.halfZ(), 1e-3f,
				"the shin is not a straight line at ring " + i);
			wandered = Math.max(wandered, Math.abs(ring.middleX() - ring.halfX() - inner));
		}

		// The inside is held, but not exactly: two legs whose inner faces never move
		// keep vanilla's tenth-of-a-pixel overlap for their whole length and read as
		// one column. The reference has 0.55 of a pixel between its legs, so a share
		// of the narrowing goes inward and this is how much of it arrives.
		assertTrue(wandered > 0.1f && wandered < 0.45f,
			"the inner face moved by " + wandered + ", which is a column or a cone");
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
