package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a document's lines are put in front of a player.
 *
 * The point of most of these is that the values arrive from a file as well as from
 * the editor, and a conversation must not fail to open because a number in it is
 * silly. Silly is corrected; the scene still plays.
 */
class MannerTest {

	@Test
	@DisplayName("the ordinary settings are the old constants, to the number")
	void ordinaryIsWhatItWas() {
		// This is the whole of the promise that nobody's existing map changed. The bar
		// was one row; a line came down after ten seconds and after twelve blocks; the
		// typing ran at whatever the player had set. If any of these move, every map
		// ever built with this mod looks different, so they are written down here where
		// moving them costs a failing test rather than a report from somebody's players.
		assertEquals(1, Manner.ORDINARY.lines());
		assertEquals(1f, Manner.ORDINARY.width());
		assertEquals(200, Manner.ORDINARY.idleTicks(), "ten seconds, in ticks");
		assertEquals(12.0, Manner.ORDINARY.range());
		assertFalse(Manner.ORDINARY.holdsPlayer());
		assertEquals(Manner.PLAYER_PACE, Manner.ORDINARY.pace());
	}

	@Test
	@DisplayName("a document that says nothing leaves the player's reading speed alone")
	void playerPaceWins() {
		// Somebody's reading speed is a preference about reading; a document's is a
		// decision about drama. An author who never thought about it must not quietly
		// overrule a player who did.
		assertEquals(45f, Manner.ORDINARY.paceOr(45f));
		assertEquals(12f, Manner.ORDINARY.withPace(12f).paceOr(45f));
	}

	@Test
	@DisplayName("nought means never, in both of the fields that can mean it")
	void noughtIsNever() {
		// A distinct value rather than a very large one. "Stays until answered" is a
		// thing scenes want, and writing it as a hundred thousand ticks would be a lie
		// that eventually comes true in front of somebody.
		assertTrue(Manner.ORDINARY.withIdleTicks(0).staysUp());
		assertFalse(Manner.ORDINARY.staysUp());
		assertTrue(Manner.ORDINARY.withRange(0).survivesDistance());
		assertFalse(Manner.ORDINARY.survivesDistance());
	}

	@Test
	@DisplayName("silly numbers are corrected rather than refused")
	void nonsenseIsClamped() {
		assertEquals(1f, Manner.ORDINARY.withWidth(4f).width(), "wider than the screen is the screen");
		assertEquals(Manner.NARROWEST, Manner.ORDINARY.withWidth(0.001f).width());
		assertEquals(1f, Manner.ORDINARY.withWidth(Float.NaN).width());
		assertEquals(1, Manner.ORDINARY.withLines(0).lines(), "no rows is not a bar");
		assertEquals(Manner.MOST_LINES, Manner.ORDINARY.withLines(99).lines());
		assertEquals(0.0, Manner.ORDINARY.withRange(-5).range(), "behind you is not a distance");
		assertEquals(0, Manner.ORDINARY.withIdleTicks(-40).idleTicks());
	}

	@Test
	@DisplayName("a pace of nothing is the player's, and any other pace is a usable one")
	void paceIsEitherHandedBackOrUsable() {
		// Nought has a meaning and must survive being clamped; everything else has to
		// land somewhere a line can actually be read at.
		assertEquals(Manner.PLAYER_PACE, Manner.ORDINARY.withPace(0f).pace());
		assertEquals(Manner.PLAYER_PACE, Manner.ORDINARY.withPace(-1f).pace());
		assertEquals(Manner.SLOWEST, Manner.ORDINARY.withPace(0.5f).pace());
		assertEquals(Manner.FASTEST, Manner.ORDINARY.withPace(9999f).pace());
	}

	@Test
	@DisplayName("a dialogue written without any of this is shown the ordinary way")
	void oldDocumentsAreOrdinary() {
		// The seven-part constructor is every caller written before this existed, and
		// there are a great many of them. None should have to say anything.
		Dialogue was = new Dialogue("d", Dialogue.CURRENT_FORMAT, "a",
			java.util.Map.of("a", new Node.End("a")), java.util.Map.of());
		assertEquals(Manner.ORDINARY, was.manner());
	}
}
