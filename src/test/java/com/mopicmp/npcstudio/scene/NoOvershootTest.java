package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A curve may not leave the range its own keys describe.
 *
 * <h2>What happened</h2>
 *
 * A darkening was keyed at six tenths on nought seconds, six tenths again on ten,
 * and dropped to two tenths at thirteen — a flat stretch and then a fade up into
 * the light. The flat stretch was not flat. It rose to nearly sixty-five
 * hundredths in the middle, went visibly darker and came back, and there was
 * nothing anywhere in the scene to explain it.
 *
 * <h2>Why the arithmetic was right and the answer nonsense</h2>
 *
 * The slope at a key was its two neighbours' and nothing else — a plain
 * Catmull-Rom spline. At the second key the neighbours are six tenths behind and
 * two tenths ahead, so the slope there points downwards; and a curve that must
 * leave the first key at six tenths, reach the second at six tenths, and be
 * heading down when it arrives has no choice but to bulge upwards on the way.
 *
 * That is the worst shape of bug: every number in the document is what somebody
 * typed, and only the picture is wrong.
 *
 * <h2>What is pinned</h2>
 *
 * Not the exact curve — that is a matter of taste and may be tuned. What is
 * pinned is the property: between any two keys, the value stays between them.
 */
class NoOvershootTest {

	private static Track track(int... pairs) {
		List<Key> keys = new java.util.ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			keys.add(new Key(pairs[i], pairs[i + 1] / 1000f, Key.Ease.SMOOTH));
		}
		return new Track("кто", "что", keys);
	}

	/** The highest and lowest the track reaches between two moments. */
	private static float[] reach(Track track, double from, double to) {
		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (double t = from; t <= to; t += 0.25) {
			float v = track.valueAt(t);
			low = Math.min(low, v);
			high = Math.max(high, v);
		}
		return new float[] { low, high };
	}

	@Test
	@DisplayName("a level stretch stays level, whatever comes after it")
	void theReportedCase() {
		// Six tenths at nought, six tenths at ten seconds, two tenths at thirteen.
		Track fade = track(0, 600, 200, 600, 260, 200);
		float[] flat = reach(fade, 0, 200);
		assertEquals(0.6f, flat[0], 1e-4, "it dipped below the level it was held at");
		assertEquals(0.6f, flat[1], 1e-4, "it rose above the level it was held at");
	}

	@Test
	@DisplayName("and it still falls away smoothly afterwards")
	void theFadeStillFades() {
		// The flatness must not have been bought by making everything linear: the
		// point of the curve is that a move out of a hold is not a hinge.
		Track fade = track(0, 600, 200, 600, 260, 200);
		assertEquals(0.6f, fade.valueAt(200), 1e-4);
		assertEquals(0.2f, fade.valueAt(260), 1e-4);
		assertTrue(fade.valueAt(205) > fade.valueAt(230), "it is going down");
		// Leaving the plateau it is level, so the first moments after it move less
		// than the middle of the fade does. That is the easing.
		double early = fade.valueAt(200) - fade.valueAt(206);
		double middle = fade.valueAt(227) - fade.valueAt(233);
		assertTrue(middle > early, "it eases out of the hold rather than hinging");
	}

	@Test
	@DisplayName("a peak is a peak and a trough is a trough")
	void extremesAreNotExceeded() {
		// An arm swung up and back down must not go higher than it was keyed to.
		Track swing = track(0, 0, 20, 900, 40, 0);
		float[] over = reach(swing, 0, 40);
		assertTrue(over[1] <= 0.9f + 1e-4, "swung past the top: " + over[1]);
		assertTrue(over[0] >= -1e-4, "dipped below the bottom: " + over[0]);
	}

	@Test
	@DisplayName("no segment ever leaves the range of its own two keys")
	void nothingEverLeavesItsSegment() {
		// The general property, over a shape awkward enough to break a naive spline:
		// uneven spacing, a plateau, a spike and a long tail.
		Track awkward = track(0, 100, 5, 100, 7, 900, 60, 880, 61, 300, 200, 300);
		List<Key> keys = awkward.keys();
		for (int i = 0; i + 1 < keys.size(); i++) {
			Key a = keys.get(i);
			Key b = keys.get(i + 1);
			float low = Math.min(a.value(), b.value());
			float high = Math.max(a.value(), b.value());
			float[] went = reach(awkward, a.at(), b.at());
			assertTrue(went[0] >= low - 1e-4,
				"between " + a.at() + " and " + b.at() + " it fell to " + went[0]);
			assertTrue(went[1] <= high + 1e-4,
				"between " + a.at() + " and " + b.at() + " it rose to " + went[1]);
		}
	}

	@Test
	@DisplayName("two keys are still exactly a straight line")
	void twoKeysAreStraight() {
		Track ramp = track(0, 0, 100, 1000);
		for (int tick = 0; tick <= 100; tick += 5) {
			assertEquals(tick / 100f, ramp.valueAt(tick), 1e-5, "at " + tick);
		}
	}
}
