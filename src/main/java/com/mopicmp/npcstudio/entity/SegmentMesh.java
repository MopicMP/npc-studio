package com.mopicmp.npcstudio.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a limb by extruding a ring down its length.
 *
 * <h2>Why extruding and not stacking</h2>
 *
 * This stacked boxes first, and that was wrong at the root. Two closed boxes end to
 * end are two separate solids, so where they differ in width there is a hole, and
 * the hole has to be patched with a strip that borrows a row of texels from its
 * neighbour. That patch was built, tested and written into the plan as the one
 * place the word "perfect" had to be weakened.
 *
 * Nothing needed weakening. Extruding a ring — copying it, moving it down, scaling
 * it, and bridging the two with a band of quads — makes that band <b>by
 * construction</b>. There is no hole, so there is nothing to patch, and the band
 * carries the texels lying between the two rows rather than borrowing one.
 *
 * <h2>And it is what the shape wanted anyway</h2>
 *
 * A band between a wide ring and a narrow one goes down <em>and</em> inward at
 * once: one slope, rather than a vertical wall, a step and another vertical wall.
 * A leg that narrows toward the knee and swells again at the calf is a sequence of
 * such slopes, and a stack of boxes cannot make one at all.
 *
 * <h2>Slanted is not un-cubic</h2>
 *
 * Worth saying, because getting it wrong is what caused the stacking. This art is
 * flat polygons on a grid of texels; a slanted quad is exactly as cubic as an
 * axis-aligned one. The style is the grid, not the right angles.
 *
 * <h2>Why it is given the vanilla faces rather than the skin layout</h2>
 *
 * Because the layout is exactly the sort of thing that gets remembered wrong. A box
 * of width w and depth d puts its front face at one offset and its flank at
 * another, and every one of those offsets is a chance to be a pixel out on somebody
 * else's skin.
 *
 * The model already knows. Minecraft bakes texture coordinates into the corners of
 * every face it builds, so this is handed those faces and stretches them between
 * rings. Nothing here knows what a skin looks like, which is why it cannot be wrong
 * about one.
 *
 * <h2>What must stay true</h2>
 *
 * A loft of two rings with the source's own numbers has to give the source back,
 * corner for corner and texel for texel — which is neat rather than a special case,
 * because a plain box <em>is</em> two rings. That is not a nicety: it is what lets
 * an ordinary character keep being drawn by Minecraft, and it is the test that says
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
	 * One cross-section of a limb: where it is, how big it is, and what it reads.
	 *
	 * <h2>The share is told, not measured</h2>
	 *
	 * Where a ring sits along the limb and where it reads from on the skin are two
	 * different questions, and tying them together gets one of them wrong. Lengthen a
	 * thigh and the knee line on the skin has to stay on the knee: the ring moved
	 * down, but it still reads the same row.
	 *
	 * @param y      where it sits along the limb, in the part's own space
	 * @param share  where it reads, as a fraction of the source's rows
	 * @param halfX  half its width, so the two sides are symmetric about the middle
	 * @param halfZ  and half its depth
	 */
	public record Ring(float y, float share,
			float middleX, float halfX, float middleZ, float halfZ) { }

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
	 * Extrudes the source down the loft and gives back the faces to draw.
	 *
	 * A flank becomes one band per pair of rings; an end stays an end, resized to the
	 * ring it belongs to. Nothing else is emitted — in particular there are no faces
	 * inside the limb, because a loft has no inside to close off.
	 */
	public static List<Quad> build(Source source, List<Ring> loft) {
		List<Quad> out = new ArrayList<>();
		if (loft == null || loft.size() < 2) return out;

		for (Quad face : source.faces()) {
			if (isCap(face, source)) {
				boolean atTop = face.a().y() <= (source.top() + source.bottom()) / 2f;
				out.add(end(face, source, atTop ? loft.get(0) : loft.get(loft.size() - 1)));
			} else {
				for (int i = 0; i + 1 < loft.size(); i++) {
					out.add(band(face, source, loft.get(i), loft.get(i + 1)));
				}
			}
		}
		return out;
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
	 * One flank stretched between two rings.
	 *
	 * A corner is not recognised by name or by which face it belongs to. It is asked
	 * two questions — which side of the box it is on, and whether it is at the top of
	 * the face or the bottom — and put at the same side of the ring above or the ring
	 * below. That is why this needs no table of faces: the answer for a flank is the
	 * answer for the front, and the winding comes out of it untouched.
	 *
	 * Where the two rings differ in size the quad comes out slanted, which is the
	 * whole point. Where they differ in <em>both</em> width and depth the four
	 * corners are not quite coplanar and the renderer's two triangles meet along a
	 * shallow crease — the price of changing two dimensions in one step, and the
	 * reason a profile does better to change one at a time.
	 */
	private static Quad band(Quad face, Source source, Ring above, Ring below) {
		Corner[] corners = face.corners();
		Corner[] moved = new Corner[4];
		float middle = (source.top() + source.bottom()) / 2f;
		for (int i = 0; i < 4; i++) {
			Corner corner = corners[i];
			Ring ring = corner.y() <= middle ? above : below;
			moved[i] = at(corner, source, ring, span(face, ring));
		}
		return new Quad(moved[0], moved[1], moved[2], moved[3]);
	}

	/**
	 * An end of the limb, resized to the ring it caps.
	 *
	 * It keeps its own texture. There is one of each, they are the only faces the
	 * skin draws end-on, and a ring's share has nothing to say about them.
	 */
	private static Quad end(Quad face, Source source, Ring ring) {
		Corner[] corners = face.corners();
		Corner[] moved = new Corner[4];
		for (int i = 0; i < 4; i++) {
			moved[i] = at(corners[i], source, ring, corners[i].v());
		}
		return new Quad(moved[0], moved[1], moved[2], moved[3]);
	}

	/** A corner put on a ring, at the same side of it that it was of the box. */
	private static Corner at(Corner corner, Source source, Ring ring, float v) {
		float sideX = Math.signum(corner.x() - source.middleX());
		float sideZ = Math.signum(corner.z() - source.middleZ());
		return new Corner(
			ring.middleX() + sideX * ring.halfX(),
			ring.y(),
			ring.middleZ() + sideZ * ring.halfZ(),
			corner.u(), v);
	}

	/** Where along this face's rows a ring reads. */
	private static float span(Quad face, Ring ring) {
		float low = lowestV(face);
		return low + (highestV(face) - low) * ring.share();
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
