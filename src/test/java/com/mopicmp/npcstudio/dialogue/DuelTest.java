package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A skill, a scenario, and the line between them.
 *
 * <h2>What is worth pinning down here</h2>
 *
 * That the two layers are actually separate. The scenario is the only part that
 * has heard of {@code kin}; the skill knows only {@code target}. If that ever
 * stops being true the skill quietly becomes a duel-only skill, and nobody
 * notices until somebody tries to reuse it and finds it fights the wrong person.
 *
 * The branching inside the fight is the other half: the distance at which
 * shooting stops being sensible, the distance at which hitting starts, and the
 * fact that neither needs a weapon for something to happen.
 */
class DuelTest {

	@BeforeEach
	void loadTheBuiltIns() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
	}

	private static Dialogue duel() {
		return com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get("duel").orElseThrow();
	}

	private static Condition.World feeling(Map<String, Value> readings) {
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public Value sense(String name) {
				return readings.getOrDefault(name, new Value.Flag(false));
			}
		};
	}

	private static DialogueEngine.Step resume(DialogueState state, Condition.World world) {
		return DialogueEngine.step(duel(), state, new DialogueEngine.Input.Begin(), world);
	}

	// ---------------------------------------------------------------- the scenario

	private static final Condition.World SOMEBODY =
		feeling(Map.of(Sense.KIN, new Value.Flag(true)));

	private static final Condition.World NOBODY = feeling(Map.of());

	@Test
	@DisplayName("alone, she does nothing and calls nothing")
	void nobodyThere() {
		DialogueEngine.Step step = resume(DialogueState.start(duel(), Map.of()), NOBODY);
		assertEquals(List.of(), step.effects());
		assertEquals(List.of(), step.calls());
	}

	@Test
	@DisplayName("finding one of her own kind sets the fight going, and names who")
	void engaging() {
		DialogueEngine.Step step = resume(DialogueState.start(duel(), Map.of()), SOMEBODY);

		assertEquals(List.of(), step.effects(),
			"the scenario does nothing with the body itself — it delegates");
		assertEquals(1, step.calls().size(), "one call: " + step.calls());
		DialogueEngine.Call call = step.calls().get(0);
		assertEquals("fight", call.segment());
		assertEquals(Mark.KIN, call.target(), "who is the scenario's own contribution");
		assertTrue(call.start());
	}

	@Test
	@DisplayName("the scenario keeps its turn while the fight runs, and can call it off")
	void theCallDoesNotBlock() {
		// This is the whole reason a call does not wait. A scenario that had been
		// swallowed by the fight could never notice that there was nobody left to
		// fight — and a conversation that started one could never reach its last
		// line.
		DialogueEngine.Step began = resume(DialogueState.start(duel(), Map.of()), SOMEBODY);
		assertTrue(began.screen() instanceof DialogueEngine.Screen.Waiting,
			"it went straight on to watching: " + began.screen());

		DialogueEngine.Step ended = resume(began.state(), NOBODY);
		assertEquals(1, ended.calls().size());
		assertEquals("fight", ended.calls().get(0).segment());
		assertTrue(!ended.calls().get(0).start(), "and this one is a cancellation");
	}

	@Test
	@DisplayName("the scenario is the only part that has heard of kin")
	void theLayersDoNotLeak() {
		// The skill is written about `target` and nothing else. Were it to mention
		// kin, it would fight the nearest of its own kind no matter who it was
		// called about — and it would go on passing this test's other cases while
		// being useless for a guard, a brawl or a bodyguard.
		Dialogue graph = duel();
		String fight = graph.segment("fight");
		assertTrue(fight != null, "the document has no segment called fight");

		for (String id : reachable(graph, fight)) {
			String text = graph.node(id).toString();
			assertTrue(!text.contains(Sense.KIN_DISTANCE) && !text.contains("mark=kin"),
				id + " mentions kin, which only the scenario may: " + text);
		}
	}

	private static java.util.Set<String> reachable(Dialogue graph, String from) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(from));
		seen.add(from);
		while (!queue.isEmpty()) {
			Node node = graph.node(queue.poll());
			if (node == null) continue;
			for (String exit : node.exits()) {
				if (exit != null && seen.add(exit)) queue.add(exit);
			}
		}
		return seen;
	}

	// ------------------------------------------------------------------ the skill

	/** The target this far off, with this in her hand. */
	private static Condition.World fighting(double away, String weapon) {
		return feeling(Map.of(
			Sense.TARGET, new Value.Flag(true),
			Sense.TARGET_DISTANCE, new Value.Num(away),
			Sense.WEAPON, new Value.Text(weapon)));
	}

	/** The skill, started the way a call starts it. */
	private static DialogueState begun() {
		Dialogue graph = duel();
		return DialogueState.beginning(graph, graph.segment("fight"),
			Map.of(), Map.of(), Map.of());
	}

	private static List<Effect> whatSheDoes(Condition.World world) {
		return resume(begun(), world).effects();
	}

	@Test
	@DisplayName("a bow and twenty blocks is a shot")
	void shootingFromAfar() {
		assertEquals(
			List.of(new Effect.LookAt(Mark.TARGET), new Effect.Fire(Mark.TARGET)),
			whatSheDoes(fighting(20, "drawn")),
			"facing has to come first: the arrow leaves along the body");
	}

	@Test
	@DisplayName("a bow at arm's length is a club held by the wrong end")
	void tooCloseToShoot() {
		assertTrue(whatSheDoes(fighting(2, "drawn")).contains(new Effect.Strike(Mark.TARGET)));
	}

	@Test
	@DisplayName("she stops before she swings, rather than walking through them")
	void stoppingFirst() {
		List<Effect> did = whatSheDoes(fighting(2, "melee"));
		assertEquals(new Effect.Halt(), did.get(0),
			"otherwise the two of them shuffle across the floor together: " + did);
		assertTrue(did.contains(new Effect.Strike(Mark.TARGET)));
	}

	@Test
	@DisplayName("out of reach and no bow means closing the distance, at a run")
	void closingIn() {
		assertEquals(List.of(new Effect.WalkTo(Mark.TARGET, 1f)), whatSheDoes(fighting(12, "melee")));
	}

	@Test
	@DisplayName("empty hands still start a fight")
	void unarmed() {
		// What makes this usable as a first check: nothing has to be handed out for
		// something to happen. A bare fist is a real attack and she has the attack
		// damage to make one.
		assertEquals(List.of(new Effect.WalkTo(Mark.TARGET, 1f)), whatSheDoes(fighting(9, "nothing")));
		assertTrue(whatSheDoes(fighting(1.5, "nothing")).contains(new Effect.Strike(Mark.TARGET)));
	}

	@Test
	@DisplayName("a rifle from a datapack is not fired, and does not stop the fight either")
	void theWeaponWeCannotUseYet() {
		// It reads as `swung`, which wants the swing rather than the trigger, and
		// whether swinging at empty air sets one off has not been tested. So she
		// falls through to closing and hitting rather than standing still.
		assertEquals(List.of(new Effect.WalkTo(Mark.TARGET, 1f)), whatSheDoes(fighting(20, "swung")));
	}

	@Test
	@DisplayName("the fight is not settled in one tick")
	void thereIsAPauseBetweenBlows() {
		var screen = resume(begun(), fighting(2, "melee")).screen();
		assertTrue(screen instanceof DialogueEngine.Screen.Waiting w && w.ticks() > 0,
			"she should be standing off for a moment, not looping: " + screen);
	}

	// ------------------------------------------------------------------ the words

	@Test
	@DisplayName("what a call hands in is read back, and cannot be written over")
	void givenIsReadOnly() {
		DialogueState told = DialogueState.beginning(duel(), duel().segment("fight"),
			Map.of(), Map.of(), Map.of("keep", new Value.Num(5)));

		assertEquals(new Value.Num(5), told.get("keep", Scope.GIVEN));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> told.with("keep", Scope.GIVEN, new Value.Num(1)));
	}

	@Test
	@DisplayName("a skill nobody called was told nothing, which is not an error")
	void nothingGivenReadsAsNothing() {
		assertEquals(new Value.Flag(false),
			DialogueState.start(duel(), Map.of()).get("keep", Scope.GIVEN));
	}

	@Test
	@DisplayName("every graph that ships with the mod still loads")
	void theBuiltInGraphsAreSound() {
		for (String name : List.of("duel", "sentry", "doorman", "torchbearer", "example")) {
			Dialogue graph = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get(name)
				.orElseThrow(() -> new AssertionError(name + " was refused: it would not load"));
			assertTrue(DialogueValidator.validate(graph).ok(),
				name + ": " + DialogueValidator.validate(graph));
		}
	}
}
