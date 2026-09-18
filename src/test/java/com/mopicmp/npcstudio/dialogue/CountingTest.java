package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counting things that happen in the world, which is what a task is.
 *
 * <h2>What this is answering</h2>
 *
 * "Light and put out a campfire three times." Asked as a question about how to
 * generalise it rather than how to special-case it, which is the right question: the
 * campfire is one setting of "something in the world happened, and it has happened
 * enough times now".
 *
 * Three things were missing and none of them is about campfires. The graph could not
 * look at a block; it could not tell a change from a state; and it could not count,
 * because writing a variable wrote a literal. The tests here are the first and the
 * third — the second lives in the runtime, where the world is.
 */
class CountingTest {

	/** A world where one place holds one block, and nothing else is anywhere. */
	private static Condition.World holding(String mark, String block) {
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public boolean blockAt(String asked, String wanted) {
				return asked.equals(mark) && wanted.equals(block);
			}
		};
	}

	/**
	 * A document whose place watches a block, and whose way in counts it.
	 *
	 * Put together by hand rather than through the builder, which has no word for a
	 * trigger — the same way {@code TriggerTest} does it, and for the same reason:
	 * writing one would be inventing an editor for a test.
	 */
	private static Dialogue watching(Trigger watch) {
		Dialogue base = Dialogue.builder("fire")
			.variable("lit", "number")
			.start("end")
			.add(new Node.Set("count", "lit", Scope.PLAYER, Value.of(1),
				Node.Set.Change.ADD, "end"))
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), base.start(), base.nodes(),
			base.variableTypes(), Map.of("считать", "count"), base.areas(), base.manner(),
			base.places(), List.of(watch));
	}

	@Test
	@DisplayName("adding to a number counts, and counts from where it was")
	void addingCounts() {
		// The whole of what was missing. Three lightings used to need three flags and
		// three branches, because this node could only write down a number somebody had
		// typed in the editor.
		Dialogue graph = Dialogue.builder("fire")
			.variable("lit", "number")
			.start("count")
			.add(new Node.Set("count", "lit", Scope.PLAYER, Value.of(1),
				Node.Set.Change.ADD, "end"))
			.add(new Node.End("end"))
			.build();

		DialogueState now = new DialogueState("count", Map.of("lit", Value.of(2)),
			Map.of(), Map.of(), java.util.Set.of(), graph.variableTypes());
		now = DialogueEngine.step(graph, now, new DialogueEngine.Input.Begin(),
			(item, count) -> false).state();
		assertEquals(Value.of(3), now.get("lit", Scope.PLAYER));
	}

	@Test
	@DisplayName("an unwritten number starts at nought rather than being absent")
	void addingToNothingStartsAtNought() {
		// So the first lighting counts. A variable that had to be put to nought before
		// it could be added to would be a step every task needed and every task would
		// eventually be missing.
		Dialogue graph = Dialogue.builder("fire")
			.variable("lit", "number")
			.start("count")
			.add(new Node.Set("count", "lit", Scope.PLAYER, Value.of(1),
				Node.Set.Change.ADD, "end"))
			.add(new Node.End("end"))
			.build();

		var after = DialogueEngine.step(graph, DialogueState.start(graph, Map.of()),
			new DialogueEngine.Input.Begin(), (item, count) -> false);
		assertEquals(Value.of(1), after.state().get("lit", Scope.PLAYER));
	}

	@Test
	@DisplayName("adding to something that is not a number is refused at the door")
	void addingToTextIsRefused() {
		// Joining text and counting flags are both things somebody could mean, and both
		// are things somebody could mean differently. Refused rather than guessed at.
		Dialogue graph = Dialogue.builder("fire")
			.variable("name", "text")
			.start("count")
			.add(new Node.Set("count", "name", Scope.PLAYER, Value.of("x"),
				Node.Set.Change.ADD, "end"))
			.add(new Node.End("end"))
			.build();

		var report = DialogueValidator.validate(graph);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("only numbers can be added to")),
			report.errors().toString());
	}

	@Test
	@DisplayName("and if one gets past, it changes nothing rather than inventing an answer")
	void addingToTextChangesNothing() {
		// A file written by hand can hold what the validator refuses. Leaving the value
		// alone is the answer that cannot be mistaken for having worked.
		Dialogue graph = Dialogue.builder("fire")
			.variable("name", "text")
			.start("count")
			.add(new Node.Set("count", "name", Scope.PLAYER, Value.of("x"),
				Node.Set.Change.ADD, "end"))
			.add(new Node.End("end"))
			.build();

		DialogueState now = new DialogueState("count", Map.of("name", Value.of("Anna")),
			Map.of(), Map.of(), java.util.Set.of(), graph.variableTypes());
		now = DialogueEngine.step(graph, now, new DialogueEngine.Input.Begin(),
			(item, count) -> false).state();
		assertEquals(Value.of("Anna"), now.get("name", Scope.PLAYER));
	}

	@Test
	@DisplayName("a block test asks the world and nothing else")
	void aBlockIsAskedOfTheWorld() {
		Condition lit = new Condition.Block("at:костёр", "minecraft:campfire[lit=true]");
		DialogueState nowhere = DialogueState.start(
			Dialogue.builder("x").start("e").add(new Node.End("e")).build(), Map.of());

		assertTrue(lit.test(nowhere, holding("at:костёр", "minecraft:campfire[lit=true]")));
		assertFalse(lit.test(nowhere, holding("at:костёр", "minecraft:campfire[lit=false]")));
		// A place the world has nothing at reads no, the same as a place nobody is near
		// enough to have loaded. Which is why a graph must not use this to decide
		// something that has to stay true while nobody is looking.
		assertFalse(lit.test(nowhere, holding("at:другое", "minecraft:campfire[lit=true]")));
		assertFalse(lit.test(nowhere, new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
		}));
	}

	@Test
	@DisplayName("a place a block test looks at counts as a place the document points at")
	void blockPlacesAreCollected() {
		// The one warning that catches a mistyped place name is built from this list —
		// see DialogueEditing.missingPlaces. Left out, the whole half of the language
		// that reads the world instead of walking into it went unchecked.
		Dialogue graph = Dialogue.builder("fire")
			.start("where")
			.add(new Node.Branch("where", List.of(new Node.Arm(
				new Condition.Not(new Condition.Block("at:костёр", "minecraft:campfire")),
				"end")), "end"))
			.add(new Node.End("end"))
			.build();

		assertTrue(graph.marksUsed().contains("at:костёр"), graph.marksUsed().toString());
		assertEquals(List.of("костёр"), Mark.placesIn(graph.marksUsed()));
	}

	@Test
	@DisplayName("a place watching a block is a place the document points at too")
	void watchedPlacesAreCollected() {
		Dialogue graph = watching(Trigger.onBlock("костёр", "minecraft:campfire[lit=true]",
			new Condition.Always(), "считать"));

		assertTrue(graph.marksUsed().contains("at:костёр"), graph.marksUsed().toString());
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("a place set to watch and told no block is refused")
	void watchingForNothingIsRefused() {
		Dialogue graph = watching(Trigger.onBlock("костёр", "", new Condition.Always(), "считать"));

		var report = DialogueValidator.validate(graph);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("does not say")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a watching trigger is not asked whether a box of that name exists")
	void aWatchIsNotABox() {
		// It is filed under a place, not a box, and the check for boxes would have
		// refused every one of them for not being drawn.
		Dialogue graph = watching(Trigger.onBlock("костёр", "minecraft:campfire[lit=true]",
			new Condition.Inside("поляна"), "считать"));

		assertTrue(DialogueValidator.validate(graph).errors().stream()
			.noneMatch(problem -> problem.message().contains("no box of that name")),
			DialogueValidator.validate(graph).toString());
	}
}
