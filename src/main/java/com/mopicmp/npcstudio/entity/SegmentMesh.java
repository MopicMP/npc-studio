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
 * <h2>And two links is not a leg</h2>
 *
 * The paragraph above is right about the method and was wrong about the number. A
 * chain of two reads as two different bones stuck together, because a leg's outline
 * does not simply narrow: it narrows to the knee and <em>widens again</em> into the
 * calf before it narrows to the ankle. An arm does the same at the forearm and a
 * torso does it at the waist.
 *
 * Smoothness in a cubic model is not a curved surface — that was tried twice and is
 * invisible. It is <b>enough steps</b>: a staircase with enough treads reads as a
 * slope, and each tread is a whole pixel so it is crisp rather than smeared.
 *
 * Segments are cheap and steps are not. A segment costs faces; a change of
 * cross-section costs a ring and a duplicated row of texels. So a limb wants as
 * many links as it takes to put the steps at the right heights, and only as many
 * steps as the outline is worth.
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
		rings(source, chain, cuts, out);
		return out;
	}

	/** How close two edges must be before they count as the same edge. */
	private static final float SNUG = 1e-4f;

	/**
	 * The step where two segments of different cross-section meet.
	 *
	 * <h2>Why there has to be anything here at all</h2>
	 *
	 * A chain whose segments differ — a thigh wider than a calf — leaves a ring
	 * facing along the limb at every join. It is real surface, and it is the one
	 * place where "every texel appears exactly once" cannot be kept: the ring is
	 * not in the skin, because vanilla has no such surface to paint.
	 *
	 * Not drawing it, which is what happened before, leaves a hole. Looking at a
	 * knee from below you see through the thigh, because the inside of a box is not
	 * drawn.
	 *
	 * <h2>Why not the cap's own texels</h2>
	 *
	 * Because an end face is not what this is. Many skins leave the ends of a limb
	 * transparent — nobody sees the top of an arm — so a ring painted with them is
	 * a hole with extra steps.
	 *
	 * So it takes the flank's boundary row: the line of texels the surface arrives
	 * at, continued outwards across the step. One row duplicated, on a strip a
	 * fraction of a pixel wide, facing along the limb. That is the whole of the
	 * price, and naming it is the point.
	 *
	 * <h2>Nothing is emitted where nothing steps</h2>
	 *
	 * Two segments of the same cross-section produce strips of no width, and those
	 * are dropped. So a chain of one, and a chain of equal pieces, both give back
	 * exactly what they were handed — which is the property everything else rests
	 * on.
	 */
	private static void rings(Source source, List<Segment> chain, float[] cuts,
			List<Quad> out) {
		for (int i = 1; i < chain.size(); i++) {
			Segment above = chain.get(i - 1);
			Segment below = chain.get(i);
			float[] wide = box(above);
			float[] narrow = box(below);
			// Each segment covers whatever the other does not, so a piece that is
			// broader across and shallower through — which is a real shape, not a
			// pathological one — gets a ring facing each way rather than neither.
			skirt(source, wide, narrow, below.top(), cuts[i], false, out);
			skirt(source, narrow, wide, below.top(), cuts[i], true, out);
		}
	}

	/** A segment's cross-section as x0, x1, z0, z1. */
	private static float[] box(Segment segment) {
		return new float[] {
			segment.middleX() - segment.halfX(), segment.middleX() + segment.halfX(),
			segment.middleZ() - segment.halfZ(), segment.middleZ() + segment.halfZ() };
	}

	/**
	 * Whatever of {@code outer} sticks out past {@code inner}, as up to four strips.
	 *
	 * The frame decomposition: the part outside on either side across, and then the
	 * part outside front and back of what is left. Every strip belongs to one flank
	 * — the one it runs along — and takes its texture from there.
	 */
	private static void skirt(Source source, float[] outer, float[] inner, float y,
			float share, boolean facesUp, List<Quad> out) {
		float ix0 = Math.max(outer[0], inner[0]);
		float ix1 = Math.min(outer[1], inner[1]);
		float iz0 = Math.max(outer[2], inner[2]);
		float iz1 = Math.min(outer[3], inner[3]);

		if (ix1 - ix0 < SNUG || iz1 - iz0 < SNUG) {
			// The two do not overlap at all, so the whole end is a step. Nothing in a
			// body does this, and answering it with a whole face beats answering it
			// with a hole.
			strip(source, outer[0], outer[1], outer[2], outer[3], y, share, facesUp,
				true, -1, out);
			return;
		}
		strip(source, outer[0], ix0, outer[2], outer[3], y, share, facesUp, true, -1, out);
		strip(source, ix1, outer[1], outer[2], outer[3], y, share, facesUp, true, 1, out);
		strip(source, ix0, ix1, outer[2], iz0, y, share, facesUp, false, -1, out);
		strip(source, ix0, ix1, iz1, outer[3], y, share, facesUp, false, 1, out);
	}

	/**
	 * One strip of a ring, if it has any width at all.
	 *
	 * @param acrossX whether the strip runs along a flank facing across rather than
	 *                through, which is what decides where its texture comes from
	 * @param side    which of the two flanks on that axis, as a sign
	 */
	private static void strip(Source source, float x0, float x1, float z0, float z1,
			float y, float share, boolean facesUp, boolean acrossX, float side,
			List<Quad> out) {
		if (x1 - x0 < SNUG || z1 - z0 < SNUG) return;
		Quad flank = flankOn(source, acrossX, side);
		if (flank == null) return;

		float v = vAt(flank, share);
		// Along the flank's own horizontal axis, which is the one the strip does not
		// step along: a strip on the left of the body runs front to back, and the
		// texture of the left flank runs front to back with it.
		Corner a = corner(flank, acrossX, x0, z0, y, v);
		Corner b = corner(flank, acrossX, x1, z0, y, v);
		Corner c = corner(flank, acrossX, x1, z1, y, v);
		Corner d = corner(flank, acrossX, x0, z1, y, v);

		Quad quad = new Quad(a, b, c, d);
		// Wound to match the model's own ends rather than to match an assumption
		// about which way round a front face is.
		boolean down = Math.signum(normalY(quad)) == Math.signum(downward(source));
		if (down == facesUp) quad = new Quad(d, c, b, a);
		out.add(quad);
	}

	private static Corner corner(Quad flank, boolean acrossX, float x, float z, float y,
			float v) {
		return new Corner(x, y, z, uAt(flank, !acrossX, acrossX ? z : x), v);
	}

	/** The face on one side of the box, or null where the model has none. */
	private static Quad flankOn(Source source, boolean acrossX, float side) {
		for (Quad face : source.faces()) {
			if (isCap(face, source)) continue;
			boolean all = true;
			for (Corner corner : face.corners()) {
				float from = acrossX
					? corner.x() - source.middleX()
					: corner.z() - source.middleZ();
				if (Math.abs(from) < SNUG || Math.signum(from) != side) {
					all = false;
					break;
				}
			}
			if (all) return face;
		}
		return null;
	}

	/** Where along the flank's texture the join sits. */
	private static float vAt(Quad flank, float share) {
		float low = lowestV(flank);
		return low + (highestV(flank) - low) * share;
	}

	/**
	 * The flank's texture at a point along it, carried on past its own edge.
	 *
	 * A segment wider than the part it came from asks for texture beyond where the
	 * flank ends, and the honest answer is the row carrying on at the rate it was
	 * already going, rather than stopping at the edge and smearing.
	 */
	private static float uAt(Quad flank, boolean alongX, float at) {
		Corner[] corners = flank.corners();
		for (int i = 0; i < corners.length; i++) {
			for (int j = i + 1; j < corners.length; j++) {
				float from = alongX ? corners[i].x() : corners[i].z();
				float to = alongX ? corners[j].x() : corners[j].z();
				if (Math.abs(to - from) > SNUG) {
					return corners[i].u()
						+ (corners[j].u() - corners[i].u()) * (at - from) / (to - from);
				}
			}
		}
		return flank.a().u();
	}

	/** The y of a quad's normal, whichever way round it happens to be wound. */
	private static float normalY(Quad quad) {
		Corner a = quad.a(), b = quad.b(), c = quad.c();
		float ux = b.x() - a.x(), uz = b.z() - a.z();
		float vx = c.x() - a.x(), vz = c.z() - a.z();
		return uz * vx - ux * vz;
	}

	/** What a downward-facing quad looks like in this model, taken from its own end. */
	private static float downward(Source source) {
		for (Quad face : source.faces()) {
			if (!isCap(face, source)) continue;
			if (face.a().y() <= (source.top() + source.bottom()) / 2f) continue;
			return normalY(face);
		}
		return -1;
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
