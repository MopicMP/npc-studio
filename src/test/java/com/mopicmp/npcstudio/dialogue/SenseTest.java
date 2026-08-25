package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a character perceives, and what she is allowed to remember.
 *
 * <h2>The two halves and why they are one step</h2>
 *
 * A window without a memory produces a character who reacts identically to the
 * hundredth arrival and the first. A memory without a window produces one who
 * remembers nothing worth remembering. Neither half is testable alone, which is
 * why they arrived together.
 */
class SenseTest {

	/** A world where every reading is whatever the test says it is. */
	private static Condition.World feeling(Map<String, Value> readings) {
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public Value sense(String name) {
				return readings.getOrDefault(name, new Value.Flag(false));
			}
		};
	}

	private static DialogueState blank() {
		return new DialogueState("here", Map.of(), Map.of(), Map.of(), java.util.Set.of(), Map.of());
	}

	// ---------------------------------------------------------------- the window

	@Test
	@DisplayName("a reading is compared with the operators the language already had")
	void readingsUseTheOrdinaryComparison() {
		Condition near = new Condition.Compare(Sense.PLAYER_DISTANCE, Scope.SENSE,
			Condition.Op.LT, Value.of(4));

		assertTrue(near.test(blank(), feeling(Map.of(Sense.PLAYER_DISTANCE, new Value.Num(2)))));
		assertFalse(near.test(blank(), feeling(Map.of(Sense.PLAYER_DISTANCE, new Value.Num(9)))));
	}

	@Test
	@DisplayName("a reading is asked of the world, never read off the bookmark")
	void aReadingIsNotAVariable() {
		// The point of the scope: nothing about `alarm` is stored anywhere, so a
		// bookmark carrying a stale copy of it cannot exist. Here the state is empty
		// and the answer still arrives.
		Condition afraid = new Condition.Compare(Sense.ALARM, Scope.SENSE,
			Condition.Op.GT, Value.of(0.5));
		assertTrue(afraid.test(blank(), feeling(Map.of(Sense.ALARM, new Value.Num(0.9)))));
	}

	@Test
	@DisplayName("a world with nobody in it senses nothing, rather than failing")
	void anEmptyWorldAnswersNo() {
		// A dialogue driven from a unit test is such a world, and so is any caller
		// written before senses existed.
		Condition.World nobody = (_, _) -> false;
		assertEquals(new Value.Flag(false), nobody.sense(Sense.ALARM));
	}

	// ---------------------------------------------------------------- read-only

	@Test
	@DisplayName("a sense cannot be written, even by a graph that got past the validator")
	void sensesAreReadOnly() {
		assertThrows(IllegalArgumentException.class,
			() -> blank().with(Sense.ALARM, Scope.SENSE, new Value.Num(1)));
	}

	@Test
	@DisplayName("the validator refuses a graph that writes to a sense")
	void writingToASenseIsRefusedUpFront() {
		Dialogue graph = Dialogue.builder("bossy")
			.start("lie")
			.add(new Node.Set("lie", Sense.ALARM, Scope.SENSE, Value.of(0), "done"))
			.add(new Node.End("done"))
			.build();

		assertFalse(DialogueValidator.validate(graph).ok());
	}

	@Test
	@DisplayName("a misspelt reading is caught before the graph ever runs")
	void typosAreCaught() {
		// Without this it reads as false for ever, and a character waiting on it
		// stands still — which looks exactly like a character whose brain is broken,
		// with nothing in the graph to point at.
		Dialogue graph = Dialogue.builder("typo")
			.start("wait")
			.add(new Node.Until("wait",
				new Condition.Compare("lead.strngth", Scope.SENSE, Condition.Op.GT, Value.of(0.5)),
				"done"))
			.add(new Node.End("done"))
			.build();

		assertFalse(DialogueValidator.validate(graph).ok(),
			"should have been refused: " + DialogueValidator.validate(graph));
	}

	@Test
	@DisplayName("comparing a word against a number is caught too")
	void theTypeOfAReadingIsChecked() {
		Dialogue graph = Dialogue.builder("muddled")
			.start("wait")
			.add(new Node.Until("wait",
				new Condition.Compare(Sense.MOOD, Scope.SENSE, Condition.Op.GT, Value.of(0.5)),
				"done"))
			.add(new Node.End("done"))
			.build();

		assertFalse(DialogueValidator.validate(graph).ok());
	}

	@Test
	@DisplayName("every name offered has a type, and every type belongs to a name")
	void theListAndTheTypesAgree() {
		for (String name : Sense.KNOWN) {
			assertEquals(true, Sense.typeOf(name) != null, name + " has no type");
		}
		assertEquals(null, Sense.typeOf("lead.strngth"));
	}

	// --------------------------------------------------------------- the memory

	@Test
	@DisplayName("what she knows about herself is kept apart from the rest")
	void characterVariablesAreTheirOwn() {
		DialogueState state = blank()
			.with("greeted", Scope.CHARACTER, Value.of(true))
			.with("greeted", Scope.PLAYER, Value.of(false));

		assertEquals(Value.of(true), state.get("greeted", Scope.CHARACTER));
		assertEquals(Value.of(false), state.get("greeted", Scope.PLAYER),
			"one name, two owners, and they must not be the same box");
	}

	@Test
	@DisplayName("a character can be started again knowing what she knew")
	void memoryOutlivesTheBookmark() {
		// This is the whole shape of it: where she had got to is dropped whenever
		// she is interrupted, and what she learnt is not.
		Dialogue graph = Dialogue.builder("rounds")
			.variable("seen", "flag")
			.start("go")
			.add(new Node.Every("go", 20, "go"))
			.build();

		DialogueState begun = DialogueState.start(graph, Map.of(),
			Map.of("seen", Value.of(true)));
		assertEquals("go", begun.currentNode(), "back at the beginning");
		assertEquals(Value.of(true), begun.get("seen", Scope.CHARACTER), "but not a stranger");
	}

	@Test
	@DisplayName("a graph can decide on a reading and remember what it decided")
	void theTwoHalvesTogether() {
		// The doorman, in miniature: greet somebody once, and know afterwards that
		// you have. Both scopes in one run, which is the thing neither half could
		// be shown doing on its own.
		Dialogue graph = Dialogue.builder("greeter")
			.variable("greeted", "flag")
			.start("near?")
			.add(new Node.Until("near?",
				new Condition.Compare(Sense.PLAYER_DISTANCE, Scope.SENSE,
					Condition.Op.LT, Value.of(4)), "known?"))
			.add(new Node.Branch("known?", List.of(new Node.Arm(
				new Condition.Compare("greeted", Scope.CHARACTER, Condition.Op.EQ, Value.of(true)),
				"nod")), "wave"))
			.add(new Node.Act("wave", new Effect.PlayAnimation("wave", 40), "learn"))
			.add(new Node.Set("learn", "greeted", Scope.CHARACTER, Value.of(true), "rest"))
			.add(new Node.Act("nod", new Effect.PlayAnimation("nod", 30), "rest"))
			.add(new Node.Every("rest", 40, "near?"))
			.build();

		Condition.World close = feeling(Map.of(Sense.PLAYER_DISTANCE, new Value.Num(2)));
		Condition.World far = feeling(Map.of(Sense.PLAYER_DISTANCE, new Value.Num(20)));

		DialogueState state = DialogueState.start(graph, Map.of());

		// Nobody there: she stands.
		DialogueEngine.Step step =
			DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), far);
		assertEquals(List.of(), step.effects());

		// Somebody arrives: a wave, and she learns.
		step = DialogueEngine.step(graph, step.state(), new DialogueEngine.Input.Begin(), close);
		assertEquals(List.of(new Effect.PlayAnimation("wave", 40)), step.effects());
		assertEquals(Value.of(true), step.state().get("greeted", Scope.CHARACTER));

		// They are still there: a nod, not a second greeting.
		step = DialogueEngine.step(graph, step.state(), new DialogueEngine.Input.Begin(), close);
		assertEquals(List.of(new Effect.PlayAnimation("nod", 30)), step.effects());
	}

	@Test
	@DisplayName("the graphs that ship with the mod are ones the validator accepts")
	void theBuiltInGraphsAreSound() {
		// They are built in java rather than loaded, so nothing else would ever
		// check them — and a built-in graph that is refused at startup is a mod
		// that looks broken to somebody who has written nothing yet.
		// The registry is filled when the mod starts, which has not happened here.
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();

		for (String name : List.of("doorman", "torchbearer")) {
			Dialogue graph = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get(name)
				.orElseThrow(() -> new AssertionError(name + " was refused: it would not load"));
			assertTrue(DialogueValidator.validate(graph).ok(),
				name + ": " + DialogueValidator.validate(graph));
		}
	}
}
