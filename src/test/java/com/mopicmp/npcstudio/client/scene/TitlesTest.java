package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a line arrives on the screen and how it leaves.
 *
 * <h2>Why the one arithmetic bit is pinned</h2>
 *
 * Everything else about a title card is a rectangle. This is the part with an
 * answer that can be wrong, and it can be wrong in two directions that both look
 * plausible in a still: a line that never reaches full strength, and a line that
 * is still solid on the frame after its last.
 *
 * The awkward case is a short line. The fade is a fixed quarter of a second each
 * way, so a line asked to stay for three ticks would spend all of them fading in
 * and none of them readable — worse, taken naively it would never rise above a
 * third. Taking the fade out of the line's own span rather than adding it on is
 * what keeps "lasts two seconds" meaning two seconds; halving it for a short line
 * is what stops a short line from being invisible.
 */
class TitlesTest {

	@Test
	@DisplayName("a line is gone before it is cued and gone again after it ends")
	void nothingOutsideItsSpan() {
		// The bounds Scene.showingAt uses: at <= t < ends. A line still solid on the
		// frame after its last is the fault nobody notices in a still and everybody
		// notices in a film.
		assertEquals(0, Titles.solidity(-0.5, 60), 1e-9, "before it is cued");
		assertEquals(0, Titles.solidity(60, 60), 1e-9, "the frame it ends on");
		assertEquals(0, Titles.solidity(90, 60), 1e-9, "long after");
	}

	@Test
	@DisplayName("an ordinary line reaches full strength and holds it")
	void itIsFullyThereInTheMiddle() {
		// Three seconds: a quarter in, a quarter out, and two and a half seconds of
		// a line somebody can actually read.
		assertEquals(1, Titles.solidity(30, 60), 1e-9, "the middle");
		assertEquals(1, Titles.solidity(5, 60), 1e-9, "as soon as it has arrived");
		assertEquals(1, Titles.solidity(55, 60), 1e-9, "until it starts to leave");
		assertEquals(0.5, Titles.solidity(2.5, 60), 1e-9, "halfway in");
		assertEquals(0.5, Titles.solidity(57.5, 60), 1e-9, "halfway out");
	}

	@Test
	@DisplayName("a short line is still legible rather than nothing but a fade")
	void aShortLineHalvesItsFade() {
		// Four ticks. A fixed five-tick fade would never let it past four fifths and
		// would spend the whole line arriving; half of it each way is a line that is
		// briefly whole.
		assertEquals(1, Titles.solidity(2, 4), 1e-9, "the middle of a very short line");
		assertTrue(Titles.solidity(1, 4) < 1, "and it still arrives rather than appearing");
		assertTrue(Titles.solidity(3, 4) < 1, "and still leaves");
	}

	@Test
	@DisplayName("a line asked for nothing is not drawn at all")
	void nothingLastsNothing() {
		// A cue of no length is one that can never satisfy at <= t < ends, so there
		// is no moment it is on screen. Answering with anything but nought here would
		// be a line drawn at a strength for a frame that does not exist.
		assertEquals(0, Titles.solidity(0, 0), 1e-9);
		assertEquals(0, Titles.solidity(0, -5), 1e-9);
	}

	@Test
	@DisplayName("strength only ever climbs and then only ever falls")
	void itIsSmooth() {
		// No step anywhere, which is what a fade is. Checked across the whole span at
		// a finer grain than a frame, because a discontinuity of a twentieth is
		// invisible in a test that samples every tick and obvious on screen.
		double top = 0;
		double previous = 0;
		boolean falling = false;
		for (double t = 0; t < 60; t += 0.05) {
			double now = Titles.solidity(t, 60);
			assertTrue(Math.abs(now - previous) < 0.05, "no jump at " + t);
			if (now < previous) falling = true;
			assertTrue(!falling || now <= previous + 1e-9, "it does not come back at " + t);
			top = Math.max(top, now);
			previous = now;
		}
		assertEquals(1, top, 1e-9, "and it does reach the top");
		assertTrue(falling, "and it does come down again");
	}
}
