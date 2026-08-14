package com.mopicmp.npcstudio.dialogue.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading and writing dialogues, with no game running.
 *
 * The check that carries the most weight is the round trip: write a dialogue
 * out, read it back, and require the result to equal what went in. It catches
 * the whole family of quiet serialisation bugs at once — a field left out of
 * the codec, a default that swallows a real value, a list that loses its order.
 * None of those throw; they just come back subtly different.
 *
 * The rest are about the shape of the JSON, because a map maker types it by
 * hand and a hostile format costs more than a missing feature.
 */
class DialogueCodecsTest {

	private static JsonElement write(Dialogue dialogue) {
		DataResult<JsonElement> result = DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, dialogue);
		return result.getOrThrow(message -> new AssertionError("could not write: " + message));
	}

	private static Dialogue read(JsonElement json) {
		return DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE, json)
			.getOrThrow(message -> new AssertionError("could not read: " + message));
	}

	/** A dialogue using every node type, every condition and a few effects. */
	private static Dialogue everything() {
		return Dialogue.builder("everything")
			.variable("met", "flag")
			.variable("gold", "number")
			.variable("name", "text")
			.start("check")
			.add(new Node.Branch("check", List.of(
				new Node.Arm(new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ, Value.of(true)), "again"),
				new Node.Arm(new Condition.Not(new Condition.Visited("first")), "first")),
				"first"))
			.add(new Node.Line("first", "Smith", "New face.", Presentation.CUTSCENE, "wave", "remember"))
			.add(new Node.Set("remember", "met", Scope.PLAYER, Value.of(true), "world"))
			.add(new Node.Set("world", "gold", Scope.WORLD, Value.of(12.5), "again"))
			.add(new Node.Line("again", "Smith", "Back again.", Presentation.SUBTITLE, null, "menu"))
			.add(new Node.Choice("menu", "What do you need?", List.of(
				new Node.Option("Repairs.", "cyan", new Condition.All(List.of(
					new Condition.HasItem("minecraft:iron_ingot", 3),
					new Condition.Any(List.of(
						new Condition.Compare("gold", Scope.PLAYER, Condition.Op.GE, Value.of(10)),
						new Condition.Compare("name", Scope.PLAYER, Condition.Op.NE, Value.of("stranger")))))),
					"repair"),
				new Node.Option("Nothing.", "bye"))))
			.add(new Node.Act("repair", new Effect.GiveItem("minecraft:bread", 3), "sound"))
			.add(new Node.Act("sound", new Effect.PlaySound("minecraft:block.anvil.use", 0.8f, 1.2f), "build"))
			.add(new Node.Act("build", new Effect.PlaceStructure("village:forge", "npc", 40), "cmd"))
			.add(new Node.Act("cmd", new Effect.RunCommand("say the forge is up"), "wave"))
			.add(new Node.Act("wave", new Effect.PlayAnimation("wave", 63), "bye"))
			.add(new Node.Line("bye", "Smith", "Mind the step.", Presentation.FULLSCREEN, null, "end"))
			.add(new Node.End("end"))
			.build();
	}

	@Test
	@DisplayName("a dialogue written out and read back is the same dialogue")
	void roundTrip() {
		Dialogue original = everything();

		Dialogue returned = read(write(original));

		assertEquals(original.id(), returned.id());
		assertEquals(original.formatVersion(), returned.formatVersion());
		assertEquals(original.start(), returned.start());
		assertEquals(original.variableTypes(), returned.variableTypes());
		assertEquals(original.nodes(), returned.nodes());
	}

	@Test
	@DisplayName("the round trip survives a second pass, so nothing drifts")
	void roundTripIsStable() {
		// Once could be luck: a codec that loses a default on the way out and
		// invents it on the way in looks correct exactly once. Twice does not.
		JsonElement once = write(everything());
		JsonElement twice = write(read(once));

		assertEquals(once, twice);
	}

	/**
	 * A dialogue written before animations could be given a length still reads.
	 *
	 * And reads as what it used to do, which is the part worth a test. Absent
	 * means "hold it until something else takes over" — the behaviour those files
	 * were written against. Defaulting to something more sensible would have been
	 * tempting and would have changed how existing dialogues play, which is a
	 * worse failure than a field somebody has to fill in.
	 */
	@Test
	@DisplayName("an animation with no length written down keeps playing until told otherwise")
	void animationLengthIsOptional() {
		Dialogue old = read(JsonParser.parseString("""
			{
			  "id": "old",
			  "format": 1,
			  "start": "bow",
			  "nodes": [
			    {"type": "act", "id": "bow",
			     "effect": {"type": "play_animation", "animation": "bow"}, "next": "end"},
			    {"type": "end", "id": "end"}
			  ]
			}
			"""));

		assertEquals(new Effect.PlayAnimation("bow", 0), ((Node.Act) old.node("bow")).effect());
	}

	/**
	 * A face an author asked for survives being written down and read back.
	 *
	 * Worth its own case rather than trusting the codec: the effect is held as a
	 * <em>name</em> so that a file naming a face this version has never heard of
	 * still loads, and that only works if the name is what goes through the file.
	 */
	@Test
	void anExpressionSurvivesTheFile() {
		Dialogue read = read(JsonParser.parseString("""
			{
			  "id": "cross-face",
			  "format": 1,
			  "start": "cross",
			  "nodes": [
			    {"type": "act", "id": "cross",
			     "effect": {"type": "express", "expression": "ANGRY", "ticks": 40}, "next": "end"},
			    {"type": "end", "id": "end"}
			  ]
			}
			"""));

		assertEquals(new Effect.Express("ANGRY", 40), ((Node.Act) read.node("cross")).effect());
	}

	/** And one with no length written on it is held until something changes it. */
	@Test
	void anExpressionWithoutALengthIsHeld() {
		Dialogue read = read(JsonParser.parseString("""
			{
			  "id": "smile-face",
			  "format": 1,
			  "start": "smile",
			  "nodes": [
			    {"type": "act", "id": "smile",
			     "effect": {"type": "express", "expression": "HAPPY"}, "next": "end"},
			    {"type": "end", "id": "end"}
			  ]
			}
			"""));

		assertEquals(new Effect.Express("HAPPY", 0), ((Node.Act) read.node("smile")).effect());
	}

	@Test
	@DisplayName("a value is written as itself, not wrapped in a type")
	void valuesAreWrittenPlainly() {
		// Nobody hand-writing a dialogue should have to say {"type":"number"}.
		assertEquals(JsonParser.parseString("10.0"),
			DialogueCodecs.VALUE.encodeStart(JsonOps.INSTANCE, Value.of(10)).getOrThrow());
		assertEquals(JsonParser.parseString("\"hello\""),
			DialogueCodecs.VALUE.encodeStart(JsonOps.INSTANCE, Value.of("hello")).getOrThrow());
		assertEquals(JsonParser.parseString("true"),
			DialogueCodecs.VALUE.encodeStart(JsonOps.INSTANCE, Value.of(true)).getOrThrow());
	}

	@Test
	@DisplayName("each kind of value reads back as its own kind")
	void valuesKeepTheirType() {
		assertEquals(Value.of(true), DialogueCodecs.VALUE.parse(JsonOps.INSTANCE,
			JsonParser.parseString("true")).getOrThrow());
		assertEquals(Value.of(7.0), DialogueCodecs.VALUE.parse(JsonOps.INSTANCE,
			JsonParser.parseString("7")).getOrThrow());
		assertEquals(Value.of("7"), DialogueCodecs.VALUE.parse(JsonOps.INSTANCE,
			JsonParser.parseString("\"7\"")).getOrThrow());
	}

	@Test
	@DisplayName("a dialogue can be written by hand without ceremony")
	void handWrittenIsAccepted() {
		// Everything optional is left out. If this ever needs more than it says
		// here, the format has grown a tax on the person using it.
		Dialogue dialogue = read(JsonParser.parseString("""
			{
			  "id": "hello",
			  "format": 1,
			  "start": "greet",
			  "nodes": [
			    { "type": "line", "id": "greet", "text": "Good evening.", "next": "done" },
			    { "type": "end", "id": "done" }
			  ]
			}
			"""));

		assertEquals("hello", dialogue.id());
		assertTrue(DialogueValidator.validate(dialogue).ok(), "and it should be a valid dialogue");

		Node.Line line = (Node.Line) dialogue.node("greet");
		assertEquals("", line.speaker());
		assertEquals(Presentation.SUBTITLE, line.mode(), "a line is a subtitle unless it says otherwise");
	}

	@Test
	@DisplayName("nodes keep the order they were written in")
	void nodeOrderIsPreserved() {
		// They are written as a list rather than an object for this reason: a
		// writer reading their own file wants their own order back.
		Dialogue returned = read(write(everything()));

		assertEquals(List.copyOf(everything().nodes().keySet()), List.copyOf(returned.nodes().keySet()));
	}

	@Test
	@DisplayName("an unknown node type is refused by name")
	void unknownTypeIsNamed() {
		DataResult<Dialogue> result = DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE,
			JsonParser.parseString("""
				{
				  "id": "broken", "format": 1, "start": "a",
				  "nodes": [ { "type": "monologue", "id": "a" } ]
				}
				"""));

		assertTrue(result.isError());
		assertTrue(result.error().orElseThrow().message().contains("monologue"),
			"the message should name the offending type: " + result.error().orElseThrow().message());
	}

	@Test
	@DisplayName("a file with no format version is refused rather than guessed at")
	void formatVersionIsRequired() {
		// Guessing the version of a file we cannot account for is how a migration
		// corrupts data instead of declining to touch it.
		DataResult<Dialogue> result = DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE,
			JsonParser.parseString("""
				{
				  "id": "old", "start": "a",
				  "nodes": [ { "type": "end", "id": "a" } ]
				}
				"""));

		assertTrue(result.isError());
	}

	@Test
	@DisplayName("a misspelt scope says what was allowed")
	void enumErrorsAreHelpful() {
		DataResult<Scope> result = DialogueCodecs.SCOPE.parse(JsonOps.INSTANCE,
			JsonParser.parseString("\"planet\""));

		assertTrue(result.isError());
		String message = result.error().orElseThrow().message();
		assertTrue(message.contains("player") && message.contains("world"),
			"the message should list the choices: " + message);
	}

	@Test
	@DisplayName("nested conditions survive the trip")
	void conditionsNest() {
		Condition original = new Condition.Not(new Condition.All(List.of(
			new Condition.Any(List.of(new Condition.Visited("a"), new Condition.Visited("b"))),
			new Condition.HasItem("minecraft:emerald", 5))));

		JsonElement json = DialogueCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
		Condition returned = DialogueCodecs.CONDITION.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(original, returned);
	}

	@Test
	@DisplayName("a dialogue that reads back is still checked by the validator")
	void loadedDialoguesAreStillValidated() {
		// Being readable and being runnable are different things, and only the
		// second one matters to the player standing in front of the NPC.
		Dialogue dialogue = read(JsonParser.parseString("""
			{
			  "id": "dead_end", "format": 1, "start": "a",
			  "nodes": [ { "type": "line", "id": "a", "text": "…", "next": "nowhere" } ]
			}
			"""));

		assertFalse(DialogueValidator.validate(dialogue).ok());
	}
}
