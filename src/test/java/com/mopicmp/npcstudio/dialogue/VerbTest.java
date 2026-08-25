package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four things a graph can tell a body to do.
 *
 * <h2>What is being tested, and what cannot be</h2>
 *
 * That the graph produces the right order at the right moment. Whether the
 * arrow then leaves the hand is the bow's business and was settled when the bow
 * was; whether the legs reach the door is the pathfinder's, and both are tested
 * where they live.
 *
 * The line between them is the point of the step: a verb is a decision on this
 * side and physics on the other, and if any arithmetic about walking or
 * shooting ever appears in a graph, it is on the wrong side of it.
 */
class VerbTest {

	private static Condition.World feeling(Map<String, Value> readings) {
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public Value sense(String name) {
				return readings.getOrDefault(name, new Value.Flag(false));
			}
		};
	}

	private static final Condition.World SEES_YOU = feeling(Map.of(
		Sense.LEAD_SEEN, new Value.Flag(true),
		Sense.LEAD_DISTANCE, new Value.Num(9)));

	private static final Condition.World SEES_NOTHING = feeling(Map.of());

	private static DialogueEngine.Step resume(Dialogue graph, DialogueState state,
			Condition.World world) {
		return DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), world);
	}

	// ----------------------------------------------------------------- shooting

	private static Dialogue sentry() {
		return com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get("sentry").orElseThrow();
	}

	@Test
	@DisplayName("she does not shoot at what she has only heard")
	void seenNotHeard() {
		// A character who fires at noises shoots through walls at a pig. The
		// distinction has been in perception since long before there was a graph;
		// this is the first thing that ever asked for it.
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
		Dialogue graph = sentry();

		Condition.World heardOnly = feeling(Map.of(
			Sense.LEAD_SEEN, new Value.Flag(false),
			Sense.LEAD_DISTANCE, new Value.Num(9)));

		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), heardOnly);
		assertEquals(List.of(), step.effects(), "she waited");
	}

	@Test
	@DisplayName("seeing somebody close enough produces an aim and a shot, in that order")
	void seeingSomebodyIsAShot() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
		Dialogue graph = sentry();

		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), SEES_YOU);
		assertEquals(
			List.of(new Effect.LookAt(Mark.LEAD), new Effect.Fire(Mark.LEAD)),
			step.effects(),
			"turning to face has to come before the shot: the arrow leaves along the body");
	}

	@Test
	@DisplayName("too far away is not a shot")
	void rangeIsTheGraphsToChoose() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
		Dialogue graph = sentry();

		Condition.World faraway = feeling(Map.of(
			Sense.LEAD_SEEN, new Value.Flag(true),
			Sense.LEAD_DISTANCE, new Value.Num(60)));
		assertEquals(List.of(), resume(graph, DialogueState.start(graph, Map.of()), faraway).effects());
	}

	@Test
	@DisplayName("losing sight of it lowers the bow rather than firing at nothing")
	void losingTheTarget() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
		Dialogue graph = sentry();

		// Aim, shoot, and park on the timer.
		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), SEES_YOU);
		// The timer runs out and she looks again — and this time there is nobody.
		step = resume(graph, step.state(), SEES_NOTHING);

		assertTrue(step.effects().contains(new Effect.LookAt(Mark.NOTHING)),
			"she let go: " + step.effects());
		assertFalse(step.effects().contains(new Effect.Fire(Mark.LEAD)));
	}

	@Test
	@DisplayName("still there means another shot, and only one")
	void shootingAgain() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
		Dialogue graph = sentry();

		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), SEES_YOU);
		step = resume(graph, step.state(), SEES_YOU);

		assertEquals(1, step.effects().stream().filter(e -> e instanceof Effect.Fire).count(),
			"one shot per pass round the loop: " + step.effects());
	}

	// ------------------------------------------------------------------- marks

	@Test
	@DisplayName("a verb pointed at nothing anybody has heard of is refused up front")
	void marksAreChecked() {
		// Without this it resolves to nothing, and a character told to walk to
		// nothing stands still — which from outside is a graph that does not work,
		// with nothing in it to point at.
		Dialogue graph = Dialogue.builder("lost")
			.start("go")
			.add(new Node.Act("go", new Effect.WalkTo("the pub", 1f), "done"))
			.add(new Node.End("done"))
			.build();

		assertFalse(DialogueValidator.validate(graph).ok());
	}

	@Test
	@DisplayName("every mark the editor offers is one the validator accepts")
	void theListsAgree() {
		for (String mark : Mark.KNOWN) {
			Dialogue graph = Dialogue.builder("pointing")
				.start("go")
				.add(new Node.Act("go", new Effect.LookAt(mark), "done"))
				.add(new Node.End("done"))
				.build();
			assertTrue(DialogueValidator.validate(graph).ok(),
				mark + ": " + DialogueValidator.validate(graph));
		}
	}

	// ------------------------------------------------------------------ walking

	@Test
	@DisplayName("a walk is ordered and waited for separately, so she can think on the way")
	void walkingDoesNotBlock() {
		// The alternative — a verb that blocks until she arrives — is a character
		// who cannot look at anything, change her mind, or notice she is being shot
		// at while crossing a courtyard.
		Dialogue graph = Dialogue.builder("errand")
			.start("off")
			.add(new Node.Act("off", new Effect.WalkTo(Mark.LEAD, 1f), "there?"))
			.add(new Node.Until("there?",
				new Condition.Compare(Sense.WALKING, Scope.SENSE, Condition.Op.EQ, Value.of(false)),
				"done"))
			.add(new Node.End("done"))
			.build();

		Condition.World onTheWay = feeling(Map.of(Sense.WALKING, new Value.Flag(true)));
		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()), onTheWay);

		assertEquals(List.of(new Effect.WalkTo(Mark.LEAD, 1f)), step.effects());
		assertTrue(step.screen() instanceof DialogueEngine.Screen.Waiting,
			"the order went out and the graph is waiting, not blocked inside it");

		DialogueEngine.Step arrived = resume(graph, step.state(), SEES_NOTHING);
		assertTrue(arrived.screen() instanceof DialogueEngine.Screen.Finished);
	}

	@Test
	@DisplayName("the graphs that ship with the mod still all load")
	void theBuiltInGraphsAreSound() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
		for (String name : List.of("sentry", "doorman", "torchbearer")) {
			Dialogue graph = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get(name)
				.orElseThrow(() -> new AssertionError(name + " was refused: it would not load"));
			assertTrue(DialogueValidator.validate(graph).ok(),
				name + ": " + DialogueValidator.validate(graph));
		}
	}
}
