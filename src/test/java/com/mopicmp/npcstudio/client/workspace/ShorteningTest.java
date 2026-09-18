package com.mopicmp.npcstudio.client.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cutting a name to the room there is for it.
 *
 * <h2>The fault this is here about</h2>
 *
 * Two lists in this workspace draw a name from the left and a number from the
 * right of the same row, and both drew them straight over each other when the row
 * was too narrow. The case that breaks it is two things called nearly the same —
 * which is precisely the case the number beside them exists to tell apart, so the
 * failure landed exactly where the feature was needed.
 *
 * The cutting that fixes it fails silently in both directions: one letter too few
 * and the name still runs under the number, one too many and a name that would
 * have fitted is shortened for nothing. Neither throws.
 *
 * <h2>Why the widths here are made up</h2>
 *
 * They are not the game's font and are not meant to be. A test against the real
 * font would be a test whose boundary cases move when the font does; these are
 * chosen so that "exactly fits", "one over" and "the mark alone is too wide" are
 * each reachable and each obvious.
 */
class ShorteningTest {

	/** Ten pixels a letter, so every sum in the test is readable at a glance. */
	private static final Shortening.Widths TEN = letter -> 10;

	private static final int MARK = 10;

	private static String cut(String name, int room) {
		return Shortening.cut(name, room, TEN, MARK);
	}

	@Test
	@DisplayName("a name that fits comes back untouched")
	void whatFitsIsLeftAlone() {
		assertEquals("gate", cut("gate", 100));
		// Exactly filling the room is fitting. Off by one here shortens a name that was
		// never too long, which is the quieter half of the fault.
		assertEquals("gate", cut("gate", 40));
	}

	@Test
	@DisplayName("what does not fit is cut, and the cut is marked")
	void whatDoesNotFitIsMarked() {
		// One pixel short of fitting, and the answer is two letters rather than three:
		// the mark is paid for out of the same room, so 39 leaves 29, which is two
		// letters of ten. Three would come to forty and overrun by one — which is the
		// off-by-one this whole class is about, and it caught my own arithmetic in the
		// first version of this line.
		assertEquals("ga" + Shortening.MARK, cut("gate", 39));
		assertEquals("ga" + Shortening.MARK, cut("gate", 30));
		// A longer name in the same room, so that three letters are what is left after
		// the mark rather than the whole word fitting: "gate" at forty-nine is not cut
		// at all, which caught my second attempt at this line.
		assertEquals("gat" + Shortening.MARK, cut("gateway", 49));
	}

	@Test
	@DisplayName("the mark is paid for before any letter is kept")
	void theMarkIsPaidForFirst() {
		// The mistake this pins: filling the room with letters and then adding the mark
		// makes the shortened name wider than the one it replaced, which defeats the
		// whole thing. Room 30 with a mark of 10 leaves 20, which is two letters.
		String cut = cut("gateway", 30);
		assertEquals("ga" + Shortening.MARK, cut);
		assertTrue(cut.length() * 10 <= 30 + MARK, "wider than the room it was cut to");
	}

	@Test
	@DisplayName("too narrow even for the mark draws nothing")
	void tooNarrowForTheMark() {
		// Answering with the mark alone would draw past the edge, which is the thing
		// being prevented — so the honest answer is nothing.
		assertEquals("", cut("gateway", 9));
		assertEquals("", cut("gateway", 0));
		assertEquals("", cut("gateway", -5));
	}

	@Test
	@DisplayName("the mark alone is the answer when exactly its width is left")
	void exactlyTheMark() {
		assertEquals(Shortening.MARK, cut("gateway", 10));
	}

	@Test
	@DisplayName("nothing is not a crash")
	void noName() {
		assertEquals("", cut(null, 100));
		assertEquals("", cut("", 100));
	}

	@Test
	@DisplayName("a cut name is never wider than the room, at any width")
	void neverWiderThanTheRoom() {
		// The rule the whole thing exists for, over every length and every room. A
		// variable-width font is used here so the walk cannot be right by accident on
		// letters that all measure the same.
		Shortening.Widths varied = letter -> 3 + (letter % 7);
		String name = "Стражник у восточных ворот";
		for (int room = 0; room <= 200; room++) {
			String cut = Shortening.cut(name, room, varied, 6);
			int wide = 0;
			for (int i = 0; i < cut.length(); i++) {
				char at = cut.charAt(i);
				wide += at == '…' ? 6 : varied.of(at);
			}
			assertTrue(wide <= room, "room " + room + " got " + wide + " for \"" + cut + "\"");
		}
	}
}
