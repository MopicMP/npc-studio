package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * Giving somebody a property they did not have.
 *
 * <h2>What this is answering</h2>
 *
 * "How do I give the player properties they have not got?" — which sounded like a piece
 * of work per ability and turned out to be one verb for nearly all of it: the game keeps
 * forty of them, applies them on the server, and synchronises them itself.
 *
 * The tests here are about the half that is <em>not</em> free, and it is all one thing:
 * a property given is a permanent, invisible change to somebody's saved data. Nothing in
 * the game shows it, and it survives a restart. So everything about being able to find it
 * again and take it off is worth a test, and the amount is not.
 */
class TraitTest {

	private static Dialogue giving(Effect.Trait trait) {
		return Dialogue.builder("boon")
			.start("give")
			.add(new Node.Act("give", trait, "end"))
			.add(new Node.End("end"))
			.build();
	}

	private static Effect.Trait jump(String name) {
		return new Effect.Trait(name, "minecraft:jump_strength",
			Effect.Trait.How.TIMES_BASE, 0.5, true, Effect.Trait.Whose.PLAYER);
	}

	@Test
	@DisplayName("a property given under no name is refused, because nothing could take it back")
	void anUnnamedTraitIsRefused() {
		// The whole reason the name is asked for. A modifier goes on under an id and
		// comes off by the same id; with no name there is no id anybody could find
		// again, and what is left is a permanent invisible change to a player with no
		// way back. Same shape as the wall left standing.
		var report = DialogueValidator.validate(giving(jump("")));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("nothing could ever take it back")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a property given without saying which one is refused")
	void anEmptyAttributeIsRefused() {
		var report = DialogueValidator.validate(giving(
			new Effect.Trait("прыжок", "", Effect.Trait.How.ADD, 1, true,
				Effect.Trait.Whose.PLAYER)));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("without saying which one")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a named property with a real attribute is an ordinary graph")
	void aProperOneIsFine() {
		assertTrue(DialogueValidator.validate(giving(jump("прыжок"))).ok(),
			DialogueValidator.validate(giving(jump("прыжок"))).toString());
	}

	@Test
	@DisplayName("turning it off keeps the amount, so it can be turned back on")
	void turningOffKeepsTheNumber() {
		// A node that had to be emptied to be switched off is a node that cannot be
		// switched back on. The same shape as a wall, which knows its box whether it is
		// up or down.
		Effect.Trait off = jump("прыжок").turned(false);
		assertFalse(off.on());
		assertEquals(0.5, off.amount());
		assertEquals("minecraft:jump_strength", off.attribute());
		assertEquals(Effect.Trait.How.TIMES_BASE, off.how());
		assertTrue(off.turned(true).on());
	}

	@Test
	@DisplayName("an unwritten file reads as the commonest answer rather than as nothing")
	void theShortFormMeansTheOrdinaryThing() {
		// Only the name and the attribute are required. A hand-written file saying just
		// those two means "add nought to the player, on" — which is a working node
		// somebody can then fill in, not a node that refuses to load.
		String written = "{\"type\":\"trait\",\"name\":\"прыжок\","
			+ "\"attribute\":\"minecraft:jump_strength\"}";
		Effect read = DialogueCodecs.EFFECT
			.parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(written))
			.getOrThrow(IllegalStateException::new);

		assertEquals(new Effect.Trait("прыжок", "minecraft:jump_strength",
			Effect.Trait.How.ADD, 0.0, true, Effect.Trait.Whose.PLAYER), read);
	}

	@Test
	@DisplayName("a blank name and a blank attribute cannot make a nonsense record")
	void theRecordTidiesItsOwnFields() {
		// Nulls arrive from a hand-written file and from a half-built node in the
		// editor. Neither should be able to reach the runtime, where a null name would
		// become an id nobody can spell.
		Effect.Trait bare = new Effect.Trait(null, null, null, 0, true, null);
		assertEquals("", bare.name());
		assertEquals("", bare.attribute());
		assertEquals(Effect.Trait.How.ADD, bare.how());
		assertEquals(Effect.Trait.Whose.PLAYER, bare.whose());
	}

	@Test
	@DisplayName("a document knows every property it hands out, so a replay can take them off")
	void everyTraitCanBeFound() {
		// What "play it again" reads to clear them. A property left on from the last run
		// would make the second run start with every boon of the first still applied,
		// which is exactly not what playing it again means.
		Dialogue graph = Dialogue.builder("boons")
			.start("one")
			.add(new Node.Act("one", jump("прыжок"), "two"))
			.add(new Node.Act("two", new Effect.Trait("шаг", "minecraft:step_height",
				Effect.Trait.How.ADD, 1, true, Effect.Trait.Whose.PLAYER), "three"))
			.add(new Node.Act("three", new Effect.GiveItem("minecraft:bread", 1), "end"))
			.add(new Node.End("end"))
			.build();

		var named = graph.nodes().values().stream()
			.filter(node -> node instanceof Node.Act)
			.map(node -> ((Node.Act) node).effect())
			.filter(effect -> effect instanceof Effect.Trait)
			.map(effect -> ((Effect.Trait) effect).name())
			.toList();
		assertEquals(java.util.List.of("прыжок", "шаг"), named);
	}

	@Test
	@DisplayName("a verb about a person does not stop a graph being valid without one")
	void itIsAnOrdinaryVerb() {
		// It is applied to whoever the thread is about, and that is decided at run time.
		// A document holding one is not thereby a document that needs a character.
		Dialogue graph = giving(jump("прыжок"));
		assertTrue(DialogueValidator.validate(graph).ok());
		assertEquals(Map.of(), graph.variableTypes());
	}
}
