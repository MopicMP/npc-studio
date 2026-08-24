package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sound finding its way round corners.
 *
 * <h2>Why the mazes are written out</h2>
 *
 * Because the whole complaint that produced this class was that a straight line
 * gives the wrong answer in a room with a door in it, and the only way to be
 * sure the new answer is right is to draw the room and check. Each of these is a
 * plan somebody could build in a world in a minute.
 */
class AirwaysTest {

	/**
	 * A floor plan, one character per block, read as a room seen from above.
	 *
	 * {@code #} is wall and everything else is air. One storey, so the y stays at
	 * nought — which covers every case here and keeps the pictures legible.
	 */
	private static Airways.Space plan(String... rows) {
		return (x, y, z) -> {
			if (y != 0) return false;
			if (z < 0 || z >= rows.length) return false;
			String row = rows[z];
			if (x < 0 || x >= row.length()) return false;
			return row.charAt(x) != '#';
		};
	}

	@Test
	@DisplayName("in the open, the way round is the straight line")
	void openGroundIsStraight() {
		Airways.Space field = plan(
			"..........",
			"..........",
			"..........");
		// Ten blocks along an empty row is ten blocks. Anything else would mean the
		// search was adding a cost the world does not have.
		assertEquals(9, Airways.reach(field, 0, 0, 1, 9, 0, 1), 1e-6);
	}

	@Test
	@DisplayName("a diagonal costs what a diagonal costs")
	void diagonalsAreNotOverCharged() {
		Airways.Space field = plan(
			"....",
			"....",
			"....",
			"....");
		// The reason this is a proper search and not a breadth-first walk. Counting
		// every step as one makes a corridor at forty-five degrees half again as long
		// as it is, and a guard standing in it goes deaf for no reason a player could
		// ever work out.
		assertEquals(3 * Math.sqrt(2), Airways.reach(field, 0, 0, 0, 3, 0, 3), 1e-6);
	}

	@Test
	@DisplayName("a doorway is heard through, not through the wall beside it")
	void soundGoesThroughTheDoor() {
		// The reported case, drawn out. The guard at the left, the player at the
		// right, a wall between them with a gap in it. A straight line says two
		// blocks of stone; what actually happens is he hears you perfectly, round
		// through the door.
		Airways.Space rooms = plan(
			"###########",
			"#....#....#",
			"#....#....#",
			"#.........#",
			"#....#....#",
			"#....#....#",
			"###########");
		double round = Airways.reach(rooms, 2, 0, 1, 8, 0, 1);
		assertTrue(round > 0, "it must find the way through");

		// Further than the straight line, because it went round — but not much
		// further, and nothing was muffled on the way.
		double straight = Math.sqrt(6 * 6);
		assertTrue(round > straight, round + " round against " + straight + " direct");
		assertTrue(round < straight * 2.2, "and not absurdly further: " + round);
	}

	@Test
	@DisplayName("a sealed room is silence")
	void sealedIsUnreachable() {
		// The other half of the promise. If going round worked everywhere, walls
		// would mean nothing at all and there would be no point to any of it.
		Airways.Space cell = plan(
			"#######",
			"#..#..#",
			"#..#..#",
			"#..#..#",
			"#######");
		assertEquals(-1, Airways.reach(cell, 2, 0, 2, 4, 0, 2), 1e-9);
	}

	@Test
	@DisplayName("the noise itself is reached even when it stands in a doorway")
	void theSourceNeedNotBeOpen() {
		// Somebody standing in a door or wading in a slab is still making a noise,
		// and refusing to reach them would be a silence with no visible cause.
		Airways.Space wall = plan(
			".....",
			".....",
			"..#..",
			".....");
		assertTrue(Airways.reach(wall, 2, 0, 0, 2, 0, 2) > 0);
	}

	@Test
	@DisplayName("a long way round is eventually too far")
	void distanceStillEndsIt() {
		Airways.Space corridor = plan(
			"##########",
			"..........",
			"##########");
		assertEquals(-1, Airways.reach(corridor, 0, 0, 1, 9, 0, 1, 5, Airways.LOOKED_AT),
			1e-9, "nine blocks of corridor with five blocks of hearing");
		assertTrue(Airways.reach(corridor, 0, 0, 1, 9, 0, 1, 12, Airways.LOOKED_AT) > 0);
	}

	@Test
	@DisplayName("running out of budget is reported as not heard, not as heard nearby")
	void theBudgetFailsSafely() {
		Airways.Space field = plan(
			"..........",
			"..........",
			"..........");
		// Two ways of stopping, one answer, and it has to be this one: from the
		// guard's side "I stopped looking" and "it did not reach me" are the same
		// thing, and the dangerous mistake would be to return the distance so far.
		assertEquals(-1, Airways.reach(field, 0, 0, 1, 9, 0, 1, Airways.FURTHEST, 3), 1e-9);
	}

	@Test
	@DisplayName("standing on the noise is no distance at all")
	void sameBlockIsNothing() {
		assertEquals(0, Airways.reach(plan("..."), 1, 0, 0, 1, 0, 0), 1e-9);
	}
}
