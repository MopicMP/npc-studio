package com.mopicmp.npcstudio.client.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How wide a summoned panel is made, and the three rules it must not break.
 *
 * <h2>The first fault this is here to stop coming back</h2>
 *
 * The rails were built with a fixed width of 150 chosen without asking the
 * panels what they need. The dock does not clip a panel to its frame — it sizes
 * it to {@code max(frameWidth, panel.minimumWidth())} — so a panel wanting 300
 * was drawn 300 wide inside a 150 frame and every field and button in it ran out
 * past the edge. It was reported as "почти во всех панелях выходят за рамки" and
 * it was in every panel whose minimum was above 150, which is most of them.
 *
 * <h2>The second, which was the repair for the first</h2>
 *
 * The repair was "the minimum, or half the room, whichever is larger". On a
 * window of about six hundred logical pixels — a windowed game at {@code guiScale
 * 2} — half the room is about what the wider panels ask for anyway, so nearly
 * every panel opened at the ceiling. Reported as opening too wide, and it was:
 * a panel taking most of the window is the columns coming back one at a time.
 *
 * So the opening width is the minimum exactly, and half the room is not in the
 * sum at all. A limit makes a poor default.
 */
class SummonWidthTest {

	/** The same sum as {@code WorkspaceScreen.widthFor}, over a window of a given width. */
	private static int widthFor(int window, int minimum, int kept) {
		int room = Math.max(Rail.PANEL, window - Rail.WIDTH * 2);
		int least = Math.max(Rail.PANEL, minimum);
		int wanted = kept > 0 ? kept : least;
		return Math.min(room, Math.max(least, wanted));
	}

	private static int room(int window) {
		return Math.max(Rail.PANEL, window - Rail.WIDTH * 2);
	}

	/** The widest minimum any panel declares today, and a couple of ordinary ones. */
	private static final int[] MINIMUMS = { 120, 150, 190, 240, 300, 340 };

	@Test
	@DisplayName("a panel is never framed narrower than it says it needs")
	void neverBelowTheMinimum() {
		// Every window from a cramped one to a large one, and every width somebody
		// might have pulled it to, including absurdly narrow ones.
		for (int window = 300; window <= 1600; window += 20) {
			for (int minimum : MINIMUMS) {
				for (int kept : new int[] { 0, 40, 150, 500, 4000 }) {
					int wide = widthFor(window, minimum, kept);
					// Unless the window itself cannot hold it, in which case nothing can
					// and the dock says so in its own words.
					if (minimum <= room(window)) {
						assertTrue(wide >= minimum,
							"window " + window + ", minimum " + minimum + ", kept " + kept
								+ " got " + wide);
					}
				}
			}
		}
	}

	@Test
	@DisplayName("it never grows wider than the room between the strips")
	void neverWiderThanTheWindow() {
		for (int window = 300; window <= 1600; window += 20) {
			for (int minimum : MINIMUMS) {
				for (int kept : new int[] { 0, 150, 900, 4000 }) {
					assertTrue(widthFor(window, minimum, kept) <= room(window),
						"window " + window + ", minimum " + minimum + ", kept " + kept);
				}
			}
		}
	}

	@Test
	@DisplayName("nothing remembered: it opens at what it needs, not at the ceiling")
	void opensAtTheMinimum() {
		// This is the reported fault. On the window the complaint came from, a panel
		// asking 240 was opening at about 280 and one asking 340 at 340 — that is,
		// the ceiling, every time. Now the answer is the panel's own number.
		for (int window = 600; window <= 1600; window += 20) {
			for (int minimum : MINIMUMS) {
				if (minimum > room(window)) continue;
				assertEquals(Math.max(Rail.PANEL, minimum), widthFor(window, minimum, 0),
					"window " + window + ", minimum " + minimum);
			}
		}
	}

	@Test
	@DisplayName("a width that was pulled by hand is the width that comes back")
	void keepsWhatWasPulled() {
		// The other half of the complaint: the width was being kept only while the
		// panel stayed open, and dismissing a panel is now the ordinary thing to do
		// with one. See PanelWidths.
		assertEquals(420, widthFor(1200, 240, 420));
		assertEquals(200, widthFor(1200, 190, 200));
		// And the two edges still win over it.
		assertEquals(340, widthFor(1200, 340, 200), "under the minimum");
		assertEquals(room(700), widthFor(700, 240, 5000), "over the room");
	}
}
