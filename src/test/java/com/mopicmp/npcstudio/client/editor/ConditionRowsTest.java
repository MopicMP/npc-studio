package com.mopicmp.npcstudio.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueState;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;

/**
 * A condition read into rows and written back.
 *
 * <h2>What has to be true, and what deliberately does not</h2>
 *
 * The file may come out tidier than it went in — a single test no longer wrapped
 * in an "all" of one — so comparing the two conditions object for object would
 * fail on something nobody would call a bug.
 *
 * What must never change is the <b>answers</b>. So the round trip is checked by
 * asking both conditions the same questions across every combination of the
 * facts they mention, which is the property somebody actually relies on.
 *
 * And the other half: a condition too deep for rows must be refused rather than
 * flattened. Flattening loses what the author wrote without a word — the exact
 * shape of bug this project has met three times.
 */
class ConditionRowsTest {

	// ------------------------------------------------------------------ helpers

	private static Condition.Compare says(String name, boolean yes) {
		return new Condition.Compare(name, Scope.PLAYER, Condition.Op.EQ, Value.of(yes));
	}

	/** A state in which exactly these flags are true. */
	private static DialogueState believing(Map<String, Boolean> facts) {
		Dialogue graph = Dialogue.builder("t")
			.add(new Node.End("only"))
			.build();
		DialogueState state = DialogueState.start(graph, Map.of());
		for (var fact : facts.entrySet()) {
			state = state.with(fact.getKey(), Scope.PLAYER, Value.of(fact.getValue()));
		}
		return state;
	}

	private static final Condition.World NOWHERE = new Condition.World() {
		@Override public boolean hasItem(String item, int count) { return false; }
	};

	/**
	 * Whether two conditions answer alike, over every combination of these facts.
	 *
	 * Exhaustive rather than sampled: with a handful of flags there are only a few
	 * dozen worlds, and "it agreed on the ones I thought of" is how a rewriting
	 * bug survives its own test.
	 */
	private static void answersAlike(Condition was, Condition now, List<String> facts) {
		for (int mask = 0; mask < (1 << facts.size()); mask++) {
			Map<String, Boolean> world = new java.util.LinkedHashMap<>();
			for (int i = 0; i < facts.size(); i++) {
				world.put(facts.get(i), (mask & (1 << i)) != 0);
			}
			DialogueState state = believing(world);
			assertEquals(was.test(state, NOWHERE), now.test(state, NOWHERE),
				"they disagree when " + world);
		}
	}

	private static void survivesTheTrip(Condition condition, List<String> facts) {
		ConditionRows rows = ConditionRows.read(condition);
		assertTrue(rows != null, "should have been readable: " + condition);
		answersAlike(condition, rows.write(), facts);
	}

	// -------------------------------------------------------------- what fits

	@Test
	@DisplayName("nothing at all is true, and stays true")
	void emptyIsTrue() {
		assertEquals(ConditionRows.EMPTY, ConditionRows.read(new Condition.Always()));
		assertEquals(ConditionRows.EMPTY, ConditionRows.read(null));
		assertTrue(ConditionRows.EMPTY.write().test(believing(Map.of()), NOWHERE));
	}

	@Test
	@DisplayName("a single test is one row and comes back unwrapped")
	void oneTest() {
		Condition one = says("a", true);
		ConditionRows rows = ConditionRows.read(one);
		assertEquals(1, rows.rows().size());
		assertTrue(!rows.rows().get(0).not());
		assertEquals(one, rows.write(), "a simple condition should stay simple in the file");
	}

	@Test
	@DisplayName("a negated test is one row with the box ticked")
	void oneTestTurnedRound() {
		Condition one = new Condition.Not(says("a", true));
		ConditionRows rows = ConditionRows.read(one);
		assertEquals(1, rows.rows().size());
		assertTrue(rows.rows().get(0).not());
		assertEquals(one, rows.write());
	}

	@Test
	@DisplayName("all and any survive the trip, negations included")
	void groupsSurvive() {
		survivesTheTrip(new Condition.All(List.of(
			says("a", true), new Condition.Not(says("b", true)), says("c", true))),
			List.of("a", "b", "c"));

		survivesTheTrip(new Condition.Any(List.of(
			says("a", true), new Condition.Not(says("b", true)))),
			List.of("a", "b"));
	}

	@Test
	@DisplayName("an empty any is false, and does not quietly become true")
	void anEmptyAnyIsNotAnEmptyAll() {
		// Both are "no rows" on the panel and they mean opposite things. Writing
		// both out as `Always` would turn a branch that is never taken into one
		// that always is — the sort of change nobody would look for.
		ConditionRows none = new ConditionRows(false, List.of());
		assertTrue(!none.write().test(believing(Map.of()), NOWHERE));
		assertTrue(new ConditionRows(true, List.of()).write().test(believing(Map.of()), NOWHERE));
	}

	@Test
	@DisplayName("the other two leaves are rows as well")
	void itemsAndVisits() {
		assertTrue(ConditionRows.read(new Condition.HasItem("minecraft:stone", 1)) != null);
		assertTrue(ConditionRows.read(new Condition.Visited("somewhere")) != null);
	}

	// ---------------------------------------------------------- what does not

	@Test
	@DisplayName("anything nested deeper is refused rather than flattened")
	void tooDeepIsRefused() {
		// Flattening would lose the author's meaning without saying a word. Three
		// times now that has been the bug, and every time it looked like nothing
		// had happened at all.
		assertNull(ConditionRows.read(new Condition.All(List.of(
			says("a", true),
			new Condition.Any(List.of(says("b", true), says("c", true)))))),
			"an any inside an all is not a list of rows");

		assertNull(ConditionRows.read(new Condition.Not(
			new Condition.All(List.of(says("a", true))))),
			"a negated group is not a negated row");
	}

	// ------------------------------------------------------------ the real ones

	@Test
	@DisplayName("every condition in every shipped graph fits, or is honestly refused")
	void theShippedGraphs() {
		// Not an assertion that they all fit — some may not, and that is allowed.
		// What is checked is that the ones that do fit come back meaning the same,
		// because those are the ones somebody will open and save.
		DialogueRegistry.registerBuiltIn();
		int fitted = 0;
		for (String name : DialogueRegistry.names()) {
			Dialogue graph = DialogueRegistry.get(name).orElseThrow();
			for (Node node : graph.nodes().values()) {
				for (Condition condition : conditionsOf(node)) {
					ConditionRows rows = ConditionRows.read(condition);
					if (rows == null) continue;
					fitted++;
					List<Condition.VariableUse> uses = new java.util.ArrayList<>();
					condition.collectVariables(uses);
					answersAlike(condition, rows.write(),
						uses.stream().map(Condition.VariableUse::name).distinct().toList());
				}
			}
		}
		assertTrue(fitted > 0, "no conditions were found at all, so this proved nothing");
	}

	private static List<Condition> conditionsOf(Node node) {
		return switch (node) {
			case Node.Until until -> List.of(until.condition());
			case Node.Branch branch -> branch.arms().stream().map(Node.Arm::condition).toList();
			case Node.Choice choice -> choice.options().stream().map(Node.Option::condition).toList();
			default -> List.of();
		};
	}
}
