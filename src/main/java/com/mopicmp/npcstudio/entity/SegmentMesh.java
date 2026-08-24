package com.mopicmp.npcstudio.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a limb out of boxes instead of bending the one box it was given.
 *
 * <h2>Why a chain and not a profile</h2>
 *
 * Twice a limb was shaped by moving its surface along its length — a knee half a
 * pixel narrower than the thigh, an ankle two thirds of a pixel narrower again.
 * With a skin on it, at four pixels across, the whole effect was under one pixel:
 * invisible where it worked and a ragged outline where it did not. The game is not
 * drawn that way and never has been. A skeleton is thin because its arm is a two by
 * two box, not because its arm tapers; a good custom model has a thigh and a calf
 * that differ by a whole pixel and a joint cube between them.
 *
 * So a limb is a <b>chain of boxes</b>. Each one has its own cross-section and its
 * own length, and the change from one to the next is a step somebody chose, not a
 * ripple nobody can see.
 *
 * <h2>Why it is given the vanilla faces rather than the skin layout</h2>
 *
 * Because the layout is exactly the sort of thing that gets remembered wrong. A box
 * of width w and depth d puts its front face at one offset and its flank at
 * another, and every one of those offsets is a chance to be a pixel out on somebody
 * else's skin.
 *
 * The model already knows. Minecraft bakes texture coordinates into the corners of
 * every face it builds, so this is handed those faces and cuts them: a segment
 * covering the middle third of a limb takes the middle third of the face's texture.
 * Nothing here knows what a skin looks like, which is why it cannot be wrong about
 * one.
 *
 * <h2>What must stay true</h2>
 *
 * A chain of one segment with the source's own numbers has to give the source back,
 * corner for corner and texel for texel. That is not a nicety — it is what lets an
 * ordinary character keep being drawn by Minecraft, and it is the test that says
 * the generator has not quietly invented anything.
 */
public final class SegmentMesh {

	/** One corner: where it is, and where on the skin it reads from. */
	public record Corner(float x, float y, float z, float u, float v) { }

	/** Four corners in the order the model gave them, so winding survives. */
	public record Quad(Corner a, Corner b, Corner c, Corner d) {
		public Corner[] corners() {
			return new Corner[] { a, b, c, d };
		}
	}

	/**
	 * One box of a chain.
	 *
	 * Its own length, so that a tall character is tall — the height of a body is the
	 * sum of its segments and not a share of a fixed thirty-two pixels. Its own
	 * cross-section, so that a thigh and a calf can differ. Its own middle, so that
	 * a segment can sit off the limb's axis without the whole limb moving.
	 */
	public record Segment(float top, float bottom,
			float middleX, float halfX, float middleZ, float halfZ) {

		public float length() {
			return bottom - top;
		}
	}

	/**
	 * The part as Minecraft built it: its six faces and the span they cover.
	 *
	 * @param faces  every face of the box, in whatever order they arrived
	 * @param top    the smallest y of the box, in the part's own space
	 * @param bottom the largest
	 */
	public record Source(List<Quad> faces, float top, float bottom,
			float middleX, float middleZ) { }

	private SegmentMesh() { }

	/**
	 * Cuts the source into a chain and gives back the faces to draw.
	 *
	 * The chain's segments are laid end to end in the order given; each takes an
	 * equal share of the source's texture unless {@code shares} says otherwise, so a
	 * calf drawn shorter than a thigh still reads the part of the skin that belongs
	 * to it.
	 *
	 * @param shares how much of the source's length each segment stands for, in the
	 *               same order; null for equal shares
	 */
	public static List<Quad> build(Source source, List<Segment> chain, float[] shares) {
		List<Quad> out = new ArrayList<>();
		if (chain.isEmpty()) return out;

		float[] cuts = cuts(chain.size(), shares);
		for (int i = 0; i < chain.size(); i++) {
			Segment segment = chain.get(i);
			for (Quad face : source.faces()) {
				boolean cap = isCap(face, source);
				if (cap) {
					// An end is drawn only where the limb actually ends. The joins in
					// between are inside the body: drawing them costs geometry nobody
					// sees and, on a skin whose end faces are transparent, shows as a
					// hole through the limb.
					boolean atTop = face.a().y() <= (source.top() + source.bottom()) / 2f;
					if (atTop && i != 0) continue;
					if (!atTop && i != chain.size() - 1) continue;
				}
				out.add(cut(face, source, segment, cuts[i], cuts[i + 1], cap));
			}
		}
		return out;
	}

	/** Where each segment starts and ends, as a share of the source's length. */
	private static float[] cuts(int count, float[] shares) {
		float[] cuts = new float[count + 1];
		float total = 0;
		for (int i = 0; i < count; i++) total += shares == null ? 1f : Math.max(0f, shares[i]);
		if (total <= 0) total = count;
		float at = 0;
		for (int i = 0; i < count; i++) {
			cuts[i] = at;
			at += (shares == null ? 1f : Math.max(0f, shares[i])) / total;
		}
		cuts[count] = 1f;
		return cuts;
	}

	/** Whether a face looks along the limb rather than across it. */
	private static boolean isCap(Quad face, Source source) {
		float y = face.a().y();
		for (Corner corner : face.corners()) {
			if (Math.abs(corner.y() - y) > 1e-4f) return false;
		}
		return true;
	}

	/**
	 * One face of the source, put where a segment wants it.
	 *
	 * A corner is not recognised by name or by which face it belongs to. It is asked
	 * two questions — which side of the box it is on, and how far down it is — and
	 * placed at the same side and the same relative height of the segment. That is
	 * why this needs no table of faces: the answer for a flank is the answer for the
	 * front, and the winding order comes out of it untouched.
	 */
	private static Quad cut(Quad face, Source source, Segment segment,
			float from, float to, boolean cap) {
		float span = source.bottom() - source.top();
		float lowV = lowestV(face);
		float highV = highestV(face);

		Corner[] corners = face.corners();
		Corner[] moved = new Corner[4];
		for (int i = 0; i < 4; i++) {
			Corner corner = corners[i];
			float down = span <= 0 ? 0 : (corner.y() - source.top()) / span;
			float sideX = Math.signum(corner.x() - source.middleX());
			float sideZ = Math.signum(corner.z() - source.middleZ());

			// A cap is flat: every corner of it sits at one end of the source, so it
			// goes to the same end of the segment.
			float y = cap
				? (down < 0.5f ? segment.top() : segment.bottom())
				: segment.top() + down * segment.length();
			// A cap keeps its own texture: it is one end of the limb and there is only
			// one of it. A flank's texture is cut to the segment's share, which is what
			// keeps a two-piece leg reading as one leg wearing one pair of trousers.
			float v = cap ? corner.v()
				: lowV + (highV - lowV) * (from + down * (to - from));

			moved[i] = new Corner(
				segment.middleX() + sideX * segment.halfX(),
				y,
				segment.middleZ() + sideZ * segment.halfZ(),
				corner.u(), v);
		}
		return new Quad(moved[0], moved[1], moved[2], moved[3]);
	}

	private static float lowestV(Quad face) {
		float low = Float.MAX_VALUE;
		for (Corner corner : face.corners()) low = Math.min(low, corner.v());
		return low;
	}

	private static float highestV(Quad face) {
		float high = -Float.MAX_VALUE;
		for (Corner corner : face.corners()) high = Math.max(high, corner.v());
		return high;
	}
}
