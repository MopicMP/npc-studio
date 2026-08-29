package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the body's surface is allowed to do.
 *
 * These are not tests of arithmetic — the arithmetic was never what went wrong.
 * They are the statements that were false in the first telling and have to stay true
 * in this one: an untouched character is the vanilla one, a pelvis is never narrower
 * than the legs under it, and the top of a thigh and the bottom of a pelvis are the
 * same width because they are the same number.
 *
 * The ones that used to sit here about knees and ankles have gone with the thing
 * they described. A limb that is not the same width all the way down is made of
 * segments now, and what a segment may be is {@link BodyChainTest}.
 */
class BodyFieldTest {

	/** Half an arm across on the two models Minecraft ships. */
	private static final float CLASSIC = 2f;
	private static final float SLIM = 1.5f;

	private static BodyShape shape(float hips, float legs) {
		return new BodyShape(1, hips, 1, 1, legs, 1, 1, 0f, 0f);
	}

	@Test
	@DisplayName("an untouched character is the vanilla model, to the pixel")
	void defaultIsVanilla() {
		BodyShape plain = BodyShape.DEFAULT;
		for (float y = 0; y <= BodyField.HIP_Y; y += 0.5f) {
			assertEquals(4f, BodyField.torsoHalf(plain, y), 1e-4f, "torso at " + y);
		}
		for (float y = BodyField.HIP_Y; y <= 24f; y += 0.5f) {
			assertEquals(2f, BodyField.legHalf(plain, y), 1e-4f, "leg across at " + y);
			assertEquals(2f, BodyField.legDepth(plain, y), 1e-4f, "leg through at " + y);
			assertEquals(1.9f, BodyField.legMiddle(plain, y), 1e-4f, "leg middle at " + y);
		}
		for (float base : new float[] { CLASSIC, SLIM }) {
			assertEquals(base, BodyField.armHalf(plain, base), 1e-4f);
			// Six on a classic model and five and a half on a slim one, which is where
			// Minecraft puts them: the torso's side plus the arm's own half-width.
			assertEquals(4f + base, BodyField.armMiddle(plain, base), 1e-4f);
		}
		assertEquals(0f, BodyField.armSplay(plain), 1e-4f);
	}

	@Test
	@DisplayName("the pelvis is never narrower than the legs standing under it")
	void thickLegsWidenTheHips() {
		// The whole of "one setting cannot move alone", in one assertion. Nobody wired
		// the two sliders together; the pelvis is defined as what the legs need.
		for (float legs = 0.5f; legs <= 2f; legs += 0.1f) {
			BodyShape build = shape(1f, legs);
			float pelvis = BodyField.torsoHalf(build, BodyField.HIP_Y);
			float thighs = 2f * BodyField.legHalf(build, BodyField.THIGH_Y);
			assertTrue(pelvis >= thighs - 1e-4f,
				"pelvis " + pelvis + " cannot carry thighs " + thighs);
		}
	}

	@Test
	@DisplayName("the two thighs fill the pelvis exactly at the hip line")
	void theJoinIsWatertight() {
		for (float hips = 0.5f; hips <= 2f; hips += 0.1f) {
			for (float legs = 0.5f; legs <= 2f; legs += 0.25f) {
				BodyShape build = shape(hips, legs);
				float pelvis = BodyField.torsoHalf(build, BodyField.HIP_Y);
				float half = BodyField.legHalf(build, BodyField.HIP_Y);
				assertEquals(pelvis / 2f, half, 1e-4f, "thigh at the hip, hips " + hips);

				float middle = BodyField.legMiddle(build, BodyField.HIP_Y);
				assertEquals(pelvis - BodyField.LEG_OVERLAP, middle + half, 1e-4f, "outer edge");
				assertEquals(-BodyField.LEG_OVERLAP, middle - half, 1e-4f, "inner edge");
			}
		}
	}

	@Test
	@DisplayName("a wide pelvis leaves the waist and the shoulders alone")
	void hipsDoNotDragTheWholeTorso() {
		BodyShape wide = shape(1.6f, 1f);
		assertEquals(4f, BodyField.torsoHalf(wide, BodyField.SHOULDER_Y), 1e-4f);
		assertEquals(4f, BodyField.torsoHalf(wide, BodyField.WAIST_Y), 1e-4f);
		assertEquals(6.4f, BodyField.torsoHalf(wide, BodyField.HIP_Y), 1e-4f);
	}

	@Test
	@DisplayName("the surface never falls faster than a pixel for a pixel")
	void nothingIsACliff() {
		// A cliff is what the old telling had at the hip — four pixels of nothing
		// between the pelvis and the leg — and it is the one failure a still picture
		// of a standing character hides.
		for (float hips = 0.5f; hips <= 2f; hips += 0.25f) {
			for (float legs = 0.5f; legs <= 2f; legs += 0.25f) {
				BodyShape build = shape(hips, legs);
				float was = BodyField.legHalf(build, BodyField.HIP_Y);
				for (float y = BodyField.HIP_Y; y <= 24f; y += 0.1f) {
					float now = BodyField.legHalf(build, y);
					assertTrue(Math.abs(now - was) <= 0.1f + 1e-3f,
						"leg jumped from " + was + " to " + now + " at " + y);
					was = now;
				}
			}
		}
	}

	@Test
	@DisplayName("the joint lets go of the leg's turn, and only near the hip")
	void theJointIsAWeight() {
		BodyShape build = shape(1.4f, 1f);
		assertEquals(1f, BodyField.atJoint(build, BodyField.HIP_Y), 1e-4f);
		assertEquals(0f, BodyField.atJoint(build, BodyField.jointEnd(build)), 1e-4f);
		assertTrue(BodyField.atJoint(build, 13.5f) > 0.2f, "the joint gives up too early");
	}

	@Test
	@DisplayName("the shoulder holds a shorter band than the hip does")
	void theShoulderIsTheTighterJoint() {
		BodyShape build = shape(1f, 1f);
		assertEquals(1f, BodyField.atShoulder(build, BodyField.SHOULDER_Y), 1e-4f);
		assertEquals(0f, BodyField.atShoulder(build, BodyField.SHOULDER_JOINT), 1e-4f);
		// An arm goes through more than twice the angle a leg does, so it gives its
		// turn up over less of itself — otherwise a wave reads as rubber.
		assertTrue(BodyField.SHOULDER_JOINT < BodyField.THIGH_Y - BodyField.HIP_Y);
	}

	@Test
	@DisplayName("a slim arm stays slimmer than a classic one")
	void slimStaysSlim() {
		BodyShape thick = new BodyShape(1, 1, 1, 1.4f, 1, 1, 1, 0f, 0f);
		assertTrue(BodyField.armHalf(thick, SLIM) < BodyField.armHalf(thick, CLASSIC));
	}

	@Test
	@DisplayName("the arm's inner face is the torso's side, whatever the shoulders are")
	void theArmSitsOnTheTorso() {
		for (float shoulders = 0.5f; shoulders <= 2f; shoulders += 0.1f) {
			BodyShape build = new BodyShape(shoulders, 1, 1, 1, 1, 1, 1, 0f, 0f);
			float inner = BodyField.armMiddle(build, CLASSIC) - BodyField.armHalf(build, CLASSIC);
			assertEquals(BodyField.torsoHalf(build, BodyField.SHOULDER_Y), inner, 1e-4f,
				"shoulders " + shoulders);
		}
	}

	@Test
	@DisplayName("a chamfer moves the middle of a face nowhere and a corner inwards")
	void softnessIsAChamfer() {
		float half = 4f, deep = 2f, radius = 1f;
		assertEquals(1f, BodyField.chamfer(1f, 0f, half, deep, radius), 1e-4f, "face middle");
		assertEquals(1f, BodyField.chamfer(0f, 1f, half, deep, radius), 1e-4f, "face middle");

		float corner = BodyField.chamfer(1f, 1f, half, deep, radius);
		assertTrue(corner < 1f, "the corner was not drawn in");
		assertTrue(corner > 0.7f, "the corner was drawn in further than the radius");
		assertEquals(1f, BodyField.chamfer(1f, 1f, half, deep, 0f), 1e-4f);
	}

	@Test
	@DisplayName("a chamfered surface stays inside the box it came from")
	void theChamferNeverGrows() {
		float half = 2f, deep = 2f;
		for (float radius = 0f; radius <= 1.2f; radius += 0.1f) {
			for (float u = -1f; u <= 1f; u += 0.1f) {
				for (float w = -1f; w <= 1f; w += 0.1f) {
					float factor = BodyField.chamfer(u, w, half, deep, radius);
					assertTrue(Math.abs(u * half * factor) <= half + 1e-3f, "across");
					assertTrue(Math.abs(w * deep * factor) <= deep + 1e-3f, "through");
				}
			}
		}
	}

	@Test
	@DisplayName("the arms swing out only as far as the hips push them")
	void armsClearTheHips() {
		assertEquals(0f, BodyField.armSplay(shape(1f, 1f)), 1e-4f);
		assertEquals(0f, BodyField.armSplay(shape(0.7f, 0.7f)), 1e-4f, "narrow hips push nothing");

		BodyShape wide = shape(1.6f, 1f);
		float splay = BodyField.armSplay(wide);
		float reach = BodyField.HIP_Y - BodyField.SHOULDER_PIVOT_Y;
		float moved = (float) Math.sin(splay) * reach;
		float overlap = BodyField.torsoHalf(wide, BodyField.HIP_Y) - BodyField.BODY_HALF;
		assertEquals(overlap, moved, 1e-3f, "the arm did not clear the hip by exactly the overlap");
	}

	// ------------------------------------------------- the back, and the spine

	/** A character with a backside and nothing else changed. */
	private static BodyShape seated(float much) {
		return new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, 0f, 1f, 0f, much);
	}

	@Test
	@DisplayName("thickening a leg does not grow a backside")
	void theLegsAreNotTheSeat() {
		// The whole of the report. The seat used to be driven by hipsUsed, which is
		// the wider of the hips and the legs, so a slider about calves was quietly
		// also a slider about buttocks.
		BodyShape thick = shape(1f, 1.8f);
		for (float y = 0; y <= BodyField.HIP_Y; y += 0.5f) {
			assertEquals(BodyField.BODY_DEEP, BodyField.torsoBack(thick, y), 1e-4f,
				"thick legs moved the back at " + y);
		}
		// And the pelvis still widens for them, which is a different question and
		// still has to be true.
		assertTrue(BodyField.torsoHalf(thick, BodyField.HIP_Y) > BodyField.BODY_HALF);
	}

	/** A stooped character and nothing else, so a failure can only be the stoop. */
	private static BodyShape stooped(float radians) {
		return new BodyShape(1, 1, 1, 1, 1, 1, 1, 0f, radians);
	}

	/** The sixteen bands the torso is actually cut into, which is what a step means. */
	private static final float BAND = (BodyField.HIP_Y - BodyField.SHOULDER_Y) / 16f;

	@Test
	@DisplayName("an untouched back is the vanilla plane, front and back alike")
	void defaultBackIsFlat() {
		for (float y = 0; y <= BodyField.HIP_Y; y += 0.25f) {
			assertEquals(BodyField.BODY_DEEP, BodyField.torsoBack(BodyShape.DEFAULT, y), 1e-4f,
				"the back moved at " + y + " on a character nobody touched");
			assertEquals(0f, BodyField.spineLean(BodyShape.DEFAULT, y), 1e-6f, "lean at " + y);
			assertEquals(0f, BodyField.spineDrop(BodyShape.DEFAULT, y), 1e-6f, "drop at " + y);
		}
	}

	@Test
	@DisplayName("wide hips build a seat behind them, and a hollow above it")
	void hipsWorkThroughAsWellAsAcross() {
		// The fault this answers: hips only ever moved the sides apart, so a
		// wide-hipped character was a wider plank seen from the front and the same
		// plank seen from the side. Measured on the reference, the back of the torso
		// stands 0.55 pixels further out at the seat than at the shoulders.
		// Read at a middling setting rather than at the end of the slider's travel.
		// The first version put the reference's depth at the maximum, and the report
		// was that you cannot see it unless you wind the slider all the way up and go
		// looking. A slider has to say something in the middle of its range.
		BodyShape middling = shape(1.3f, 1f);
		assertTrue(BodyField.torsoBack(seated(1f), BodyField.HIP_Y) > BodyField.BODY_DEEP,
			"the seat slider does nothing on its own");
		float shoulders = BodyField.torsoBack(middling, BodyField.SHOULDER_Y);
		float seat = BodyField.torsoBack(middling, BodyField.HIP_Y);
		assertEquals(BodyField.BODY_DEEP, shoulders, 1e-4f, "the shoulders are not a seat");
		// The hips alone land near the reference's 0.55 without anybody touching the
		// seat slider, which is the point of their keeping a share of it.
		assertEquals(0.45f, seat - shoulders, 0.1f, "the hips carry no seat of their own");
		assertTrue(BodyField.torsoBack(seated(1f), BodyField.HIP_Y)
				- BodyField.BODY_DEEP > 1.4f,
			"the seat slider at its top is still shallower than a pixel and a half");

		BodyShape wide = shape(1.6f, 1f);
		assertTrue(BodyField.torsoBack(wide, BodyField.HIP_Y) > seat,
			"the rest of the slider's travel does nothing");

		// And it turns round on the way, or it is a wedge rather than a seat.
		float least = Float.MAX_VALUE;
		for (float y = 0; y <= BodyField.HIP_Y; y += 0.25f) {
			least = Math.min(least, BodyField.torsoBack(wide, y));
		}
		assertTrue(least < BodyField.BODY_DEEP - 0.05f,
			"there is no small of the back: the shallowest point is " + least);
	}

	@Test
	@DisplayName("the seat arrives gradually, like everything else on this model")
	void theSeatHasNoStep() {
		// The bar is the limb's, converted. A limb may move 0.6 of a pixel across a
		// pixel of its height; a band of torso is only three quarters of a pixel
		// tall, so its share of the same slope is 0.6 times the band. Stating it as a
		// slope rather than as a step is what keeps the two comparable when one of
		// them is cut more finely than the other.
		BodyShape wide = new BodyShape(1, 2f, 1, 1, 1, 1, 1, 0f, 0f, 1f, 0f, 1f);
		float was = BodyField.torsoBack(wide, 0);
		for (float y = BAND; y <= BodyField.HIP_Y; y += BAND) {
			float now = BodyField.torsoBack(wide, y);
			assertTrue(Math.abs(now - was) <= 0.6f * BAND,
				"the back steps by " + (now - was) + " at " + y);
			was = now;
		}
	}

	@Test
	@DisplayName("a stoop is pinned at the hips and spent by the shoulders")
	void theSpineLeansWhereASpineDoes() {
		// This is the whole of the fix for "осанка под прямым углом". The lean used
		// to be one rigid turn of the torso about the waist, which tilted the bottom
		// of the torso against the flat top of the thigh and left a corner. Nought at
		// the hips is what keeps that junction square.
		BodyShape old = stooped(BodyShape.MAX_STOOP);
		assertEquals(0f, BodyField.spineLean(old, BodyField.HIP_Y), 1e-4f, "the hips moved");
		// And the small of the back is nearly upright: the bend belongs to the
		// shoulders. Read as how much of the whole lean has happened by the waist.
		float atWaist = BodyField.spineLean(old, BodyField.WAIST_Y);
		float whole = BodyField.spineLean(old, BodyField.SHOULDER_Y);
		assertTrue(Math.abs(atWaist) < 0.15f * Math.abs(whole),
			"a third of the way up the back has already spent " + (atWaist / whole)
				+ " of its lean, which tips the lumbar spine");
		assertEquals(0f, BodyField.spineDrop(old, BodyField.HIP_Y), 1e-4f, "the hips dropped");
		assertTrue(BodyField.spineLean(old, BodyField.SHOULDER_Y) < -1f,
			"the shoulders did not come forward");
		assertTrue(BodyField.spineDrop(old, BodyField.SHOULDER_Y) > 0f,
			"a bent back is a shorter back and this one is not");

		// Below the hips there is no torso, and nothing there may move: that is where
		// the legs are.
		assertEquals(0f, BodyField.spineLean(old, BodyField.HIP_Y + 1f), 1e-4f);
		assertEquals(0f, BodyField.spineLean(old, BodyField.THIGH_Y), 1e-4f);
	}

	@Test
	@DisplayName("and it bends on the way rather than hinging")
	void theSpineIsACurveNotAHinge() {
		// A hinge puts all its turn in one place and leaves every other band at
		// nought; this bend is deliberately top-heavy, because a stoop lives in the
		// upper back, so the bar is not "even" but "spread". Sixteen bands, and the
		// busiest of them carries a few times the average rather than all of it — a
		// real hinge would be sixteen times the average with fifteen zeroes beside
		// it.
		BodyShape old = stooped(BodyShape.MAX_STOOP);
		float most = 0;
		float total = 0;
		int bands = 0;
		int quiet = 0;
		float was = BodyField.spineLean(old, BodyField.HIP_Y);
		for (float y = BodyField.HIP_Y - BAND; y >= BodyField.SHOULDER_Y - 1e-4f; y -= BAND) {
			float now = BodyField.spineLean(old, y);
			float step = Math.abs(now - was);
			most = Math.max(most, step);
			total += step;
			if (step <= 1e-4f) quiet++;
			bands++;
			was = now;
		}
		assertTrue(bands >= 15, "only " + bands + " bands of spine");
		assertTrue(most <= 4f * total / bands,
			"one band takes " + most + " of the lean where the average is " + total / bands
				+ ", which is a hinge");
		// And nothing is left out: sixteen zeroes with one number among them is the
		// failure this is really watching for.
		assertEquals(0, quiet, "a band of the spine does not bend at all");
	}
}
