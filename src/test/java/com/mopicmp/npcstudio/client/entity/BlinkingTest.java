package com.mopicmp.npcstudio.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the eyes are open nearly all the time, and never do the same thing twice
 * running.
 *
 * A blink is a fifth of a second every several seconds, so almost every test one
 * could write about it passes by accident — sample at the wrong moments and the
 * eyes are always open. These are written to catch what people actually notice:
 * blinking too often, a crowd blinking in unison, and a lid that snaps rather
 * than moves.
 */
class BlinkingTest {

	/** Every frame of a minute, which is how any of this is actually seen. */
	private static final float FRAME = 1f / 3f;

	/**
	 * The eyes are open nearly all the time.
	 *
	 * The complaint this number was changed for: at three or four seconds apart, a
	 * character across a room looked as though it were blinking constantly. A
	 * movement noticed at a distance reads as more frequent than the same one on a
	 * face in front of you.
	 */
	@Test
	@DisplayName("the eyes are open at least nine tenths of the time")
	void theEyesAreMostlyOpen() {
		int open = 0;
		int counted = 0;
		for (int id = 1; id <= 40; id++) {
			for (int step = 0; step < 3600; step++) {
				counted++;
				if (Blinking.shut(id, step * FRAME) < 0.02f) open++;
			}
		}
		float share = open / (float) counted;
		assertTrue(share > 0.9f, "the eyes were shut or closing " + (100 - share * 100) + "% of the time");
	}

	/** And never more than shut. */
	@Test
	@DisplayName("a lid never goes further than closed")
	void theLidStaysWithinItself() {
		for (int id = 1; id <= 30; id++) {
			for (int step = 0; step < 2000; step++) {
				float shut = Blinking.shut(id, step * FRAME);
				assertTrue(shut >= 0f && shut <= 1f, "the lid was at " + shut);
			}
		}
	}

	/** A lid moves rather than snapping, at every one of the four. */
	@Test
	@DisplayName("a lid never jumps between one frame and the next")
	void theLidMovesSmoothly() {
		for (int id = 1; id <= 30; id++) {
			float last = Blinking.shut(id, 0f);
			for (int step = 1; step < 3600; step++) {
				float now = Blinking.shut(id, step * FRAME);
				assertTrue(Math.abs(now - last) < 0.35f,
					"character " + id + " snapped " + Math.abs(now - last) + " shut in one frame");
				last = now;
			}
		}
	}

	/**
	 * Two characters standing together do not blink together.
	 *
	 * The one detail that separates a crowd of people from a shop window of
	 * mannequins, and it is invisible in a screenshot — so it is counted.
	 */
	@Test
	@DisplayName("neighbours do not blink in step")
	void neighboursDoNotBlinkTogether() {
		int together = 0;
		int either = 0;
		for (int step = 0; step < 3600; step++) {
			float ticks = step * FRAME;
			for (int id = 1; id <= 10; id++) {
				boolean one = Blinking.shut(id, ticks) > 0.2f;
				boolean two = Blinking.shut(id + 1, ticks) > 0.2f;
				if (one || two) either++;
				if (one && two) together++;
			}
		}
		assertTrue(either > 0, "nobody blinked at all, so this measured nothing");
		assertTrue(together < either * 0.2,
			"neighbours were shut at the same moment " + together + " times out of " + either);
	}

	/** All four things a character can do with its lids actually happen. */
	@Test
	@DisplayName("all four of the lid's movements turn up")
	void everyActHappens() {
		Set<Blinking.Act> seen = EnumSet.noneOf(Blinking.Act.class);
		for (int id = 1; id <= 20; id++) {
			for (int turn = 0; turn < 200; turn++) seen.add(Blinking.act(id, turn));
		}
		assertEquals(EnumSet.allOf(Blinking.Act.class), seen, "some movement never came up");
	}

	/** The ordinary blink is the ordinary one. */
	@Test
	@DisplayName("most blinks are ordinary blinks")
	void mostBlinksArePlain() {
		int plain = 0;
		int counted = 0;
		for (int id = 1; id <= 60; id++) {
			for (int turn = 0; turn < 200; turn++) {
				counted++;
				if (Blinking.act(id, turn) == Blinking.Act.BLINK) plain++;
			}
		}
		assertTrue(plain > counted * 0.6,
			"a character that slow-blinks half the time looks drowsy: " + plain + " of " + counted);
	}

	/**
	 * A double blink really is two: shut, open, shut again.
	 *
	 * Worth stating, because the obvious way to write it — one pulse twice as long
	 * — is not a double blink at all, and the difference is exactly the moment in
	 * the middle where the eye is open.
	 */
	@Test
	@DisplayName("a double blink opens in the middle")
	void aDoubleBlinkIsTwoBlinks() {
		int shutRuns = 0;
		boolean was = false;
		for (int step = 0; step <= 400; step++) {
			boolean now = Blinking.shape(Blinking.Act.DOUBLE, step / 400f) > 0.3f;
			if (now && !was) shutRuns++;
			was = now;
		}
		assertEquals(2, shutRuns, "a double blink should shut twice, not once for twice as long");
	}

	/** A squint is held part way, not shut. */
	@Test
	@DisplayName("a squint is held half closed rather than shut")
	void aSquintIsPartial() {
		float most = 0;
		int held = 0;
		for (int step = 0; step <= 400; step++) {
			float shut = Blinking.shape(Blinking.Act.SQUINT, step / 400f * 1.5f);
			most = Math.max(most, shut);
			if (shut > 0.4f) held++;
		}
		assertTrue(most > 0.3f && most < 0.7f, "a squint closed to " + most);
		assertTrue(held > 150, "a squint should be held, not passed through");
	}

	/** A slow blink shuts all the way and stays there a moment. */
	@Test
	@DisplayName("a slow blink shuts fully and rests there")
	void aSlowBlinkRestsShut() {
		int fully = 0;
		for (int step = 0; step <= 400; step++) {
			if (Blinking.shape(Blinking.Act.SLOW_BLINK, step / 400f) > 0.99f) fully++;
		}
		assertTrue(fully > 20, "a slow blink should rest shut, not touch shut and leave");
	}

	/** Every movement ends: the eyes are open again well before the next one. */
	@Test
	@DisplayName("every movement finishes long before the next")
	void everyActEnds() {
		for (Blinking.Act act : Blinking.Act.values()) {
			assertEquals(0f, Blinking.shape(act, 2f), 1e-5,
				act + " was still going two seconds in");
			assertEquals(0f, Blinking.shape(act, -0.1f), 1e-5, act + " started early");
		}
	}

	/**
	 * A wink stops at the bottom, which is what makes it a wink.
	 *
	 * Every other movement the lids make passes through being shut on its way back
	 * up. The difference between passing through and pausing there is the whole
	 * difference between a blink and a joke, so it is worth stating as a number
	 * rather than trusting the shape.
	 */
	@Test
	@DisplayName("a wink pauses shut rather than passing through")
	void aWinkHolds() {
		int held = 0;
		float most = 0;
		for (int step = 0; step <= 200; step++) {
			float shut = Blinking.wink(step / 200f);
			most = Math.max(most, shut);
			if (shut > 0.99f) held++;
		}
		assertTrue(most > 0.99f, "a wink shuts the eye entirely: " + most);
		assertTrue(held > 15, "and rests there rather than touching it: " + held);
		assertEquals(0f, Blinking.wink(0f), 1e-5, "with the eye open when it begins");
		assertEquals(0f, Blinking.wink(2f), 1e-5, "and open again well before two seconds");
	}

	/**
	 * A character always winks with the same eye, and a crowd does not agree.
	 *
	 * People have a side they wink with. One that alternated would read as having
	 * something in its eye, and a village all winking the same way would read as
	 * one character copied.
	 */
	@Test
	@DisplayName("each character winks with its own eye, and not all the same one")
	void winksHaveASide() {
		for (int id = 1; id <= 50; id++) {
			assertEquals(Blinking.winksRight(id), Blinking.winksRight(id),
				"a character changed which eye it winks with");
		}

		int right = 0;
		for (int id = 1; id <= 200; id++) {
			if (Blinking.winksRight(id)) right++;
		}
		assertTrue(right > 40 && right < 160,
			"the wink should not be the same eye for everybody: " + right + " of 200");
	}
}
