package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The validator, checked against the mistakes it exists to catch.
 *
 * Each test writes a dialogue with one specific fault in it — the kind a map
 * maker actually makes — and asserts that the fault is named and located. A
 * validator that only says "invalid" would be worse than none: it would send
 * someone hunting through a hundred nodes by hand.
 *
 * The last group matters as much as the rest: a valid dialogue has to come back
 * clean. A checker that cries wolf gets ignored, and then the real problems go
 * unnoticed with it.
 */
class DialogueValidatorTest {

	private static Node.Line line(String id, String next) {
		return new Node.Line(id, "", "…", Presentation.SUBTITLE, null, next);
	}

	private static boolean mentions(DialogueValidator.Report report, String where, String fragment) {
		return report.problems().stream()
			.anyMatch(p -> java.util.Objects.equals(p.where(), where) && p.message().contains(fragment));
	}

	@Test
	@DisplayName("a transition to a node that does not exist is an error, and names it")
	void danglingExit() {
		Dialogue d = Dialogue.builder("d")
			.add(line("a", "typo_here"))
			.add(new Node.End("b"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(mentions(report, "a", "typo_here"), report.toString());
	}

	@Test
	@DisplayName("reading an undeclared variable is caught — that is a misspelt name")
	void undeclaredRead() {
		// Without this check the misspelt name would simply read as false forever,
		// and the branch would never be taken. Nothing would look broken.
		Dialogue d = Dialogue.builder("d")
			.variable("met", "flag")
			.add(new Node.Branch("check", List.of(new Node.Arm(
				new Condition.Compare("mett", Scope.PLAYER, Condition.Op.EQ, Value.of(true)), "a")), "a"))
			.add(line("a", "end"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(mentions(report, "check", "mett"), report.toString());
	}

	@Test
	@DisplayName("writing the wrong type into a variable is caught")
	void wrongTypeWritten() {
		Dialogue d = Dialogue.builder("d")
			.variable("gold", "number")
			.add(new Node.Set("s", "gold", Scope.PLAYER, Value.of("plenty"), "a"))
			.add(line("a", "end"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(mentions(report, "s", "declared as number"), report.toString());
	}

	@Test
	@DisplayName("comparing a number against text is caught before it silently fails")
	void comparisonThatCanNeverMatch() {
		Dialogue d = Dialogue.builder("d")
			.variable("gold", "number")
			.add(new Node.Choice("ask", "?", List.of(
				new Node.Option("Pay", null,
					new Condition.Compare("gold", Scope.PLAYER, Condition.Op.GE, Value.of("ten")), "a"),
				new Node.Option("Leave", "a"))))
			.add(line("a", "end"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(mentions(report, "ask", "can never match"), report.toString());
	}

	@Test
	@DisplayName("a loop with no waiting node is an error, and the cycle is spelled out")
	void freezeLoop() {
		Dialogue d = Dialogue.builder("d")
			.variable("n", "number")
			.add(new Node.Set("a", "n", Scope.PLAYER, Value.of(1), "b"))
			.add(new Node.Set("b", "n", Scope.PLAYER, Value.of(2), "a"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(report.toString().contains("without ever waiting"), report.toString());
		assertTrue(report.toString().contains("a -> b") || report.toString().contains("b -> a"),
			"the message should name the cycle: " + report);
	}

	@Test
	@DisplayName("a conversation that goes round through a choice is fine")
	void loopThroughAChoiceIsAllowed() {
		// Going back to a menu is ordinary writing, and must not be reported. The
		// difference from the previous test is only that the player can interrupt.
		Dialogue d = Dialogue.builder("d")
			.add(new Node.Choice("menu", "Anything else?", List.of(
				new Node.Option("Tell me about the war.", "war"),
				new Node.Option("Nothing.", "end"))))
			.add(line("war", "menu"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertTrue(report.ok(), report.toString());
	}

	@Test
	@DisplayName("a dialogue with no way to finish is an error")
	void noEndAtAll() {
		Dialogue d = Dialogue.builder("d")
			.add(line("a", "b"))
			.add(line("b", "a"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(report.toString().contains("no end node"), report.toString());
	}

	@Test
	@DisplayName("a branch that can never reach an end is an error")
	void deadEndBranch() {
		Dialogue d = Dialogue.builder("d")
			.add(new Node.Choice("ask", "?", List.of(
				new Node.Option("Out", "end"),
				new Node.Option("In", "trap"))))
			.add(line("trap", "trap2"))
			.add(line("trap2", "trap"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertFalse(report.ok());
		assertTrue(mentions(report, "trap", "ever reaches an end"), report.toString());
	}

	@Test
	@DisplayName("an unreachable node is a warning, not an error")
	void unreachableIsOnlyAWarning() {
		// Leftovers from a rewrite are untidy, not broken; the dialogue still runs.
		Dialogue d = Dialogue.builder("d")
			.add(line("a", "end"))
			.add(new Node.End("end"))
			.add(line("orphan", "end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertTrue(report.ok(), "an orphan must not stop the dialogue from running");
		assertTrue(mentions(report, "orphan", "cannot be reached"), report.toString());
	}

	@Test
	@DisplayName("a choice whose options are all conditional is warned about")
	void allOptionsConditional() {
		Dialogue d = Dialogue.builder("d")
			.add(new Node.Choice("ask", "?", List.of(
				new Node.Option("With a key", null, new Condition.HasItem("key", 1), "end"))))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertTrue(report.ok(), "it might still work, so it is not an error");
		assertTrue(mentions(report, "ask", "stuck"), report.toString());
	}

	@Test
	@DisplayName("a well-written dialogue comes back with nothing at all")
	void cleanDialogueIsSilent() {
		Dialogue d = Dialogue.builder("smith")
			.variable("met", "flag")
			.variable("gold", "number")
			.start("check")
			.add(new Node.Branch("check", List.of(new Node.Arm(
				new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ, Value.of(true)), "again")), "first"))
			.add(new Node.Line("first", "Smith", "New face.", Presentation.SUBTITLE, "wave", "remember"))
			.add(new Node.Set("remember", "met", Scope.PLAYER, Value.of(true), "menu"))
			.add(new Node.Line("again", "Smith", "Back again.", Presentation.SUBTITLE, null, "menu"))
			.add(new Node.Choice("menu", "What do you need?", List.of(
				new Node.Option("Repairs.", "cyan",
					new Condition.Compare("gold", Scope.PLAYER, Condition.Op.GE, Value.of(10)), "repair"),
				new Node.Option("Nothing.", "bye"))))
			.add(new Node.Line("repair", "Smith", "Hand it over.", Presentation.FULLSCREEN, null, "menu"))
			.add(new Node.Line("bye", "Smith", "Mind the step.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(d);

		assertEquals(List.of(), report.problems(), report.toString());
	}
}
