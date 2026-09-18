package com.mopicmp.npcstudio.dialogue.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How much of a line has been said by now.
 *
 * <h2>Why this is worth testing at all</h2>
 *
 * Because when it is wrong the report is "the text feels a bit off", and that is
 * the one kind of fault that can never be chased down by looking at a screen. Out
 * here it is arithmetic, and arithmetic can be asked questions.
 */
class PaceTest {

	private static final float STEADY = 100f;

	@Test
	@DisplayName("nothing is on screen before it starts, and the first letter is not made to wait")
	void beginning() {
		assertEquals(0, Pace.shown("hello", STEADY, 0, false));
		// At a hundred a second a letter is ten milliseconds. A line that shows nothing
		// at all for its first instant reads as a line that failed to arrive, which is
		// why the comparison is strict rather than loose.
		assertEquals(1, Pace.shown("hello", STEADY, 10, false));
	}

	@Test
	@DisplayName("without breathing every letter costs the same, which is what it used to do")
	void flat() {
		// The old behaviour exactly, and it has to stay reachable: every dialogue
		// written before any of this existed is read back with breathing off, and none
		// of them should type differently today than they did yesterday.
		assertEquals(5, Pace.shown("a.b,c", STEADY, 50, false));
		assertEquals(3, Pace.shown("a.b,c", STEADY, 30, false));
	}

	@Test
	@DisplayName("a full stop holds the next letter back")
	void stops() {
		// Fifty milliseconds is five letters flat. With breathing, the letter after the
		// full stop costs seven beats instead of one, so only the first two arrive.
		assertEquals(5, Pace.shown("ab.cd", STEADY, 50, false));
		assertEquals(3, Pace.shown("ab.cd", STEADY, 50, true));
	}

	@Test
	@DisplayName("a comma holds it back less than a full stop does")
	void commaIsShorterThanAStop() {
		long comma = Pace.millisFor("ab,cd", STEADY, true);
		long stop = Pace.millisFor("ab.cd", STEADY, true);
		long flat = Pace.millisFor("abxcd", STEADY, true);
		assertTrue(flat < comma, "a comma is a pause and a letter is not");
		assertTrue(comma < stop, "a thought ending is longer than a breath");
	}

	@Test
	@DisplayName("the pause falls after the mark and not on it")
	void afterNotOn() {
		// The mark itself has to appear before the wait starts. Drawn on the mark, the
		// reader waits at a place they cannot see and the comma lands late — which
		// reads as stuttering rather than as breathing.
		assertEquals(3, Pace.shown("ab,cd", STEADY, 30, true));
		assertEquals(3, Pace.shown("ab,cd", STEADY, 50, true));
	}

	@Test
	@DisplayName("a line break is the longest pause there is")
	void breaks() {
		assertTrue(Pace.millisFor("ab\ncd", STEADY, true)
			> Pace.millisFor("ab.cd", STEADY, true),
			"somebody who pressed return meant more than somebody who typed a dot");
	}

	@Test
	@DisplayName("breathing is a shape, not a slowdown that can be tuned away")
	void beatsScaleWithPace() {
		// Held as beats rather than as milliseconds on purpose: a line set to type
		// slowly should breathe slowly too, so that fast and slow are the same
		// performance at two speeds rather than two different performances.
		long slow = Pace.millisFor("ab.cd", 50f, true);
		long fast = Pace.millisFor("ab.cd", 100f, true);
		assertEquals(slow, fast * 2, 2, "halving the pace should double everything, pauses too");
	}

	@Test
	@DisplayName("the whole line eventually shows, and never more than the whole line")
	void ending() {
		assertEquals(5, Pace.shown("hello", STEADY, 100_000, true));
		assertEquals(0, Pace.shown("", STEADY, 100_000, true));
		assertEquals(0, Pace.shown(null, STEADY, 100_000, true));
	}

	@Test
	@DisplayName("a pace of nothing shows the line rather than never showing it")
	void nonsensePace() {
		// This arrives from a file as well as from a person, and a conversation must
		// not sit blank for ever because a number in it was silly.
		assertEquals(5, Pace.shown("hello", 0f, 10, true));
		assertEquals(5, Pace.shown("hello", -3f, 10, true));
	}

	@Test
	@DisplayName("an emoji is one letter, not two halves of one")
	void codePoints() {
		String line = "a😀b";
		assertEquals(3, Pace.shown(line, STEADY, 100_000, false));
		// And it is not cut in half part way through: two shown means the letter and
		// the whole emoji, never the emoji's first surrogate on its own.
		assertEquals(2, Pace.shown(line, STEADY, 20, false));
	}

	@Test
	@DisplayName("saying how long a line takes agrees with when it finishes showing")
	void lengthAgreesWithShowing() {
		// Two ways of asking one question, and they are asked from opposite ends: one
		// decides what is on screen, the other decides how long to leave it there. A
		// bar timed by the second while the first is still typing takes the sentence
		// away in the middle of itself.
		String line = "Ну что, пойдём? Дорога дальняя — успеем к ночи.";
		long takes = Pace.millisFor(line, 45f, true);
		assertTrue(Pace.shown(line, 45f, takes, true) >= line.codePointCount(0, line.length()),
			"by the time it says it is finished, it should be finished");
		assertTrue(Pace.shown(line, 45f, takes - 200, true) < line.codePointCount(0, line.length()),
			"and not finished a moment before that");
	}
}
