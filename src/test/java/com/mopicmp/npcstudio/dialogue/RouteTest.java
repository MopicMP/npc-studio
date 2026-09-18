package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A character sent along a path.
 *
 * <h2>What is actually being checked</h2>
 *
 * That one node can wait for something several ticks long without the bookmark
 * learning to count. The route node asks two readings and gets three answers out
 * of them — not started, going, arrived — and every fault this thing can have is a
 * fault in that arithmetic:
 *
 * <ul>
 * <li>ordering the walk again every tick, so she restarts from the first point
 *     twenty times a second and never leaves it;</li>
 * <li>not ordering it at all after the first tick, so an interruption is
 *     permanent;</li>
 * <li>falling through on the first entry, before the order has reached the legs,
 *     which is the fault the two-node version of this had and the reason the node
 *     exists;</li>
 * <li>and taking somebody else's arrival for its own, which shows up only in a
 *     graph with two routes in it.</li>
 * </ul>
 *
 * The last one is why the reading is a name and not a flag, and it is the one that
 * would otherwise be found by a map author rather than here.
 */
class RouteTest {

	/**
	 * A character whose legs answer.
	 *
	 * The whole world these nodes see: what she is walking, and what she last
	 * finished. Written as a little mutable pair rather than as a mock, because the
	 * interesting part is the sequence of answers over several ticks and a stub that
	 * cannot change is a stub that can only test one of them.
	 */
	private static final class Legs implements Condition.World {
		String going = "";
		String done = "";

		@Override public boolean hasItem(String item, int count) {
			return false;
		}

		@Override public Value sense(String name) {
			return switch (name) {
				case Sense.WALKING_TO -> new Value.Text(going);
				case Sense.WALKED -> new Value.Text(done);
				default -> new Value.Flag(false);
			};
		}

		/** What a body does when it is handed the orders that came out of a step. */
		void carryOut(List<Effect> effects) {
			for (Effect effect : effects) {
				switch (effect) {
					case Effect.Follow(String order, Route _) -> {
						going = order;
						if (order.equals(done)) done = "";
					}
					case Effect.Arrived(String order) -> {
						if (order.equals(done)) done = "";
					}
					default -> { }
				}
			}
		}

		/** She reached the end of whatever she was on. */
		void arrive() {
			done = going;
			going = "";
		}
	}

	private static final Route ROUND = new Route(
		List.of(new Route.Point.At(10, 64, 10), new Route.Point.At(20, 64, 10),
			new Route.Point.At(20, 64, 20)),
		Route.Gait.WALK, Route.STROLL, Route.From.WORLD);

	private static Dialogue patrol() {
		return Dialogue.builder("patrol")
			.start("round")
			.add(new Node.Walk("round", ROUND, "round"))
			.build();
	}

	private static DialogueEngine.Step resume(Dialogue graph, DialogueState state,
			Condition.World world) {
		return DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), world);
	}

	private static Route blank(float pace) {
		return new Route(List.of(), Route.Gait.WALK, pace, Route.From.WORLD);
	}

	@Nested
	@DisplayName("one journey")
	class OneJourney {

		@Test
		@DisplayName("the first tick orders the walk and stops there")
		void itOrdersAndWaits() {
			Dialogue graph = patrol();
			Legs legs = new Legs();

			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), legs);

			assertEquals(List.of(new Effect.Follow("round", ROUND)), step.effects());
			// Nought means ask again next tick. Anything else would be a character who
			// walks in bursts with pauses in between.
			assertEquals(0, assertInstanceOf(DialogueEngine.Screen.Waiting.class,
				step.screen()).ticks());
			assertEquals("round", step.state().currentNode(), "and the bookmark stayed on it");
		}

		@Test
		@DisplayName("it does not fall straight through before the order has reached the legs")
		void theFaultThisNodeExistsToFix() {
			// Written the obvious way — an `act` that orders a walk and an `until` that
			// waits for walking to stop — this whole thing ran in one step: effects are
			// carried out after a step finishes, so the wait was asked before the order
			// had gone anywhere and was over before it began. She stood at the first
			// point with the graph already past the last.
			Dialogue graph = patrol();
			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), new Legs());

			assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen(),
				"it must stop here, whatever the legs say about a walk nobody has begun");
		}

		@Test
		@DisplayName("while she is walking it, nothing is ordered again")
		void theOrderIsNotRepeated() {
			// Not merely wasteful. An order arriving afresh is an order to start, and a
			// route started twenty times a second is a character who never gets past her
			// first point.
			Dialogue graph = patrol();
			Legs legs = new Legs();
			DialogueState mind = DialogueState.start(graph, Map.of());

			DialogueEngine.Step first = resume(graph, mind, legs);
			legs.carryOut(first.effects());

			DialogueEngine.Step second = resume(graph, first.state(), legs);
			assertEquals(List.of(), second.effects(), "she is already on it");
		}

		@Test
		@DisplayName("arriving moves the graph on, and takes the arrival with it")
		void arrivingMovesOn() {
			Dialogue graph = Dialogue.builder("errand")
				.start("go")
				.add(new Node.Walk("go", ROUND, "done"))
				.add(new Node.End("done"))
				.build();

			Legs legs = new Legs();
			DialogueEngine.Step first = resume(graph, DialogueState.start(graph, Map.of()), legs);
			legs.carryOut(first.effects());
			legs.arrive();

			DialogueEngine.Step step = resume(graph, first.state(), legs);
			assertInstanceOf(DialogueEngine.Screen.Finished.class, step.screen());
			assertTrue(step.effects().contains(new Effect.Arrived("go")),
				"the arrival has to be collected, not merely looked at: " + step.effects());
		}
	}

	@Nested
	@DisplayName("going round")
	class GoingRound {

		@Test
		@DisplayName("a route leading back to itself sets off again rather than spinning")
		void theLoopThatWouldHaveSpun() {
			// The fault this guards is not subtle once it happens and is invisible until
			// then. A finished route stays finished, so a node re-entered a moment later
			// would find the same arrival lying there, pass through it, come back round
			// and pass through it again — the graph at full speed and the character
			// standing still.
			Dialogue graph = patrol();
			Legs legs = new Legs();

			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), legs);
			legs.carryOut(step.effects());
			legs.arrive();

			// The tick she gets back: the arrival is taken and the round is ordered
			// afresh, both in one step, because the node it leads to is itself.
			step = resume(graph, step.state(), legs);
			assertEquals(List.of(new Effect.Arrived("round"), new Effect.Follow("round", ROUND)),
				step.effects(), "taken, then ordered again");
			assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen(),
				"and it stops there rather than going round the graph without going round the map");
		}

		@Test
		@DisplayName("and the tick after that it is walking, not ordering")
		void andThenItSettles() {
			Dialogue graph = patrol();
			Legs legs = new Legs();

			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), legs);
			legs.carryOut(step.effects());
			legs.arrive();
			step = resume(graph, step.state(), legs);
			legs.carryOut(step.effects());

			step = resume(graph, step.state(), legs);
			assertEquals(List.of(), step.effects());
		}
	}

	@Nested
	@DisplayName("more than one route")
	class MoreThanOne {

		private Dialogue twoRounds() {
			return Dialogue.builder("two")
				.start("first")
				.add(new Node.Walk("first", ROUND, "second"))
				.add(new Node.Walk("second", ROUND, "first"))
				.build();
		}

		@Test
		@DisplayName("a node waits for its own route and not for whichever one ended")
		void itWaitsForItsOwn() {
			// This is why the reading is a name rather than a flag. "Is she walking" is
			// answered yes by a walk ordered anywhere else in the graph, and a node
			// reading it would move on the moment an unrelated one finished — which
			// looks like a character skipping half her round for no reason.
			Dialogue graph = twoRounds();
			Legs legs = new Legs();

			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), legs);
			legs.carryOut(step.effects());

			// Something else entirely finished a walk while she is on the first round.
			legs.done = "somebody_elses_errand";

			step = resume(graph, step.state(), legs);
			assertEquals(List.of(), step.effects(), "she is still on her own");
			assertEquals("first", step.state().currentNode());
		}

		@Test
		@DisplayName("finishing one hands the body to the next")
		void oneAfterAnother() {
			Dialogue graph = twoRounds();
			Legs legs = new Legs();

			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), legs);
			legs.carryOut(step.effects());
			legs.arrive();

			step = resume(graph, step.state(), legs);
			assertEquals(List.of(new Effect.Arrived("first"), new Effect.Follow("second", ROUND)),
				step.effects());
			assertEquals("second", step.state().currentNode());
		}
	}

	@Nested
	@DisplayName("being knocked off it")
	class Interrupted {

		@Test
		@DisplayName("a character whose walk was taken away is put back on her round")
		void sheGoesBackToIt() {
			// Not a special case anywhere — it falls out of the node being idempotent.
			// The node asks whether anybody is carrying its order, finds nobody, and
			// orders it. Which is what a patrol interrupted by a fight ought to do
			// afterwards, and it costs no code at all to have.
			Dialogue graph = patrol();
			Legs legs = new Legs();

			DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), legs);
			legs.carryOut(step.effects());

			// Something else halted her mid-round, without the route ever finishing.
			legs.going = "";

			step = resume(graph, step.state(), legs);
			assertEquals(List.of(new Effect.Follow("round", ROUND)), step.effects(),
				"back on her round");
		}
	}

	@Nested
	@DisplayName("steps rather than coordinates")
	class Steps {

		/**
		 * The case the whole relative form exists for.
		 *
		 * Builders build off to the side of the map and paste the finished thing in.
		 * Every coordinate in it changes, so a route written in coordinates has to be
		 * drawn again from nothing — reported exactly that way, and it is not a rare
		 * case but how large maps are made.
		 */
		@Test
		@DisplayName("a route of steps means the same shape wherever the anchor is")
		void itSurvivesTheBuildingBeingMoved() {
			List<Route.Point> path = List.of(
				new Route.Point.Go(4, 0, 0),
				new Route.Point.Go(4, 0, 2),
				new Route.Point.Go(0, 0, 2));

			int[][] here = walked(path, 100, 64, 100, Route.Facing.SOUTH);
			int[][] there = walked(path, -812, 71, 340, Route.Facing.SOUTH);

			for (int i = 0; i < path.size(); i++) {
				assertEquals(here[i][0] - 100, there[i][0] + 812, "step " + i + " sideways");
				assertEquals(here[i][1] - 64, there[i][1] - 71, "step " + i + " in height");
				assertEquals(here[i][2] - 100, there[i][2] - 340, "step " + i + " along");
			}
		}

		@Test
		@DisplayName("forward is the anchor's forward, and it turns with her")
		void theFrameTurns() {
			Route.Point four = new Route.Point.Go(4, 0, 0);
			// Facing south is +Z, west is minus X, north is minus Z, east is +X. Which
			// is the game's own yaw and not ours to choose — a frame that disagreed
			// with it would put "forward" behind a character placed facing forward.
			assertArrayEquals(new int[] { 0, 0, 4 },
				Route.world(four, 0, 0, 0, Route.Facing.SOUTH));
			assertArrayEquals(new int[] { -4, 0, 0 },
				Route.world(four, 0, 0, 0, Route.Facing.WEST));
			assertArrayEquals(new int[] { 0, 0, -4 },
				Route.world(four, 0, 0, 0, Route.Facing.NORTH));
			assertArrayEquals(new int[] { 4, 0, 0 },
				Route.world(four, 0, 0, 0, Route.Facing.EAST));
		}

		@Test
		@DisplayName("left is on the left, from where the anchor is standing")
		void leftIsLeft() {
			// Stand facing south and your left hand points east. Getting this back to
			// front is the mistake nobody would catch by reading, because the route
			// still looks like a route — it is simply mirrored.
			assertArrayEquals(new int[] { 2, 0, 0 },
				Route.world(new Route.Point.Go(0, 0, 2), 0, 0, 0, Route.Facing.SOUTH));
			assertArrayEquals(new int[] { 0, 0, 2 },
				Route.world(new Route.Point.Go(0, 0, 2), 0, 0, 0, Route.Facing.WEST));
		}

		@Test
		@DisplayName("a click turned into a step and back again is the same block")
		void thereAndBack() {
			// The editor writes clicks as steps and everything downstream reads them as
			// places. If those two disagree by so much as a block, a route is right on
			// the screen and wrong on the ground, which is the worse of the two.
			for (Route.Facing facing : Route.Facing.values()) {
				for (int[] block : new int[][] {
						{ 214, 71, -88 }, { -3, 0, 5 }, { 0, 0, 0 }, { 12, 80, 12 } }) {
					Route.Point.Go step = Route.step(block[0], block[1], block[2],
						10, 64, -20, facing);
					assertArrayEquals(block, Route.world(step, 10, 64, -20, facing),
						"facing " + facing + " at " + java.util.Arrays.toString(block));
				}
			}
		}

		@Test
		@DisplayName("changing how a route is written does not move it")
		void switchingFormLeavesThePathAlone() {
			// A switch that changes the writing and the ground at once is a switch
			// nobody would dare press twice.
			Route steps = new Route(List.of(
					new Route.Point.Go(4, 0, 0, 40),
					new Route.Point.Go(4, 1, 2)),
				Route.Gait.RUN, 0.7f, Route.From.CHARACTER);

			Route places = steps.allWrittenFrom(Route.From.WORLD, 10, 64, -20,
				Route.Facing.WEST);

			assertEquals(Route.From.WORLD, places.from());
			for (int i = 0; i < steps.size(); i++) {
				assertArrayEquals(
					Route.world(steps.at(i), 10, 64, -20, Route.Facing.WEST),
					Route.world(places.at(i), 10, 64, -20, Route.Facing.WEST),
					"point " + i);
				assertEquals(steps.at(i).stay(), places.at(i).stay(), "point " + i + " waits");
			}

			// And back again, which has to land on exactly the steps it started as.
			assertEquals(steps,
				places.allWrittenFrom(Route.From.CHARACTER, 10, 64, -20, Route.Facing.WEST));
		}

		@Test
		@DisplayName("a heading is snapped to the quarter turn it is nearest")
		void facingIsSnapped() {
			// Four and not three hundred and sixty, because "four forward" only means
			// four blocks if forward is along an axis. Nudging a character while
			// building must not quietly rotate her whole round.
			assertEquals(Route.Facing.SOUTH, Route.Facing.ofYaw(0));
			assertEquals(Route.Facing.SOUTH, Route.Facing.ofYaw(20));
			assertEquals(Route.Facing.SOUTH, Route.Facing.ofYaw(-20));
			assertEquals(Route.Facing.WEST, Route.Facing.ofYaw(88));
			assertEquals(Route.Facing.NORTH, Route.Facing.ofYaw(180));
			assertEquals(Route.Facing.EAST, Route.Facing.ofYaw(-90));
			// And a full turn is where it started, rather than off the end of the list.
			assertEquals(Route.Facing.SOUTH, Route.Facing.ofYaw(360));
			assertEquals(Route.Facing.SOUTH, Route.Facing.ofYaw(-360));
		}

		@Test
		@DisplayName("a route of places needs no anchor and says so")
		void placesNeedNothing() {
			assertTrue(ROUND.size() > 0 && !ROUND.needsAnchor());
			assertTrue(new Route(List.of(new Route.Point.Go(1, 0, 0)),
				Route.Gait.WALK, Route.STROLL, Route.From.CHARACTER).needsAnchor());
		}

		private int[][] walked(List<Route.Point> path, int x, int y, int z,
				Route.Facing facing) {
			int[][] where = new int[path.size()][];
			for (int i = 0; i < path.size(); i++) {
				where[i] = Route.world(path.get(i), x, y, z, facing);
			}
			return where;
		}
	}

	@Nested
	@DisplayName("a route in a conversation")
	class InAConversation {

		/**
		 * The shape somebody writes the first time they have a route node.
		 *
		 * Say a line, walk to the gate, say another. It is the plainest use of the
		 * thing and it produced a red message and a conversation thrown away, because
		 * the runtime refused any graph that stood still — on the grounds that nothing
		 * would come back and wake it. Something does; the character is ticked.
		 *
		 * What is checked here is the engine's half: that the conversation is left
		 * standing exactly where it was, with its bookmark on the route, so that
		 * stepping it again a tick later carries on rather than starting over.
		 */
		@Test
		@DisplayName("it parks on the route and keeps its place")
		void itKeepsItsPlace() {
			Dialogue graph = Dialogue.builder("errand")
				.start("hello")
				.add(new Node.Line("hello", "", "Wait here.",
					Presentation.SUBTITLE, null, "go"))
				.add(new Node.Walk("go", ROUND, "back"))
				.add(new Node.Line("back", "", "That is done.",
					Presentation.SUBTITLE, null, "over"))
				.add(new Node.End("over"))
				.build();

			Legs legs = new Legs();
			DialogueState mind = DialogueState.start(graph, Map.of());

			DialogueEngine.Step said = resume(graph, mind, legs);
			assertInstanceOf(DialogueEngine.Screen.Line.class, said.screen());

			// The player moves on from the line, and the graph reaches the route.
			DialogueEngine.Step step = DialogueEngine.step(graph, said.state(),
				new DialogueEngine.Input.Advance(), legs);
			legs.carryOut(step.effects());
			assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen());
			assertEquals("go", step.state().currentNode(),
				"the bookmark has to stay on the route, or coming back starts the graph again");

			// Ticked while she walks: nothing new is ordered and nothing is shown.
			step = resume(graph, step.state(), legs);
			assertEquals(List.of(), step.effects());
			assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen());

			// And when she gets there, the conversation carries on by itself.
			legs.arrive();
			step = resume(graph, step.state(), legs);
			DialogueEngine.Screen.Line line =
				assertInstanceOf(DialogueEngine.Screen.Line.class, step.screen());
			assertEquals("That is done.", line.text().plain());
		}
	}

	@Nested
	@DisplayName("the end of a graph")
	class TheEnd {

		@Test
		@DisplayName("an ending nobody has touched leaves her where she stands")
		void stayingIsTheDefault() {
			// Every graph written before homing existed ends this way, and quietly
			// teaching all of them to walk off would change scenes nobody has opened.
			Dialogue graph = Dialogue.builder("plain")
				.start("done").add(new Node.End("done")).build();

			assertEquals(List.of(),
				resume(graph, DialogueState.start(graph, Map.of()), new Legs()).effects());
		}

		@Test
		@DisplayName("an ending told to send her home orders it once, at the end")
		void walkingHome() {
			Dialogue graph = Dialogue.builder("errand")
				.start("done")
				.add(new Node.End("done", Node.Homing.WALK))
				.build();

			DialogueEngine.Step step =
				resume(graph, DialogueState.start(graph, Map.of()), new Legs());
			assertEquals(List.of(new Effect.GoHome(true)), step.effects());
			assertInstanceOf(DialogueEngine.Screen.Finished.class, step.screen());
		}

		@Test
		@DisplayName("and told to put her there, says so rather than saying walk")
		void teleportingHome() {
			Dialogue graph = Dialogue.builder("reset")
				.start("done")
				.add(new Node.End("done", Node.Homing.TELEPORT))
				.build();

			assertEquals(List.of(new Effect.GoHome(false)),
				resume(graph, DialogueState.start(graph, Map.of()), new Legs()).effects());
		}
	}

	@Nested
	@DisplayName("the shape of a route")
	class TheShape {

		@Test
		@DisplayName("a pace of nothing is refused, because it is a walk that never arrives")
		void paceHasAFloor() {
			assertEquals(Route.CRAWL, blank(0f).pace());
			assertEquals(Route.DASH, blank(5f).pace());
			assertEquals(Route.STROLL, blank(Float.NaN).pace());
		}

		@Test
		@DisplayName("standing at a point for a negative time is standing there for none")
		void stayHasAFloor() {
			assertEquals(0, new Route.Point.At(1, 2, 3, -40).stay());
			assertEquals(0, new Route.Point.Go(1, 2, 3, -40).stay());
		}

		@Test
		@DisplayName("a route longer than the cap is cut rather than refused")
		void tooManyPoints() {
			// Refusing would take the graph down over a file somebody edited by hand;
			// cutting leaves the rest of the map working and the route visibly short,
			// which is a thing anybody can see and fix.
			List<Route.Point> many = new java.util.ArrayList<>();
			for (int i = 0; i < Route.MOST + 10; i++) many.add(new Route.Point.At(i, 64, 0));
			assertEquals(Route.MOST,
				new Route(many, Route.Gait.WALK, Route.STROLL, Route.From.WORLD).size());
		}

		@Test
		@DisplayName("adding to a full route changes nothing rather than throwing")
		void addingWhenFull() {
			// Reached from a click in the world, where the two hundred and fifty-seventh
			// press must not take the editor down.
			List<Route.Point> many = new java.util.ArrayList<>();
			for (int i = 0; i < Route.MOST; i++) many.add(new Route.Point.At(i, 64, 0));
			Route full = new Route(many, Route.Gait.WALK, Route.STROLL, Route.From.WORLD);
			assertEquals(full, full.and(new Route.Point.At(1, 1, 1)));
		}

		@Test
		@DisplayName("a route with no points is a node the validator lets through")
		void anEmptyRouteIsLegal() {
			// A fresh route node has none — there is no point in the world it would be
			// less wrong to guess at — and a document is saved as it is being drawn. A
			// validator that refused one would mean the node could never be made.
			Dialogue graph = Dialogue.builder("blank")
				.start("go")
				.add(new Node.Walk("go", Route.NOWHERE, "done"))
				.add(new Node.End("done"))
				.build();

			assertTrue(DialogueValidator.validate(graph).ok());
		}
	}
}
