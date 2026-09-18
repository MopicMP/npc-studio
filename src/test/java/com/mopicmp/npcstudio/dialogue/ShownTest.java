package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a thing standing in the air is made of.
 *
 * The placeholder tests are the ones with a promise behind them: one hologram for everybody
 * today, with the text written through variables so that showing each person their own is a
 * change of who the values come from and not a change of the file format.
 */
class ShownTest {

	@Test
	@DisplayName("a name in braces is replaced by what it reads")
	void placeholdersFill() {
		Shown label = Shown.of("Level {level} — {name}");
		assertEquals("Level 3 — Mira",
			label.filled(Map.of("level", "3", "name", "Mira")).plain());
	}

	@Test
	@DisplayName("a name nothing answers for is left standing where anybody can see it")
	void unknownNamesStay() {
		// Blanking it would leave a gap on the wall that nobody can account for. Left
		// standing, the misspelt name is on the wall pointing at itself.
		assertEquals("Level {levl}",
			Shown.of("Level {levl}").filled(Map.of("level", "3")).plain());
	}

	@Test
	@DisplayName("a brace nobody closed is text like any other")
	void anUnclosedBraceIsText() {
		assertEquals("Half a {thing",
			Shown.of("Half a {thing").filled(Map.of("thing", "no")).plain());
	}

	@Test
	@DisplayName("the names it reads can be listed, so a document can be checked for them")
	void namesAreListed() {
		assertEquals(List.of("level", "name"), Shown.of("{level}: {name} ({level})").names());
	}

	@Test
	@DisplayName("only words have variables in them")
	void blocksHaveNoNames() {
		Shown block = new Shown(Shown.Kind.BLOCK,
			com.mopicmp.npcstudio.dialogue.text.Words.of("minecraft:{stone}"), 1.0f, false);
		assertTrue(block.names().isEmpty(),
			"a block id is an id; braces in one are a misspelt id rather than a variable");
	}

	@Test
	@DisplayName("a size is kept inside what can be drawn and pressed")
	void sizeIsClamped() {
		assertEquals(Shown.BIGGEST, Shown.of("x").looking(900f, false).size());
		assertEquals(1.0f, Shown.of("x").looking(Float.NaN, false).size());
	}

	@Test
	@DisplayName("no plaque is a background the game draws nothing for")
	void theBackgroundThreshold() {
		// Below an alpha of 0x1A the game draws no panel at all, which is what "words in
		// the air" means. Said here because it is the one number in this record that is
		// the game's rather than ours.
		assertTrue(((Shown.of("x").behind() >>> 24) & 0xFF) < 0x1A);
		assertTrue(((Shown.of("x").looking(1f, true).behind() >>> 24) & 0xFF) >= 0x1A);
	}

	@Test
	@DisplayName("the two settings that are only about words say so")
	void onlyWordsAreDressed() {
		assertTrue(Shown.of("x").isText());
		assertFalse(Shown.of("minecraft:bread").being(Shown.Kind.ITEM).isText());
	}

	@Test
	@DisplayName("it survives being written down and read back")
	void itRoundTrips() {
		Shown was = new Shown(Shown.Kind.ITEM,
			com.mopicmp.npcstudio.dialogue.text.Words.of("minecraft:bread"), 2.5f, true);
		var codec = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.SHOWN;
		var written = codec.encodeStart(JsonOps.INSTANCE, was).result().orElseThrow();
		assertEquals(was, codec.parse(JsonOps.INSTANCE, written).result().orElseThrow());
	}

	@Test
	@DisplayName("a file that says nothing about what it is gets words")
	void theDefaultIsWords() {
		var read = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.SHOWN.parse(JsonOps.INSTANCE,
			com.google.gson.JsonParser.parseString("{\"what\":\"hello\"}")).result().orElseThrow();
		assertEquals(Shown.Kind.TEXT, read.kind());
		assertEquals("hello", read.plain());
	}

	@Test
	@DisplayName("a kind spelled wrong comes out as words rather than refusing the file")
	void anUnknownKindIsWords() {
		// A whole document that will not load over one misspelt word in one node is a
		// worse afternoon than a hologram that came out as text and says so by looking
		// wrong the moment anybody walks past it.
		var read = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.SHOWN.parse(JsonOps.INSTANCE,
			com.google.gson.JsonParser.parseString("{\"kind\":\"bloc\",\"what\":\"x\"}"))
			.result().orElseThrow();
		assertEquals(Shown.Kind.TEXT, read.kind());
	}
}
