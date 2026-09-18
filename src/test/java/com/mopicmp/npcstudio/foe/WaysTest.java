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

	/**
	 * A floor plan with a raised ledge along one side.
	 *
	 * {@code #} is wall as ever; {@code =} is a block at floor level with air above
	 * it, which is a thing you can stand <em>on</em> — a step, a kerb, the edge of a
	 * path, the top of a low wall. Every built place is full of them.
	 */
	private static Ways.Ground ledges(String... rows) {
		return new Ways.Ground() {
			private char at(int x, int z) {
				if (z < 0 || z >= rows.length) return '#';
				String row = rows[z];
				if (x < 0 || x >= row.length()) return '#';
				return row.charAt(x);
			}

			@Override
			public boolean clear(int x, int y, int z) {
				if (y == -1) return false;
				char what = at(x, z);
				if (what == '#') return y != 0 && y != 1;
				// A ledge fills the walking level and leaves everything above it open.
				if (what == '=') return y != 0;
				return true;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (y == -1) return true;
				char what = at(x, z);
				if (what == '#') return y == 0 || y == 1;
				return what == '=' && y == 0;
			}
		};
	}

	/**
	 * A floor with things lying on it that a body walks over rather than into.
	 *
	 * {@code ~} is a rug: a block with a collision an inch high, which is a carpet, a
	 * layer of snow, a pressure plate. {@code =} is a ledge — a block filling the
	 * walking level with air above, which is a step or a kerb. {@code #} is wall.
	 *
	 * The one that matters is the rug, because a body stands <em>in</em> that block
	 * with its feet an inch off the floor, and the search has to agree with the body
	 * about which block that is.
	 */
	private static Ways.Ground floor(String... rows) {
		return new Ways.Ground() {
			private char at(int x, int z) {
				if (z < 0 || z >= rows.length) return '#';
				String row = rows[z];
				if (x < 0 || x >= row.length()) return '#';
				return row.charAt(x);
			}

			@Override
			public boolean clear(int x, int y, int z) {
				if (y == -1) return false;
				char what = at(x, z);
				if (what == '#') return y != 0 && y != 1;
				if (what == '=') return y != 0;
				// A rug is walked onto rather than into: low enough that a body stands
				// in this very block. That is the whole of the fix being checked here.
				return true;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (y == -1) return true;
				char what = at(x, z);
				if (what == '#') return y == 0 || y == 1;
				if (what == '=') return y == 0;
				// A rug holds you up as well as letting you stand in it, which is what
				// makes two heights standable at once and the order of the loop matter.
				return what == '~' && y == 0;
			}
		};
	}

	@Test
	@DisplayName("a rug is walked over, not climbed onto")
	void aRugIsNotAWall() {
		// The fault this is written for, and the third telling of "he jumps all the
		// time, and in great quantities".
		//
		// A carpet has a collision, so it counted as a wall, so the only place left to
		// stand was the block above it. The route then said "stand at one" while the
		// body stood at nought and a bit — and the walking, comparing those, found
		// nearly a whole block of climb and jumped. Every tick, all the way across the
		// rug.
		Ways.Ground room = floor(
			"~~~~~~~~~",
			"~~~~~~~~~",
			"~~~~~~~~~");
		List<int[]> path = Ways.to(room, 0, 0, 1, 8, 0, 1);

		assertTrue(!path.isEmpty(), "it is a floor with a rug on it, not a wall");
		for (int[] step : path) {
			assertEquals(0, step[1],
				"she went up over the rug at " + step[0] + ", " + step[1] + ", " + step[2]);
		}
	}

	@Test
	@DisplayName("a walk along a kerb stays on the flat instead of climbing onto it")
	void itDoesNotClimbTheFurniture() {
		// The fault this is written for, reported as "he jumps all the time, and in
		// great quantities". Only one height was ever offered per direction — the
		// first that worked, and then the loop broke — and the loop counted down from
		// a climb. So anywhere a neighbouring block stood at walking height, the route
		// went over it rather than past it, and going up a block is a jump. In a built
		// place that is a jump at very nearly every step.
		//
		// The comment beside the break said the order was arbitrary because the cost
		// would decide. The cost cannot decide between things it is never shown.
		Ways.Ground street = ledges(
			"=========",
			".........",
			"=========");
		List<int[]> path = Ways.to(street, 0, 0, 1, 8, 0, 1);

		assertTrue(!path.isEmpty(), "there is a clear lane straight down the middle");
		for (int[] step : path) {
			assertEquals(0, step[1],
				"she went up onto the kerb at " + step[0] + ", " + step[1] + ", " + step[2]);
		}
	}

	@Test
	@DisplayName("but a step that has to be climbed is still climbed")
	void itStillClimbsWhenThereIsNoWayRound() {
		// The other half, and the reason the fix is an order rather than a refusal to
		// go up. Preferring the flat must not mean being unable to leave the ground.
		Ways.Ground step = ledges(
			"###########",
			"...==......",
			"###########");
		List<int[]> path = Ways.to(step, 0, 0, 1, 10, 0, 1);

		assertTrue(!path.isEmpty(), "the only way along the corridor is over the step");
		assertTrue(path.stream().anyMatch(where -> where[1] == 1),
			"she has to go up to get past it");
		int[] last = path.get(path.size() - 1);
		assertEquals(10, last[0]);
		assertEquals(0, last[1], "and she comes down again on the far side");
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

	// ------------------------------------- how far it reaches, and what it avoids

	/** Open ground with a floor, as wide as anybody could want. */
	private static Ways.Ground open() {
		return new Ways.Ground() {
			@Override public boolean clear(int x, int y, int z) { return y >= 0; }

			@Override public boolean solid(int x, int y, int z) { return y == -1; }
		};
	}

	@Test
	@DisplayName("a walk across open ground reaches far further than the budget used to allow")
	void reachesAcrossOpenGround() {
		// The number that mattered and nothing else. A search ordered only by how far it
		// had walked spreads out as a disc, so the budget was a radius — about thirty
		// blocks — and beyond that it reported that there was no way at all.
		//
		// That is what the report was: half the route walked, half of it not, decided
		// by nothing about the route except its length. Fifty blocks is an ordinary
		// distance across a courtyard and used to be past the edge.
		List<int[]> path = Ways.to(open(), 0, 0, 0, 50, 0, 0);
		assertTrue(!path.isEmpty(), "fifty blocks of empty floor is not a hard question");
		assertEquals(50, path.size(), "and the way across it is fifty steps");
	}

	@Test
	@DisplayName("it still finds the shortest way, which is the thing pointing it could have cost")
	void stillShortest() {
		// A search pointed at its target finds the shortest route only while the
		// pointing never overestimates what is left. If that ever stopped being true
		// the paths would get quietly longer — never broken, never reported, and
		// impossible to see. So it is asked here rather than trusted.
		Ways.Ground room = plan(
			".........",
			"....#....",
			".........");
		List<int[]> path = Ways.to(room, 0, 0, 1, 8, 0, 1);
		assertTrue(!path.isEmpty());
		assertEquals(8, path.size(), "round one post, not round the room");
	}

	@Test
	@DisplayName("a block that has already stopped her is gone round, not through")
	void goesRoundWhatStoppedHer() {
		// The doorway is passable as far as the blocks are concerned, and she has just
		// spent a second and a half proving it is not — a gate that is shut, somebody
		// standing in it, a post her body is a hair too wide for. None of that is
		// visible to a search of the blocks, and all of it is obvious to the legs.
		// Two ways through, one of them straight ahead. The plan's own edges count as
		// wall, so the second gap has to be drawn rather than assumed — which the first
		// version of this test did assume, and the test caught it.
		Ways.Ground twoDoors = plan(
			".........",
			".........",
			"###.###.#",
			".........",
			".........");
		// Told nothing, she takes the near door, which is straight in front of her.
		List<int[]> straight = Ways.to(twoDoors, 3, 0, 1, 3, 0, 3);
		assertTrue(!straight.isEmpty());
		assertTrue(straight.stream().anyMatch(step -> step[0] == 3 && step[2] == 2),
			"the near gap is at 3, and nothing yet says not to use it");

		// Told that the near gap stopped her, she goes the long way round to the far one.
		List<int[]> around = Ways.to(twoDoors, 3, 0, 1, 3, 0, 3,
			Ways.FURTHEST, Ways.LOOKED_AT,
			java.util.Set.of(Ways.named(3, 0, 2)));
		assertTrue(!around.isEmpty(), "there is still a way, and she should still take one");
		assertTrue(around.stream().noneMatch(step -> step[0] == 3 && step[2] == 2),
			"having been stopped there, she should not walk straight back into it");
	}

	@Test
	@DisplayName("a block that stopped her is priced, not walled off")
	void shunningIsAPriceAndNotAWall() {
		// The important half. Sometimes the thing that stopped her is the only door
		// there is, and a search forbidden to use it would report that there is no way
		// — leaving a character who stands still for ever rather than one who waits a
		// moment and goes through.
		Ways.Ground oneDoor = plan(
			".........",
			"####.####",
			".........");
		List<int[]> only = Ways.to(oneDoor, 4, 0, 0, 4, 0, 2,
			Ways.FURTHEST, Ways.LOOKED_AT,
			java.util.Set.of(Ways.named(4, 0, 1)));
		assertTrue(!only.isEmpty(), "the only door is still a door");
		assertTrue(only.stream().anyMatch(step -> step[0] == 4 && step[2] == 1),
			"and it is the way through, however dearly it is priced");
	}

	@Test
	@DisplayName("what it costs to be avoided is enough to be worth a detour and not enough to forbid one")
	void shunningIsPricedSensibly() {
		// A number worth a test because both ways of getting it wrong are silent. Too
		// low and the second attempt walks into the same post; too high and it is a
		// wall by another name, with the character crossing a village to avoid a
		// doorway somebody has since stepped out of.
		assertTrue(Ways.SHUNNED > 2,
			"cheaper than a couple of blocks and the detour never happens");
		assertTrue(Ways.SHUNNED < Ways.FURTHEST / 2,
			"dearer than this and it stops being a price");
	}
}
