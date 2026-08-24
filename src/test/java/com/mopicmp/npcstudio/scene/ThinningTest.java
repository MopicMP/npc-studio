package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Thinning a recording, checked without starting the game.
 *
 * This is the layer where a mistake is least findable by looking. A thinning that
 * keeps too much makes an animation nobody can edit, and one that keeps too
 * little makes it judder — and "it juddered" points at the recording, the curve,
 * the frame rate and the playback before it points here. So what is pinned is the
 * guarantee itself: whatever comes out plays within the tolerance of what went
 * in, everywhere, and not merely at the keys that survived.
 */
class ThinningTest {

	private static Track recorded(float[] values) {
		Track track = Track.of("рулевой", Channels.YAW);
		for (int tick = 0; tick < values.length; tick++) {
			track = track.with(Key.at(tick, values[tick]));
		}
		return track;
	}

	/** The worst the thinned curve disagrees with the recording, at any recorded tick. */
	private static float worstGap(Track recorded, Track thinned) {
		float worst = 0;
		for (Key key : recorded.keys()) {
			worst = Math.max(worst, Math.abs(thinned.valueAt(key.at()) - key.value()));
		}
		return worst;
	}

	@Test
	@DisplayName("a straight climb comes down to its two ends")
	void aRampIsTwoKeys() {
		float[] values = new float[201];
		for (int tick = 0; tick < values.length; tick++) values[tick] = tick * 0.5f;

		Track thinned = Thinning.thinned(recorded(values), 0.5f);

		assertEquals(2, thinned.keys().size(), "nothing in the middle says anything new");
		assertEquals(0f, thinned.keys().get(0).value(), 1e-4);
		assertEquals(100f, thinned.keys().get(1).value(), 1e-4);
	}

	@Test
	@DisplayName("a value that never moves comes down to its two ends")
	void aHoldIsTwoKeys() {
		float[] values = new float[120];
		java.util.Arrays.fill(values, 42f);

		assertEquals(2, Thinning.thinned(recorded(values), 0.5f).keys().size());
	}

	@Test
	@DisplayName("a hand-turned wheel keeps its shape and loses most of its keys")
	void aWheelThinsAndStaysTrue() {
		// Three seconds of somebody turning a wheel: not even, not smooth, and
		// wandering the way a hand does.
		float[] values = new float[60];
		float at = 0;
		for (int tick = 0; tick < values.length; tick++) {
			at += 6 + (float) Math.sin(tick * 0.7) * 2.5f;
			values[tick] = at;
		}
		Track dense = recorded(values);

		Track thinned = Thinning.thinned(dense, Thinning.ANGLE_TOLERANCE);

		assertTrue(thinned.keys().size() < dense.keys().size() / 2,
			"kept " + thinned.keys().size() + " of " + dense.keys().size());
		// The guarantee, and the only one worth having: it is not "the keys we kept
		// are right", it is "everything in between is right too".
		assertTrue(worstGap(dense, thinned) <= Thinning.ANGLE_TOLERANCE,
			"the played curve left the recording by " + worstGap(dense, thinned));
	}

	@Test
	@DisplayName("a sharp corner is kept, because a curve through it is not the same movement")
	void aCornerSurvives() {
		// Up for a second, then straight back down. Thinning to the two ends would
		// be a movement that never went anywhere.
		float[] values = new float[41];
		for (int tick = 0; tick <= 20; tick++) values[tick] = tick * 3f;
		for (int tick = 21; tick <= 40; tick++) values[tick] = (40 - tick) * 3f;
		Track dense = recorded(values);

		Track thinned = Thinning.thinned(dense, Thinning.ANGLE_TOLERANCE);

		assertTrue(thinned.keys().size() >= 3, "the top of the movement has to be a key");
		assertTrue(worstGap(dense, thinned) <= Thinning.ANGLE_TOLERANCE);
		assertEquals(60f, thinned.valueAt(20), Thinning.ANGLE_TOLERANCE, "and it reaches the top");
	}

	@Test
	@DisplayName("the first and last moments are never moved")
	void theEndsAreKept() {
		float[] values = new float[80];
		for (int tick = 0; tick < values.length; tick++) {
			values[tick] = (float) Math.sin(tick * 0.3) * 30;
		}
		Track dense = recorded(values);

		Track thinned = Thinning.thinned(dense, Thinning.ANGLE_TOLERANCE);

		assertEquals(dense.firstMoment(), thinned.firstMoment());
		assertEquals(dense.lastMoment(), thinned.lastMoment());
		assertEquals(dense.valueAt(dense.firstMoment()), thinned.valueAt(thinned.firstMoment()), 1e-4);
		assertEquals(dense.valueAt(dense.lastMoment()), thinned.valueAt(thinned.lastMoment()), 1e-4);
	}

	@Test
	@DisplayName("a tighter tolerance keeps more, and never fewer")
	void toleranceIsMonotone() {
		float[] values = new float[100];
		for (int tick = 0; tick < values.length; tick++) {
			values[tick] = (float) (Math.sin(tick * 0.25) * 40 + Math.cos(tick * 0.11) * 10);
		}
		Track dense = recorded(values);

		int loose = Thinning.thinned(dense, 4f).keys().size();
		int tight = Thinning.thinned(dense, 0.2f).keys().size();

		assertTrue(tight >= loose, "tight kept " + tight + ", loose kept " + loose);
		assertTrue(worstGap(dense, Thinning.thinned(dense, 0.2f)) <= 0.2f);
		assertTrue(worstGap(dense, Thinning.thinned(dense, 4f)) <= 4f);
	}

	@Test
	@DisplayName("noise nothing can smooth ends as the recording rather than as a loop")
	void unthinnableIsLeftAlone() {
		// Alternating every tick: there is no curve through this within any small
		// tolerance, and the honest outcome is to keep it.
		float[] values = new float[40];
		for (int tick = 0; tick < values.length; tick++) values[tick] = tick % 2 == 0 ? 0 : 30;
		Track dense = recorded(values);

		Track thinned = Thinning.thinned(dense, 0.5f);

		assertEquals(dense.keys().size(), thinned.keys().size());
	}

	@Test
	@DisplayName("a track of two keys or fewer is already as thin as it goes")
	void shortTracksAreUntouched() {
		Track two = Track.of("a", Channels.YAW).with(Key.at(0, 0)).with(Key.at(10, 90));
		assertEquals(two, Thinning.thinned(two, 0.5f));
	}

	@Test
	@DisplayName("each kind of channel is thinned in its own units")
	void tolerancesAreByKind() {
		// A degree is a small angle and a block is an enormous distance. One number
		// for both is either useless for one or destructive for the other.
		assertEquals(Thinning.ANGLE_TOLERANCE, Thinning.toleranceFor(Channels.YAW), 1e-6);
		assertEquals(Thinning.ANGLE_TOLERANCE,
			Thinning.toleranceFor(Channels.of("wheel", Channels.TURN_Z)), 1e-6);
		assertEquals(Thinning.PLACE_TOLERANCE, Thinning.toleranceFor(Channels.X), 1e-6);
		assertEquals(Thinning.PLACE_TOLERANCE,
			Thinning.toleranceFor(Channels.of("head", Channels.SHIFT_Y)), 1e-6);
		assertEquals(Thinning.PLAIN_TOLERANCE, Thinning.toleranceFor(Channels.SCALE), 1e-6);

		// A fold is in degrees and is not an angle: it cannot pass through a full
		// turn, so recording must not treat it as one — but half a degree is still
		// half a degree, so thinning must. Left on the plain tolerance it would keep
		// almost every key of a recorded fold, since a hundredth of a degree is
		// below what anybody can draw.
		assertEquals(Thinning.ANGLE_TOLERANCE,
			Thinning.toleranceFor(Channels.of("rightArm", Channels.BEND)), 1e-6);
		assertFalse(Channels.isAngle(Channels.of("rightArm", Channels.BEND)),
			"a fold does not wrap, so recording must not unwrap it");
	}

	@Test
	@DisplayName("tidying a scene leaves the parts and the cues alone")
	void tidyingIsOnlyAboutKeys() {
		Scene scene = Scene.empty("корабль")
			.with(Role.of("рулевой", Role.Kind.CHARACTER).boundTo("abc"))
			.with(Cue.text(10, "Земля!", 20));
		for (int tick = 0; tick < 60; tick++) {
			scene = scene.keyed("рулевой", Channels.YAW, Key.at(tick, tick * 2f));
		}

		Scene tidy = Thinning.tidied(scene);

		assertEquals(scene.cast(), tidy.cast());
		assertEquals(scene.cues(), tidy.cues());
		assertEquals(scene.length(), tidy.length());
		assertEquals(2, tidy.track("рулевой", Channels.YAW).keys().size());
		assertTrue(Thinning.keyCount(tidy) < Thinning.keyCount(scene));
	}

	@Test
	@DisplayName("tidying one participant leaves everybody else's recording untouched")
	void tidyingOnePart() {
		Scene scene = Scene.empty("корабль");
		for (int tick = 0; tick < 60; tick++) {
			scene = scene.keyed("рулевой", Channels.YAW, Key.at(tick, tick * 2f));
			scene = scene.keyed("матрос", Channels.YAW, Key.at(tick, tick * 2f));
		}

		Scene tidy = Thinning.tidied(scene, "рулевой");

		assertEquals(2, tidy.track("рулевой", Channels.YAW).keys().size());
		assertEquals(60, tidy.track("матрос", Channels.YAW).keys().size());
	}
}
