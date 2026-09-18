package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * Properties that are simply true while something holds.
 *
 * <h2>The distinction this is built on</h2>
 *
 * Everything else in the language is event-driven: something happens, a graph runs.
 * "Double jump while you are carrying the feather" is not an event — it has no occasion,
 * no order and no flow, so it is a table and not a graph. A branch would have implied an
 * order in which things are tried, and pretending in a language costs more than a gap
 * because the pretence gets maintained.
 *
 * <h2>The half this is not</h2>
 *
 * The switch, and only the switch. Spending energy on the jump, filling it back up over
 * time, forbidding it in the dungeon — all events, all graph. That was got wrong out loud
 * once, by offering the table as the answer to abilities when it is half of one, and the
 * larger half is the other.
 */
class StandingTest {

	private static Dialogue withRules(List<Standing> rules) {
		Dialogue base = Dialogue.builder("прыжок")
			.variable("перо", "flag")
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("никуда", "end"), base.areas(), base.manner(),
			base.places(),
			List.of(Trigger.onUse(new Trigger.Cause.UsedAny("minecraft:campfire"),
				new Condition.Always(), "никуда")),
			base.voices(), Dialogue.Kind.PLAYER, rules);
	}

	private static Standing jump() {
		return Standing.always("прыжок", "minecraft:jump_strength",
			Effect.Trait.How.TIMES_BASE, 0.5);
	}

	@Test
	@DisplayName("a property with no condition is on all the time, and says so shortly")
	void alwaysIsTheOrdinaryForm() {
		// The commonest shape, and the file says nothing about the condition when there
		// is nothing to say — writing out "always" for every one of them would be noise
		// in a file people read.
		Standing rule = jump();
		assertTrue(rule.when() instanceof Condition.Always);

		var written = DialogueCodecs.STANDING
			.encodeStart(JsonOps.INSTANCE, rule).getOrThrow(IllegalStateException::new);
		assertFalse(written.getAsJsonObject().has("when"),
			"nothing to say about the condition, so nothing said: " + written);
		assertEquals(rule, DialogueCodecs.STANDING.parse(JsonOps.INSTANCE, written)
			.getOrThrow(IllegalStateException::new));
	}

	@Test
	@DisplayName("a property gated on a flag survives the file with its condition")
	void aGatedPropertySurvives() {
		Standing gated = jump().whenever(new Condition.Compare("перо", Scope.PLAYER,
			Condition.Op.EQ, Value.of(true)));
		Dialogue graph = withRules(List.of(gated));

		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, graph).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(List.of(gated), back.standing());
	}

	@Test
	@DisplayName("a property with no name is refused, because nothing could ever take it off")
	void anUnnamedPropertyIsRefused() {
		// Worse here than for the verb that grants one: a standing rule applies itself to
		// everybody who ever meets its condition, and a modifier lives in the player's
		// own saved data with nothing in the game to show it.
		var report = DialogueValidator.validate(withRules(List.of(
			Standing.always("", "minecraft:jump_strength", Effect.Trait.How.ADD, 1))));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("nothing could ever take it off")),
			report.errors().toString());
	}

	@Test
	@DisplayName("two properties under one name are refused, because the second would win silently")
	void oneNameTwiceIsRefused() {
		// One handle is one modifier. The second replaces the first with no word said,
		// and the first simply never applies — which from outside is a property that was
		// written down and does nothing.
		var report = DialogueValidator.validate(withRules(List.of(jump(),
			Standing.always("прыжок", "minecraft:gravity", Effect.Trait.How.ADD, -0.02))));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("named twice")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a property that names no game property is refused")
	void anEmptyAttributeIsRefused() {
		var report = DialogueValidator.validate(withRules(List.of(
			Standing.always("прыжок", "", Effect.Trait.How.ADD, 1))));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("which property it gives")),
			report.errors().toString());
	}

	@Test
	@DisplayName("the variables a property reads are counted, so a misspelt one is caught")
	void gatingVariablesAreCounted() {
		// Left out of the count, a name gating an ability could be misspelt with nothing
		// to say so — and an ability that never turns on is exactly the silence the rest
		// of this is built to break.
		Dialogue graph = withRules(List.of(jump().whenever(
			new Condition.Compare("пiро", Scope.PLAYER, Condition.Op.EQ, Value.of(true)))));

		assertTrue(Variables.used(graph).stream()
			.anyMatch(use -> use.name().equals("пiро")), Variables.used(graph).toString());
		assertTrue(Variables.undeclared(graph).containsKey("пiро"),
			Variables.undeclared(graph).toString());
		assertFalse(DialogueValidator.validate(graph).ok(),
			"a misspelt name gating a property is a fault like any other");
	}

	@Test
	@DisplayName("a well-formed set of rules with properties is an ordinary document")
	void aProperOneIsFine() {
		Dialogue graph = withRules(List.of(jump().whenever(
			new Condition.Compare("перо", Scope.PLAYER, Condition.Op.EQ, Value.of(true)))));
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
		assertTrue(graph.isRules());
		assertEquals(1, graph.standing().size());
	}

	@Test
	@DisplayName("a document with no properties writes none, so old files do not grow a field")
	void noPropertiesMeansNoField() {
		Dialogue plain = Dialogue.builder("greeting")
			.start("end").add(new Node.End("end")).build();
		var written = DialogueCodecs.DIALOGUE
			.encodeStart(JsonOps.INSTANCE, plain).getOrThrow(IllegalStateException::new);
		assertFalse(written.getAsJsonObject().has("standing"),
			"a document with none says nothing: " + written);
	}
}
