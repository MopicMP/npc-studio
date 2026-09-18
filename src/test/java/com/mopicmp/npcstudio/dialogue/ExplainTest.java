package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Saying what a branch decided, with the values that were in it.
 *
 * <h2>What this is answering</h2>
 *
 * "I worked with the branch, declared the variables, everything looks right, and
 * something breaks and I cannot tell what." That is a graph running correctly and doing
 * something other than intended: nothing is wrong, so nothing complains, and the only
 * symptom is a scene going the wrong way.
 *
 * The account has to be right about two things, and the tests are both of them: the
 * actual values, and the <b>order</b>. Order is what people get wrong about branches —
 * the first arm that holds takes it — so an arm that is true and never reached is a real
 * fault and an invisible one.
 */
class ExplainTest {

	/** A world that answers nothing, which is every question a branch of variables asks. */
	private static final Condition.World NOWHERE = new Condition.World() {
		@Override public boolean hasItem(String item, int count) { return false; }
		@Override public boolean inside(String area) { return false; }
		@Override public Value sense(String name) { return Value.of(0); }
		@Override public int roll(int sides) { return 0; }
	};

	private static DialogueState holding(Map<String, Value> player) {
		return new DialogueState("where", player, Map.of(), Map.of(), java.util.Set.of(), Map.of());
	}

	@Test
	@DisplayName("a comparison is written with the value that was in it, not only the wanted one")
	void theActualValueIsSaid() {
		// The whole point. "errand_sent must be true" is what the author already knows;
		// "errand_sent is false" is what they do not.
		String said = Explain.say(
			new Condition.Compare("errand_sent", Scope.PLAYER, Condition.Op.EQ, Value.of(true)),
			holding(Map.of()), NOWHERE);
		assertTrue(said.contains("errand_sent"), said);
		assertTrue(said.contains("= false"), "the value it actually holds: " + said);
		assertTrue(said.contains("wanted eq true"), said);
	}

	@Test
	@DisplayName("a comparison of two types says so, and only when they disagree")
	void mismatchedTypesAreNamed() {
		// The comparison that is quietly false for ever: the branch is never taken, the
		// validator has nothing to say once both are declared, and from outside it looks
		// like the mod is broken.
		String wrong = Explain.say(
			new Condition.Compare("gold", Scope.PLAYER, Condition.Op.EQ, Value.of("three")),
			holding(Map.of("gold", Value.of(3))), NOWHERE);
		assertTrue(wrong.contains("number against text"), wrong);

		String fine = Explain.say(
			new Condition.Compare("gold", Scope.PLAYER, Condition.Op.EQ, Value.of(3)),
			holding(Map.of("gold", Value.of(3))), NOWHERE);
		assertFalse(fine.contains("against"), "no noise on a working line: " + fine);
	}

	@Test
	@DisplayName("an arm that is true but never reached is named as such")
	void theOrderIsTheWholePoint() {
		// Two arms that both hold. The first takes it; the second is true and does
		// nothing, which is invisible in the editor and is the fault people actually hit.
		Node.Branch branch = new Node.Branch("where", List.of(
			new Node.Arm(new Condition.Always(), "first"),
			new Node.Arm(new Condition.Always(), "second")), "otherwise");

		var told = Explain.arms(branch, holding(Map.of()), NOWHERE);
		assertEquals(3, told.size(), "every arm, and the otherwise");
		assertTrue(told.get(0).text().contains("[yes]"), told.get(0).text());
		assertTrue(told.get(1).text().contains("too late"), told.get(1).text());
		assertFalse(told.get(2).text().contains("taken"), "otherwise was not reached");
	}

	@Test
	@DisplayName("when nothing holds, the account says the otherwise was taken")
	void otherwiseIsSaidOutLoud() {
		Node.Branch branch = new Node.Branch("where", List.of(
			new Node.Arm(new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ,
				Value.of(true)), "again")), "first");

		var told = Explain.arms(branch, holding(Map.of()), NOWHERE);
		assertTrue(told.get(0).text().contains("[no]"), told.get(0).text());
		assertTrue(told.get(1).text().contains("[taken]"), told.get(1).text());
	}

	@Test
	@DisplayName("a declared name nothing has written shows its default rather than being absent")
	void unwrittenVariablesAreStillListed() {
		// The whole question in "the branch did not fire": the name is there, and it
		// holds false because nobody wrote to it. Absent from the list, it would read as
		// a typo somewhere and send the search off in the wrong direction.
		Dialogue graph = Dialogue.builder("doc")
			.variable("errand_sent", "flag")
			.start("end").add(new Node.End("end")).build();

		var said = Explain.variables(graph, holding(Map.of()));
		assertEquals(1, said.size());
		assertTrue(said.get(0).startsWith("errand_sent:"), said.get(0));
		assertTrue(said.get(0).contains("player false"), said.get(0));
		// Every scope, because "set in the wrong scope" looks exactly like "not set at
		// all" from the branch that reads it.
		assertTrue(said.get(0).contains("character"), said.get(0));
	}

	@Test
	@DisplayName("text is quoted and numbers are not, so three and \"three\" are told apart")
	void valuesAreShownAsTheyWouldBeWritten() {
		assertEquals("\"three\"", Explain.show(Value.of("three")));
		assertEquals("3", Explain.show(Value.of(3)));
		assertEquals("true", Explain.show(Value.of(true)));
		assertEquals("2.5", Explain.show(Value.of(2.5)));
	}
}
