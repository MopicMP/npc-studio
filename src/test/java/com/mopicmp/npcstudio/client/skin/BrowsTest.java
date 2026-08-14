package com.mopicmp.npcstudio.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a brow lifts, tilts, and never leaves the face.
 *
 * The arithmetic is four lines long, which is exactly why it is worth testing:
 * every mistake available in it is a sign, and a sign error here draws a
 * plausible face that means the opposite of what was asked for. Nothing would
 * look broken and everything would be wrong.
 */
class BrowsTest {

	/** A raise lifts both ends of both brows by the same amount. */
	@Test
	@DisplayName("a raise lifts the whole brow, level")
	void aRaiseLiftsBothEnds() {
		Brows up = new Brows(1f, 1f, 0f);
		for (boolean right : new boolean[] { true, false }) {
			assertEquals(Brows.TRAVEL, up.inner(right), 1e-5, "the inner end goes up");
			assertEquals(Brows.TRAVEL, up.outer(right), 1e-5, "and the outer with it");
		}

		Brows down = new Brows(-1f, -1f, 0f);
		assertTrue(down.inner(true) < 0f && down.outer(true) < 0f, "and a scowl goes down");
	}

	/**
	 * A slant pivots rather than lifting.
	 *
	 * The property that makes the two numbers independent: an author can ask for a
	 * shape without also asking for a height. If a slant lifted, every worried face
	 * would also be a slightly surprised one.
	 */
	@Test
	@DisplayName("a slant tilts the brow without moving it up or down")
	void aSlantPivots() {
		Brows worried = new Brows(0f, 0f, 1f);
		for (boolean right : new boolean[] { true, false }) {
			assertTrue(worried.inner(right) > 0.1f,
				"the inner end goes up: " + worried.inner(right));
			assertTrue(worried.outer(right) < -0.1f,
				"and the outer end down: " + worried.outer(right));
			assertEquals(0f, worried.inner(right) + worried.outer(right), 1e-5,
				"by the same amount, so the brow is where it was drawn");
		}

		Brows knotted = new Brows(0f, 0f, -1f);
		assertTrue(knotted.inner(true) < 0f, "a frown pulls the inner ends down instead");
	}

	/**
	 * A brow never travels more than a pixel, however the two are added up.
	 *
	 * They share a budget and neither knows about the other, so a full raise with a
	 * full slant asks for a pixel and a half. A brow that far from the face is not a
	 * raised brow, it is a mark on somebody's forehead.
	 */
	@Test
	@DisplayName("no combination puts a brow more than a pixel from where it was drawn")
	void aBrowStaysOnTheFace() {
		for (int raise = -10; raise <= 10; raise++) {
			for (int slant = -10; slant <= 10; slant++) {
				Brows brows = new Brows(raise / 10f, raise / 10f, slant / 10f);
				for (boolean right : new boolean[] { true, false }) {
					assertTrue(Math.abs(brows.inner(right)) <= Brows.TRAVEL + 1e-5,
						"inner end at " + brows.inner(right));
					assertTrue(Math.abs(brows.outer(right)) <= Brows.TRAVEL + 1e-5,
						"outer end at " + brows.outer(right));
				}
			}
		}
	}

	/**
	 * One brow can move while the other does not.
	 *
	 * The raised eyebrow is the most recognisable thing a face this size can do, and
	 * it exists only because the two sides are carried apart. A shared raise with a
	 * flag for "one of them" would have had to decide which one here, where the
	 * character's own side is not known.
	 */
	@Test
	@DisplayName("a raised eyebrow is one brow and not the other")
	void oneBrowCanActAlone() {
		Brows doubt = new Brows(0.6f, 0f, 0f);
		assertTrue(doubt.moves(true), "the raised one moves");
		assertTrue(!doubt.moves(false), "and the other stays where it was drawn");
		assertTrue(doubt.any(), "which is still something to draw");
	}

	/** Brows nobody has asked anything of are not drawn at all. */
	@Test
	@DisplayName("brows at rest are left alone")
	void restingBrowsAreNotDrawn() {
		assertTrue(!Brows.NONE.any(), "an untouched face costs no quads");
		assertTrue(!new Brows(0.01f, -0.01f, 0.01f).any(),
			"and neither does a movement too small to see");
		assertTrue(new Brows(0.2f, 0.2f, 0f).any(), "while a real one does");
	}

	/**
	 * A face with no expression on it is the face the artist drew.
	 *
	 * Worth stating separately from the above: this is the promise that the mod adds
	 * nothing to a skin nobody has asked it to change.
	 */
	@Test
	@DisplayName("nothing asked for means nothing moved")
	void nothingAskedForMovesNothing() {
		for (boolean right : new boolean[] { true, false }) {
			assertEquals(0f, Brows.NONE.inner(right), 1e-5);
			assertEquals(0f, Brows.NONE.outer(right), 1e-5);
		}
	}
}
