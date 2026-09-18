package com.mopicmp.npcstudio.dialogue.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A decorated line surviving an ordinary text box, and being typed out on screen.
 *
 * <h2>Why these two live together</h2>
 *
 * Because they are the same worry from both ends. A line that has been coloured
 * still has to pass through a plain field beside the graph, and it still has to
 * arrive on screen one letter at a time — and the lazy version of either is to
 * work in plain text and put the colours back afterwards. That gives a line that
 * loses its colours when a typo is fixed, and a line that arrives white and
 * repaints itself at the end. Both read as faults rather than as features.
 */
class RewordingTest {

	private static final Look RED = Look.PLAIN.withColour(0xE57373);
	private static final Look BIG = Look.PLAIN.withSize(2f);

	private static Words dressed() {
		return new Words(List.of(
			new Words.Run("Ты ", Look.PLAIN),
			new Words.Run("не вернёшься", RED)));
	}

	@Test
	@DisplayName("what did not change keeps how it was drawn")
	void bothEndsSurvive() {
		// Fixing a typo at the front must not cost the colour at the back.
		Words now = dressed().reworded("Вы не вернёшься");

		assertEquals("Вы не вернёшься", now.plain());
		assertEquals(RED, now.looks().get(3), "the red half is still red");
		assertEquals(Look.PLAIN, now.looks().get(0));
	}

	@Test
	@DisplayName("new letters are drawn as whatever they were typed after")
	void newLettersJoinWhatCameBefore() {
		// Typing inside the red stretch gives red letters, which is what every text
		// editor does and what anybody would expect without being told.
		Words now = dressed().reworded("Ты не вернёшься сюда");

		assertEquals(RED, now.looks().get(now.length() - 1));
		// And one run, not two: the added letters look the same as their neighbours.
		assertEquals(2, now.runs().size(), "found " + now.runs());
	}

	@Test
	@DisplayName("typing at the very front takes the drawing of what is now second")
	void typingAtTheStart() {
		Words now = new Words(List.of(new Words.Run("да", RED))).reworded("ада");
		assertEquals(RED, now.looks().get(0));
	}

	@Test
	@DisplayName("a plain line stays a cheap plain line")
	void plainStaysPlain() {
		assertTrue(Words.of("привет").reworded("прощай").isPlain());

		Words line = dressed();
		assertSame(line, line.reworded(line.plain()),
			"the same words are not a change at all");
	}

	@Test
	@DisplayName("emptying the line empties it")
	void clearingWorks() {
		assertTrue(dressed().reworded("").isEmpty());
		assertTrue(dressed().reworded(null).isEmpty());

		// And filling an empty one is ordinary rather than a special case.
		assertEquals("да", Words.EMPTY.reworded("да").plain());
		assertTrue(Words.EMPTY.reworded("да").isPlain());
	}

	@Test
	@DisplayName("the typed-out prefix carries its own drawing")
	void typingOutKeepsColour() {
		Words line = dressed();

		assertEquals("Ты", line.first(2).plain());
		assertTrue(line.first(2).isPlain(), "the first two letters were never red");

		Words five = line.first(5);
		assertEquals("Ты не", five.plain());
		assertEquals(RED, five.looks().get(3), "and the red starts exactly where it does in the whole line");
	}

	@Test
	@DisplayName("cutting at nothing and past the end are both ordinary")
	void theEndsOfCutting() {
		Words line = dressed();
		assertTrue(line.first(0).isEmpty());
		assertTrue(line.first(-4).isEmpty());
		assertSame(line, line.first(line.length()));
		assertSame(line, line.first(9999));
	}

	@Test
	@DisplayName("cutting inside a surrogate pair does not halve it")
	void cuttingCountsCodePoints() {
		Words line = new Words(List.of(new Words.Run("a🙂b", BIG)));

		assertEquals("a", line.first(1).plain());
		assertEquals("a🙂", line.first(2).plain());
		assertEquals(2, line.first(2).length());
	}
}
