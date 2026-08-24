package com.mopicmp.npcstudio.client.model;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * What the handles look like: a shaft and a head, three of them.
 *
 * <h2>Why a cone and not a rectangle</h2>
 *
 * Because a rectangle does not say which end of itself is the front. An arrow is
 * read at a glance — the point is the direction, the shaft is the axis — and
 * three plain bars sticking out of a box are three bars, not a set of arrows.
 * The first version drew bars, and that is what it looked like.
 *
 * <h2>Why the head changes shape</h2>
 *
 * The same arm both moves the box and, with the resize tool armed, moves one of
 * its faces. One set of arms rather than two, because they are small on screen
 * already and doubling them makes both harder to hit. The price of that is having
 * to say which job is armed, so the head becomes a cube when it is resizing — the
 * shape every editor uses for a scale handle.
 *
 * <h2>Model pixels</h2>
 *
 * Everything here is in the space the model is drawn in, sixteen units to a
 * block. {@link Gizmo} works out the length in that space from how far away the
 * camera is, so the arms keep their size on the display; nothing in this file
 * knows about any of that, which is the point of the split.
 */
public final class Arrows {

	/** A blank white pixel: a solid colour needs no picture. */
	private static final Identifier BLANK = NpcStudio.id("textures/model/blank.png");

	/** Full brightness. Handles are not lit by the world; they are drawn on top of it. */
	private static final int LIT = 0x00F000F0;

	/** How round the cone is. Eight sides reads as round and costs eight quads. */
	private static final int SIDES = 8;

	// Everything below is a share of the arm's length, so the whole arrow keeps its
	// proportions at any distance. Slimmer than they first were: a head wide enough
	// to notice is a head that covers the corner of the box it is pointing at, and
	// the corner is the thing being aimed at. How easily an arm is caught is set
	// separately, in Gizmo, and did not change with this.
	private static final float HEAD = 0.22f;
	private static final float RADIUS = 0.065f;
	private static final float SHAFT = 0.018f;
	private static final float BLOCK = 0.055f;

	/** How thick a ring is, as a share of its radius. */
	private static final float BAND = 0.018f;

	/** How many straight pieces a ring is drawn as. */
	private static final int ROUND = 32;

	private static final int RED = 0xFFFF4A4A;
	private static final int GREEN = 0xFF44DD55;
	private static final int BLUE = 0xFF4A8CFF;

	/** What the arm under the mouse turns, which is what every editor turns it. */
	private static final int LIT_UP = 0xFFFFD54A;

	private Arrows() { }

	/**
	 * The collision box, drawn as twelve edges.
	 *
	 * <h2>Why this is drawn at all</h2>
	 *
	 * Because "the collision is somewhere, but not on the cube" is not a report
	 * anybody should have to make by walking into empty air. A box that decides
	 * where a person may stand and cannot be seen is a box that can only be argued
	 * about; drawn, it is either on the model or it is not, and whoever is looking
	 * can say which in one glance.
	 *
	 * Edges rather than faces, because a filled box hides the thing it is supposed
	 * to be agreeing with.
	 *
	 * The numbers are in blocks and in the object's own upright frame — the same
	 * frame the game holds the box in — so this deliberately does <em>not</em> turn
	 * with the object. That is the honest picture: the box does not turn either.
	 */
	public static void outline(PoseStack pose, SubmitNodeCollector collector,
			List<com.mopicmp.npcstudio.model.ModelPose.Solid> solids, float scale, int colour) {
		if (solids.isEmpty()) return;
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(BLANK), (at, buffer) -> {
			for (var solid : solids) box(buffer, at, solid, scale, colour);
		});
	}

	/**
	 * The twelve edges of one box, in the box's own directions.
	 *
	 * The box as it really is, not the upright one the collision approximates it
	 * with. Drawing the approximation was honest and unreadable: a turned box is
	 * tens of small upright ones, and tens of them each with twelve edges is a green
	 * fog that has to be capped, and a capped drawing of the truth is a drawing that
	 * stops halfway with no way of telling. The shape is the thing being checked
	 * against the model; the staircase is how it is enforced.
	 */
	private static void box(VertexConsumer buffer, PoseStack.Pose at,
			com.mopicmp.npcstudio.model.ModelPose.Solid solid, float scale, int colour) {
		float thick = 0.02f / Math.max(1e-4f, scale);

		for (int axis = 0; axis < 3; axis++) {
			int one = (axis + 1) % 3;
			int two = (axis + 2) % 3;
			for (int corner = 0; corner < 4; corner++) {
				float alongOne = ((corner & 1) == 0 ? -1 : 1) * solid.half()[one];
				float alongTwo = ((corner & 2) == 0 ? -1 : 1) * solid.half()[two];

				float[] middle = new float[3];
				for (int i = 0; i < 3; i++) {
					middle[i] = (solid.middle()[i]
						+ solid.axes()[one][i] * alongOne
						+ solid.axes()[two][i] * alongTwo) * scale;
				}
				bar(buffer, at, colour, middle,
					solid.axes()[axis], solid.half()[axis] * scale,
					solid.axes()[one], solid.axes()[two], thick * scale);
			}
		}
	}

	/** One edge: a thin square bar about a middle, along a direction. */
	private static void bar(VertexConsumer buffer, PoseStack.Pose at, int colour, float[] middle,
			float[] along, float reach, float[] across, float[] up, float thick) {
		float[][] ends = new float[8][3];
		for (int corner = 0; corner < 8; corner++) {
			float t = (corner & 1) == 0 ? -reach - thick : reach + thick;
			float u = (corner & 2) == 0 ? -thick : thick;
			float v = (corner & 4) == 0 ? -thick : thick;
			for (int i = 0; i < 3; i++) {
				ends[corner][i] = middle[i] + along[i] * t + across[i] * u + up[i] * v;
			}
		}

		quad(buffer, at, colour, ends[0], ends[2], ends[3], ends[1]);
		quad(buffer, at, colour, ends[4], ends[5], ends[7], ends[6]);
		quad(buffer, at, colour, ends[0], ends[1], ends[5], ends[4]);
		quad(buffer, at, colour, ends[2], ends[6], ends[7], ends[3]);
		quad(buffer, at, colour, ends[1], ends[3], ends[7], ends[5]);
		quad(buffer, at, colour, ends[0], ends[4], ends[6], ends[2]);
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose at, int colour,
			float[] a, float[] b, float[] c, float[] d) {
		vertex(buffer, at, colour, a, 0, 1);
		vertex(buffer, at, colour, b, 1, 1);
		vertex(buffer, at, colour, c, 1, 0);
		vertex(buffer, at, colour, d, 0, 0);
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose at, int colour, float[] point,
			float u, float v) {
		buffer.addVertex(at, point[0], point[1], point[2])
			.setColor(colour)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LIT)
			// One normal for every face of every arm, pointing up. A handle shaded
			// like a solid object reads as part of the model; a handle in flat colour
			// reads as an instrument, which is what it is.
			.setNormal(at, 0, 1, 0);
	}
}
