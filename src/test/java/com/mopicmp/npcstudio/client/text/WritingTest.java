package com.mopicmp.npcstudio.client.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * The caret and everything that moves it.
 *
 * <h2>Why this is worth a test at all</h2>
 *
 * Because a text editor is not hard, it is <em>fiddly</em>, and fiddly is what
 * tests are for. Every rule here is obvious and every one of them is wrong the
 * first time somebody writes it: what shift-arrow does when the selection folds
 * back on itself, whether a word jump lands before or inside the spaces, whether
 * pressing bold with nothing selected does anything at all.
 *
 * None of it needs a window. Where the caret goes is a rule; where it is drawn
 * is a fact about a font, and only the first has any edge cases.
 */
class WritingTest {

	private static final Look RED = Look.PLAIN.withColour(0xE57373);
	private static final Look BOLD = Look.PLAIN.withBold(true);

	private static Writing of(String text) {
		return new Writing(Words.of(text));
	}

	@Test
	@DisplayName("a fresh buffer has the caret at the end and nothing selected")
	void startsAtTheEnd() {
		Writing writing = of("привет");
		assertEquals(6, writing.caret());
		assertFalse(writing.hasSelection());
		assertEquals("привет", writing.plain());
	}

	@Test
	@DisplayName("typing puts letters where the caret is")
	void typing() {
		Writing writing = of("прет");
		writing.moveTo(2, false);
		writing.insert("ив");
		assertEquals("привет", writing.plain());
		assertEquals(4, writing.caret());
	}

	@Test
	@DisplayName("typing over a selection replaces it")
	void typingOverASelection() {
		Writing writing = of("привет");
		writing.moveTo(0, false);
		writing.moveTo(6, true);
		writing.insert("да");
		assertEquals("да", writing.plain());
		assertFalse(writing.hasSelection());
	}

	@Test
	@DisplayName("a selection folds back to nothing rather than turning inside out")
	void selectionHasDirection() {
		// The case a start-and-length selection gets wrong: shift-left three, then
		// shift-right three, has to be back to no selection.
		Writing writing = of("привет");
		writing.moveTo(3, false);
		writing.move(-3, true);
		assertEquals(0, writing.selectionStart());
		assertEquals(3, writing.selectionEnd());

		writing.move(3, true);
		assertFalse(writing.hasSelection(), "back where it started means nothing selected");
	}

	@Test
	@DisplayName("a word jump lands before the word, not inside the spaces")
	void wordJumps() {
		// What makes repeated presses walk back a word at a time instead of
		// alternating between the word and the gap in front of it.
		Writing writing = of("одно  два");
		writing.toEnd(false);
		assertEquals(6, writing.wordLeft());

		writing.moveTo(6, false);
		assertEquals(0, writing.wordLeft());

		writing.moveTo(0, false);
		assertEquals(6, writing.wordRight());
	}

	@Test
	@DisplayName("backspace and delete take one thing each, and nothing at the ends")
	void removing() {
		Writing writing = of("аб");
		writing.toEnd(false);
		assertTrue(writing.backspace());
		assertEquals("а", writing.plain());
		assertTrue(writing.backspace());
		assertFalse(writing.backspace(), "nothing to the left of the start");

		writing = of("аб");
		writing.moveTo(0, false);
		assertTrue(writing.delete());
		assertEquals("б", writing.plain());
		writing.toEnd(false);
		assertFalse(writing.delete(), "nothing to the right of the end");
	}

	@Test
	@DisplayName("new letters are dressed like the one before them")
	void typingInherits() {
		Writing writing = new Writing(new Words(List.of(new Words.Run("аб", RED))));
		writing.toEnd(false);
		writing.insert("в");
		assertEquals(RED, writing.words().looks().get(2));
	}

	@Test
	@DisplayName("pressing bold with nothing selected changes what is typed next")
	void pendingLook() {
		// Otherwise the button does nothing at all with an empty selection, and a
		// person presses it, types, sees plain text, and concludes it is broken.
		Writing writing = of("аб");
		writing.toEnd(false);
		writing.dress(look -> look.withBold(true));
		writing.insert("вг");

		assertEquals(BOLD, writing.words().looks().get(2));
		assertEquals(BOLD, writing.words().looks().get(3), "and the second letter too");
		assertEquals(Look.PLAIN, writing.words().looks().get(0), "what was there is untouched");
	}

	@Test
	@DisplayName("moving the caret gives up on what was pressed")
	void movingClearsPending() {
		// "From here on" stops being true the moment "here" moves.
		Writing writing = of("аб");
		writing.toEnd(false);
		writing.dress(look -> look.withBold(true));
		writing.moveTo(0, false);
		writing.insert("в");
		assertEquals(Look.PLAIN, writing.words().looks().get(0));
	}

	@Test
	@DisplayName("dressing a selection dresses only it")
	void dressingASelection() {
		Writing writing = of("абвг");
		writing.moveTo(1, false);
		writing.moveTo(3, true);
		writing.dress(look -> look.withColour(0xE57373));

		List<Look> looks = writing.words().looks();
		assertEquals(Look.PLAIN, looks.get(0));
		assertEquals(RED, looks.get(1));
		assertEquals(RED, looks.get(2));
		assertEquals(Look.PLAIN, looks.get(3));
	}

	@Test
	@DisplayName("a stretch drawn two ways answers neither")
	void mixedSelectionHasNoOneLook() {
		// So that no button claims to be on for a selection that is half bold —
		// which is the state where pressing it has to mean "make it all bold".
		Writing writing = of("абвг");
		writing.moveTo(0, false);
		writing.moveTo(2, true);
		writing.dress(look -> look.withBold(true));
		writing.selectAll();

		assertNull(writing.lookHere());
		assertFalse(writing.allAre(Look::bold), "half of it is not all of it");

		writing.moveTo(0, false);
		writing.moveTo(2, true);
		assertTrue(writing.allAre(Look::bold));
	}

	@Test
	@DisplayName("control characters are not letters")
	void controlCharactersAreDropped() {
		// A newline is a real thing to type; a tab and a bell are holes in the line.
		Writing writing = of("");
		writing.insert("аб\nв");
		assertEquals("аб\nв", writing.plain());
	}

	@Test
	@DisplayName("what comes out is what would be stored")
	void roundTripsToWords() {
		Writing writing = of("абвг");
		writing.moveTo(1, false);
		writing.moveTo(3, true);
		writing.dress(look -> look.withColour(0xE57373));

		Words words = writing.words();
		assertEquals("абвг", words.plain());
		// Welded on the way out: three runs, not four letters.
		assertEquals(3, words.runs().size(), "found " + words.runs());

		// And reading it back gives the same thing, which is what makes the window
		// safe to open twice.
		assertEquals(words, new Writing(words).words());
	}

	@Test
	@DisplayName("the caret never lands inside a character")
	void surrogatePairsAreOneStep() {
		Writing writing = of("a🙂b");
		assertEquals(3, writing.length());
		writing.toEnd(false);
		writing.backspace();
		writing.backspace();
		assertEquals("a", writing.plain(), "the emoji went as one thing");
	}

	@Test
	@DisplayName("the selection can be copied out as plain text")
	void copying() {
		Writing writing = of("привет");
		writing.moveTo(0, false);
		writing.moveTo(3, true);
		assertEquals("при", writing.selected());

		writing.moveTo(3, false);
		assertEquals("", writing.selected());
	}

	@Test
	@DisplayName("a run of typing comes back in one press")
	void undoTakesBackAWholeRun() {
		// Undo that gives back one letter is undo nobody uses. Typing a word and
		// changing your mind about it is one decision and should cost one press.
		Writing writing = of("");
		writing.insert("привет");
		assertTrue(writing.undo());
		assertEquals("", writing.plain());
		assertFalse(writing.undo(), "and there was nothing before that");
	}

	@Test
	@DisplayName("typing, then deleting, are two things")
	void kindsAreSeparateSteps() {
		Writing writing = of("");
		writing.insert("абв");
		writing.backspace();
		writing.backspace();

		writing.undo();
		assertEquals("абв", writing.plain(), "the deleting came back first");
		writing.undo();
		assertEquals("", writing.plain(), "and the typing after it");
	}

	@Test
	@DisplayName("typing somewhere else starts a new step")
	void movingBreaksTheRun() {
		// Two words typed in two places are two things done, even though both are
		// typing — and the caret having moved is exactly what says so.
		Writing writing = of("аб");
		writing.toEnd(false);
		writing.insert("вг");
		writing.moveTo(0, false);
		writing.insert("яя");

		assertEquals("яяабвг", writing.plain());
		writing.undo();
		assertEquals("абвг", writing.plain());
		writing.undo();
		assertEquals("аб", writing.plain());
	}

	@Test
	@DisplayName("dressing is a step of its own")
	void dressingUndoesAlone() {
		// Taking back a colour must not also take back the sentence it was put on.
		Writing writing = of("");
		writing.insert("абв");
		writing.selectAll();
		writing.dress(look -> look.withColour(0xE57373));

		writing.undo();
		assertEquals("абв", writing.plain(), "the words are still here");
		assertTrue(writing.words().isPlain(), "and the colour is not");
	}

	@Test
	@DisplayName("redo puts back what undo took, until something else is done")
	void redoing() {
		Writing writing = of("");
		writing.insert("абв");
		writing.undo();
		assertTrue(writing.redo());
		assertEquals("абв", writing.plain());

		writing.undo();
		writing.insert("я");
		assertFalse(writing.redo(), "a new change is a new future; the old one is gone");
	}

	@Test
	@DisplayName("undo puts the caret back where it was, not where it ended")
	void theCaretComesBackToo() {
		// Otherwise the next thing typed after an undo lands somewhere nobody chose.
		Writing writing = of("абвг");
		writing.moveTo(2, false);
		writing.insert("яя");
		assertEquals(4, writing.caret());

		writing.undo();
		assertEquals("абвг", writing.plain());
		assertEquals(2, writing.caret());
	}
}
