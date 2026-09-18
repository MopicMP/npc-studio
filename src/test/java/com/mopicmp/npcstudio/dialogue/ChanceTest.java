package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fork the graph decides for itself.
 *
 * Asked for as "this character says one of four lines", and built as a way of
 * choosing rather than as a property of speech — so what is tested here is the
 * choosing, and the lines are only what happens to be on the other side of it.
 */
class ChanceTest {

	/** A world whose every throw is the number it was told, and nothing else. */
	private static Condition.World throwing(int roll) {
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public int roll(int sides) { return roll; }
		};
	}

	private static Dialogue fourGreetings() {
		return Dialogue.builder("guard")
			.start("pick")
			.add(new Node.Chance("pick", List.of("one", "two", "three", "four")))
			.add(new Node.Line("one", "Guard", "Move along.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("two", "Guard", "Nothing to see.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("three", "Guard", "Quiet today.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("four", "Guard", "Watch yourself.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}

	/** A bookmark at the start of a graph, with nothing else known. */
	private static DialogueState at(Dialogue graph) {
		return new DialogueState(graph.start(), java.util.Map.of(), java.util.Map.of(),
			java.util.Map.of(), java.util.Set.of(), graph.variableTypes());
	}

	private static String spokenWith(int roll) {
		Dialogue graph = fourGreetings();
		var step = DialogueEngine.step(graph, at(graph),
			new DialogueEngine.Input.Begin(), throwing(roll));
		return ((DialogueEngine.Screen.Line) step.screen()).text().plain();
	}

	@Test
	@DisplayName("each throw reaches its own way, and all four are reachable")
	void everyWayIsReachable() {
		assertEquals("Move along.", spokenWith(0));
		assertEquals("Nothing to see.", spokenWith(1));
		assertEquals("Quiet today.", spokenWith(2));
		assertEquals("Watch yourself.", spokenWith(3));
	}

	@Test
	@DisplayName("a throw outside the ways still lands on one of them")
	void nothingFallsOffTheEnd() {
		// The engine hands the throw the number of ways as its bound, so this should
		// never arise from the runtime. It is guarded anyway because the world is an
		// interface anybody may implement, and the failure it would otherwise cause is
		// an exception in the middle of somebody's scene rather than a dull greeting.
		assertEquals("Move along.", spokenWith(4));
		assertEquals("Watch yourself.", spokenWith(-1));
	}

	@Test
	@DisplayName("a world that has never heard of a throw takes the first way")
	void theDefaultIsDullAndNotBroken() {
		// Which is what every test written before this existed hands in, and what a
		// dialogue exercised without a world hands in. Answering nought is a scene
		// that always goes the same way — legible, and not a crash.
		Dialogue graph = fourGreetings();
		var step = DialogueEngine.step(graph, at(graph),
			new DialogueEngine.Input.Begin(), (item, count) -> false);
		assertEquals("Move along.", ((DialogueEngine.Screen.Line) step.screen()).text().plain());
	}

	@Test
	@DisplayName("a fork is a fork: no ways is refused, one way is warned about")
	void theValidatorHasSomethingToSay() {
		Dialogue empty = Dialogue.builder("broken")
			.start("pick")
			.add(new Node.Chance("pick", List.of()))
			.add(new Node.End("end"))
			.build();
		assertFalse(DialogueValidator.validate(empty).ok(),
			"a fork with no ways out is a node the engine walks into and cannot leave");

		Dialogue single = Dialogue.builder("pointless")
			.start("pick")
			.add(new Node.Chance("pick", List.of("end")))
			.add(new Node.End("end"))
			.build();
		var report = DialogueValidator.validate(single);
		assertTrue(report.ok(), "one way is not broken, only pointless");
		assertTrue(report.toString().contains("never decides anything"),
			"but it is said out loud, because it is nearly always unfinished: " + report);
	}

	@Test
	@DisplayName("the ways are the node's exits, so the validator and the canvas see them")
	void waysAreExits() {
		// Not a formality. Everything that walks a graph — the reachability check, the
		// freeze check, the wires on the canvas — asks a node for its exits, and a kind
		// that does not answer is a kind all of those quietly skip.
		Node.Chance fork = new Node.Chance("pick", List.of("one", "two", "three", "four"));
		assertEquals(List.of("one", "two", "three", "four"), fork.exits());
		assertFalse(fork.waits(), "a throw is instant; a graph that stopped here would hang");
	}
}
