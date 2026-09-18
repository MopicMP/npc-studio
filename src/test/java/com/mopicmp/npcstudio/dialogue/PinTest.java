package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a node sits on the canvas.
 *
 * The whole of what matters about this field is what its <em>absence</em> means, so
 * that is most of what is asked here.
 */
class PinTest {

	@Test
	@DisplayName("a document nobody has arranged says nothing, which is not the same as the origin")
	void nothingMeansLayItOut() {
		// The distinction the editor turns on. Empty is "lay it out for me", which is
		// every graph written before this and every graph written by hand in a
		// datapack; a map of pins at nought would be a graph whose nodes are all
		// stacked in the top-left corner and were put there on purpose.
		Dialogue plain = new Dialogue("d", Dialogue.CURRENT_FORMAT, "a",
			java.util.Map.of("a", new Node.End("a")), java.util.Map.of());
		assertTrue(plain.places().isEmpty());
		assertNull(plain.place("a"));
	}

	@Test
	@DisplayName("a pin that names a node which is gone is not an error")
	void strayPinsAreHarmless() {
		// Nodes are deleted and pins are not chased down after them, on purpose: this
		// is a note about a drawing, and a conversation must play identically whether
		// or not anybody ever opened it in a window.
		Dialogue was = new Dialogue("d", Dialogue.CURRENT_FORMAT, "a",
			java.util.Map.of("a", new Node.End("a")), java.util.Map.of(), java.util.Map.of(),
			java.util.Map.of(), Manner.ORDINARY,
			java.util.Map.of("a", new Pin(10, 20), "long_gone", new Pin(30, 40)));
		assertEquals(new Pin(10, 20), was.place("a"));
		assertEquals(new Pin(30, 40), was.place("long_gone"));
	}

	@Test
	@DisplayName("a place too far away to scroll to is brought back within reach")
	void absurdPlacesAreClamped() {
		// This arrives from a file. A node at two billion is a node nobody can scroll
		// to, on a canvas that then looks empty for a reason its author cannot see.
		assertEquals(Pin.FURTHEST, new Pin(Integer.MAX_VALUE, 0).x());
		assertEquals(-Pin.FURTHEST, new Pin(0, Integer.MIN_VALUE).y());
		// And an ordinary one is left exactly alone, which is the case that matters
		// every other time.
		assertEquals(new Pin(-320, 44), new Pin(-320, 44));
	}
}
