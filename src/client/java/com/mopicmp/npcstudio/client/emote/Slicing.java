package com.mopicmp.npcstudio.client.emote;

import java.util.Arrays;

import net.minecraft.client.model.geom.ModelPart;

/**
 * Cutting a model's face into pieces, with nothing else attached to it.
 *
 * Pulled out of the renderer for the same reason {@link Folding} was: it could
 * not be asked anything. A face comes out of here as several faces, and whether
 * those still carry the right piece of skin is a question with an exact answer —
 * but only if something can call it without a running game.
 *
 * The invariant is not "the pieces look right". It is that the pieces are a
 * <b>partition</b>: together they cover exactly the face they came from, no part
 * of it twice, and each piece's corner of skin is the corner of skin that was at
 * that spot. A piece whose four corners share one texture coordinate is not a
 * small piece, it is a piece painted in a single flat colour, and that is a
 * failure this can catch and a screenshot cannot describe.
 *
 * Positions are in the model's own pixels, which is what {@code ModelPart.Vertex}
 * stores; the heights cut at are in blocks, which is what {@code worldY} returns.
 * The two are not mixed: heights decide <em>where</em> to cut, and the cut point
 * itself is found by interpolating along the edge, which is unitless.
 */
public final class Slicing {

	public static final int AXIS_X = 0;
	public static final int AXIS_Y = 1;
	public static final int AXIS_Z = 2;

	private Slicing() { }

	/** Where a corner sits along one axis, in blocks. */
	public static float along(ModelPart.Vertex corner, int axis) {
		return switch (axis) {
			case AXIS_X -> corner.worldX();
			case AXIS_Z -> corner.worldZ();
			default -> corner.worldY();
		};
	}

	/**
	 * One of {@code count} equal slabs of a face, cut across the given axis.
	 *
	 * @param low  where the part begins along that axis, in blocks
	 * @param high where it ends
	 */
	public static ModelPart.Vertex[] piece(ModelPart.Vertex[] corners, int axis,
			int index, int count, float low, float high) {
		if (count <= 1) return corners;
		float step = (high - low) / count;
		if (step <= 0) return corners;

		return clip(
			clip(corners, low + step * index, axis, true),
			low + step * (index + 1), axis, false);
	}

	/**
	 * Keeps the part of a face on one side of a height.
	 *
	 * Walks the corners in order and, where an edge crosses, makes a new corner on
	 * it. Position and texture are interpolated by the same fraction, so the skin
	 * runs continuously across every boundary.
	 */
	public static ModelPart.Vertex[] clip(ModelPart.Vertex[] corners, float height,
			int axis, boolean below) {
		if (corners.length == 0) return corners;
		ModelPart.Vertex[] kept = new ModelPart.Vertex[corners.length * 2];
		int size = 0;

		for (int i = 0; i < corners.length; i++) {
			ModelPart.Vertex from = corners[i];
			ModelPart.Vertex to = corners[(i + 1) % corners.length];
			float fromAt = along(from, axis);
			float toAt = along(to, axis);
			boolean fromIn = below ? fromAt >= height : fromAt <= height;
			boolean toIn = below ? toAt >= height : toAt <= height;

			if (fromIn) kept[size++] = from;
			if (fromIn != toIn) {
				float span = toAt - fromAt;
				float t = span == 0 ? 0 : (height - fromAt) / span;
				kept[size++] = new ModelPart.Vertex(
					lerp(from.x(), to.x(), t),
					lerp(from.y(), to.y(), t),
					lerp(from.z(), to.z(), t),
					lerp(from.u(), to.u(), t),
					lerp(from.v(), to.v(), t));
			}
		}
		return Arrays.copyOf(kept, size);
	}

	public static float lerp(float from, float to, float t) {
		return from + (to - from) * t;
	}
}
