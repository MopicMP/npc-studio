package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A box of the map: what is inside it, and where it actually is.
 *
 * <h2>Why arithmetic this plain is worth testing</h2>
 *
 * Because every one of its mistakes is a trap that quietly does not spring, and a
 * trap that does not spring looks exactly like a trap somebody has not reached yet.
 * There is no exception, no red line, nothing in a log. The author walks the
 * corridor, nothing happens, and there is no way to tell a broken box from a
 * mistyped name from a graph that never got there.
 *
 * Three of the four faults below are the kind a person writes without noticing:
 *
 * <ul>
 * <li>taking the corners in the order they were clicked, which is right for half
 *     the boxes anybody draws and inside-out for the other half;</li>
 * <li>testing the far edge as the near side of the high corner, which loses one
 *     block on each of three axes — so a trap drawn over a doorway has a gap in
 *     the doorway;</li>
 * <li>counting the cells of a big box in an {@code int}, where the overflow lands
 *     negative and reads to every ceiling as "well under the limit";</li>
 * <li>and rewriting a box into the other form and moving it while doing so.</li>
 * </ul>
 */
class AreaTest {

	/** The character it hangs off: at 100, 64, 200, facing south, so forward is +z. */
	private static final int[] HOME = { 100, 64, 200 };
	private static final Route.Facing FACING = Route.Facing.SOUTH;

	private static int[][] world(Area area) {
		return area.world(HOME[0], HOME[1], HOME[2], FACING);
	}

	private static boolean holds(Area area, double x, double y, double z) {
		return area.holds(x, y, z, HOME[0], HOME[1], HOME[2], FACING);
	}

	private static Area at(int[] one, int[] other) {
		return new Area(
			new Route.Point.At(one[0], one[1], one[2]),
			new Route.Point.At(other[0], other[1], other[2]),
			Route.From.WORLD);
	}

	@Nested
	@DisplayName("where the box is")
	class Corners {

		@Test
		@DisplayName("the corners come back sorted, whichever way round they were clicked")
		void sorted() {
			int[][] onward = world(at(new int[] { 4, 70, -2 }, new int[] { -1, 64, 6 }));
			int[][] backward = world(at(new int[] { -1, 64, 6 }, new int[] { 4, 70, -2 }));

			// The same box either way. Two clicks arrive in whatever order somebody
			// clicked them, and every test downstream assumes low is low.
			assertArrayEquals(onward[0], backward[0], "low corner");
			assertArrayEquals(onward[1], backward[1], "high corner");
			assertArrayEquals(new int[] { -1, 64, -2 }, onward[0]);
			assertArrayEquals(new int[] { 4, 70, 6 }, onward[1]);
		}

		@Test
		@DisplayName("a box of steps sits where the character is, not where the world's nought is")
		void steps() {
			// Four forward and two left of somebody facing south, level with her feet.
			// Forward is +z and left is +x for that facing; see Route.Facing.
			Area box = new Area(
				new Route.Point.Go(0, 0, 0),
				new Route.Point.Go(4, 2, 2),
				Route.From.CHARACTER);

			assertArrayEquals(new int[] { 100, 64, 200 }, world(box)[0]);
			assertArrayEquals(new int[] { 102, 66, 204 }, world(box)[1]);
		}

		@Test
		@DisplayName("the two forms mix in one box")
		void mixed() {
			// One corner pinned to a piece of ground that will never move, the other
			// hanging off the character. Legal on purpose: a doorway of a building that
			// gets pasted about, squared off against the cliff behind it.
			Area box = new Area(
				new Route.Point.At(90, 60, 190),
				new Route.Point.Go(1, 1, 1),
				Route.From.CHARACTER);

			assertArrayEquals(new int[] { 90, 60, 190 }, world(box)[0]);
			assertArrayEquals(new int[] { 101, 65, 201 }, world(box)[1]);
		}
	}

	@Nested
	@DisplayName("standing in it")
	class Holds {

		@Test
		@DisplayName("a box of one block holds that block and not its neighbours")
		void oneBlock() {
			Area box = at(new int[] { 10, 64, 10 }, new int[] { 10, 64, 10 });

			// Anywhere in the block, which is the whole of it and not its corner.
			assertTrue(holds(box, 10.0, 64.0, 10.0), "the corner of it");
			assertTrue(holds(box, 10.5, 64.5, 10.5), "the middle of it");
			assertTrue(holds(box, 10.99, 64.99, 10.99), "just inside the far side");

			assertFalse(holds(box, 11.0, 64.5, 10.5), "the next block along x");
			assertFalse(holds(box, 10.5, 64.5, 11.0), "the next block along z");
			assertFalse(holds(box, 9.99, 64.5, 10.5), "the block behind");
		}

		@Test
		@DisplayName("both corner blocks are inside, which is what drawing two corners means")
		void inclusive() {
			Area box = at(new int[] { 0, 64, 0 }, new int[] { 2, 66, 2 });

			// The high corner block counts. Written as "< high" rather than "< high + 1"
			// this is false, and a trap drawn over a doorway then has a gap in the
			// doorway — which somebody finds by walking through it.
			assertTrue(holds(box, 2.5, 66.5, 2.5), "the middle of the high corner block");
			assertTrue(holds(box, 0.0, 64.0, 0.0), "the very corner of the low block");
			assertFalse(holds(box, 3.0, 66.5, 2.5), "one past the high corner");
		}

		@Test
		@DisplayName("height counts as much as the floor does")
		void height() {
			Area box = at(new int[] { 0, 64, 0 }, new int[] { 4, 65, 4 });

			assertTrue(holds(box, 2.0, 65.9, 2.0), "standing on the upper block");
			// A box two blocks tall does not catch somebody on the roof, and it must
			// not: a trigger that fires from above is a trigger that fires when
			// somebody walks over the corridor rather than through it.
			assertFalse(holds(box, 2.0, 66.0, 2.0), "standing on top of it");
			assertFalse(holds(box, 2.0, 63.9, 2.0), "in the cellar underneath");
		}
	}

	@Nested
	@DisplayName("how big it is")
	class Size {

		@Test
		@DisplayName("a box of one block is one block")
		void one() {
			assertEquals(1, at(new int[] { 5, 5, 5 }, new int[] { 5, 5, 5 })
				.cells(HOME[0], HOME[1], HOME[2], FACING));
		}

		@Test
		@DisplayName("a wall across a corridor is a handful")
		void wall() {
			// Five wide, four tall, one deep — the ordinary thing this feature exists
			// for, and the number is here so that a ceiling written later can be judged
			// against something real rather than against a feeling.
			assertEquals(20, at(new int[] { 0, 64, 0 }, new int[] { 4, 67, 0 })
				.cells(HOME[0], HOME[1], HOME[2], FACING));
		}

		@Test
		@DisplayName("a box too big to fill says so rather than going negative")
		void doesNotOverflow() {
			// Three spans of two thousand. None of them looks unreasonable on its own,
			// and multiplied in an int the answer is negative — which reads as "well
			// under the limit" to every ceiling anybody would write, so the one check
			// meant to refuse this would wave it through.
			Area huge = at(new int[] { 0, 0, 0 }, new int[] { 1999, 1999, 1999 });
			long many = huge.cells(HOME[0], HOME[1], HOME[2], FACING);

			assertTrue(many > 0, "a count of blocks is never negative");
			assertEquals(2000L * 2000L * 2000L, many);
		}
	}

	@Nested
	@DisplayName("changing how it is written")
	class Rewriting {

		@Test
		@DisplayName("a box rewritten in the other form covers the same ground")
		void staysPut() {
			Area steps = new Area(
				new Route.Point.Go(2, 0, -3),
				new Route.Point.Go(7, 3, 1),
				Route.From.CHARACTER);

			Area places = steps.allWrittenFrom(Route.From.WORLD,
				HOME[0], HOME[1], HOME[2], FACING);

			// The whole requirement. A switch that changes how a box is written and
			// moves it at the same time is a switch nobody would dare press twice.
			assertArrayEquals(world(steps)[0], world(places)[0], "low corner");
			assertArrayEquals(world(steps)[1], world(places)[1], "high corner");
			assertEquals(Route.From.WORLD, places.from());
		}

		@Test
		@DisplayName("and back again, which is the round trip somebody actually does")
		void andBack() {
			Area places = at(new int[] { 104, 66, 197 }, new int[] { 99, 64, 205 });
			Area steps = places.allWrittenFrom(Route.From.CHARACTER,
				HOME[0], HOME[1], HOME[2], FACING);

			assertArrayEquals(world(places)[0], world(steps)[0], "low corner");
			assertArrayEquals(world(places)[1], world(steps)[1], "high corner");
			assertTrue(steps.needsAnchor(), "written as steps, it now hangs off somebody");
		}

		@Test
		@DisplayName("a box written as steps moves with the character it hangs off")
		void movesWithHer() {
			// The reason steps are the ordinary form. The corridor was built off to the
			// side and pasted in somewhere else; every coordinate in it changed, and the
			// trap is still over the same doorway.
			Area box = new Area(
				new Route.Point.Go(3, 0, 0),
				new Route.Point.Go(6, 2, 2),
				Route.From.CHARACTER);

			int[][] here = box.world(100, 64, 200, FACING);
			int[][] moved = box.world(1100, 70, -800, FACING);

			assertArrayEquals(new int[] { 1000, 6, -1000 }, new int[] {
				moved[0][0] - here[0][0], moved[0][1] - here[0][1], moved[0][2] - here[0][2] });
			assertEquals(box.cells(100, 64, 200, FACING), box.cells(1100, 70, -800, FACING),
				"and it is the same size wherever it went");
		}

		@Test
		@DisplayName("a corner never waits, however a hand-written file asks it to")
		void cornersDoNotWait() {
			// A corner borrows the route point's shape and that shape can pause. A place
			// cannot: there is nobody standing at a corner to do the waiting. Dropped on
			// the way in, so the impossible state cannot exist to be puzzled over.
			Area box = new Area(
				new Route.Point.At(0, 0, 0, 40),
				new Route.Point.Go(1, 1, 1, 20),
				Route.From.WORLD);

			assertEquals(0, box.first().stay());
			assertEquals(0, box.second().stay());
		}
	}

	@Nested
	@DisplayName("in a document")
	class InADocument {

		@Test
		@DisplayName("the boxes a graph names are found, wherever they are named")
		void namesFound() {
			Area box = at(new int[] { 0, 0, 0 }, new int[] { 1, 1, 1 });
			Dialogue graph = Dialogue.builder("trap")
				.area("hall", box)
				.area("door", box)
				.area("unused", box)
				.add(new Node.Until("wait", new Condition.Inside("hall"), "shut"))
				.add(new Node.Act("shut", new Effect.Wall("door", true), "open"))
				.add(new Node.Act("open", new Effect.Wall("door", false), "done"))
				.add(new Node.End("done"))
				.build();

			// Both places a box can be named — a question and a verb — and the box
			// nobody named stays out of it. This is what makes "you are about to delete
			// a box two nodes are using" answerable at the moment somebody deletes one.
			assertEquals(java.util.Set.of("hall", "door"), graph.areasUsed());
		}

		@Test
		@DisplayName("a box named inside a nest of ands and nots is still found")
		void namesFoundDeep() {
			Dialogue graph = Dialogue.builder("trap")
				.add(new Node.Until("wait", new Condition.All(List.of(
					new Condition.Not(new Condition.Inside("outside")),
					new Condition.Any(List.of(
						new Condition.Inside("hall"),
						new Condition.HasItem("minecraft:key", 1))))), "done"))
				.add(new Node.End("done"))
				.build();

			// Nested is the ordinary case, not the exception: "inside the hall and not
			// yet past the door" is two boxes in one condition, and a search that only
			// looked at the top would report neither.
			assertEquals(java.util.Set.of("outside", "hall"), graph.areasUsed());
		}

		@Test
		@DisplayName("the editor and the document count the same boxes as used")
		void oneWalkForBoth() {
			Area box = at(new int[] { 0, 0, 0 }, new int[] { 1, 1, 1 });
			Dialogue graph = Dialogue.builder("trap")
				.area("hall", box)
				.add(new Node.Until("wait", new Condition.All(List.of(
					new Condition.Inside("hall"),
					new Condition.Not(new Condition.Inside("porch")))), "shut"))
				.add(new Node.Act("shut", new Effect.Wall("door", true), "done"))
				.add(new Node.End("done"))
				.build();

			// The editor asks over loose nodes, the file asks over a finished document,
			// and they must agree. Two walks would drift, and the drift shows up as a
			// box the panel calls unused and the graph is still naming — which is a box
			// somebody deletes, and a trap that then never springs.
			assertEquals(graph.areasUsed(), Dialogue.areasUsed(graph.nodes().values()));
			assertEquals(java.util.Set.of("hall", "porch", "door"), graph.areasUsed());
		}

		@Test
		@DisplayName("a document written before boxes existed still reads, with none")
		void olderFilesStillRead() {
			// The promise every optional field in this file makes. A graph saved last
			// month has no boxes key at all, and absent has to go on meaning "none"
			// rather than becoming a refusal.
			Dialogue graph = Dialogue.builder("plain")
				.add(new Node.End("done"))
				.build();

			assertTrue(graph.areas().isEmpty());
			assertTrue(graph.areasUsed().isEmpty());
		}
	}

	/**
	 * Carrying the boxes to where the build went.
	 *
	 * The reason this exists at all is in {@link Area#shifted}: the boxes stopped
	 * hanging off a character, so moving a build has to be something somebody does
	 * rather than something that happens to them.
	 */
	@Nested
	class Moving {

		@Test
		@DisplayName("a box in coordinates lands over the same blocks of the moved build")
		void coordinatesTravel() {
			Area box = new Area(new Route.Point.At(10, 64, 10),
				new Route.Point.At(14, 66, 14), Route.From.WORLD);
			// The build was pasted two hundred east and eight up.
			Area now = box.shifted(200, 8, 0);

			assertTrue(now.holds(212.5, 73, 12.5, 0, 0, 0, Route.Facing.SOUTH),
				"the middle of the box moved with the build");
			assertFalse(now.holds(12.5, 65, 12.5, 0, 0, 0, Route.Facing.SOUTH),
				"and is no longer where the build used to stand");
			// The shape is untouched: a move is a move, not a resize.
			assertArrayEquals(box.world(0, 0, 0, Route.Facing.SOUTH)[1],
				new int[] { 14, 66, 14 });
			assertEquals(box.cells(0, 0, 0, Route.Facing.SOUTH),
				now.cells(0, 0, 0, Route.Facing.SOUTH));
		}

		@Test
		@DisplayName("a corner written as a step is left alone, having travelled already")
		void stepsAreNotMovedTwice() {
			// She was placed in the new build, and everything measured from her came
			// with her. Shifting it as well would land it exactly one whole move past
			// the target, which is the sort of wrong that looks like a different bug.
			Area box = new Area(new Route.Point.Go(1, 0, 1),
				new Route.Point.At(14, 66, 14), Route.From.CHARACTER);
			Area now = box.shifted(200, 8, 0);

			assertEquals(box.first(), now.first());
			assertEquals(new Route.Point.At(214, 74, 14), now.second());
		}

		@Test
		@DisplayName("nothing to move is not a change")
		void wholelyStepsBoxIsUntouched() {
			// Which is what lets the move report how many it left alone: a box it did
			// not touch compares equal, and the count is honest rather than assumed.
			Area box = new Area(new Route.Point.Go(1, 0, 1),
				new Route.Point.Go(3, 2, 3), Route.From.CHARACTER);
			assertEquals(box, box.shifted(200, 8, 0));
		}
	}
}
