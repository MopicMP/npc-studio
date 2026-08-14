package com.mopicmp.npcstudio.emote;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.emote.Folding;

/**
 * That a folded limb tiles: no surface twice anywhere, and none outside itself.
 *
 * Nine attempts at the hatching round a bent elbow were made from screenshots
 * and eight were wrong. The hatching was z-fighting — two surfaces of the same
 * limb in the same place — so what has to be shown is not that the fold looks
 * right but that the fold is a <b>bijection</b>: every scrap of skin lands
 * somewhere, once.
 *
 * The version before this one cut the limb along the mitre and moved rigid
 * pieces, which forced each piece to reach past the middle and draw a band of
 * its neighbour's skin a second time. That reads in game as skin standing
 * outside the model, and no guard here caught it because every guard was about
 * overlap and none was about surface being <em>duplicated</em>. Both are checked
 * now.
 *
 * The fold turns y against z and never touches x, so a limb's whole behaviour is
 * an outline in a plane.
 */
class FoldingTest {

	/** An arm: twelve pixels long, four thick, in blocks. */
	private static final float TOP = -2f / 16f;
	private static final float BOTTOM = 10f / 16f;
	private static final float ARM = 2f / 16f;
	private static final float MIDDLE = (TOP + BOTTOM) / 2f;

	/** A sleeve is the same box inflated by a quarter of a pixel, all round. */
	private static final float SLEEVE = 2.25f / 16f;

	/** Which side of the joint's plane a point of the world falls, and how far. */
	private static float across(float[] point, float bend) {
		float tilt = bend / 2f;
		return (point[0] - MIDDLE) * (float) Math.cos(tilt)
			+ point[1] * (float) Math.sin(tilt);
	}

	/**
	 * The two halves tile: neither reaches across the joint into the other.
	 *
	 * Both leans tilt their own end face by half the fold, so both end faces are
	 * the bisector, and swinging the hand half by the whole fold lands its face on
	 * the shoulder's. Everything the shoulder keeps is then on one side of that
	 * plane and everything the hand keeps on the other — two half-spaces, nothing
	 * for the depth buffer to be undecided about, no hatching.
	 */
	@Test
	@DisplayName("the two halves never reach across the joint")
	void theHalvesTile() {
		for (float bend = -Folding.MOST; bend <= Folding.MOST; bend += 0.02f) {
			for (int i = 0; i <= 120; i++) {
				float y = TOP + (BOTTOM - TOP) * i / 120f;
				for (int j = 0; j <= 40; j++) {
					float z = -SLEEVE + 2 * SLEEVE * j / 40f;
					float side = across(Folding.fold(y, z, TOP, BOTTOM, bend, ARM), bend);

					if (Folding.swings(y, TOP, BOTTOM)) {
						assertTrue(side >= -1e-5f, String.format(
							"bend %.2f: the hand half reached back over the joint at (%.3f, %.3f)",
							bend, y, z));
					} else {
						assertTrue(side <= 1e-5f, String.format(
							"bend %.2f: the shoulder half reached on over the joint at (%.3f, %.3f)",
							bend, y, z));
					}
				}
			}
		}
	}

	/**
	 * And there is no gap between them: the joint is one surface, not two edges.
	 *
	 * Tiling on its own is easy and useless — shrinking both halves to nothing
	 * would manage it. Where the shoulder half ends and the hand half begins has
	 * to be the same point of the world, at every depth through the limb.
	 */
	@Test
	@DisplayName("and there is no gap where they meet")
	void theHalvesMeetExactly() {
		for (float bend = -Folding.MOST; bend <= Folding.MOST; bend += 0.02f) {
			for (float z : new float[] { -SLEEVE, -ARM, 0, ARM, SLEEVE }) {
				// Approached from each side, because the two halves reach the joint
				// by different arithmetic and it is their agreeing that matters.
				float[] ends = Folding.fold(MIDDLE, z, TOP, BOTTOM, bend, ARM);
				float[] starts = Folding.fold(Math.nextUp(MIDDLE), z, TOP, BOTTOM, bend, ARM);

				double apart = Math.hypot(ends[0] - starts[0], ends[1] - starts[1]);
				assertTrue(apart < 1e-5f, String.format(
					"bend %.2f: the halves met %.5f apart at depth %.3f", bend, apart, z));
			}
		}
	}

	/**
	 * No scrap of skin is ever drawn in two places.
	 *
	 * The guard that was missing, and the one the last version needed. Cutting the
	 * limb and moving rigid pieces made each piece reach past the middle for the
	 * surface the outside of a bend needs, so a band of skin came out twice —
	 * which is what "the texture stands outside the model" was.
	 *
	 * A lean cannot do it, and this is why: along the limb the fold is strictly
	 * increasing at every depth, so two different heights can never arrive at the
	 * same place. Measured rather than argued, at the depths where it would fail
	 * first.
	 */
	@Test
	@DisplayName("no scrap of skin is ever drawn twice")
	void theFoldNeverDoublesBackOnItself() {
		for (float bend = -Folding.MOST; bend <= Folding.MOST; bend += 0.02f) {
			for (float z : new float[] { -SLEEVE, -ARM, 0, ARM, SLEEVE }) {
				float[] last = Folding.fold(TOP, z, TOP, BOTTOM, bend, ARM);

				for (int i = 1; i <= 400; i++) {
					float y = TOP + (BOTTOM - TOP) * i / 400f;
					float[] now = Folding.fold(y, z, TOP, BOTTOM, bend, ARM);

					// Measured along the piece the step lands on. There is a crease at
					// the joint, so a step across it is only going forwards in the frame
					// it arrives in — judging it in the frame it left would call every
					// fold sharper than a right angle a reversal.
					float along = Folding.angleAt(y, TOP, BOTTOM, bend);
					float step = (now[0] - last[0]) * (float) Math.cos(along)
						+ (now[1] - last[1]) * (float) Math.sin(along);

					assertTrue(step > -1e-6f, String.format(
						"bend %.2f: the limb doubled back on itself at %.3f, depth %.3f",
						bend, y, z));
					last = now;
				}
			}
		}
	}

	/**
	 * Neither end of the limb moves off where it was hung.
	 *
	 * A lean is nought at its own far end, so a shoulder stays on its shoulder
	 * whatever the elbow does, and the hand is moved only by the swing — never
	 * stretched, never slid along the arm. This is what keeps a limb the length
	 * the animation aimed it.
	 */
	@Test
	@DisplayName("neither end of the limb is moved by the lean")
	void theEndsStayWhereTheyWereHung() {
		for (float bend = -Folding.MOST; bend <= Folding.MOST; bend += 0.02f) {
			for (float z : new float[] { -ARM, 0, ARM }) {
				float[] shoulder = Folding.fold(TOP, z, TOP, BOTTOM, bend, ARM);
				assertTrue(Math.abs(shoulder[0] - TOP) < 1e-6f
					&& Math.abs(shoulder[1] - z) < 1e-6f,
					String.format("bend %.2f moved the shoulder", bend));

				// The hand may be swung, but it must still be exactly as far from the
				// joint as it started, and exactly as thick.
				float[] hand = Folding.fold(BOTTOM, z, TOP, BOTTOM, bend, ARM);
				float[] axis = Folding.fold(BOTTOM, 0, TOP, BOTTOM, bend, ARM);
				assertTrue(Math.abs((float) Math.hypot(hand[0] - axis[0], hand[1] - axis[1])
					- Math.abs(z)) < 1e-5f, String.format("bend %.2f thinned the hand", bend));

				float reach = (float) Math.hypot(axis[0] - MIDDLE, axis[1]);
				assertTrue(Math.abs(reach - (BOTTOM - MIDDLE)) < 1e-5f, String.format(
					"bend %.2f put the hand %.4f from the joint instead of %.4f",
					bend, reach, BOTTOM - MIDDLE));
			}
		}
	}

	/** A limb nobody bent comes through untouched, to the last decimal. */
	@Test
	@DisplayName("no bend changes nothing at all")
	void restIsExact() {
		for (int i = 0; i <= 20; i++) {
			float y = TOP + (BOTTOM - TOP) * i / 20f;
			for (float z : new float[] { -ARM, 0, ARM }) {
				float[] got = Folding.fold(y, z, TOP, BOTTOM, 0, ARM);
				assertTrue(Math.abs(got[0] - y) < 1e-6f && Math.abs(got[1] - z) < 1e-6f,
					"moved at " + y);
			}
		}
	}

	/**
	 * Away from the joint, a limb is left exactly as it was drawn.
	 *
	 * The measurement this version exists for. bendy-lib ramps the lean over a
	 * whole half, so every point is dragged a little — and because the lean is
	 * proportional to depth, a limb's <em>side</em> gets one edge dragged forwards
	 * and the other back. A sleeve's horizontal stripe came out diagonal and the
	 * pattern smeared along the arm.
	 *
	 * The mitre only needs the lean to be full <em>at the joint</em>. So outside
	 * the stretch it is spread over, nothing moves at all — not approximately, not
	 * by a fraction of a pixel: the point comes back bit for bit.
	 */
	@Test
	@DisplayName("away from the joint a limb is left exactly as it was drawn")
	void nothingMovesOutsideTheJoint() {
		for (float bend = -Folding.MOST; bend <= Folding.MOST; bend += 0.02f) {
			float reach = Folding.reach(TOP, BOTTOM, bend, ARM);

			for (int i = 0; i <= 200; i++) {
				float y = TOP + (BOTTOM - TOP) * i / 200f;
				if (Math.abs(y - MIDDLE) <= reach) continue;

				for (float z : new float[] { -SLEEVE, -ARM, 0, ARM, SLEEVE }) {
					float[] got = Folding.fold(y, z, TOP, BOTTOM, bend, ARM);
					float[] want = Folding.swings(y, TOP, BOTTOM)
						? Folding.carry(y, z, MIDDLE, bend) : new float[] { y, z };

					assertTrue(Math.abs(got[0] - want[0]) < 1e-6f
						&& Math.abs(got[1] - want[1]) < 1e-6f, String.format(
						"bend %.2f moved a point at %.3f, depth %.3f, which is %.3f clear of the joint",
						bend, y, z, Math.abs(y - MIDDLE) - reach));
				}
			}
		}
	}

	/**
	 * And at the joint itself the lean is bendy-lib's, to the last decimal.
	 *
	 * Holding the drag back is a change to <em>where</em> the lean is spent, not to
	 * how much of it there is. At the joint it has to be the full
	 * {@code tan(bend/2) · depth} the reference computes, or the two halves stop
	 * meeting and the whole construction comes apart.
	 */
	@Test
	@DisplayName("and at the joint it is bendy-lib's lean, to the last decimal")
	void theLeanAtTheJointMatchesTheReference() {
		for (float bend = -Folding.MOST; bend <= Folding.MOST; bend += 0.01f) {
			for (float z : new float[] { -SLEEVE, -ARM, ARM, SLEEVE }) {
				// s = Math.tan(bendValue / 2) * distFromBend, at the joint where
				// bendy-lib's own ramp reaches one.
				float theirs = (float) Math.tan(bend / 2f) * z;
				float[] ours = Folding.fold(MIDDLE, z, TOP, BOTTOM, bend, ARM);

				assertTrue(Math.abs((MIDDLE - ours[0]) - theirs) < 1e-5f, String.format(
					"bend %.2f at depth %.3f: we lean %.5f, bendy-lib %.5f",
					bend, z, MIDDLE - ours[0], theirs));
			}
		}
	}
}
