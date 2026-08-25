package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The engine running a graph that nobody is talking to.
 *
 * <h2>What these are really checking</h2>
 *
 * That there was no second engine. The claim behind the whole behaviour graph is
 * that the interpreter written for conversations never actually knew there was a
 * player — it only knew it sometimes had to stop — and that giving it other
 * reasons to stop is the entire difference. These are that claim, written down
 * so it stays true.
 */
class WaitingTest {

	/** A character who has nothing in her hands. */
	private static final Condition.World EMPTY_HANDED = (_, _) -> false;

	private static final Condition.World HOLDING = (_, _) -> true;

	private static DialogueEngine.Step resume(Dialogue graph, DialogueState state,
			Condition.World world) {
		return DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), world);
	}

	// ------------------------------------------------------------------ the timer

	private static Dialogue ticking() {
		return Dialogue.builder("ticking")
			.start("act")
			.add(new Node.Act("act", new Effect.PlayAnimation("wave", 40), "pause"))
			.add(new Node.Every("pause", 60, "act"))
			.build();
	}

	@Test
	@DisplayName("a graph with no player in it runs, does something, and parks")
	void itRunsWithoutAnybody() {
		Dialogue graph = ticking();
		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), EMPTY_HANDED);

		assertEquals(List.of(new Effect.PlayAnimation("wave", 40)), step.effects());
		DialogueEngine.Screen.Waiting waiting =
			assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen());
		assertEquals(60, waiting.ticks());
	}

	@Test
	@DisplayName("the bookmark is already past the timer, so coming back carries on")
	void aTimerNeedsNoStateOfItsOwn() {
		// This is the whole reason a timer costs nothing to save: the wait is served
		// by whoever is counting ticks, and the graph has already moved on. Were the
		// bookmark left on the timer, resuming would park again and the character
		// would wait for ever, one countdown at a time.
		Dialogue graph = ticking();
		DialogueEngine.Step first = resume(graph, DialogueState.start(graph, Map.of()), EMPTY_HANDED);
		assertEquals("act", first.state().currentNode());

		DialogueEngine.Step second = resume(graph, first.state(), EMPTY_HANDED);
		assertEquals(List.of(new Effect.PlayAnimation("wave", 40)), second.effects(),
			"it went round and waved again");
	}

	// ----------------------------------------------------------------- the question

	private static Dialogue watching() {
		return Dialogue.builder("watching")
			.start("dark")
			.add(new Node.Until("dark", new Condition.HasItem("minecraft:torch", 1), "wave"))
			.add(new Node.Act("wave", new Effect.PlayAnimation("wave", 40), "done"))
			.add(new Node.End("done"))
			.build();
	}

	@Test
	@DisplayName("a question that does not hold parks for a single tick")
	void waitingOnSomething() {
		Dialogue graph = watching();
		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), EMPTY_HANDED);

		assertEquals(List.of(), step.effects(), "she did nothing");
		assertEquals(0, assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen()).ticks(),
			"nought means ask again next tick");
		assertEquals("dark", step.state().currentNode(), "and the bookmark stayed on the question");
	}

	@Test
	@DisplayName("the same graph, the same bookmark, a different world — and she moves")
	void theQuestionIsAskedAgainEveryTime() {
		// The bookmark not moving is what makes this possible, and it is the
		// difference between `until` and `every`: one is a place to stand, the other
		// is a door to walk through.
		Dialogue graph = watching();
		DialogueState parked = resume(graph, DialogueState.start(graph, Map.of()), EMPTY_HANDED).state();

		DialogueEngine.Step step = resume(graph, parked, HOLDING);
		assertEquals(List.of(new Effect.PlayAnimation("wave", 40)), step.effects());
		assertInstanceOf(DialogueEngine.Screen.Finished.class, step.screen());
	}

	// -------------------------------------------------------------- the two halves

	@Test
	@DisplayName("a conversation still cannot be waited on, and says so by parking")
	void aDialogueThatWaitsIsVisible() {
		// The runtime on the other side refuses this rather than leaving a player
		// looking at a box that will never change. What is checked here is only that
		// the engine reports it plainly enough to be refused.
		Dialogue graph = Dialogue.builder("odd")
			.start("hold")
			.add(new Node.Every("hold", 20, "done"))
			.add(new Node.End("done"))
			.build();

		assertInstanceOf(DialogueEngine.Screen.Waiting.class,
			resume(graph, DialogueState.start(graph, Map.of()), EMPTY_HANDED).screen());
	}

	@Test
	@DisplayName("a graph that loops through a wait is not a freeze")
	void loopingThroughAWaitIsFine() {
		// The validator refuses a cycle that never waits, because that is a hung
		// server. A behaviour graph is a cycle by nature — a character goes on
		// living — and what saves it is that the cycle passes through something that
		// stops. That reasoning was written for `line` and holds unchanged for these.
		DialogueValidator.Report report = DialogueValidator.validate(ticking());
		assertTrue(report.ok(), "errors: " + report);
	}
}
