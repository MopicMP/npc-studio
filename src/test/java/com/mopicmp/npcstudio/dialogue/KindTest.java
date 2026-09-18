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
 * What a document is for, and why it has to say so itself.
 *
 * <h2>The argument this settles</h2>
 *
 * A conversation says who it is about by being carried: a character holds the name of
 * her dialogue, a box starts a scene. A document about the player is carried by nobody —
 * so if it does not say what it is, nothing does, and it lives under a conversation's
 * name where nobody will find it.
 *
 * The counter-argument, which lost, was that four sorts of document means four languages
 * that drift apart. It lost because that is an argument against forking the <em>language</em>,
 * and this forks the <em>container</em>: the nodes, conditions, verbs, variables, editor
 * and validator are all still one. The day the kind starts meaning "and here the
 * conditions are different" is the day it was a mistake, and these tests are partly there
 * to make that day loud.
 */
class KindTest {

	private static Dialogue rules(Dialogue.Kind kind, List<Trigger> triggers) {
		Dialogue base = Dialogue.builder("прыжок")
			.variable("энергия", "number")
			.add(new Node.Act("give", new Effect.Trait("прыжок", "minecraft:jump_strength",
				Effect.Trait.How.TIMES_BASE, 0.5, true, Effect.Trait.Whose.PLAYER), "end"))
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("щёлкнули", "give"), base.areas(), base.manner(),
			base.places(), triggers, base.voices(), kind);
	}

	private static List<Trigger> oneRule() {
		return List.of(Trigger.onUse(new Trigger.Cause.UsedAny("minecraft:campfire"),
			new Condition.Always(), "щёлкнули"));
	}

	@Test
	@DisplayName("a document that says nothing is a conversation, as every old file is")
	void absentMeansAConversation() {
		// The whole of what makes this field safe to add to a format other people
		// already have maps in.
		Dialogue plain = Dialogue.builder("greeting")
			.start("end").add(new Node.End("end")).build();
		assertEquals(Dialogue.Kind.SCENE, plain.kind());
		assertFalse(plain.isRules());

		Dialogue read = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, plain).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(Dialogue.Kind.SCENE, read.kind());
	}

	@Test
	@DisplayName("a set of rules survives the file, because losing it makes it a conversation")
	void theKindSurvivesTheFile() {
		Dialogue graph = rules(Dialogue.Kind.PLAYER, oneRule());
		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, graph).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(Dialogue.Kind.PLAYER, back.kind());
		assertTrue(back.isRules());
	}

	@Test
	@DisplayName("a set of rules has no beginning, because nobody carries it")
	void rulesHaveNoStart() {
		// A start on one is a start nothing can ever reach: there is no character to
		// click and no box to walk into. Refused rather than left, because half a graph
		// that is unreachable looks exactly like a graph that works.
		Dialogue base = rules(Dialogue.Kind.PLAYER, oneRule());
		Dialogue withStart = new Dialogue(base.id(), base.formatVersion(), "give",
			base.nodes(), base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), base.triggers(), base.voices(), base.kind());

		var report = DialogueValidator.validate(withStart);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("nobody carries")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a set of rules with no triggers is a word, not a refusal")
	void rulesWithNoTriggersAreWarnedAbout() {
		// An ordinary half hour's work in progress. Refusing to save it would strand
		// somebody in the middle of writing one, and the editor holds the save back on
		// an error.
		var report = DialogueValidator.validate(rules(Dialogue.Kind.PLAYER, List.of()));
		assertTrue(report.ok(), "still saveable: " + report.errors());
		assertTrue(report.problems().stream()
			.anyMatch(problem -> problem.message().contains("nothing will ever set it off")),
			report.problems().toString());
	}

	@Test
	@DisplayName("the character's own memory is refused in a document about the player")
	void characterScopeIsRefusedInRules() {
		// "What the subject remembers" is the character's store in a conversation. In a
		// document about the player the subject is the player, so it would be a second
		// name for the store "player" already names — and two names for one store is a
		// variable written under one and read under the other, which is the fault this
		// whole run of work started from.
		Dialogue base = rules(Dialogue.Kind.PLAYER, oneRule());
		Dialogue reading = new Dialogue(base.id(), base.formatVersion(), base.start(),
			Map.of("give", new Node.Set("give", "энергия", Scope.CHARACTER, Value.of(1),
					"end"),
				"end", new Node.End("end")),
			base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), base.triggers(), base.voices(), base.kind());

		var report = DialogueValidator.validate(reading);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("say \"player\" instead")),
			report.errors().toString());

		// And the same graph as a conversation is perfectly ordinary, which is the half
		// that shows the rule is about the kind and not about the node.
		Dialogue asScene = new Dialogue(reading.id(), reading.formatVersion(), "give",
			reading.nodes(), reading.variableTypes(), reading.segments(), reading.areas(),
			reading.manner(), reading.places(), List.of(), reading.voices(),
			Dialogue.Kind.SCENE);
		assertTrue(DialogueValidator.validate(asScene).ok(),
			DialogueValidator.validate(asScene).toString());
	}

	@Test
	@DisplayName("rules about things are their own kind, filed apart from rules about the player")
	void thingsAreTheirOwnKind() {
		// Memory on a stack has to be a capability of something, and this is the
		// something. It is not built yet, so today this differs from PLAYER only in how
		// it is filed — and the name is here now because a name in a file format is the
		// expensive thing to change later.
		Dialogue graph = rules(Dialogue.Kind.THING, oneRule());
		assertTrue(graph.isRules());
		assertEquals(Dialogue.Kind.THING, graph.kind());
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("a conversation is still allowed to be a place as well")
	void aSceneMayStillBeBoth() {
		// Both on purpose, and this is the test that says so. A document may hold what a
		// character says when clicked and what happens when somebody walks into her room,
		// as separate threads through the same nodes — so a kind that split those would
		// have split something deliberately whole.
		Dialogue base = Dialogue.builder("inn")
			.start("greet")
			.add(new Node.Line("greet", "", "Evening.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("door", "", "The door creaks.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.area("порог", new Area(new Route.Point.At(10, 64, 10),
				new Route.Point.At(14, 66, 14), Route.From.WORLD))
			.build();
		Dialogue both = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), Map.of("входят", "door"), base.areas(),
			base.manner(), base.places(), List.of(Trigger.of("порог", "входят")),
			base.voices(), Dialogue.Kind.SCENE);

		assertFalse(both.isRules());
		assertTrue(both.hasStart());
		assertEquals(1, both.triggers().size());
		assertTrue(DialogueValidator.validate(both).ok(),
			DialogueValidator.validate(both).toString());
	}
}
