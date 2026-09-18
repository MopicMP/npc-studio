package com.mopicmp.npcstudio.dialogue.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * A decorated line on its way to a file and back.
 *
 * <h2>The check that matters is the boring one</h2>
 *
 * Not that a colour survives — that a line with no colour comes back as a
 * <em>bare string</em>. Every dialogue written before any of this existed is such
 * a line, and if the writer quietly started spelling them as arrays of one run,
 * nothing would break, nothing would look wrong, and every file in the mod would
 * be rewritten the first time it was opened.
 *
 * That is the shape of failure this whole file is arranged against, and it is
 * invisible unless something says it out loud.
 */
class WordsCodecTest {

	private static String write(Words words) {
		return WordsCodec.WORDS.encodeStart(JsonOps.INSTANCE, words).getOrThrow().toString();
	}

	private static Words read(String json) {
		JsonElement parsed = JsonParser.parseString(json);
		return WordsCodec.WORDS.parse(JsonOps.INSTANCE, parsed).getOrThrow();
	}

	@Test
	@DisplayName("a plain line is written as the string it always was")
	void plainStaysAString() {
		assertEquals("\"Привет.\"", write(Words.of("Привет.")));
		assertEquals("\"\"", write(Words.EMPTY));
	}

	@Test
	@DisplayName("a plain string is read as a line")
	void aStringReadsAsALine() {
		Words read = read("\"Привет.\"");
		assertEquals("Привет.", read.plain());
		assertTrue(read.isPlain());
	}

	@Test
	@DisplayName("colour, faces, font and size all come back")
	void everythingSurvives() {
		Words written = new Words(List.of(
			new Words.Run("Ты ", Look.PLAIN),
			new Words.Run("не вернёшься", new Look(0xE57373, true, true, true, true,
				"npc_studio:cinzel", 1.5f))));

		assertEquals(written, read(write(written)));
	}

	@Test
	@DisplayName("only what was chosen is written down")
	void defaultsAreNotSpelledOut() {
		// A run that says nothing beyond its words has to write as its words. Left to
		// spell out every default, a two-word line would come out as forty characters
		// of false, and the format stops being one anybody writes by hand.
		String json = write(new Words(List.of(
			new Words.Run("да", Look.PLAIN.withColour(0x66BB6A)))));

		assertEquals("[{\"text\":\"да\",\"color\":\"#66bb6a\"}]", json);
	}

	@Test
	@DisplayName("a colour reads and writes as six hexadecimal digits")
	void coloursAreHex() {
		assertEquals(0x000000, read("[{\"text\":\"a\",\"color\":\"#000000\"}]")
			.runs().get(0).look().colour());
		assertEquals(0xFFFFFF, read("[{\"text\":\"a\",\"color\":\"#ffffff\"}]")
			.runs().get(0).look().colour());

		// Black is a colour somebody chose and must not come back as "no colour":
		// nought is a perfectly ordinary value and only a null means unchosen.
		assertEquals("[{\"text\":\"a\",\"color\":\"#000000\"}]",
			write(new Words(List.of(new Words.Run("a", Look.PLAIN.withColour(0))))));
	}

	@Test
	@DisplayName("a colour that is not one is refused, and the message says what one is")
	void badColoursAreRefused() {
		var failed = WordsCodec.WORDS.parse(JsonOps.INSTANCE,
			JsonParser.parseString("[{\"text\":\"a\",\"color\":\"red\"}]"));

		assertTrue(failed.isError());
		assertTrue(failed.error().orElseThrow().message().contains("#e57373"),
			"the complaint has to show the shape it wanted: " + failed.error().orElseThrow().message());
	}

	@Test
	@DisplayName("runs welded on the way in stay welded on the way out")
	void weldingSurvivesTheFile() {
		// A file written by hand may well spell out three runs that draw the same. It
		// must not come back as three, or opening and saving would preserve a seam
		// that the editor itself would never have created.
		Words read = read("[{\"text\":\"a\"},{\"text\":\"b\"},{\"text\":\"c\"}]");

		assertEquals("abc", read.plain());
		assertEquals(1, read.runs().size());
		assertEquals("\"abc\"", write(read), "and it is plain, so it goes back to being a string");
	}

	@Test
	@DisplayName("a size that is not a size does not become one")
	void impossibleSizes() {
		assertEquals(Look.ORDINARY,
			read("[{\"text\":\"a\",\"size\":0.0}]").runs().get(0).look().size());
		assertEquals(Look.ORDINARY,
			read("[{\"text\":\"a\",\"size\":-2.0}]").runs().get(0).look().size());
	}
}
