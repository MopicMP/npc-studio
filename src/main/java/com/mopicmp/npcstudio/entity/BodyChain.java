package com.mopicmp.npcstudio.entity;

import java.util.List;

/**
 * What a limb is made of, for a given build.
 *
 * The one place that turns settings into rings. {@link SegmentMesh} knows how to
 * cut a part into boxes and nothing about bodies; {@link BodyField} knows where the
 * body's surfaces are and nothing about boxes; this is the join between them, and it
 * is deliberately small.
 *
 * <h2>Rings, not pieces</h2>
 *
 * A limb is described by its cross-sections down its length, and the surface between
 * two of them is made by extruding one into the other. So this hands over a list of
 * rings and {@link SegmentMesh} bridges them; where two rings differ, the band
 * between them comes out slanted, which is what a limb's outline actually does.
 *
 * <h2>What must stay true</h2>
 *
 * An untouched build gives two rings with the part's own numbers — a plain box —
 * which {@code SegmentMesh} then hands back unchanged. That is what keeps an
 * ordinary character on the fast path, and it is checked rather than hoped for.
 */
public final class BodyChain {

	private BodyChain() { }

	/**
	 * The rings of one limb.
	 *
	 * @param top    where the part's box begins, in its own space
	 * @param bottom where it ends
	 * @param halfX  half its width, as the model built it
	 * @param halfZ  half its depth
	 * @param middleX where its middle sits across, which is not its pivot
	 * @param middleZ the same front to back
	 */
	public static List<SegmentMesh.Ring> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ) {
		return limb(shape, top, bottom, middleX, halfX, middleZ, halfZ, halfX, halfZ, middleX);
	}

	/**
	 * The rings of a limb whose top belongs to what it hangs from.
	 *
	 * A thigh is not free to be any width it likes: two of them stand under a pelvis
	 * and the join has to be a join. So the topmost ring is given the parent's
	 * numbers and the rest follow the limb's own outline.
	 *
	 * With nothing asked for it comes back as two rings — which is a plain box, and
	 * the mesh hands the model straight back.
	 *
	 * @param halfX   the limb's own half-width
	 * @param upperX  the half-width the parent dictates at the top
	 * @param upperZ  the same through
	 * @param upperMiddleX where the top ring sits across, which a wide pelvis moves
	 */
	public static List<SegmentMesh.Ring> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ,
			float upperX, float upperZ, float upperMiddleX) {
		return limb(shape, top, bottom, middleX, halfX, middleZ, halfZ,
			upperX, upperZ, upperMiddleX, LEG);
	}

	/** The same, told which outline to follow — a leg's or an arm's. */
	private static List<SegmentMesh.Ring> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ,
			float upperX, float upperZ, float upperMiddleX, float[] profile) {
		float length = (bottom - top) * shape.height();
		boolean parted = upperX != halfX || upperZ != halfZ || upperMiddleX != middleX;

		// Nothing asked for and nothing to say: two rings, the part's own size, and the
		// mesh gives the model straight back. A plain box is a loft of two.
		if (shape.taper() <= 0 && shape.height() == 1f && !parted) {
			return List.of(
				new SegmentMesh.Ring(top, 0f, middleX, halfX, middleZ, halfZ),
				new SegmentMesh.Ring(bottom, 1f, middleX, halfX, middleZ, halfZ));
		}

		var loft = new java.util.ArrayList<SegmentMesh.Ring>(RINGS);
		for (int i = 0; i < RINGS; i++) {
			float share = i / (float) (RINGS - 1);
			float y = top + length * share;
			if (i == 0) {
				// The top ring belongs to whatever the limb hangs from: two thighs stand
				// under one pelvis, and the join has to be a join.
				loft.add(new SegmentMesh.Ring(y, 0f, upperMiddleX, upperX, middleZ, upperZ));
			} else {
				loft.add(new SegmentMesh.Ring(y, share, middleX,
					step(halfX, shape.taper(), profile[i]), middleZ,
					step(halfZ, shape.taper(), profile[i])));
			}
		}
		return List.copyOf(loft);
	}

	/**
	 * How far a turning limb's corner reaches from the joint it turns about.
	 *
	 * <h2>Kept, because the derivation is worth keeping</h2>
	 *
	 * The far corner of a turning piece sits at its own half-diagonal from the join,
	 * and turning keeps it there — a corner travels on a circle. So a cover of that
	 * half-extent contains the corner at every angle there is, and there is nothing
	 * to tune.
	 *
	 * The cubic model measured in {@code docs/body-shape-mesh.md} has a calf 2.4
	 * across and a knee cube 3.4: half of 2.4 times the root of two is 1.697, and
	 * half of 3.4 is 1.7. Somebody arrived at that by eye and this arrives at it from
	 * the circle.
	 *
	 * <h2>Why nothing uses it yet, said plainly</h2>
	 *
	 * A gap can only open where the surface is <em>broken</em>, and a lofted limb is
	 * one shell with no break in it. The break arrives when a bend becomes a real
	 * turn at the knee rather than a fold of the vertices — which is the animation
	 * step — and this is the number the knob will have to be then.
	 */
	public static float jointReach(float half) {
		return (float) (half * Math.sqrt(2));
	}

	/**
	 * How many cross-sections a limb is described by, and why seven.
	 *
	 * Seven rings make six bands, and six is the fewest that can carry an anatomy: a
	 * hip, a thigh, a knee, a calf, a shin and an ankle. A flank is twelve rows of
	 * texels, so six bands take two rows each.
	 *
	 * Rings are cheap now, which they were not when a limb was a stack of boxes. Then
	 * every change of width was a hole to patch and a row of texels to duplicate;
	 * extruded, a change of width is a slanted band and costs nothing beyond the quad
	 * that was going to be drawn anyway.
	 */
	public static final int RINGS = 7;

	/**
	 * Where a limb is narrow, as a share of the whole range from widest to thinnest.
	 *
	 * <h2>Why a table and not a number</h2>
	 *
	 * Because an outline is not monotone. Read the numbers and the leg is in them: 0,
	 * 0.05, 0.25 — narrowing towards the knee — then back <em>out</em> to 0.15 at the
	 * knee itself and 0.25 at the calf, and only then away to 0.7 and 1 at the ankle.
	 * It turns round twice. No single number saying "how much narrower the bottom is"
	 * can say that, and a limb built from one reads as two bones stuck together —
	 * which is exactly what it was reported as.
	 *
	 * Nought is the widest the limb gets and one is the thinnest. The slider says how
	 * many pixels those two are apart, so the shape is here and the amount is the
	 * player's.
	 *
	 * These are authored rather than measured. There is no cubic model to take them
	 * off — the reference pictures are a rendering, not a mesh — so they are what a
	 * leg and an arm do, written down, and the honest thing is to say so.
	 */
	private static final float[] LEG = { 0f, 0.05f, 0.25f, 0.15f, 0.25f, 0.7f, 1f };

	/** The same for an arm, whose hand is wider than the wrist it hangs from. */
	private static final float[] ARM = { 0f, 0.15f, 0.35f, 0.25f, 0.35f, 0.8f, 0.55f };

	/**
	 * The outline a limb is meant to have, before anything is rounded off it.
	 *
	 * Public so that the intent can be checked apart from what survives quantising,
	 * because at present those are two different things and hiding the difference
	 * would be the dishonest part. The table turns round twice; a four-pixel limb
	 * with a pixel and a half of range has two whole-pixel levels to put that on, so
	 * what actually comes out is a taper.
	 *
	 * Nothing is wrong with either half of that. The shape is right and the room is
	 * missing, and the room comes from a bigger figure — which is what the drawings
	 * being compared against are.
	 */
	public static float[] outline(boolean leg) {
		return (leg ? LEG : ARM).clone();
	}

	/**
	 * One ring's half-width, in whole pixels of box.
	 *
	 * <h2>Rounding is not a compromise here, it is the point</h2>
	 *
	 * The smallest step this art has is a whole pixel of width, which is half a pixel
	 * on each side. Anything finer was tried twice and is invisible: a four-pixel arm
	 * whose surface moves by a third of a pixel looks like a four-pixel arm with a
	 * ragged edge.
	 *
	 * So the outline is worked out as a continuous shape and then <b>quantised</b>,
	 * and the bands between the rings do the rest — a slope from one whole width to
	 * the next, which is crisp at both ends and gradual in between.
	 *
	 * <h2>What this means for a thin limb, said plainly</h2>
	 *
	 * A vanilla limb is four pixels across, so between its full width and half of it
	 * there are only two whole-pixel levels. An outline cannot show more widths than
	 * there are levels: <b>four pixels has no room for an anatomy.</b> It is not a
	 * fault to be tuned away — it is why a skeleton's arm is a stick.
	 *
	 * What length buys is different and worth keeping separate: it does not add
	 * levels, it spreads the ones there are further apart, and that is what reads as
	 * gradual.
	 */
	private static float step(float half, float taper, float share) {
		// The step is rounded, not the width. Rounding the width would move a limb
		// nobody asked to move: a part whose own half-width is 1.2 would come back as
		// 1.0, which is the generator inventing something.
		//
		// The floor is put on the step rather than on the result for the same reason —
		// clamping the width lands wherever half of the limb happens to be, which is
		// off the grid for every limb whose width is not a multiple of two.
		float most = (float) Math.floor(half) / 2f;
		float drop = Math.min(most, Math.round(taper / 2f * share * 2f) / 2f);
		return half - drop;
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
	public static List<SegmentMesh.Ring> leg(BodyShape shape, float top, float bottom,
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
	public static List<SegmentMesh.Ring> arm(BodyShape shape, float top, float bottom,
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
