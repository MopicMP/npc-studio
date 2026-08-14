package com.mopicmp.npcstudio.emote;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.emote.Slicing;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;

/**
 * That cutting a face into bands still leaves the right skin on each band.
 *
 * Written because a bent limb came out painted in flat colours, and a flat
 * colour is what a face looks like when its corners share one texture
 * coordinate. That is a fact about arithmetic, and it had never been asked,
 * because the arithmetic lived inside a mixin where nothing could call it.
 *
 * <h2>Built from real cubes, and the first version of this was not</h2>
 *
 * The faces here come from {@link ModelPart.Cube} — the same constructor the
 * player model uses — rather than from coordinates written out by hand. The
 * first version of this test did write them out by hand, and so checked a guess
 * about where a sleeve's skin lives against that same guess. It passed, and the
 * bug it was written to find was still there.
 *
 * The properties: the bands cover exactly the stripe of skin the face started
 * with, none of it twice, none collapsed to a point, and none reaching outside
 * the cube's own patch of skin. That last is the whole question for an outer
 * layer — a sleeve whose skin is entirely blank must stay invisible, and the only
 * way it can stop being invisible is by reading somebody else's texels.
 */
class SlicingTest {

	private static final float SKIN = 64f;
	private static final int BANDS = 16;

	/** The player's right arm, exactly as the vanilla model builds it. */
	private static ModelPart.Cube arm() {
		return new ModelPart.Cube(40, 16, -3, -2, -2, 4, 12, 4, 0, 0, 0,
			false, SKIN, SKIN, EnumSet.allOf(Direction.class));
	}

	/** And its sleeve, which is the same box grown a quarter of a pixel. */
	private static ModelPart.Cube sleeve() {
		return new ModelPart.Cube(40, 32, -3, -2, -2, 4, 12, 4, 0.25f, 0.25f, 0.25f,
			false, SKIN, SKIN, EnumSet.allOf(Direction.class));
	}

	/** The extent of what is actually drawn, which is the corners, not the box. */
	private static float[] drawnExtent(ModelPart.Cube cube) {
		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex corner : polygon.vertices()) {
				low = Math.min(low, corner.worldY());
				high = Math.max(high, corner.worldY());
			}
		}
		return new float[] { low, high };
	}

	/**
	 * A cube's stated bounds are not the cube it draws.
	 *
	 * {@code minY} and {@code maxY} are the box that was asked for; the corners are
	 * that box grown by its inflation. For a limb the two agree, for every outer
	 * layer they do not — and measuring the wrong one is how a sleeve came to be
	 * cut to the length of the arm inside it.
	 */
	@Test
	@DisplayName("a layer's stated bounds are smaller than what it draws")
	void theStatedBoundsAreNotTheDrawnOnes() {
		ModelPart.Cube arm = arm();
		float[] armDrawn = drawnExtent(arm);
		assertTrue(Math.abs(armDrawn[0] - arm.minY / 16f) < 1e-6f
			&& Math.abs(armDrawn[1] - arm.maxY / 16f) < 1e-6f,
			"a limb with no inflation should draw exactly its own box");

		ModelPart.Cube sleeve = sleeve();
		float[] drawn = drawnExtent(sleeve);
		float grown = 0.25f / 16f;
		assertTrue(Math.abs(drawn[0] - (sleeve.minY / 16f - grown)) < 1e-6f
			&& Math.abs(drawn[1] - (sleeve.maxY / 16f + grown)) < 1e-6f,
			String.format("a sleeve draws %.5f..%.5f but states %.5f..%.5f",
				drawn[0], drawn[1], sleeve.minY / 16f, sleeve.maxY / 16f));
	}

	/**
	 * Every band of every face keeps its own stripe of skin, and they add up.
	 *
	 * Run over the limb and its layer, and over every face of each — the ends of a
	 * box face along the limb and are not cut across it, so they are left out. A
	 * band with no height in the texture is a band painted in one colour, however
	 * tall it is on the model.
	 */
	@Test
	@DisplayName("every band of every face keeps its own stripe of skin")
	void theBandsPartitionTheSkin() {
		for (ModelPart.Cube cube : new ModelPart.Cube[] { arm(), sleeve() }) {
			float[] extent = drawnExtent(cube);

			for (ModelPart.Polygon polygon : cube.polygons) {
				if (Math.abs(polygon.normal().y()) > 0.5f) continue;
				float whole = spanV(polygon.vertices());
				float covered = 0;

				for (int band = 0; band < BANDS; band++) {
					ModelPart.Vertex[] piece = Slicing.piece(polygon.vertices(),
						Slicing.AXIS_Y, band, BANDS, extent[0], extent[1]);
					assertTrue(piece.length >= 3, "a band came out with nothing in it");

					float span = spanV(piece);
					assertTrue(span > 1e-6f,
						"a band has no height in the texture, so it is one flat colour");
					covered += span;
				}

				assertTrue(Math.abs(covered - whole) < 1e-5f, String.format(
					"the bands cover %.5f of skin where the face had %.5f", covered, whole));
			}
		}
	}

	/**
	 * And no band ever reaches outside its own cube's patch of skin.
	 *
	 * The property that decides whether a blank outer layer stays blank. Every
	 * coordinate a sleeve emits has to land inside the rectangle the sleeve was
	 * given; one that lands outside is reading somebody else's skin, and an arm's
	 * skin is not blank.
	 */
	@Test
	@DisplayName("and never reaches outside its own patch of skin")
	void noBandReadsSomebodyElsesSkin() {
		for (ModelPart.Cube cube : new ModelPart.Cube[] { arm(), sleeve() }) {
			float[] extent = drawnExtent(cube);

			for (ModelPart.Polygon polygon : cube.polygons) {
				float[] patch = bounds(polygon.vertices());

				for (int band = 0; band < BANDS; band++) {
					for (ModelPart.Vertex corner : Slicing.piece(polygon.vertices(),
							Slicing.AXIS_Y, band, BANDS, extent[0], extent[1])) {
						assertTrue(corner.u() >= patch[0] - 1e-6f && corner.u() <= patch[1] + 1e-6f
							&& corner.v() >= patch[2] - 1e-6f && corner.v() <= patch[3] + 1e-6f,
							String.format(
								"a band read (%.5f, %.5f), outside its own %.5f..%.5f by %.5f..%.5f",
								corner.u(), corner.v(), patch[0], patch[1], patch[2], patch[3]));
					}
				}
			}
		}
	}

	private static float spanV(ModelPart.Vertex[] piece) {
		float[] at = bounds(piece);
		return at[3] - at[2];
	}

	/** Least and most u, then least and most v. */
	private static float[] bounds(ModelPart.Vertex[] piece) {
		float uLow = Float.MAX_VALUE;
		float uHigh = -Float.MAX_VALUE;
		float vLow = Float.MAX_VALUE;
		float vHigh = -Float.MAX_VALUE;
		for (ModelPart.Vertex corner : piece) {
			uLow = Math.min(uLow, corner.u());
			uHigh = Math.max(uHigh, corner.u());
			vLow = Math.min(vLow, corner.v());
			vHigh = Math.max(vHigh, corner.v());
		}
		return new float[] { uLow, uHigh, vLow, vHigh };
	}
}
