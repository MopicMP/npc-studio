package com.mopicmp.npcstudio.emote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.emote.EmoteApplier;

/**
 * The two corrections that keep a posed character in one piece.
 *
 * Everything else about applying an emote needs a model and a running game to
 * look at. These two are arithmetic, and they are the arithmetic that decides
 * whether a character bends or comes apart — so they are worth pinning down
 * where a test can reach them.
 */
class EmoteApplierTest {

	@Test
	@DisplayName("a character standing straight needs no correction at all")
	void nothingHappensAtRest() {
		var still = EmoteApplier.waistCorrection(0, 0, 0);
		assertEquals(0f, still.x, 1e-5f);
		assertEquals(0f, still.y, 1e-5f);
		assertEquals(0f, still.z, 1e-5f);

		var unfolded = EmoteApplier.waistAfterFolding(0);
		assertEquals(0f, unfolded.y, 1e-5f);
		assertEquals(0f, unfolded.z, 1e-5f);
	}

	/**
	 * The gap that made a bending character leave its hips behind.
	 *
	 * A torso fold turns the body box about its own middle, so the waist — six
	 * pixels below that middle — swings on a six-pixel lever. The legs are not
	 * part of that box and never moved with it, which is why the fighting pose,
	 * burpees and the delight all looked as though the legs and the body had come
	 * apart.
	 *
	 * The fighting pose folds by 0.63 radians. That is three and a half pixels of
	 * travel on a torso only four pixels deep — not a subtlety.
	 */
	@Test
	@DisplayName("the fighting pose swings the waist three and a half pixels back")
	void aRealFoldMovesTheWaistFar() {
		var moved = EmoteApplier.waistAfterFolding(0.63f);

		assertEquals(3.5f, moved.z, 0.1f, "backwards");
		assertEquals(-1.1f, moved.y, 0.2f, "and a little up, since the fold shortens it");
	}

	@Test
	@DisplayName("folding the other way carries the waist the other way")
	void theCorrectionFollowsTheSign() {
		var forwards = EmoteApplier.waistAfterFolding(0.5f);
		var backwards = EmoteApplier.waistAfterFolding(-0.5f);

		assertEquals(forwards.z, -backwards.z, 1e-5f);
		// The drop is the same either way: a fold shortens the reach whichever way
		// it goes, which is what a cosine does and what a spine does.
		assertEquals(forwards.y, backwards.y, 1e-5f);
		assertTrue(forwards.y < 0, "and it is always a shortening, never a stretch");
	}

	/**
	 * The waist never travels further than the lever it swings on.
	 *
	 * A bound worth having because the correction is added to wherever the emote
	 * already put the legs: if it could run away, a character's legs would leave
	 * the screen rather than merely sit wrongly.
	 */
	@Test
	@DisplayName("no fold can carry the waist further than its own arm")
	void theTravelIsBounded() {
		for (float fold = -3.2f; fold <= 3.2f; fold += 0.1f) {
			var moved = EmoteApplier.waistAfterFolding(fold);
			float travel = (float) Math.hypot(moved.y, moved.z);
			assertTrue(travel <= 12.01f, "travelled " + travel + " at " + fold);
		}
	}
}
