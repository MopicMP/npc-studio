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
 * Rules about a kind of thing, and what it takes for one stack to remember.
 *
 * <h2>The trap this is built around rather than into</h2>
 *
 * A stack has no identity that survives being handled: it copies, splits, merges, sits
 * in chests and burns in lava. "This sword remembers" is therefore a wish the game
 * cannot grant as stated, and a mod that pretended otherwise would hand somebody two
 * swords with one memory the first time they split a stack.
 *
 * The game solves it and has for years: things with insides do not stack. A shulker box
 * holds one; a stack that remembers is given a maximum stack size of one, using the
 * game's own component for the game's own reason. That turns a trap into a rule —
 * visible in the tooltip, and with no case left where memory quietly goes missing.
 *
 * This was very nearly not built at all. "It only bites the one person who wanted memory
 * on a stack, and you are not him" was the reasoning, and it was wrong twice: this is a
 * public mod, and having decided the answer was not needed I stopped looking for it. The
 * answer was in the game the whole time.
 */
class ThingTest {

	private static Dialogue about(Dialogue.Kind kind, Node node) {
		Dialogue base = Dialogue.builder("меч")
			.variable("убито", "number")
			.add(node)
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("ударили", node.id()), base.areas(), base.manner(),
			base.places(),
			List.of(Trigger.onItem("minecraft:iron_sword", new Condition.Always(), "ударили")),
			base.voices(), kind, List.of());
	}

	private static Node counting(Scope scope) {
		return new Node.Set("count", "убито", scope, Value.of(1), Node.Set.Change.ADD, "end");
	}

	@Test
	@DisplayName("an item is named by kind, because an item has no place")
	void anItemHasNoPlace() {
		// Everything else a rule can be about sits somewhere: a box, a marked point, a
		// block. An item is wherever somebody carried it, which is the whole point of
		// one — so it is named by kind and names no place at all.
		Trigger rule = Trigger.onItem("minecraft:flint_and_steel", new Condition.Always(), "x");
		assertTrue(rule.onItem());
		assertEquals("", rule.place());
		assertEquals("minecraft:flint_and_steel", rule.about());
		assertTrue(rule.isRule(), "using a thing runs now and is over, like every rule");
	}

	@Test
	@DisplayName("the stack's own memory is what \"the subject remembers\" means here")
	void theSubjectIsTheStack() {
		// The whole reason things are their own kind. In a document about the player
		// this scope is refused — it would be a second name for the player's own store —
		// and here it is a real and separate one that travels with the sword.
		Dialogue graph = about(Dialogue.Kind.THING, counting(Scope.CHARACTER));
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("and the same scope in a document about the player is still refused")
	void thePlayerKindStillRefusesIt() {
		// The two halves of one rule. Loosening it for things must not loosen it for
		// the player, where it is the fault this whole run of work started from.
		Dialogue graph = about(Dialogue.Kind.PLAYER, counting(Scope.CHARACTER));
		var report = DialogueValidator.validate(graph);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("say \"player\" instead")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a rule about any item whatever is refused")
	void aRuleAboutEveryItemIsRefused() {
		Dialogue base = about(Dialogue.Kind.THING, counting(Scope.CHARACTER));
		Dialogue everything = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(),
			List.of(Trigger.onItem("", new Condition.Always(), "ударили")),
			base.voices(), base.kind(), base.standing());

		var report = DialogueValidator.validate(everything);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("every click in the world")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a rule about an item survives the file")
	void itSurvivesTheFile() {
		Dialogue graph = about(Dialogue.Kind.THING, counting(Scope.CHARACTER));
		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, graph).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(graph.triggers(), back.triggers());
		assertEquals(Dialogue.Kind.THING, back.kind());
	}

	@Test
	@DisplayName("a document about things may still be about the player instead")
	void anItemMayAlsoBeMerelyTheOccasion() {
		// "Flint and steel lights things" is a rule about the kind, and the subject
		// there is nobody in particular — the item was the occasion, not the subject.
		// Which of the two it is comes from the document's kind, which is what the kind
		// is for.
		Dialogue graph = about(Dialogue.Kind.PLAYER, counting(Scope.PLAYER));
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
		assertTrue(graph.triggers().get(0).onItem());
	}
}
