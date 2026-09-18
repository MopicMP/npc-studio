package com.mopicmp.npcstudio.client.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a hovered icon's name is drawn.
 *
 * <h2>Why arithmetic this small is worth a test</h2>
 *
 * Because it is arithmetic against edges, in two languages, and the failure is
 * silent. A label that runs past the right of the window is simply not readable,
 * and it is not readable only for the longest word in one of the languages — which
 * is the case nobody sitting in front of the other language will ever hit. The
 * same for a label that misses its button by two pixels: it names its neighbour,
 * and naming the neighbour is worse than saying nothing at all.
 *
 * The lengths below are in the font's own units, which is what the drawing
 * measures with; the point of the test is the placement, not the measuring.
 */
class IconNameTest {

	private static final int TOOLBAR = 16;

	@Test
	@DisplayName("a toolbar label never runs off either end of the window")
	void toolLabelsStayInTheWindow() {
		for (int window = 200; window <= 1600; window += 13) {
			for (int text : new int[] { 10, 40, 90, 140, 220 }) {
				// Every icon position the top strip can produce, including one right
				// against the far edge — which is where the receiver ends up on a narrow
				// window and is the button whose name is longest in both languages.
				for (int icon = 0; icon <= window; icon += 17) {
					int left = IconName.leftUnderTool(window, icon, text);
					assertTrue(left >= 0, "off the left: window " + window + " text " + text);
					// It may only overhang when the window itself cannot hold the plate,
					// in which case nothing could and the left edge is the better half to
					// keep — a name read from its start is still a name.
					if (IconName.wide(text) <= window) {
						assertTrue(left + IconName.wide(text) <= window,
							"off the right: window " + window + " icon " + icon + " text " + text);
					}
				}
			}
		}
	}

	@Test
	@DisplayName("a toolbar label starts at its icon whenever there is room")
	void toolLabelsFollowTheirIcon() {
		// The ordinary case, which the clamping must not disturb: the plate begins
		// where the button begins, so the eye goes straight down from one to the other.
		assertEquals(100, IconName.leftUnderTool(1200, 100, 60));
		assertEquals(6, IconName.leftUnderTool(1200, 6, 60));
	}

	@Test
	@DisplayName("a rail label sits beside its strip, never over it")
	void railLabelsClearTheStrip() {
		for (int window = 200; window <= 1600; window += 13) {
			for (int text : new int[] { 10, 60, 150 }) {
				int left = IconName.leftBesideRail(window, false, text);
				assertTrue(left >= Rail.WIDTH, "left strip covered");

				int right = IconName.leftBesideRail(window, true, text);
				assertTrue(right >= 0, "off the left: window " + window);
				if (IconName.wide(text) + Rail.WIDTH <= window) {
					assertTrue(right + IconName.wide(text) <= window - Rail.WIDTH,
						"right strip covered: window " + window + " text " + text);
				}
			}
		}
	}

	@Test
	@DisplayName("a rail label is level with the button it names")
	void railLabelsMatchTheirButton() {
		// The button's own top is TOOLBAR + 2 + which * WIDTH, and the plate is
		// centred in it. Worked out from the mouse instead, this was off by the strip's
		// two pixels of padding and drifted by a whole button down a long strip.
		for (int which = 0; which < 8; which++) {
			int button = TOOLBAR + 2 + which * Rail.WIDTH;
			int top = IconName.topBesideRail(TOOLBAR, which);
			assertTrue(top >= button, "above its button at " + which);
			assertTrue(top + IconName.TALL <= button + Rail.WIDTH, "below its button at " + which);
		}
	}

	@Test
	@DisplayName("the index the label uses is the index the click uses")
	void oneAnswerAboutWhichButton() {
		int count = 4;
		// Every pixel down a strip of four, and the answer has to be the button that
		// is actually drawn there — one sum, used by both, so a name cannot come to
		// disagree with what pressing it does.
		for (int which = 0; which < count; which++) {
			int button = TOOLBAR + 2 + which * Rail.WIDTH;
			for (int y = button; y < button + Rail.WIDTH; y++) {
				assertEquals(which, IconName.railIndex(TOOLBAR, y, count), "at y " + y);
			}
		}
		// The two pixels of padding above the first button are not a button. They used
		// to answer "the first one", because the negative divided towards zero.
		assertEquals(-1, IconName.railIndex(TOOLBAR, TOOLBAR, count));
		assertEquals(-1, IconName.railIndex(TOOLBAR, TOOLBAR + 1, count));
		// And past the last one there is nothing.
		assertEquals(-1, IconName.railIndex(TOOLBAR, TOOLBAR + 2 + count * Rail.WIDTH, count));
	}
}
