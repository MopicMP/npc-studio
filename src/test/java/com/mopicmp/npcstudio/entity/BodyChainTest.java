package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the settings are allowed to make of a limb. */
class BodyChainTest {

	/** A vanilla leg: four across, twelve long, hanging from the hip. */
	private static List<SegmentMesh.Segment> leg(BodyShape shape) {
		return BodyChain.limb(shape, 0, 12, 0, 2, 0, 2);
	}

	@Test
	@DisplayName("an untouched build is one segment, the part's own")
	void nothingAskedNothingDone() {
		List<SegmentMesh.Segment> chain = leg(BodyShape.DEFAULT);
		assertEquals(1, chain.size(), "an ordinary character was cut up for nothing");
		SegmentMesh.Segment only = chain.get(0);
		assertEquals(0f, only.top(), 1e-4f);
		assertEquals(12f, only.bottom(), 1e-4f);
		assertEquals(2f, only.halfX(), 1e-4f);
		assertEquals(2f, only.halfZ(), 1e-4f);
	}

	@Test
	@DisplayName("taper takes its pixel off the width, half from each side")
	void taperIsAWidth() {
		BodyShape stepped = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, 1f);
		List<SegmentMesh.Segment> chain = leg(stepped);
		assertEquals(2, chain.size());
		assertEquals(2f, chain.get(0).halfX(), 1e-4f, "the thigh changed");
		assertEquals(1.5f, chain.get(1).halfX(), 1e-4f, "the calf is not a pixel narrower");
		assertEquals(1.5f, chain.get(1).halfZ(), 1e-4f, "the calf narrowed only one way");
	}

	@Test
	@DisplayName("a limb never tapers away to nothing")
	void thereIsAlwaysALimbLeft() {
		BodyShape silly = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, BodyShape.MAX_TAPER);
		List<SegmentMesh.Segment> chain = BodyChain.limb(silly, 0, 12, 0, 1f, 0, 1f);
		assertTrue(chain.get(1).halfX() >= 0.5f, "the calf disappeared");
	}

	@Test
	@DisplayName("height is length, and it grows downwards from the hip")
	void tallMeansLongerLegs() {
		BodyShape tall = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1.25f, 0f);
		List<SegmentMesh.Segment> chain = leg(tall);
		float lowest = chain.get(chain.size() - 1).bottom();
		assertEquals(15f, lowest, 1e-4f, "a quarter taller did not make a quarter longer leg");
		assertEquals(0f, chain.get(0).top(), 1e-4f, "the hip moved");

		// And the model has to come up by exactly what the leg went down by, or the
		// character stands in the floor.
		assertEquals(3f, BodyChain.lift(tall, 12f), 1e-4f);
		assertEquals(0f, BodyChain.lift(BodyShape.DEFAULT, 12f), 1e-4f);
	}

	@Test
	@DisplayName("the two thighs still fill the pelvis, now that a thigh is a box")
	void theHipJoinSurvivedTheRewrite() {
		// The invariant that outlived the smooth flare it was invented for. Vanilla
		// says eight equals four and four; a segmented leg has to say the same.
		for (float hips = 0.5f; hips <= 2f; hips += 0.1f) {
			BodyShape build = new BodyShape(1, hips, 1, 1, 1, 1, 1, 0f, 0f, 1f, 1f);
			SegmentMesh.Segment thigh = BodyChain.leg(build, 12, 24, -1f, -1.9f, 0, 0).get(0);
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
	@DisplayName("an untouched leg is still one segment and still the vanilla leg")
	void theLegFastPathSurvives() {
		var chain = BodyChain.leg(BodyShape.DEFAULT, 12, 24, -1f, -1.9f, 0, 0);
		assertEquals(1, chain.size(), "an ordinary leg was cut up for nothing");
		assertEquals(2f, chain.get(0).halfX(), 1e-4f);
		assertEquals(1.9f, -chain.get(0).middleX() + 1.9f, 1e-4f, "the leg moved");
	}

	@Test
	@DisplayName("the segments always add up to the whole limb")
	void nothingIsLostBetweenThem() {
		for (float height = BodyShape.MIN_HEIGHT; height <= BodyShape.MAX_HEIGHT; height += 0.1f) {
			for (float taper = 0; taper <= BodyShape.MAX_TAPER; taper += 0.25f) {
				BodyShape shape = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, height, taper);
				List<SegmentMesh.Segment> chain = leg(shape);
				float covered = 0;
				float was = chain.get(0).top();
				for (SegmentMesh.Segment segment : chain) {
					assertEquals(was, segment.top(), 1e-4f, "a gap between segments");
					covered += segment.length();
					was = segment.bottom();
				}
				assertEquals(12f * height, covered, 1e-3f,
					"the limb is not as long as it was asked to be");
			}
		}
	}
}
