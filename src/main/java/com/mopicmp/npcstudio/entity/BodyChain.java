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
		float length = (bottom - top) * shape.height();
		boolean parted = upperX != halfX || upperZ != halfZ || upperMiddleX != middleX;

		// Nothing asked for and nothing to say: one segment, the part's own size, and
		// the mesh gives the model straight back.
		if (shape.taper() <= 0 && shape.height() == 1f && !parted) {
			return List.of(new SegmentMesh.Segment(top, bottom, middleX, halfX, middleZ, halfZ));
		}

		// Half the taper off each side, because the number is the width of the box and
		// not the distance one face moves. Never past half the limb: a calf thinner
		// than that is a stick, and the pixel it is drawn with does not exist.
		float thin = Math.min(shape.taper() / 2f, Math.min(halfX, halfZ) / 2f);
		float lowerX = halfX - thin;
		float lowerZ = halfZ - thin;

		// Three equal thirds, and the middle one is the joint.
		//
		// Equal because the limb's flank is twelve rows of texels and three divides
		// twelve — see BodyChainTest#everyCutLandsOnAWholeTexel. Equal in length as
		// well as in texture, so every face comes out at a density of exactly one:
		// four rows of skin over four pixels of limb, everywhere.
		float first = top + length / 3f;
		float second = top + length * 2f / 3f;
		return List.of(
			new SegmentMesh.Segment(top, first, upperMiddleX, upperX, middleZ, upperZ),
			joint(first, second, middleX, lowerX, middleZ, lowerZ),
			new SegmentMesh.Segment(second, top + length, middleX, lowerX, middleZ, lowerZ));
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
		return limb(shape, top, bottom, middle, half, middleZ, deep);
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
