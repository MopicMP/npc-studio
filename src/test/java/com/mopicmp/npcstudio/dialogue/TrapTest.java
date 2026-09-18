package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The trap, run as a conversation: walk on, cross the stretch, and be shut in.
 *
 * <h2>What this is proving</h2>
 *
 * That the whole thing is three ordinary boxes and no new machinery. Nothing here
 * knows what a trap is: {@code until} already knew how to stand still, {@code act}
 * already knew how to do a thing, and the two new words are a question and a verb
 * that drop into them. If this test needed a fourth kind of node to pass, the
 * design was wrong and the place to find that out is here.
 *
 * <h2>The faults it is watching for</h2>
 *
 * <ul>
 * <li>falling straight through the wait, which is what happens if a condition is
 *     read once and remembered rather than asked every tick;</li>
 * <li>raising the wall twice, or raising it before the player has crossed —
 *     either way the corridor seals in front of somebody who is still walking;</li>
 * <li>and dropping the wall by naming a different box from the one that raised
 *     it, which is the mistake that leaves an invisible wall in a corridor for
 *     ever and is the reason a box has a name at all.</li>
 * </ul>
 */
class TrapTest {

	/**
	 * A corridor with somebody walking down it.
	 *
	 * The world these nodes see is one fact — which boxes the player is standing in
	 * — and it changes between ticks, which is the whole point. A stub that could
	 * not change could only ever test the first tick, and the first tick is the one
	 * that works by accident.
	 */
	private static final class Corridor implements Condition.World {

		private final java.util.Set<String> standingIn = new java.util.LinkedHashSet<>();

		/** What has actually been built in the world, box by box. */
		private final java.util.Set<String> sealed = new java.util.LinkedHashSet<>();

		/** Every wall order ever given, so that a wall raised twice is visible. */
		private final List<String> orders = new ArrayList<>();

		void walkInto(String area) {
			standingIn.add(area);
		}

		void walkOutOf(String area) {
			standingIn.remove(area);
		}

		@Override public boolean hasItem(String item, int count) {
			return false;
		}

		@Override public boolean inside(String area) {
			return standingIn.contains(area);
		}

		/** What the world does when it is handed the orders that came out of a step. */
		void carryOut(List<Effect> effects) {
			for (Effect effect : effects) {
				if (!(effect instanceof Effect.Wall(String area, boolean up))) continue;
				orders.add((up ? "seal " : "open ") + area);
				if (up) sealed.add(area);
				else sealed.remove(area);
			}
		}
	}

	/**
	 * The graph the map actually wants.
	 *
	 * She says something, walks on, and the conversation stands still until the
	 * player has followed her past the doorway. Then both ends seal, she says the
	 * thing she brought them there to say, and the passage is given back.
	 */
	private static Dialogue corridorGraph() {
		Area anywhere = new Area(new Route.Point.Go(0, 0, 0),
			new Route.Point.Go(4, 2, 8), Route.From.CHARACTER);
		return Dialogue.builder("corridor")
			.area("stretch", anywhere)
			.area("behind", anywhere)
			.area("ahead", anywhere)
			.add(new Node.Line("call", "guard", "Follow me.", Presentation.SUBTITLE, null, "crossed"))
			.add(new Node.Until("crossed", new Condition.Inside("stretch"), "shutBehind"))
			.add(new Node.Act("shutBehind", new Effect.Wall("behind", true), "shutAhead"))
			.add(new Node.Act("shutAhead", new Effect.Wall("ahead", true), "speech"))
			.add(new Node.Line("speech", "guard", "Now we can talk.", Presentation.SUBTITLE, null, "openBehind"))
			.add(new Node.Act("openBehind", new Effect.Wall("behind", false), "openAhead"))
			.add(new Node.Act("openAhead", new Effect.Wall("ahead", false), "done"))
			.add(new Node.End("done"))
			.build();
	}

	/** One tick of the conversation: step it, and let the world carry out what came back. */
	private static DialogueEngine.Step tick(Dialogue graph, DialogueState state,
			Corridor world) {
		DialogueEngine.Step step =
			DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), world);
		world.carryOut(step.effects());
		return step;
	}

	/** Past whatever line is on the screen, which is a press of the key. */
	private static DialogueEngine.Step onward(Dialogue graph, DialogueState state,
			Corridor world) {
		DialogueEngine.Step step =
			DialogueEngine.step(graph, state, new DialogueEngine.Input.Advance(), world);
		world.carryOut(step.effects());
		return step;
	}

	@Nested
	@DisplayName("crossing the stretch")
	class Crossing {

		/** Opened, and past the line she calls them with, so the wait is what is left. */
		private DialogueState following(Dialogue graph, Corridor world) {
			DialogueState state = DialogueState.start(graph, java.util.Map.of());
			state = tick(graph, state, world).state();
			return onward(graph, state, world).state();
		}

		@Test
		@DisplayName("nothing is sealed while the player is still short of it")
		void waitsOutside() {
			Dialogue graph = corridorGraph();
			Corridor world = new Corridor();
			DialogueState state = following(graph, world);

			for (int i = 0; i < 40; i++) {
				DialogueEngine.Step step = tick(graph, state, world);
				state = step.state();
				assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen(),
					"still waiting on tick " + i);
			}

			// Two seconds of standing outside, and not one block has been placed. A
			// condition read once and remembered would have sealed the corridor on the
			// first tick, in front of somebody who had not moved.
			assertTrue(world.sealed.isEmpty(), "nothing built: " + world.sealed);
			assertTrue(world.orders.isEmpty(), "nothing ordered: " + world.orders);
		}

		@Test
		@DisplayName("stepping in seals both ends, once each")
		void sealsOnCrossing() {
			Dialogue graph = corridorGraph();
			Corridor world = new Corridor();
			DialogueState state = following(graph, world);

			state = tick(graph, state, world).state();
			world.walkInto("stretch");
			DialogueEngine.Step step = tick(graph, state, world);

			// Both walls up and the next line reached, in the one step the crossing
			// happened. Not two steps: an author watching this sees the corridor shut
			// and hears her speak together, which is the whole effect being built.
			assertEquals(java.util.Set.of("behind", "ahead"), world.sealed);
			assertEquals(List.of("seal behind", "seal ahead"), world.orders);
			assertInstanceOf(DialogueEngine.Screen.Line.class, step.screen());
		}

		@Test
		@DisplayName("standing there does not seal it a second time")
		void sealsOnlyOnce() {
			Dialogue graph = corridorGraph();
			Corridor world = new Corridor();
			DialogueState state = following(graph, world);
			world.walkInto("stretch");
			state = tick(graph, state, world).state();

			// She is talking now and the player is still inside the box. The wait is
			// behind the bookmark, so the condition is nobody's business any more — but
			// a graph that re-read it would order the wall again every tick, and every
			// order is a fresh pass over every block in it.
			for (int i = 0; i < 20; i++) {
				state = tick(graph, state, world).state();
			}

			assertEquals(List.of("seal behind", "seal ahead"), world.orders);
		}

		@Test
		@DisplayName("leaving the stretch again after it has sealed changes nothing")
		void leavingDoesNotUnseal() {
			Dialogue graph = corridorGraph();
			Corridor world = new Corridor();
			DialogueState state = following(graph, world);
			world.walkInto("stretch");
			state = tick(graph, state, world).state();

			// Which is the honest behaviour and worth pinning: the walls come down when
			// the graph says so, not when somebody backs out of the square that raised
			// them. A wall that opened as soon as the player stepped back would be a
			// trap anybody escapes by taking one step.
			world.walkOutOf("stretch");
			state = tick(graph, state, world).state();

			assertEquals(java.util.Set.of("behind", "ahead"), world.sealed);
		}
	}

	@Nested
	@DisplayName("letting them out")
	class Opening {

		@Test
		@DisplayName("the walls that went up are the walls that come down")
		void opensTheSameBoxes() {
			Dialogue graph = corridorGraph();
			Corridor world = new Corridor();
			DialogueState state = DialogueState.start(graph, java.util.Map.of());
			state = tick(graph, state, world).state();
			state = onward(graph, state, world).state();

			world.walkInto("stretch");
			state = tick(graph, state, world).state();

			// Past the line she brought them there to say, which is what leads to the
			// opening — the whole trap, walked from one end to the other.
			DialogueEngine.Step step = onward(graph, state, world);

			assertTrue(world.sealed.isEmpty(), "still standing: " + world.sealed);
			assertEquals(
				List.of("seal behind", "seal ahead", "open behind", "open ahead"),
				world.orders);
			assertInstanceOf(DialogueEngine.Screen.Finished.class, step.screen());
		}

		@Test
		@DisplayName("a name is what ties the two together")
		void namesTieThemTogether() {
			// The reason a box has a name at all. The node that opens a wall is not the
			// node that raised it — they are two boxes on the canvas, drawn apart — so
			// the only thing making them the same wall is the word in both of them.
			Dialogue graph = corridorGraph();

			Effect.Wall up = (Effect.Wall) ((Node.Act) graph.node("shutBehind")).effect();
			Effect.Wall down = (Effect.Wall) ((Node.Act) graph.node("openBehind")).effect();

			assertEquals(up.area(), down.area());
			assertTrue(up.up());
			assertFalse(down.up());
		}
	}

	@Nested
	@DisplayName("the question on its own")
	class TheQuestion {

		private DialogueState somewhere() {
			return DialogueState.start(
				Dialogue.builder("a").add(new Node.End("done")).build(), java.util.Map.of());
		}

		@Test
		@DisplayName("a world with nobody in it says the player is not in any box")
		void emptyWorldSaysNo() {
			// The default on the interface, and it is an answer rather than a stub: a
			// dialogue being tested without a map has nobody standing anywhere. It has
			// to be "no", because "yes" would spring every trap in a graph the moment
			// anybody ran it without a world.
			Condition.World nowhere = (item, count) -> false;
			assertFalse(new Condition.Inside("hall").test(somewhere(), nowhere));
		}

		@Test
		@DisplayName("a box that does not exist reads as being outside it")
		void unknownBoxIsOutside() {
			Corridor world = new Corridor();
			world.walkInto("hall");

			// Somebody deleted the box and left the node naming it. Not an error at run
			// time — the same choice a named place that has gone makes — because a
			// conversation stopping dead in front of a player is worse than a trap that
			// does not spring. The editor is where this gets said out loud.
			assertFalse(new Condition.Inside("gone").test(somewhere(), world));
			assertTrue(new Condition.Inside("hall").test(somewhere(), world));
		}

		@Test
		@DisplayName("it can be turned round, which is half of what it is for")
		void canBeNegated() {
			Corridor world = new Corridor();
			Condition stillOutside = new Condition.Not(new Condition.Inside("hall"));

			// "Walk on until they are no longer behind us" is as ordinary a thing to
			// write as the other way round, and it comes free by being a condition
			// rather than a node.
			assertTrue(stillOutside.test(somewhere(), world));
			world.walkInto("hall");
			assertFalse(stillOutside.test(somewhere(), world));
		}
	}
}
