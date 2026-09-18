package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.Mark;

/**
 * Named places, and the one thing that must not break about how they are written.
 *
 * <h2>What is actually being protected here</h2>
 *
 * The vocabulary of marks is closed on purpose, and its being closed is what
 * catches {@code palyer} before somebody spends an evening watching a character
 * stare at nothing. Adding places threatened exactly that: the obvious way to do
 * it — read any unrecognised word as the name of a place — would have turned every
 * typo in every relative mark into a place that happens not to exist yet, and the
 * validator would have had nothing left to say about any of them.
 *
 * So a place is a different form rather than a different word, and these are the
 * tests that say so.
 */
class SpotTest {

	@Test
	@DisplayName("a typo in a relative mark is still a typo")
	void typosAreStillCaught() {
		// The whole reason for the prefix. Each of these is a real mark spelled wrong,
		// and every one of them must stay wrong now that places exist.
		assertFalse(Mark.known("palyer"));
		assertFalse(Mark.known("laed"));
		assertFalse(Mark.known("Post"));
		assertFalse(Mark.known("gate"), "a bare word is not a place, it is a mistake");
		assertFalse(Mark.known(""));
	}

	@Test
	@DisplayName("a place is written with the prefix and read back without it")
	void placesRoundTrip() {
		assertEquals("at:gate", Mark.at("gate"));
		assertEquals("gate", Mark.place(Mark.at("gate")));
		assertTrue(Mark.known(Mark.at("gate")));

		// Including one in another language, which is the ordinary case for this map.
		assertEquals("ворота", Mark.place(Mark.at("ворота")));
		assertTrue(Mark.known(Mark.at("ворота")));
	}

	@Test
	@DisplayName("the relative marks are not places")
	void relativeMarksAreNotPlaces() {
		for (String mark : Mark.KNOWN) {
			assertNull(Mark.place(mark), mark);
			assertTrue(Mark.known(mark), mark);
		}
	}

	@Test
	@DisplayName("the prefix with nothing after it names nothing")
	void emptyPlaceIsNotAPlace() {
		// Otherwise "at:" would be a valid mark that can never resolve, which is the
		// silent-nothing failure this whole check exists to prevent.
		assertNull(Mark.place("at:"));
		assertFalse(Mark.known("at:"));
	}

	@Test
	@DisplayName("a name is trimmed, and a colon in one is refused")
	void namesAreTidied() {
		assertEquals("gate", Spot.tidy("  gate  "));
		assertEquals("the third window", Spot.tidy("the third window"),
			"spaces are allowed; this is a label, not an identifier");

		// A colon would make at:x:y, which cannot be read back to the name that was
		// typed — Mark.place would answer "x:y" and no such place was ever put down.
		assertNull(Spot.tidy("x:y"));
		assertNull(Spot.tidy("   "));
		assertNull(Spot.tidy(null));
		assertNull(Spot.tidy("a".repeat(Spot.LONGEST + 1)));
		assertEquals("a".repeat(Spot.LONGEST), Spot.tidy("a".repeat(Spot.LONGEST)));
	}

	@Test
	@DisplayName("every name that tidies to something makes a mark that reads back")
	void tidyNamesSurviveBeingWritten() {
		for (String wanted : new String[] { "gate", " gate ", "ворота", "the third window",
				"a".repeat(Spot.LONGEST) }) {
			String name = Spot.tidy(wanted);
			assertEquals(name, Mark.place(Mark.at(name)), wanted);
		}
	}
}
