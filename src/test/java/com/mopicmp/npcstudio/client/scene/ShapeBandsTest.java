package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Matrix4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catching a folded limb where it actually is.
 *
 * <h2>What was reported</h2>
 *
 * "The selection does not take the fold into account." Which it did not: a limb
 * was one box whatever it was doing, so a folded arm had its outline drawn
 * straight through where the forearm no longer was, and a click on the hand
 * landed on nothing.
 *
 * <h2>What is pinned</h2>
 *
 * That the bands follow the fold rather than merely being more boxes, and — the
 * one that matters more — that a limb nobody folded is still exactly the single
 * box it always was. A change that improves the folded case and quietly moves the
 * straight one would be worse than the fault it fixed, because every limb in
 * every scene is straight most of the time.
 */
class ShapeBandsTest {

	/** An arm: four pixels across, twelve down, four through, in blocks. */
	private static final float[] ARM = {
		-2 / 16f, 0, -2 / 16f, 2 / 16f, 12 / 16f, 2 / 16f };

	private static Posing.Shape armFolded(float radians) {
		return new Posing.Shape(new Matrix4f(), ARM.clone(), new float[9], radians);
	}

	@Test
	@DisplayName("a limb nobody folded is the one box it always was")
	void aStraightLimbIsUnchanged() {
		List<float[]> bands = armFolded(0).bands(6);

		assertEquals(1, bands.size(), "a straight limb needs no cutting up");
		for (int i = 0; i < ARM.length; i++) {
			assertEquals(ARM[i], bands.get(0)[i], 1e-6, "the box moved at " + i);
		}
	}

	@Test
	@DisplayName("a folded limb is cut up, and the pieces stay inside its own width")
	void aFoldedLimbIsBands() {
		List<float[]> bands = armFolded(1.2f).bands(6);

		assertEquals(6, bands.size());
		for (float[] band : bands) {
			// The fold moves a point along the limb and through it, never across it,
			// which is what lets a band stay a box at all.
			assertEquals(ARM[0], band[0], 1e-6, "a band widened sideways");
			assertEquals(ARM[3], band[3], 1e-6, "a band widened sideways");
			assertTrue(band[4] >= band[1], "a band came out inside out");
			assertTrue(band[5] >= band[2], "a band came out inside out");
		}
	}

	@Test
	@DisplayName("the fold takes the far end of the limb somewhere the straight box is not")
	void theBandsGoWhereTheLimbWent() {
		// The whole point. If every band still sits inside the box round a straight
		// arm, nothing has been gained and the outline is the old one with more
		// lines in it.
		List<float[]> bands = armFolded(1.6f).bands(6);

		float furthest = 0;
		for (float[] band : bands) {
			furthest = Math.max(furthest, Math.max(Math.abs(band[2]), Math.abs(band[5])));
		}
		assertTrue(furthest > Math.abs(ARM[2]) * 1.5f,
			"the folded limb reached only " + furthest + " through, so it never bent");
	}

	@Test
	@DisplayName("the bands between them still cover the whole length of the limb")
	void nothingIsLeftOut() {
		// A gap between two bands is a strip of arm no click can land on, and it
		// would show up as a limb with a dead line across it.
		List<float[]> bands = armFolded(0.8f).bands(6);

		for (int i = 1; i < bands.size(); i++) {
			float[] above = bands.get(i - 1);
			float[] below = bands.get(i);
			assertTrue(below[1] <= above[4] + 1e-4,
				"a gap opened between band " + (i - 1) + " and " + i);
		}
	}

	@Test
	@DisplayName("more bands follow the curve more closely, and never less closely")
	void moreBandsAreNeverWorse() {
		// The number is a trade: six ray tests a click against how well the outline
		// hugs the arm. Whatever it is set to, asking for more must not make the
		// answer coarser.
		float coarse = through(armFolded(1.4f).bands(2));
		float fine = through(armFolded(1.4f).bands(12));

		assertTrue(fine <= coarse + 1e-4,
			"twelve bands enclosed " + fine + " where two enclosed " + coarse);
	}

	/** How much depth the bands take up between them: less is a closer fit. */
	private static float through(List<float[]> bands) {
		float total = 0;
		for (float[] band : bands) total += band[5] - band[2];
		return total / bands.size();
	}
}
