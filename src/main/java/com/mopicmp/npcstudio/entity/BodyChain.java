package com.mopicmp.npcstudio.entity;

import java.util.List;

/**
 * What a limb is made of, for a given build.
 *
 * The one place that turns settings into segments. {@link SegmentMesh} knows how to
 * cut a part into boxes and nothing about bodies; {@link BodyField} knows where the
 * body's surfaces are and nothing about boxes; this is the join between them, and it
 * is deliberately small.
 *
 * <h2>Two segments, and why not more yet</h2>
 *
 * A thigh and a calf, an upper arm and a forearm. That is what the reference model
 * does, and it is the fewest that can say the thing a single box cannot: that a limb
 * is not the same width all the way down. More segments are possible — nothing here
 * assumes two — but every extra one is another place for a skin to be cut, and the
 * cuts have to earn themselves.
 *
 * <h2>What must stay true</h2>
 *
 * An untouched build gives one segment with the part's own numbers, which
 * {@code SegmentMesh} then hands back unchanged. That is what keeps an ordinary
 * character on the fast path, and it is checked rather than hoped for.
 */
public final class BodyChain {

	/**
	 * How much of a limb's length the upper segment takes.
	 *
	 * Half, because a knee is at the middle of a leg and an elbow is at the middle
	 * of an arm — which is also where the fold already happens, so the place that
	 * steps and the place that bends are one place.
	 */
	public static final float UPPER = 0.5f;

	private BodyChain() { }

	/**
	 * The chain for one limb.
	 *
	 * @param top    where the part's box begins, in its own space
	 * @param bottom where it ends
	 * @param halfX  half its width, as the model built it
	 * @param halfZ  half its depth
	 * @param middleX where its middle sits across, which is not its pivot
	 * @param middleZ the same front to back
	 */
	public static List<SegmentMesh.Segment> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ) {
		return limb(shape, top, bottom, middleX, halfX, middleZ, halfZ, halfX, halfZ, middleX);
	}

	/**
	 * The chain for a limb whose upper piece belongs to what it hangs from.
	 *
	 * A thigh is not free to be any width it likes: two of them stand under a pelvis
	 * and the join has to be a join. So the upper segment is given the parent's
	 * numbers and the lower one the limb's own, and the step between them lands at
	 * the knee — which is where a step belongs and where the fold already is.
	 *
	 * With nothing asked for, both are the same and the chain collapses to one
	 * segment, which the mesh hands straight back as the model it came from.
	 *
	 * @param halfX   the limb's own half-width, below the joint
	 * @param upperX  the half-width the parent dictates at the top
	 * @param upperZ  the same through
	 * @param upperMiddleX where the upper segment sits across, which a wide pelvis moves
	 */
	public static List<SegmentMesh.Segment> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ,
			float upperX, float upperZ, float upperMiddleX) {
		return limb(shape, top, bottom, middleX, halfX, middleZ, halfZ,
			upperX, upperZ, upperMiddleX, LEG);
	}

	/** The same, told which outline to follow — a leg's or an arm's. */
	private static List<SegmentMesh.Segment> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ,
			float upperX, float upperZ, float upperMiddleX, float[] profile) {
		float length = (bottom - top) * shape.height();
		boolean parted = upperX != halfX || upperZ != halfZ || upperMiddleX != middleX;

		// Nothing asked for and nothing to say: one segment, the part's own size, and
		// the mesh gives the model straight back.
		if (shape.taper() <= 0 && shape.height() == 1f && !parted) {
			return List.of(new SegmentMesh.Segment(top, bottom, middleX, halfX, middleZ, halfZ));
		}

		var chain = new java.util.ArrayList<SegmentMesh.Segment>(LINKS);
		for (int i = 0; i < LINKS; i++) {
			float from = top + length * i / LINKS;
			float to = top + length * (i + 1) / LINKS;
			if (i == 0) {
				// The top link belongs to whatever the limb hangs from: two thighs stand
				// under one pelvis, and the join has to be a join.
				chain.add(new SegmentMesh.Segment(from, to, upperMiddleX, upperX, middleZ, upperZ));
			} else if (i == JOINT) {
				chain.add(null);
			} else {
				chain.add(new SegmentMesh.Segment(from, to, middleX,
					step(halfX, shape.taper(), profile[i]), middleZ,
					step(halfZ, shape.taper(), profile[i])));
			}
		}
		// The joint last, because its size is read off the link below it.
		SegmentMesh.Segment below = chain.get(JOINT + 1);
		chain.set(JOINT, joint(top + length * JOINT / LINKS, top + length * (JOINT + 1) / LINKS,
			middleX, below.halfX(), middleZ, below.halfZ()));
		return List.copyOf(chain);
	}

	/**
	 * How many links a limb is, and why six.
	 *
	 * A flank is twelve rows of texels, and a cut has to land on a whole row — see
	 * {@code BodyChainTest#everyCutLandsOnAWholeTexel}. Twelve divides by six and
	 * gives two rows each, which is also the fewest that can carry an anatomy: a hip,
	 * a thigh, a knee, a calf, a shin and an ankle.
	 *
	 * Links are cheap. What costs is a <em>change of cross-section</em>, since each
	 * one is a ring and a duplicated row of texels — so a limb has as many links as
	 * it takes to put the steps at the right heights, and only as many steps as the
	 * outline is worth.
	 */
	public static final int LINKS = 6;

	/** Which link is the joint: the knee, the elbow. */
	private static final int JOINT = 2;

	/**
	 * Where a limb is narrow, as a share of the whole range from widest to thinnest.
	 *
	 * <h2>Why a table and not a number</h2>
	 *
	 * Because an outline is not monotone. A leg narrows to the knee and then
	 * <em>widens again</em> into the calf before it narrows to the ankle; an arm does
	 * the same at the hand, which is thicker than the wrist it hangs from. One number
	 * saying "how much narrower the bottom is" cannot say either, and a limb built
	 * from one reads as two bones stuck together — which is exactly what it was
	 * reported as.
	 *
	 * Nought is the widest the limb gets and one is the thinnest. The slider says how
	 * many pixels those two are apart, so the shape is here and the amount is the
	 * player's.
	 *
	 * These are authored rather than measured. There is no cubic model to take them
	 * off — the reference pictures are a rendering, not a mesh — so they are what a
	 * leg and an arm do, written down, and the honest thing is to say so.
	 */
	private static final float[] LEG = { 0f, 0.15f, 0f, 0.35f, 0.75f, 1f };

	/** The same for an arm, whose hand is wider than its wrist. */
	private static final float[] ARM = { 0f, 0.2f, 0f, 0.5f, 1f, 0.7f };

	/**
	 * One link's half-width, in whole pixels of box.
	 *
	 * <h2>Rounding is not a compromise here, it is the point</h2>
	 *
	 * The smallest step this art has is a whole pixel of width, which is half a pixel
	 * on each side. Anything finer was tried twice and is invisible: a four-pixel arm
	 * whose surface moves by a third of a pixel looks like a four-pixel arm with a
	 * ragged edge.
	 *
	 * So the profile is worked out as a continuous shape and then <b>quantised</b>,
	 * and what comes out is a staircase whose treads are all crisp.
	 *
	 * <h2>What this means for a thin limb, said plainly</h2>
	 *
	 * A vanilla limb is four pixels across, so between its full width and half of it
	 * there are only two whole-pixel levels. A profile cannot show more steps than
	 * there are levels, and no arrangement of numbers changes that: <b>four pixels
	 * has no room for an anatomy.</b>
	 *
	 * It is not a fault to be tuned away. It is why a skeleton's arm is a stick and
	 * why the figures this is being compared against have thick legs — a limb has to
	 * be given the width before it can be given the shape.
	 */
	private static float step(float half, float taper, float share) {
		// The step is rounded, not the width. Rounding the width would move a limb
		// nobody asked to move: a part whose own half-width is 1.2 would come back as
		// 1.0, which is the generator inventing something — the one thing this corner
		// of the project has had to take out again every time it did it.
		// Never past half the limb: a calf thinner than that is a stick, and the pixel
		// it would be drawn with does not exist. The limit is put on the step rather
		// than on the result, so that running into it still leaves a whole-pixel edge —
		// clamping the width instead lands wherever half of the limb happens to be,
		// which is off the grid for every limb whose width is not a multiple of two.
		float most = (float) Math.floor(half) / 2f;
		float drop = Math.min(most, Math.round(taper / 2f * share * 2f) / 2f);
		return half - drop;
	}

	/**
	 * The cube that stands between two segments so a bend cannot open a gap.
	 *
	 * <h2>Why a limb made of boxes needs one at all</h2>
	 *
	 * Two boxes meeting end to end are flush only while they are in line. Turn the
	 * lower one about the join and its end face tilts: a wedge opens on one side and
	 * the other side drives into its neighbour. Nothing about the skin causes this —
	 * it is what rigid pieces do — and it is why every well-built cubic model has a
	 * knob at the knee.
	 *
	 * <h2>How wide, and why exactly that</h2>
	 *
	 * The far corner of the turning segment's end face sits at its own half-diagonal
	 * from the join, and turning keeps it at that distance — a corner travels on a
	 * circle. A cube whose half-extent is that radius therefore contains the circle,
	 * and the corner can never leave it, <em>at any angle at all</em>.
	 *
	 * So the joint's half-extent is the lower segment's half-diagonal, and there is
	 * nothing to tune.
	 *
	 * <h2>The reference model agrees to three decimal places</h2>
	 *
	 * The cubic model measured in {@code docs/body-shape-mesh.md} has a calf 2.4
	 * across and a knee cube 3.4. Half of 2.4, times the root of two, is 1.697. Half
	 * of 3.4 is 1.7. Somebody arrived at that by eye; this arrives at it from the
	 * circle a corner travels on, and they are the same number.
	 *
	 * <h2>What it costs, said plainly</h2>
	 *
	 * The joint is wider than the limb — a knee that shows. On a limb tapered by a
	 * pixel it is about six per cent wider than the thigh, which is the knob in the
	 * reference model and not a defect. Cover for every angle cannot be had for less:
	 * a cube that fits inside the limb is a cube the corner leaves.
	 */
	private static SegmentMesh.Segment joint(float top, float bottom,
			float middleX, float halfX, float middleZ, float halfZ) {
		float reach = (float) (Math.max(halfX, halfZ) * Math.sqrt(2));
		return new SegmentMesh.Segment(top, bottom, middleX, reach, middleZ, reach);
	}

	/**
	 * The chain for a leg, with the pelvis having its say about the top of it.
	 *
	 * The invariant that survived the rewrite: two thighs stand under a pelvis and
	 * fill it exactly, less the tenth of a pixel vanilla overlaps them by. It was
	 * true when the leg was a smooth flare and it stays true now the leg is boxes —
	 * the flare has become the upper segment.
	 *
	 * @param side    minus one on the character's right, plus one on its left
	 * @param pivotX  where the part hangs, so the answer comes back in its own space
	 * @param lip     a quarter pixel for an outer layer, nought for the limb itself
	 */
	public static List<SegmentMesh.Segment> leg(BodyShape shape, float top, float bottom,
			float side, float pivotX, float middleZ, float lip) {
		float own = BodyField.legGirth(shape) + lip;
		float pelvis = BodyField.torsoHalf(shape, BodyField.HIP_Y) / 2f + lip;
		float upperMiddle = side * (BodyField.legMiddle(shape, BodyField.HIP_Y)) - pivotX;
		float ownMiddle = side * Math.max(1.9f, BodyField.legGirth(shape) - BodyField.LEG_OVERLAP)
			- pivotX;
		return limb(shape, top, bottom, ownMiddle, own, middleZ, own,
			pelvis, BodyField.BODY_DEEP + lip, upperMiddle);
	}

	/**
	 * The chain for an arm.
	 *
	 * Nothing dictates its top the way a pelvis dictates a thigh's: an arm hangs
	 * beside the torso rather than under it, and its inner face is already put on the
	 * torso's side by {@link BodyField#armMiddle}. So both ends are the arm's own,
	 * and the only step is the one the taper asks for.
	 */
	public static List<SegmentMesh.Segment> arm(BodyShape shape, float top, float bottom,
			float side, float pivotX, float middleZ, float base, float lip) {
		float half = BodyField.armHalf(shape, base) + lip;
		float deep = BodyField.armDepth(shape) + lip;
		float middle = side * BodyField.armMiddle(shape, base) - pivotX;
		return limb(shape, top, bottom, middle, half, middleZ, deep,
			half, deep, middle, ARM);
	}

	/**
	 * How far the whole model has to be lifted so the feet stay on the floor.
	 *
	 * A longer leg grows downwards, because a limb hangs from its pivot and that is
	 * where the hip is. The entity does not move, so without this a tall character
	 * stands in the ground up to its ankles — which is the sort of thing that is
	 * obvious in a picture and invisible in arithmetic.
	 *
	 * In pixels, positive upwards.
	 *
	 * @param legLength how long a leg's own box is, before stretching
	 */
	public static float lift(BodyShape shape, float legLength) {
		return legLength * (shape.height() - 1f);
	}
}
