package com.mopicmp.npcstudio.client.emote;

/**
 * The arithmetic of folding a limb, with nothing else attached to it.
 *
 * <h2>What the hatching was</h2>
 *
 * Not a bending artefact. <b>Z-fighting</b>: two surfaces of the same limb in
 * the same place, the depth buffer unable to choose, every pixel picking a
 * different winner, the pattern crawling as the angle changes.
 *
 * Established rather than deduced. The hatching does not depend on the skin's
 * resolution; it is there with the second layer switched off; it appears only
 * while a limb is bent; and it vanished the moment a version stopped the two
 * halves of a limb from occupying the same space.
 *
 * <h2>The joint is a mitre, and it is made by leaning, not by cutting</h2>
 *
 * Two boxes meeting at an angle fit exactly when each is cut along the bisector
 * — a picture frame's corner. That much several versions had right.
 *
 * They got it by <em>cutting</em> the box on that plane and moving rigid pieces,
 * which forces each piece to reach past the middle for the material the outside
 * of a bend needs, and so to borrow a band of skin from its neighbour and draw
 * it twice. That band is skin appearing where no skin belongs, and it was the
 * bug — not the price of the method, as it had been written up.
 *
 * There is no need to borrow. Lean the half instead: slide each point along the
 * limb by {@code (distance from the far end / half the limb) · tan(bend/2) ·
 * depth}. The far end does not move at all, the joint end moves the full amount,
 * and everything between moves its share. The end face tilts by half the fold —
 * which is the bisector — so the halves still meet exactly, but every scrap of
 * surface a half needs is found <b>inside that half</b>, by stretching, rather
 * than taken from the other one.
 *
 * Nothing leaves the box it started in. That is the property the cutting version
 * could not have.
 *
 * This is how {@code bendy-lib} does it — the library Emotecraft actually bends
 * with, and which an earlier verdict here wrongly concluded did not exist,
 * having looked in the two jars that do not contain it. The arithmetic below is
 * that library's, written out in this codebase's terms.
 *
 * <h2>Why the fold is drawn in bands</h2>
 *
 * Within a half the transformation is affine — a lean is a shear and a shear
 * keeps flat things flat — so a face needs cutting up only where the two halves
 * meet. It is cut into bands anyway, with a boundary exactly on the joint, so
 * that no single quad ever straddles it.
 *
 * Distances are blocks, angles radians, and every method is a function of its
 * arguments alone.
 */
public final class Folding {

	/**
	 * The sharpest fold anything is drawn at, whatever the animation asks for.
	 *
	 * The lean is {@code tan(bend/2)} and a tangent runs away: at two and a half
	 * radians a four-pixel limb already leans by six, which is its whole half. The
	 * pack's sharpest is 2.11, in a fighting pose's knee, so this clears
	 * everything that exists while keeping the lean shorter than the limb.
	 */
	public static final float MOST = 2.2f;

	private Folding() { }

	/** As much of a fold as is ever drawn. */
	public static float allowed(float bend) {
		return Math.clamp(bend, -MOST, MOST);
	}

	/** The joint, which is the middle of the limb. */
	public static float middle(float top, float bottom) {
		return (top + bottom) / 2f;
	}

	/** Whether a point belongs to the half that swings. */
	public static boolean swings(float y, float top, float bottom) {
		return y > middle(top, bottom);
	}

	/**
	 * How much of the limb the lean is spread over, either side of the joint.
	 *
	 * <h2>The reason this is not simply half the limb</h2>
	 *
	 * bendy-lib ramps the lean from nought at a half's far end to its full value at
	 * the joint, so every point of the limb is dragged a little. That is fine for a
	 * shape whose surface is plain, and wrong for a character drawn pixel by pixel:
	 * the lean is proportional to depth, so on a limb's <em>side</em> — the one face
	 * whose depth varies across it — one edge is dragged forwards and the other
	 * back, and a horizontal stripe of a sleeve comes out as a diagonal with the
	 * rest of the pattern smeared along the arm.
	 *
	 * Nothing requires the drag to reach that far. The mitre closes as long as the
	 * lean reaches its full value <b>at the joint</b>; where it starts is free. So
	 * it starts as late as the angle allows, and the rest of the limb — most of it,
	 * at the angles animations actually use — is left exactly as it was drawn.
	 *
	 * How late is set by the one thing that limits it. Across this stretch the
	 * surface is compressed by {@code depth · tan(bend/2) / reach}; let that reach
	 * one and the limb folds through itself. {@link #KEEP} is what is held back
	 * from that, so a sharp fold spreads and a gentle one stays a crease.
	 */
	public static float reach(float top, float bottom, float bend, float halfDepth) {
		float half = (bottom - top) / 2f;
		if (half <= 0) return 0;

		float needed = Math.abs((float) Math.tan(bend / 2f) * halfDepth) / (1f - KEEP);
		return Math.clamp(needed, Math.min(LEAST, half), half);
	}

	/** How much of itself the leaned stretch keeps, at its most compressed. */
	private static final float KEEP = 0.35f;

	/** The least of a limb the lean is ever spread over: a pixel and a half. */
	private static final float LEAST = 1.5f / 16f;

	/**
	 * Where a point of the limb ends up.
	 *
	 * Two pieces and no cut. The shoulder half leans one way and stays where it is;
	 * the hand half leans the other way and is then swung about the joint. Both
	 * leans are nought until {@link #reach} of the joint and full at it, so both far
	 * ends stay exactly where they were hung and most of the limb is never touched
	 * at all.
	 */
	public static float[] fold(float y, float z, float top, float bottom,
			float bend, float halfDepth) {
		float half = (bottom - top) / 2f;
		if (half <= 0 || bend == 0) return new float[] { y, z };

		float middle = middle(top, bottom);
		float reach = reach(top, bottom, bend, halfDepth);
		if (reach <= 0) return new float[] { y, z };

		float lean = (float) Math.tan(bend / 2f) * z;

		if (y <= middle) {
			float ramp = Math.clamp((y - (middle - reach)) / reach, 0f, 1f);
			return new float[] { y - ramp * lean, z };
		}
		float ramp = Math.clamp((middle + reach - y) / reach, 0f, 1f);
		return carry(y + ramp * lean, z, middle, bend);
	}

	/**
	 * Swings a point about the joint, rigidly.
	 *
	 * Kept separate because the hand half is turned by exactly this and nothing
	 * else — so a hand is moved and turned, never stretched, whatever the joint
	 * above it is doing.
	 */
	public static float[] carry(float y, float z, float middle, float angle) {
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		float along = y - middle;
		return new float[] {
			middle + along * cos - z * sin,
			along * sin + z * cos };
	}

	/**
	 * How far a surface at this height has been turned, for its shading.
	 *
	 * Nought on the shoulder half and the whole fold on the hand half. A lean does
	 * not turn a surface — it slides it along itself — so there is nothing in
	 * between to account for.
	 */
	public static float angleAt(float y, float top, float bottom, float bend) {
		return swings(y, top, bottom) ? bend : 0;
	}
}
