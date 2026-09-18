package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Putting a document's memory back, so a scene can be played a second time.
 *
 * <h2>What this is answering</h2>
 *
 * "I need to reset variables under certain conditions so I can play the dialogue
 * again." Half of that was always possible — writing false over a flag resets it — and
 * the tests here are mostly about the half that was not.
 *
 * That half is where the player has <em>been</em>. "Has this node been visited" is a
 * condition the language has, so a scene put back to the start by its variables alone
 * replays with some of its branches already decided, and which ones is invisible:
 * nothing anywhere shows the visited set. A reset that gets this wrong does not look
 * broken, it looks like a scene that mostly works.
 */
class ForgetTest {

	private static final Condition.World EMPTY_POCKETS = (item, count) -> false;

	/** An errand with a flag, a reset, and somewhere to have been. */
	private static Dialogue errand(Node.Forget reset) {
		return Dialogue.builder("errand")
			.variable("errand_sent", "flag")
			.variable("gold", "number")
			.start("sent")
			.add(new Node.Set("sent", "errand_sent", Scope.PLAYER, Value.of(true), "paid"))
			.add(new Node.Set("paid", "gold", Scope.PLAYER, Value.of(7), reset.id()))
			.add(reset)
			.add(new Node.End("end"))
			.build();
	}

	private static DialogueState after(Dialogue graph) {
		return DialogueEngine.step(graph, DialogueState.start(graph, Map.of()),
			new DialogueEngine.Input.Begin(), EMPTY_POCKETS).state();
	}

	@Test
	@DisplayName("an empty list means everything the document declares")
	void everythingIsTheOrdinaryCase() {
		// Both variables were written on the way here, and both are back at the default
		// of their declared type — not absent, which is a different thing the reader
		// would have to know about.
		DialogueState now = after(errand(new Node.Forget("reset", "end")));
		assertEquals(Value.of(false), now.get("errand_sent", Scope.PLAYER));
		assertEquals(Value.of(0), now.get("gold", Scope.PLAYER));
	}

	@Test
	@DisplayName("named variables leave the rest of the document's memory alone")
	void namingSomeKeepsTheOthers() {
		// The case the whole option exists for: start the errand again without
		// forgetting that the player was ever paid.
		DialogueState now = after(errand(new Node.Forget("reset", false,
			List.of("errand_sent"), Scope.PLAYER, true, "end")));
		assertEquals(Value.of(false), now.get("errand_sent", Scope.PLAYER));
		assertEquals(Value.of(7), now.get("gold", Scope.PLAYER));
	}

	@Test
	@DisplayName("where the player has been is forgotten too, and only for this document")
	void visitsGoWithTheVariables() {
		// The half no number of `set` nodes could do. And only this document's nodes:
		// the visited set belongs to the player and holds everywhere they have ever
		// been, so replaying one errand must not forget that they met the innkeeper.
		Dialogue graph = errand(new Node.Forget("reset", "end"));
		DialogueState began = new DialogueState("sent", Map.of(), Map.of(), Map.of(),
			Set.of("innkeeper_hello"), graph.variableTypes());
		DialogueState now = DialogueEngine.step(graph, began,
			new DialogueEngine.Input.Begin(), EMPTY_POCKETS).state();

		assertFalse(now.visited().contains("sent"), "this document's visits go");
		assertFalse(now.visited().contains("reset"), "including its own, deliberately");
		assertTrue(now.visited().contains("innkeeper_hello"),
			"somebody else's visits are not this node's to forget");
	}

	@Test
	@DisplayName("keeping the visits is a choice, and it keeps them")
	void visitsCanBeKept() {
		DialogueState now = after(errand(new Node.Forget("reset", false,
			List.of("errand_sent"), Scope.PLAYER, false, "end")));
		assertTrue(now.visited().contains("sent"));
	}

	@Test
	@DisplayName("a name this document does not declare is left alone rather than guessed at")
	void undeclaredNamesAreNotTouched() {
		// There is no declared type, so there is no default to put back — and writing a
		// flag over what another document keeps as a number would be this node breaking
		// a graph it cannot see. The validator names the node; the engine does nothing.
		Dialogue graph = errand(new Node.Forget("reset", false,
			List.of("errand_sent", "somebody_elses"), Scope.PLAYER, true, "end"));
		DialogueState began = new DialogueState("sent",
			Map.of("somebody_elses", Value.of("kept")), Map.of(), Map.of(), Set.of(),
			graph.variableTypes());

		DialogueState now = DialogueEngine.step(graph, began,
			new DialogueEngine.Input.Begin(), EMPTY_POCKETS).state();
		assertEquals(Value.of("kept"), now.get("somebody_elses", Scope.PLAYER));
	}

	@Test
	@DisplayName("the validator refuses a reset that names something undeclared")
	void undeclaredNamesAreRefusedAtTheDoor() {
		var report = DialogueValidator.validate(errand(new Node.Forget("reset", false,
			List.of("no_such_flag"), Scope.PLAYER, true, "end")));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("no_such_flag")),
			report.errors().toString());
	}

	@Test
	@DisplayName("naming nothing and keeping the visits is a node that does nothing, and is said so")
	void aResetThatDoesNothingIsWarnedAbout() {
		// This state exists only because "everything" is a field rather than an empty
		// list — which is the point of it being a field. Told apart, "put back none of
		// them" is a thing somebody can have said, and worth a word.
		Dialogue graph = errand(new Node.Forget("reset", false, List.of(), Scope.PLAYER,
			false, "end"));
		var report = DialogueValidator.validate(graph);
		// A half-finished edit rather than a broken graph. The difference matters:
		// the editor holds the save back on an error, and would strand somebody in the
		// middle of ticking the names they meant.
		assertTrue(report.ok(), "still runnable: " + report.errors());
		assertTrue(report.problems().stream()
			.anyMatch(problem -> problem.message().contains("puts nothing back")),
			report.problems().toString());
	}

	@Test
	@DisplayName("turning \"everything\" off does not throw away the names that were ticked")
	void theTickedNamesSurviveTheSwitch() {
		// The reason "everything" is not an empty list. Inferred from emptiness, this
		// node would have read as "all of them" the moment somebody unticked the last
		// name — a meaning changing because a list happened to empty, with no word said.
		Node.Forget both = new Node.Forget("reset", true, List.of("errand_sent"),
			Scope.PLAYER, true, "end");
		assertTrue(both.everything());
		assertEquals(List.of(), both.naming(), "while it means everything, it names none");
		assertEquals(List.of("errand_sent"), both.variables(), "and remembers the ticks");

		Node.Forget named = new Node.Forget("reset", false, both.variables(),
			Scope.PLAYER, true, "end");
		assertEquals(List.of("errand_sent"), named.naming());
	}

	@Test
	@DisplayName("a reset is one more node with one way out, so the graph still checks out")
	void itIsAnOrdinaryNodeOtherwise() {
		Node.Forget reset = new Node.Forget("reset", "end");
		assertEquals(List.of("end"), reset.exits());
		assertFalse(reset.waits());
		assertTrue(reset.everything());
		assertTrue(DialogueValidator.validate(errand(reset)).ok());
	}
}
