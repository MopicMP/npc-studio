package com.mopicmp.npcstudio.dialogue.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The form a decorated line is stored in.
 *
 * <h2>What is worth protecting</h2>
 *
 * That the same-looking text is the same object. Runs are welded and blanks
 * dropped on construction, so a word typed and deleted again leaves no seam —
 * and without that, every edit would add one, and a file would slowly fill with
 * boundaries nobody put there and nobody can see.
 *
 * And that the flat form and the run form describe the same text in both
 * directions. The editor lives in the flat form and the file lives in runs; a
 * conversion that is right one way and nearly right the other shows up as
 * colours shifted by one character, in a line nobody was editing at the time.
 */
class WordsTest {

	private static final Look RED = Look.PLAIN.withColour(0xE57373);
	private static final Look BIG = Look.PLAIN.withSize(2f);

	@Test
	@DisplayName("neighbours that draw the same are one run")
	void weldsNeighbours() {
		Words words = new Words(List.of(
			new Words.Run("Ты ", Look.PLAIN),
			new Words.Run("не ", Look.PLAIN),
			new Words.Run("вернёшься", RED)));

		assertEquals(2, words.runs().size(), "found " + words.runs());
		assertEquals("Ты не ", words.runs().get(0).text());
		assertEquals("Ты не вернёшься", words.plain());
	}

	@Test
	@DisplayName("a run with no words is not a run")
	void dropsBlanks() {
		Words words = new Words(List.of(
			new Words.Run("", RED),
			new Words.Run("да", Look.PLAIN),
			new Words.Run("", BIG)));

		assertEquals(1, words.runs().size(), "found " + words.runs());
		assertEquals("да", words.plain());
		assertTrue(words.isPlain(), "an empty coloured run must not make the line decorated");
	}

	@Test
	@DisplayName("a look meaning nothing in two spellings is one look")
	void looksAreNormalised() {
		// The reason this matters is welding: if a blank font read as different from
		// no font, a line would split into two runs that draw identically, and the
		// file would grow a seam out of nowhere.
		assertEquals(Look.PLAIN, Look.PLAIN.withFont(""));
		assertEquals(Look.PLAIN, Look.PLAIN.withSize(0f));
		assertEquals(Look.PLAIN, Look.PLAIN.withSize(-3f));
		assertEquals(Look.PLAIN, Look.PLAIN.withSize(Float.POSITIVE_INFINITY));
		assertTrue(Look.PLAIN.withSize(Float.NaN).isPlain(),
			"a size that is not a number is not a size");
	}

	@Test
	@DisplayName("flat and runs describe the same text both ways")
	void roundTripThroughTheFlatForm() {
		Words words = new Words(List.of(
			new Words.Run("Ты ", Look.PLAIN),
			new Words.Run("не вернёшься", RED)));

		assertEquals(words, Words.of(words.plain(), words.looks()));

		List<Look> looks = words.looks();
		assertEquals(words.plain().length(), looks.size());
		assertEquals(Look.PLAIN, looks.get(0));
		assertEquals(RED, looks.get(3));
	}

	@Test
	@DisplayName("a character outside the basic plane is one thing with one look")
	void codePointsNotChars() {
		// Two chars in memory, one character on the screen. Counted as two, it could
		// be styled apart — and half a surrogate pair is not a character at all.
		String emoji = "a🙂b";
		Words words = Words.of(emoji);

		assertEquals(3, words.length());
		assertEquals(3, words.looks().size());
		assertEquals(words, Words.of(emoji, words.looks()));

		Words painted = Words.of(emoji, List.of(Look.PLAIN, RED, Look.PLAIN));
		assertEquals(3, painted.runs().size(), "found " + painted.runs());
		assertEquals("🙂", painted.runs().get(1).text(), "the pair stayed whole");
	}

	@Test
	@DisplayName("the flat form is checked against the text rather than trusted")
	void countsMustAgree() {
		// An edit that updated the letters and forgot the looks would otherwise show
		// up much later, as colours off by one, in a line nobody was editing.
		assertThrows(IllegalArgumentException.class,
			() -> Words.of("абв", List.of(Look.PLAIN, RED)));
		assertThrows(IllegalArgumentException.class,
			() -> Words.of("аб", List.of(Look.PLAIN, RED, RED)));
		assertThrows(IllegalArgumentException.class, () -> Words.of("аб", null));
	}

	@Test
	@DisplayName("nothing at all is a line too")
	void emptiness() {
		assertTrue(Words.of("").isEmpty());
		assertTrue(Words.of(null).isEmpty());
		assertTrue(Words.of("").isPlain());
		assertEquals("", Words.of("").plain());
		assertEquals(0, Words.of("").length());
		assertTrue(Words.of("").looks().isEmpty());
		assertTrue(Words.EMPTY.allOf(RED).isEmpty(),
			"colouring nothing gives nothing rather than an empty coloured run");
	}

	@Test
	@DisplayName("a decorated line knows it is one")
	void plainOrNot() {
		assertTrue(Words.of("да").isPlain());
		assertFalse(new Words(List.of(new Words.Run("да", RED))).isPlain());
		assertFalse(Words.of("да").allOf(BIG).isPlain());

		// And painting the whole line one way leaves one run, not one per letter.
		assertEquals(1, Words.of("да").allOf(RED).runs().size());
	}
}
