package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walls, and how much of a footstep gets through one.
 *
 * <h2>What has to stay true</h2>
 *
 * Two things, pulling opposite ways, and every number in {@link Muffle} is a
 * compromise between them. One wall must not make somebody inaudible, or hiding
 * is solved by standing behind anything and there is no game in it. Several
 * walls must be silence, or walls mean nothing at all.
 */
class MuffleTest {

	@Test
	@DisplayName("nothing in the way takes nothing out")
	void openAirIsUntouched() {
		assertEquals(1f, Muffle.carried(0), 1e-4);
		assertEquals(1f, Muffle.carried(-1), 1e-4, "and a negative sum is not a megaphone");
	}

	@Test
	@DisplayName("one wall muffles a sprinter without silencing him")
	void oneWallIsTense() {
		// The case the whole feature is for: you are behind a wall, you are running,
		// and it is not safe. It must not be safe.
		float sprinting = Noise.loudness(0.28, false, true, false);
		float through = sprinting * Muffle.carried(Muffle.ORDINARY);
		assertTrue(through > 0.2f && through < 0.45f,
			"a sprinter through one wall came to " + through);
		assertTrue(Noise.heard(4, through) > 0, "and is still audible next door");
	}

	@Test
	@DisplayName("four walls are silence")
	void deepInsideIsSilent() {
		float sprinting = Noise.loudness(0.28, false, true, false);
		float through = sprinting * Muffle.carried(4 * Muffle.ORDINARY);
		assertEquals(0f, Noise.heard(6, through), 1e-4,
			"through four blocks of stone it came to " + through);
	}

	@Test
	@DisplayName("absorption compounds, so the second wall is worth less than the first")
	void itCompounds() {
		// Which is how absorption actually works, and it is why a second layer of
		// stone buys so much less than the first one did.
		float first = 1 - Muffle.carried(1);
		float second = Muffle.carried(1) - Muffle.carried(2);
		assertTrue(first > second, first + " then " + second);
		assertEquals(Muffle.carried(1) * Muffle.carried(1), Muffle.carried(2), 1e-5);
	}

	@Test
	@DisplayName("wool beats stone, and a wall of it is a quiet room")
	void woolIsBetterThanStone() {
		// Above one on purpose. Wool genuinely absorbs more than stone, and a mod
		// where building a padded room does something is a better mod.
		assertTrue(Muffle.Absorbs.PADDING.factor > Muffle.ORDINARY);
		assertTrue(Muffle.carried(Muffle.Absorbs.PADDING.factor)
			< Muffle.carried(Muffle.ORDINARY));
	}

	@Test
	@DisplayName("glass hides you from eyes and not from ears")
	void glassIsAlmostNothing() {
		// The pleasing case, and it falls out of the table rather than being written
		// in: thin things stop nothing acoustically while stopping sight completely.
		assertTrue(Muffle.Absorbs.THIN.factor < 0.2f);
		assertTrue(Muffle.carried(Muffle.Absorbs.THIN.factor) > 0.75f);
	}

	@Test
	@DisplayName("a curtain of vines is not a wall")
	void growingThingsAbsorbNothing() {
		// Otherwise a jungle would be deaf, and hiding would be a matter of standing
		// in a bush.
		assertEquals(0f, Muffle.Absorbs.NOTHING.factor, 1e-4);
		assertEquals(1f, Muffle.carried(Muffle.Absorbs.NOTHING.factor), 1e-4);
	}

	@Test
	@DisplayName("every material is somewhere sensible against an ordinary wall")
	void theWholeTableIsOrdered() {
		// The table is checked as a whole rather than entry by entry, because the way
		// it will go wrong is somebody adding a material and giving it a number out
		// of scale with the rest — not somebody mistyping a constant, which would not
		// compile.
		for (Muffle.Absorbs kind : Muffle.Absorbs.values()) {
			assertTrue(kind.factor >= 0 && kind.factor <= 2,
				kind + " absorbs " + kind.factor);
			assertTrue(Muffle.carried(kind.factor) > 0 && Muffle.carried(kind.factor) <= 1,
				kind + " lets through " + Muffle.carried(kind.factor));
		}
		assertTrue(Muffle.Absorbs.PADDING.factor > Muffle.Absorbs.SOFT.factor);
		assertTrue(Muffle.Absorbs.SOFT.factor > Muffle.Absorbs.STICKY.factor);
		assertTrue(Muffle.Absorbs.STICKY.factor > Muffle.Absorbs.THIN.factor);
		assertTrue(Muffle.Absorbs.THIN.factor > Muffle.Absorbs.NOTHING.factor);
	}
}
