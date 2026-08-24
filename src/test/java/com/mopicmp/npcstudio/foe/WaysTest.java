package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walking somewhere, drawn out as floor plans.
 *
 * <h2>Why the plans are written down</h2>
 *
 * Because every mistake a pathfinder makes looks the same from inside the game —
 * a character standing still, or walking into a wall — and none of them says
 * which of a dozen rules was wrong. Each of these is a room somebody could build
 * in a minute and check by walking it.
 */
class WaysTest {

	/**
	 * A floor plan seen from above, one storey, with a solid floor beneath it.
	 *
	 * {@code #} is wall, everything else is air. The floor is at y = -1 and is
	 * everywhere, so these test routes and not falling.
	 */
	private static Ways.Ground plan(String... rows) {
		return new Ways.Ground() {
			private boolean wall(int x, int y, int z) {
				if (y != 0 && y != 1) return false;
				if (z < 0 || z >= rows.length) return true;
				String row = rows[z];
				if (x < 0 || x >= row.length()) return true;
				return row.charAt(x) == '#';
			}

			@Override
			public boolean clear(int x, int y, int z) {
				if (y == -1) return false;
				return !wall(x, y, z);
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return y == -1 || wall(x, y, z);
			}
		};
	}

	private static double lengthOf(List<int[]> path) {
		return path.size();
	}

	@Test
	@DisplayName("a clear room is crossed in a straight line")
	void straightAcross() {
		Ways.Ground room = plan(
			".........",
			".........",
			".........");
		List<int[]> path = Ways.to(room, 0, 0, 1, 8, 0, 1);
		assertTrue(!path.isEmpty(), "there is obviously a way");
		assertEquals(8, lengthOf(path), "and it is eight steps, not a tour of the room");

		int[] last = path.get(path.size() - 1);
		assertEquals(8, last[0]);
		assertEquals(1, last[2]);
	}

	@Test
	@DisplayName("the path does not include where you already are")
	void itStartsAtTheNextStep() {
		Ways.Ground room = plan("....", "....");
		List<int[]> path = Ways.to(room, 0, 0, 0, 3, 0, 0);
		assertTrue(path.get(0)[0] != 0 || path.get(0)[2] != 0,
			"a character is already standing on the first block");
	}

	@Test
	@DisplayName("a wall is walked round, through the door")
	void roundThroughTheDoor() {
		Ways.Ground rooms = plan(
			"###########",
			"#....#....#",
			"#....#....#",
			"#.........#",
			"#....#....#",
			"#....#....#",
			"###########");
		List<int[]> path = Ways.to(rooms, 2, 0, 1, 8, 0, 1);
		assertTrue(!path.isEmpty(), "the door is open");

		// Every step must be somewhere a person could stand — which is the whole
		// promise, and the one that fails silently by letting somebody walk through a
		// corner.
		for (int[] step : path) {
			assertTrue(Ways.standable(rooms, step[0], step[1], step[2]),
				"stepped into " + step[0] + "," + step[1] + "," + step[2]);
		}
		// And it went through the gap, which is the only way across.
		assertTrue(path.stream().anyMatch(step -> step[2] == 3 && step[0] == 5),
			"it must pass through the doorway");
	}

	@Test
	@DisplayName("a sealed room has no way out")
	void sealedIsUnreachable() {
		Ways.Ground cell = plan(
			"#######",
			"#..#..#",
			"#..#..#",
			"#..#..#",
			"#######");
		assertTrue(Ways.to(cell, 2, 0, 2, 4, 0, 2).isEmpty());
	}

	@Test
	@DisplayName("corners are not cut through the masonry")
	void noCuttingCorners() {
		// The classic diagonal bug: a step between two walls passes through the join
		// between them. It looks like a character walking through a corner, because
		// it is one.
		Ways.Ground corner = plan(
			"..#..",
			"..#..",
			"##...",
			".....");
		List<int[]> path = Ways.to(corner, 1, 0, 1, 3, 0, 1);
		for (int i = 0; i < path.size(); i++) {
			int[] step = path.get(i);
			int[] before = i == 0 ? new int[] { 1, 0, 1 } : path.get(i - 1);
			int dx = step[0] - before[0];
			int dz = step[2] - before[2];
			if (dx != 0 && dz != 0) {
				assertTrue(corner.clear(before[0] + dx, before[1], before[2])
					&& corner.clear(before[0], before[1], before[2] + dz),
					"cut a corner from " + before[0] + "," + before[2]
						+ " to " + step[0] + "," + step[2]);
			}
		}
	}

	@Test
	@DisplayName("a step up one block is taken, and two is not")
	void climbingOneAndNotTwo() {
		Ways.Ground stair = terrain(new int[][] {
			{ 0, 0, 0, 1, 1 },
			{ 0, 0, 0, 1, 1 } });
		assertTrue(!Ways.to(stair, 0, 1, 0, 4, 2, 0).isEmpty(), "one block up is a step");

		Ways.Ground wall = terrain(new int[][] {
			{ 0, 0, 0, 2, 2 },
			{ 0, 0, 0, 2, 2 } });
		assertTrue(Ways.to(wall, 0, 1, 0, 4, 3, 0).isEmpty(), "two blocks up is a wall");
	}

	@Test
	@DisplayName("a drop of three is taken and a cliff is not")
	void droppingButNotFalling() {
		// Otherwise a character walks off a cliff to investigate a noise and arrives
		// dead, which is a poor sort of investigation.
		Ways.Ground ledge = terrain(new int[][] {
			{ 3, 3, 0, 0, 0 },
			{ 3, 3, 0, 0, 0 } });
		assertTrue(!Ways.to(ledge, 0, 4, 0, 4, 1, 0).isEmpty(), "three down is a step down");

		Ways.Ground cliff = terrain(new int[][] {
			{ 8, 8, 0, 0, 0 },
			{ 8, 8, 0, 0, 0 } });
		assertTrue(Ways.to(cliff, 0, 9, 0, 4, 1, 0).isEmpty(), "eight down is a cliff");
	}

	@Test
	@DisplayName("the flat way round beats the way over the furniture")
	void flatIsPreferred() {
		// Climbing costs a little more than walking, so a character crossing a room
		// goes round the table rather than over it.
		Ways.Ground room = terrain(new int[][] {
			{ 0, 1, 0 },
			{ 0, 0, 0 },
			{ 0, 1, 0 } });
		List<int[]> path = Ways.to(room, 0, 1, 1, 2, 1, 1);
		assertTrue(!path.isEmpty());
		for (int[] step : path) {
			assertEquals(1, step[1], "it climbed onto something at "
				+ step[0] + "," + step[2]);
		}
	}

	@Test
	@DisplayName("going nowhere is an empty path and not a failure")
	void alreadyThere() {
		assertTrue(Ways.to(plan("..."), 1, 0, 0, 1, 0, 0).isEmpty());
	}

	@Test
	@DisplayName("running out of budget gives up rather than half a route")
	void theBudgetFailsSafely() {
		Ways.Ground room = plan(
			"..........",
			"..........",
			"..........");
		assertTrue(Ways.to(room, 0, 0, 1, 9, 0, 1, Ways.FURTHEST, 3).isEmpty());
	}

	@Test
	@DisplayName("somewhere too far to be worth walking is refused")
	void distanceEndsIt() {
		Ways.Ground corridor = plan(
			"##########",
			"..........",
			"##########");
		assertTrue(Ways.to(corridor, 0, 0, 1, 9, 0, 1, 4, Ways.LOOKED_AT).isEmpty());
		assertTrue(!Ways.to(corridor, 0, 0, 1, 9, 0, 1, 20, Ways.LOOKED_AT).isEmpty());
	}

	@Test
	@DisplayName("every step of every path is somewhere a person could stand")
	void everyStepIsStandable() {
		Ways.Ground rooms = plan(
			"###########",
			"#....#....#",
			"#.........#",
			"#....#....#",
			"###########");
		for (int[] step : Ways.to(rooms, 1, 0, 1, 9, 0, 3)) {
			assertTrue(Ways.standable(rooms, step[0], step[1], step[2]));
		}
	}

	/**
	 * Ground with heights: the number is how many blocks of floor are stacked there.
	 *
	 * So {@code 0} is the base floor and {@code 2} is a two-block wall. Written this
	 * way because the interesting cases are all about up and down, and a plan seen
	 * from above cannot show them.
	 */
	private static Ways.Ground terrain(int[][] heights) {
		return new Ways.Ground() {
			private int heightAt(int x, int z) {
				if (z < 0 || z >= heights.length) return 99;
				if (x < 0 || x >= heights[z].length) return 99;
				return heights[z][x];
			}

			@Override
			public boolean clear(int x, int y, int z) {
				return y > heightAt(x, z);
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return y <= heightAt(x, z);
			}
		};
	}
}
