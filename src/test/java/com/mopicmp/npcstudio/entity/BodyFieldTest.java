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
		BodyShape wide = shape(1.6f, 1f);
		float shoulders = BodyField.torsoBack(wide, BodyField.SHOULDER_Y);
		float seat = BodyField.torsoBack(wide, BodyField.HIP_Y);
		assertEquals(BodyField.BODY_DEEP, shoulders, 1e-4f, "the shoulders are not a seat");
		assertEquals(0.55f, seat - shoulders, 0.1f, "the seat is not the reference's depth");

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
		// The bar is looser than a limb's, and deliberately. A limb's outline is a
		// smooth run and its bar is 0.6 of a pixel of width per pixel of height; a
		// seat is a feature, and the reference's own features are steeper than its
		// runs — its wrist steps 1.1 pixels. At the widest hips the sliders allow,
		// the seat is a whole pixel deep and it arrives over three and a half.
		BodyShape wide = shape(2f, 2f);
		float was = BodyField.torsoBack(wide, 0);
		for (float y = BAND; y <= BodyField.HIP_Y; y += BAND) {
			float now = BodyField.torsoBack(wide, y);
			assertTrue(Math.abs(now - was) <= 0.4f,
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
		// A hinge puts all its turn in one place; a bend spreads it. Read as the
		// change per band, a hinge is one big number among zeroes and a bend is a
		// hump. So: every band moves, and no band moves more than twice the average.
		BodyShape old = stooped(BodyShape.MAX_STOOP);
		float most = 0;
		float total = 0;
		int bands = 0;
		float was = BodyField.spineLean(old, BodyField.HIP_Y);
		for (float y = BodyField.HIP_Y - BAND; y >= BodyField.SHOULDER_Y - 1e-4f; y -= BAND) {
			float now = BodyField.spineLean(old, y);
			float step = Math.abs(now - was);
			most = Math.max(most, step);
			total += step;
			bands++;
			was = now;
		}
		assertTrue(bands >= 15, "only " + bands + " bands of spine");
		assertTrue(most <= 2f * total / bands,
			"one band takes " + most + " of the lean where the average is " + total / bands
				+ ", which is a hinge");
	}
}
