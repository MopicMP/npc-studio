package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * The map's places as they are kept, and the two rules that are easy to get wrong.
 *
 * <h2>What is being protected</h2>
 *
 * Both rules are about a name already in use, and they pull in opposite
 * directions. Putting a place down under a name that exists must <em>move</em> it,
 * because that is the entire reason a graph refers to places by name: "the gate is
 * in the wrong spot" is corrected by pointing at the right spot, not by editing
 * every graph that mentions it. And the ceiling on how many there may be must not
 * turn a move into a refusal, or a full map becomes one whose existing places can
 * no longer be corrected — which is the worse of the two failures and the one that
 * only shows up on a map somebody has spent a long time on.
 */
class WorldSpotsTest {

	private static BlockPos at(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	@Test
	@DisplayName("a place is found by the name it was put down under")
	void putAndFind() {
		WorldSpots spots = new WorldSpots();
		assertTrue(spots.put("gate", at(10, 64, -3)));
		assertEquals(at(10, 64, -3), spots.at("gate"));
		assertNull(spots.at("ворота"), "a different name is a different place");
		assertNull(spots.at(null));
	}

	@Test
	@DisplayName("putting one down under a name in use moves it")
	void putMoves() {
		WorldSpots spots = new WorldSpots();
		spots.put("gate", at(1, 64, 1));
		assertTrue(spots.put("gate", at(90, 71, -12)));
		assertEquals(1, spots.count(), "moving is not adding");
		assertEquals(at(90, 71, -12), spots.at("gate"));
	}

	@Test
	@DisplayName("the ceiling stops new ones and never stops a move")
	void ceilingAllowsMoves() {
		WorldSpots spots = new WorldSpots();
		for (int i = 0; i < WorldSpots.MOST; i++) {
			assertTrue(spots.put("place " + i, at(i, 64, 0)), "filling at " + i);
		}
		assertEquals(WorldSpots.MOST, spots.count());

		assertFalse(spots.put("one more", at(0, 64, 0)), "a full map takes no new places");
		assertEquals(WorldSpots.MOST, spots.count());

		// And the part that matters: a map at the ceiling is still a map whose places
		// can be corrected. Refusing here would be a mistake nobody meets until the
		// day it is too late to fix.
		assertTrue(spots.put("place 0", at(500, 70, 500)), "a move at the ceiling");
		assertEquals(at(500, 70, 500), spots.at("place 0"));
		assertEquals(WorldSpots.MOST, spots.count());
	}

	@Test
	@DisplayName("dropping one says whether there was anything to drop")
	void dropping() {
		WorldSpots spots = new WorldSpots();
		spots.put("gate", at(1, 64, 1));
		assertTrue(spots.drop("gate"));
		assertNull(spots.at("gate"));
		// False rather than true, because the answer decides whether everybody in the
		// world is told. Telling them about nothing is a packet per stray click.
		assertFalse(spots.drop("gate"));
		assertFalse(spots.drop("never existed"));
	}

	@Test
	@DisplayName("nothing sensible can be put down under no name or nowhere")
	void refusesNonsense() {
		WorldSpots spots = new WorldSpots();
		assertFalse(spots.put(null, at(0, 0, 0)));
		assertFalse(spots.put("gate", null));
		assertEquals(0, spots.count());
	}

	@Test
	@DisplayName("the list comes back with every place on it")
	void listsEverything() {
		WorldSpots spots = new WorldSpots();
		spots.put("gate", at(1, 64, 1));
		spots.put("ворота", at(2, 64, 2));
		assertEquals(2, spots.all().size());
		assertTrue(spots.all().contains(new Spot("gate", at(1, 64, 1))));
		assertTrue(spots.all().contains(new Spot("ворота", at(2, 64, 2))));
	}
}
