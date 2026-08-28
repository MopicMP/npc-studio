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
			upperX, upperZ, upperMiddleX, true);
	}

	/** The same, told which outline to follow — a leg's or an arm's. */
	private static List<SegmentMesh.Ring> limb(BodyShape shape,
			float top, float bottom, float middleX, float halfX, float middleZ, float halfZ,
			float upperX, float upperZ, float upperMiddleX, boolean leg) {
		float[] across = leg ? LEG_WIDE : ARM_WIDE;
		float[] through = leg ? LEG_DEEP : ARM_DEEP;
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
				// Across and through are read from separate tables on purpose. A calf
				// is no wider than the shin above it and is markedly deeper — the
				// muscle bulges backwards, not sideways — and one number for both
				// cannot say that. Measured on the reference: down one leg the width
				// holds at 2.317 while the depth goes 2.573 to 3.458.
				loft.add(new SegmentMesh.Ring(y, share, middleX,
					step(halfX, shape.taper(), across[i]), middleZ,
					step(halfZ, shape.taper(), through[i])));
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
	 * How many cross-sections a limb is described by, and why thirteen.
	 *
	 * Thirteen rings make twelve bands, and a limb's flank is twelve rows of texels —
	 * so <b>one band is exactly one pixel row of the skin</b>. That is the finest an
	 * outline can be without a band straddling a row, and it is the resolution the
	 * reference models work at: down the Elf's leg the width changes at nine
	 * different heights, one to three pixels apart.
	 *
	 * Seven was the earlier answer, and it was chosen to pair with a rounding that
	 * has since been shown wrong. Six bands could carry the names of a leg's parts
	 * but not its outline: a whole change of width had to happen inside one band, and
	 * a whole change of width in one band is a step.
	 *
	 * Rings are cheap, which they were not when a limb was a stack of boxes. Then
	 * every change of width was a hole to patch and a row of texels to duplicate;
	 * extruded, a change of width is a slanted band and costs nothing beyond the quad
	 * that was going to be drawn anyway.
	 */
	public static final int RINGS = 13;

	/**
	 * Where a limb is narrow, as a share of the whole range from widest to thinnest.
	 *
	 * <h2>Why a table and not a number</h2>
	 *
	 * Because an outline is not monotone. Read the numbers and the leg is in them: it
	 * narrows steadily from the hip to 0.62 below the knee, comes back <em>out</em> to
	 * 0.45 where the calf swells, and only then goes away to 1 at the ankle. It turns
	 * round. No single number saying "how much narrower the bottom is" can say that,
	 * and a limb built from one reads as two bones stuck together — which is exactly
	 * what it was reported as.
	 *
	 * <h2>Why every table now ends at its thinnest</h2>
	 *
	 * They did not: a hand is wider than the wrist it hangs from and a foot is deeper
	 * than the ankle, so the last ring turned back out. Rendered, both read as a flat
	 * plate stuck on the end of the limb, and the reason is the skin. A vanilla arm
	 * is a plain box textured as an arm to the fingertips; there is no hand drawn as
	 * a separate shape, so a ring that flares has no texels made for it and gets the
	 * forearm's, stretched. Anatomy the skin does not carry cannot be modelled.
	 *
	 * Nought is the widest the limb gets and one is the thinnest. The slider says how
	 * many pixels those two are apart, so the shape is here and the amount is the
	 * player's.
	 *
	 * <h2>What the reference actually does</h2>
	 *
	 * Measured down the Elf's left leg, nineteen pieces, width in vanilla pixels:
	 * 2.964, 2.933, 2.670, 2.317, 2.287, 2.006, 2.582, 2.006, 2.026. The whole range
	 * from widest to thinnest is <b>under one pixel</b>, and it is spent in eight
	 * changes of 0.02 to 0.58 — not one of them a round number and not one of them a
	 * whole pixel. The arm is the same: 3.382, 3.620, 3.045, 1.944, 1.858, 1.135,
	 * widening before it narrows.
	 *
	 * That is where the smoothness comes from. It is not a smarter curve and it is
	 * not a different kind of box — the boxes are plain axis-aligned boxes, 95% of
	 * them on the Elf and every last one on the Halloween model. It is that no single
	 * step is large enough to read as a step.
	 *
	 * These particular numbers are still authored: the reference's own profile has
	 * its boots baked into it, and a boot is not an anatomy. What is taken from the
	 * measurement is the character — how much range, and how finely it is spent.
	 */
	private static final float[] LEG_WIDE = {
		0f, 0.05f, 0.14f, 0.24f, 0.38f, 0.55f, 0.62f,
		0.45f, 0.52f, 0.72f, 0.90f, 0.98f, 1f };

	/**
	 * A leg through, which is a different shape from a leg across.
	 *
	 * The calf bulges backwards and hardly at all sideways. Tying depth to width, as
	 * one table did, makes a leg that is round all the way down.
	 *
	 * The bottom eases rather than turning out into a foot, and that is a limit of
	 * the skin rather than of the model: a vanilla leg is a plain box textured as a
	 * leg all the way to the sole, and there is no foot drawn on it. Bulging the last
	 * ring forward would stretch texels over a surface nobody painted.
	 */
	private static final float[] LEG_DEEP = {
		0f, 0.04f, 0.12f, 0.22f, 0.36f, 0.48f, 0.52f,
		0.38f, 0.30f, 0.44f, 0.70f, 0.90f, 1f };

	/** An arm across, whose hand is wider than the wrist it hangs from. */
	private static final float[] ARM_WIDE = {
		0f, 0.06f, 0.15f, 0.26f, 0.36f, 0.44f, 0.40f,
		0.48f, 0.62f, 0.78f, 0.90f, 0.97f, 1f };

	/** An arm through, whose elbow stands out behind it. */
	private static final float[] ARM_DEEP = {
		0f, 0.05f, 0.13f, 0.24f, 0.34f, 0.38f, 0.28f,
		0.42f, 0.58f, 0.74f, 0.88f, 0.96f, 1f };

	/**
	 * The outline a limb has, across or through.
	 *
	 * Public because the shape is worth checking on its own, apart from any
	 * particular slider setting. Nought is the widest the limb gets and one is the
	 * thinnest; the slider says how many pixels apart those two are, so the shape is
	 * here and the amount is the player's.
	 *
	 * @param leg  a leg's outline rather than an arm's
	 * @param deep front to back rather than side to side
	 */
	public static float[] outline(boolean leg, boolean deep) {
		return (leg ? (deep ? LEG_DEEP : LEG_WIDE) : (deep ? ARM_DEEP : ARM_WIDE)).clone();
	}

	/**
	 * One ring's half-width. Nothing is rounded, and that is the whole of the fix.
	 *
	 * <h2>What used to be here, and why it was wrong</h2>
	 *
	 * This line used to read {@code Math.round(taper / 2f * share * 2f) / 2f} — the
	 * step quantised to half a pixel a side, a whole pixel of width. The reasoning
	 * written above it was that a whole pixel is the smallest step this art has and
	 * anything finer is invisible.
	 *
	 * That is true of a <em>texture</em> and false of a <em>silhouette</em>, and the
	 * conclusion was carried from where it had been tested to where it had not. The
	 * reference models settle it: the Elf's leg never takes a whole-pixel step, its
	 * largest is 0.58 and its smallest is 0.02, and it is the smoothest limb of the
	 * six. Rounding to whole pixels gave a four-pixel leg exactly three widths to
	 * choose from — 4, 3 and 2 — so an outline of a dozen bands came out as a
	 * staircase of three, which is what it was reported as: one wider, another
	 * narrower, a third wider again.
	 *
	 * <h2>The cost, stated rather than hidden</h2>
	 *
	 * A face 2.3 pixels wide carries texels that were drawn for a face 2 or 3 wide,
	 * so the density on the flanks stops being exactly one texel per square pixel.
	 * The skin still cannot slide or tear: a vertex's UV does not depend on where the
	 * vertex is, so moving one changes the shape and nothing else. The reference
	 * models pay the same price and it is what they look like.
	 *
	 * <h2>The floor</h2>
	 *
	 * A limb keeps at least half its own width, whatever the slider says. Smooth,
	 * because a floor on a grid was the other half of the same mistake — it landed
	 * the width wherever half the limb happened to be, which is off any grid for
	 * every limb whose width is not a multiple of two.
	 */
	private static float step(float half, float taper, float share) {
		return half - Math.min(half / 2f, taper / 2f * share);
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
			half, deep, middle, false);
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
