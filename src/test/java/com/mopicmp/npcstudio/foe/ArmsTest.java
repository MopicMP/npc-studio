package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.foe.Arms.Kind;
import com.mopicmp.npcstudio.foe.Arms.Signs;

/**
 * Which sign wins when several are true at once.
 *
 * <h2>Why this is the part worth testing</h2>
 *
 * Reading the signs off an item is a list of component lookups and cannot be
 * wrong in an interesting way. The <em>order</em> they are weighed in can, and
 * ordinary items set several at once: a trident is a weapon and a thrown thing;
 * a datapack rifle is a weapon and a gun. Which check comes first is the whole
 * of the classification, so it is the whole of the test.
 */
class ArmsTest {

	/** Nothing set, so each test can turn on only what it is about. */
	private static Signs nothing() {
		return new Signs(false, false, false, false, false, false, false);
	}

	private static Signs with(boolean swung, boolean loaded, boolean drawn,
			boolean shield, boolean melee, boolean thrown) {
		return new Signs(false, swung, loaded, drawn, shield, melee, thrown);
	}

	@Test
	@DisplayName("an empty hand is not a weapon of any kind")
	void emptyHanded() {
		assertEquals(Kind.NOTHING,
			Arms.kindOf(new Signs(true, false, false, false, false, false, false)));
	}

	@Test
	@DisplayName("a datapack rifle is a rifle, not a sword")
	void theOneThatMatters() {
		// A guns++ rifle is a carrot on a stick carrying piercing_weapon={} and the
		// enchantment that fires it. Weighed the other way round it is a melee
		// weapon, and a character holding a rifle walks up to you and hits you with
		// it — which is the failure every other mod has, reached from a new
		// direction.
		assertEquals(Kind.SWUNG, Arms.kindOf(with(true, false, false, false, true, false)));
	}

	@Test
	@DisplayName("a spear is not a club")
	void rangedBeatsMelee() {
		// A trident carries a weapon component as well as its own use animation.
		assertEquals(Kind.DRAWN, Arms.kindOf(with(false, false, true, false, true, false)));
	}

	@Test
	@DisplayName("a loaded crossbow is not a drawn bow")
	void loadedBeatsDrawn() {
		// They are worked completely differently — one is charged in advance and
		// then goes off at a touch — so a graph that cannot tell them apart holds
		// the trigger on a crossbow waiting for a draw that never happens.
		assertEquals(Kind.LOADED, Arms.kindOf(with(false, true, true, false, false, false)));
	}

	@Test
	@DisplayName("a shield is a shield even though you can hit with it")
	void shieldBeatsMelee() {
		assertEquals(Kind.SHIELD, Arms.kindOf(with(false, false, false, true, true, false)));
	}

	@Test
	@DisplayName("an arrow that can be dispensed is not a thrown weapon in her hand")
	void meleeBeatsThrown() {
		// Several ordinary items can be flung by a dispenser without being anything
		// somebody would fight with, so the plainer readings come first.
		assertEquals(Kind.MELEE, Arms.kindOf(with(false, false, false, false, true, true)));
	}

	@Test
	@DisplayName("a snowball is thrown")
	void thrown() {
		assertEquals(Kind.THROWN, Arms.kindOf(with(false, false, false, false, false, true)));
	}

	@Test
	@DisplayName("something nobody can place is admitted to rather than guessed at")
	void anHonestShrug() {
		// A wrong confident answer costs more than this does. An author can teach
		// the rest with blocks — reading the item's own name — and cannot unteach
		// us a guess we made for them.
		assertEquals(Kind.OTHER, Arms.kindOf(nothing()));
	}

	@Test
	@DisplayName("every kind a graph might branch on can actually be reached")
	void noDeadCategories() {
		// A name in the vocabulary that nothing can produce is a lie in the
		// vocabulary: an author writes a branch for it and waits for ever.
		var reached = new java.util.HashSet<Kind>();
		for (int bits = 0; bits < 64; bits++) {
			reached.add(Arms.kindOf(with(
				(bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0,
				(bits & 8) != 0, (bits & 16) != 0, (bits & 32) != 0)));
		}
		reached.add(Arms.kindOf(new Signs(true, false, false, false, false, false, false)));
		assertEquals(java.util.Set.of(Kind.values()), reached);
	}
}
