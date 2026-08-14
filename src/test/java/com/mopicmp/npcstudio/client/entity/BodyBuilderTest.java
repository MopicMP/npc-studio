package com.mopicmp.npcstudio.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape of a body down its own length.
 *
 * The rest of this feature needs a running game to look at, but the profile is
 * arithmetic, and it is the arithmetic that decides whether a belly reads as a
 * stomach or as a shelf somebody could put a drink on.
 */
class BodyBuilderTest {

	// ------------------------------------------------------- square to round

	/**
	 * A box left alone is the box Minecraft built, to the vertex.
	 *
	 * The property the whole change rests on. Rounding was added underneath
	 * bending and thickening, which already worked; if nought means "unchanged"
	 * then it cannot have broken either of them.
	 */
	@Test
	@DisplayName("no roundness moves nothing at all")
	void nothingHappensAtNought() {
		for (float u = -1; u <= 1f; u += 0.25f) {
			for (float w = -1; w <= 1f; w += 0.25f) {
				assertEquals(1f, BodyBuilder.round(u, w, 0), 0f);
			}
		}
	}

	@Test
	@DisplayName("the middle of a face stays where it is, however round")
	void faceMiddlesDoNotMove() {
		assertEquals(1f, BodyBuilder.round(0, -1, 1f), 1e-5f, "the front");
		assertEquals(1f, BodyBuilder.round(1, 0, 1f), 1e-5f, "the side");
		assertEquals(1f, BodyBuilder.round(0, 1, 1f), 1e-5f, "the back");
	}

	/**
	 * Fully round means exactly the ellipse that fits inside the box.
	 *
	 * Checked all the way round the outline rather than at the corners, because
	 * "the corners came in" is also true of a chamfer, an octagon, and several
	 * things that are not round at all.
	 */
	@Test
	@DisplayName("every point of a fully rounded limb lands on its ellipse")
	void fullyRoundIsAnEllipse() {
		float across = 2f;
		float through = 1f;

		for (int step = 0; step < 64; step++) {
			// Walk the square's own boundary rather than sampling angles: it is the
			// vertices of the box that get moved, and they sit on the boundary.
			float t = step / 16f % 4f;
			float u = t < 1 ? -1 + 2 * t : t < 2 ? 1 : t < 3 ? 1 - 2 * (t - 2) : -1;
			float w = t < 1 ? -1 : t < 2 ? -1 + 2 * (t - 1) : t < 3 ? 1 : 1 - 2 * (t - 3);

			float factor = BodyBuilder.round(u, w, 1f);
			float x = u * factor * across;
			float z = w * factor * through;

			assertEquals(1f, x * x / (across * across) + z * z / (through * through), 1e-4f,
				"off the ellipse at u=" + u + " w=" + w);
		}
	}

	@Test
	@DisplayName("a corner is what moves furthest")
	void cornersComeIn() {
		float corner = BodyBuilder.round(1, 1, 1f);
		float middle = BodyBuilder.round(1, 0, 1f);

		assertTrue(corner < middle, "a corner draws in and a face middle does not");
		assertEquals(1f / (float) Math.sqrt(2), corner, 1e-5f);
	}

	/**
	 * The outer skin layer never crosses the body underneath it.
	 *
	 * This is the shimmer bug written as a test. A jacket is an inflated copy of
	 * the torso, and when the two surfaces cross, the overlap flickers — badly on
	 * a character scaled up. The reason it cannot happen here is that both are cut
	 * at the same fractions of their own width, so they arrive at this arithmetic
	 * with identical coordinates and leave it multiplied by their own, larger,
	 * half-extents.
	 */
	@Test
	@DisplayName("the jacket stays outside the body at every roundness")
	void theLayerNeverCrosses() {
		float bodyAcross = 4f;
		float bodyThrough = 2f;
		float coatAcross = 4.25f;
		float coatThrough = 2.25f;

		for (float roundness = 0; roundness <= 1f; roundness += 0.1f) {
			for (float u = -1; u <= 1f; u += 0.2f) {
				for (float w : new float[] { -1, 1 }) {
					float factor = BodyBuilder.round(u, w, roundness);
					assertTrue(Math.abs(u * factor * coatAcross) >= Math.abs(u * factor * bodyAcross),
						"the coat came inside the body across");
					assertTrue(Math.abs(w * factor * coatThrough) >= Math.abs(w * factor * bodyThrough),
						"and through");
				}
			}
		}
	}

	// ----------------------------------------------------- chest and stomach

	/** A build nobody asked for pushes nothing anywhere. */
	@Test
	@DisplayName("no chest and no stomach move nothing")
	void plainPushesNothing() {
		for (float t = 0; t <= 1f; t += 0.1f) {
			for (float side = -1; side <= 1f; side += 0.25f) {
				assertEquals(0f, BodyBuilder.forward(t, side, 0, 0), 0f);
			}
		}
	}

	/**
	 * A chest is two lobes, not one bulge across the breastbone.
	 *
	 * The correction that started all of this. One bulge spread over the whole
	 * front is a barrel, and no amount of tuning its size makes it read as a
	 * chest — which is exactly why the mod this grew out of draws two boxes.
	 */
	@Test
	@DisplayName("a chest is fullest on either side of the middle, not in it")
	void aChestHasTwoLobes() {
		float middle = BodyBuilder.forward(0.35f, 0f, 1f, 0);
		float lobe = BodyBuilder.forward(0.35f, 0.45f, 1f, 0);

		assertTrue(lobe > middle + 0.1f,
			"middle " + middle + " should be well under the lobe " + lobe);
		assertTrue(middle > 0, "and the two still join, rather than leaving a gully");
	}

	/** A stomach is one, centred, and broader across than a chest. */
	@Test
	@DisplayName("a stomach is one bulge in the middle")
	void aStomachIsOne() {
		float middle = BodyBuilder.forward(0.85f, 0f, 0, 1f);
		float side = BodyBuilder.forward(0.85f, 0.45f, 0, 1f);

		assertTrue(middle > side, "fullest in the middle");
		assertTrue(side > 0, "and still there at the sides");
	}

	/** Each sits where its name says. */
	@Test
	@DisplayName("the chest is high and the stomach is low")
	void eachSitsWhereItBelongs() {
		assertTrue(BodyBuilder.forward(0.35f, 0.45f, 1f, 0)
			> BodyBuilder.forward(0.9f, 0.45f, 1f, 0), "chest");
		assertTrue(BodyBuilder.forward(0.85f, 0f, 0, 1f)
			> BodyBuilder.forward(0.1f, 0f, 0, 1f), "stomach");
	}

	/**
	 * The shoulder line is never pushed forward.
	 *
	 * The arms are moved outwards by the shoulder setting and by nothing else, so
	 * a chest that reached the top of the torso would push the body out from under
	 * arms that had not been told to move. Held over from the old profile, where
	 * this caught a real mistake.
	 */
	@Test
	@DisplayName("nothing pushes the shoulder line the arms hang from")
	void theShoulderLineIsLeftAlone() {
		for (float side = -1; side <= 1f; side += 0.1f) {
			assertEquals(0f, BodyBuilder.forward(0, side, 2f, 2f), 1e-5f, "at " + side);
		}
	}

	/** A stomach does not stop politely above the belt. */
	@Test
	@DisplayName("a stomach is still most of itself at the waistline")
	void aStomachReachesTheBottom() {
		assertTrue(BodyBuilder.forward(1f, 0f, 0, 1f) > 0.6f,
			"the waistline came out at " + BodyBuilder.forward(1f, 0f, 0, 1f));
	}

	/** The largest jump between neighbouring samples on a grid this fine. */
	private static float roughest(int bands, int columns) {
		float worst = 0;
		for (int column = 0; column <= columns; column++) {
			float side = -1f + 2f * column / columns;
			for (int band = 1; band <= bands; band++) {
				worst = Math.max(worst, Math.abs(
					BodyBuilder.forward(band / (float) bands, side, 1f, 1f)
						- BodyBuilder.forward((band - 1) / (float) bands, side, 1f, 1f)));
			}
		}
		for (int band = 0; band <= bands; band++) {
			float t = band / (float) bands;
			for (int column = 1; column <= columns; column++) {
				worst = Math.max(worst, Math.abs(
					BodyBuilder.forward(t, -1f + 2f * column / columns, 1f, 1f)
						- BodyBuilder.forward(t, -1f + 2f * (column - 1) / columns, 1f, 1f)));
			}
		}
		return worst;
	}

	/**
	 * The surface is continuous, and the test cannot be fooled by tuning it.
	 *
	 * A threshold picked to sit just above whatever the numbers happen to be today
	 * proves nothing — it passes for a steep slope and for a cliff alike. What
	 * tells them apart is what happens when the grid is refined: on a continuous
	 * surface the jumps shrink with it, and on one with a ledge in it the ledge
	 * stays exactly as tall however finely it is sampled.
	 *
	 * So this halves the spacing and insists the roughness halves with it. A steep
	 * chest is allowed to be steep; a discontinuity is caught however small it is.
	 */
	@Test
	@DisplayName("refining the grid smooths the surface, which a ledge would not do")
	void theSurfaceIsContinuous() {
		float coarse = roughest(16, BodyBuilder.LOBE_COLUMNS);
		float fine = roughest(64, BodyBuilder.LOBE_COLUMNS * 4);

		assertTrue(fine < coarse * 0.4f,
			"four times the samples left the roughness at " + fine + " against " + coarse);
	}

	/** And it is not a cliff even at the resolution it is drawn at. */
	@Test
	@DisplayName("no single step is anywhere near the whole push")
	void noStepIsACliff() {
		assertTrue(roughest(16, BodyBuilder.LOBE_COLUMNS) < 0.5f,
			"a step of " + roughest(16, BodyBuilder.LOBE_COLUMNS) + " of a full push");
	}

	/**
	 * A chest drawn on the real grid still reads as two lobes with a cleft.
	 *
	 * The test that caught the worst of it. On four columns the samples landed on
	 * the two peaks and the trough between them and nowhere else, so what would
	 * have been drawn was two pyramids. This asserts both halves of the fix: that
	 * the cleft is a cleft rather than a canyon, and that there are enough samples
	 * on the way up each lobe for it to curve.
	 */
	@Test
	@DisplayName("a chest sampled at the drawing grid is two lobes, not two pyramids")
	void theChestSurvivesItsGrid() {
		int columns = BodyBuilder.LOBE_COLUMNS;
		float[] across = new float[columns + 1];
		float peak = 0;
		for (int i = 0; i <= columns; i++) {
			across[i] = BodyBuilder.forward(0.35f, -1f + 2f * i / columns, 1f, 0);
			peak = Math.max(peak, across[i]);
		}

		float cleft = across[columns / 2];
		assertTrue(cleft > peak * 0.3f,
			"the middle came out at " + cleft + " against a peak of " + peak);
		assertTrue(cleft < peak * 0.9f, "and it is still a cleft rather than a barrel");

		// Enough samples strictly between nothing and the peak that each lobe has a
		// slope rather than a single jump to the top.
		int onTheWayUp = 0;
		for (float value : across) {
			if (value > peak * 0.15f && value < peak * 0.85f) onTheWayUp++;
		}
		assertTrue(onTheWayUp >= 4, "only " + onTheWayUp + " samples on the slopes");
	}

	/** A flat chest is allowed, and pulls in rather than out. */
	@Test
	@DisplayName("a setting below one pushes inwards")
	void belowOneGoesIn() {
		assertTrue(BodyBuilder.forward(0.35f, 0.45f, -0.5f, 0) < 0,
			"a slider under one is a flatter character, not an error");
	}
}
