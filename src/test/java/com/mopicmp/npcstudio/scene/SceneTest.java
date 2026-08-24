package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The scene format, checked without starting the game.
 *
 * This is the layer where a mistake does not crash and does not show up in a
 * log — it comes out as an animation that is subtly wrong, and by the time
 * anybody notices, the suspect is whichever part they happened to be editing.
 * So the arithmetic is pinned here, where a wrong answer has a name.
 */
class SceneTest {

	/** Room to be exact about halves and quarters; these are all short sums. */
	private static final float CLOSE = 1e-4f;

	@Nested
	@DisplayName("a track")
	class OneTrack {

		@Test
		@DisplayName("says nothing when it has no keys, and says so")
		void emptyIsSilent() {
			Track track = Track.of("рулевой", Channels.YAW);
			assertFalse(track.speaks(), "an empty track leaves its subject alone");
			assertEquals(0f, track.valueAt(7), CLOSE, "and answers nothing meaningful");
		}

		@Test
		@DisplayName("holds outside its keys rather than carrying on past them")
		void holdsAtTheEnds() {
			Track track = Track.of("рулевой", Channels.YAW)
				.with(Key.at(10, 100))
				.with(Key.at(20, 200));

			assertEquals(100f, track.valueAt(0), CLOSE, "before the first key");
			assertEquals(100f, track.valueAt(10), CLOSE);
			assertEquals(200f, track.valueAt(20), CLOSE);
			// Extrapolating is how an arm that finished a wave carries on into the
			// floor: the data says nothing about this stretch of time.
			assertEquals(200f, track.valueAt(9999), CLOSE, "after the last key");
		}

		@Test
		@DisplayName("passes exactly through every key it was given")
		void goesThroughItsKeys() {
			Track track = Track.of("штурвал", Channels.of("wheel", Channels.TURN_Z))
				.with(Key.at(0, 0))
				.with(Key.at(3, 40))
				.with(Key.at(11, -20))
				.with(Key.at(30, 5));

			for (Key key : track.keys()) {
				assertEquals(key.value(), track.valueAt(key.at()), CLOSE,
					"the curve is wrong at tick " + key.at());
			}
		}

		@Test
		@DisplayName("a straight line stays straight, however unevenly the keys sit")
		void smoothDoesNotBulgeOnALine() {
			// The textbook Catmull-Rom tangent assumes the keys are evenly spaced,
			// and thinning a recording never produces that. With the uniform form
			// this line bulges by several units in the middle.
			Track track = Track.of("штурвал", Channels.YAW)
				.with(Key.at(0, 0))
				.with(Key.at(5, 50))
				.with(Key.at(40, 400))
				.with(Key.at(42, 420));

			for (int tick = 0; tick <= 42; tick++) {
				assertEquals(tick * 10f, track.valueAt(tick), 1e-2f,
					"straight data came out curved at tick " + tick);
			}
		}

		@Test
		@DisplayName("linear is the plain midpoint")
		void linearIsLinear() {
			Track track = Track.of("матрос", Channels.Y)
				.with(new Key(0, 0, Key.Ease.LINEAR))
				.with(new Key(8, 4, Key.Ease.LINEAR));

			assertEquals(2f, track.valueAt(4), CLOSE);
			assertEquals(0.5f, track.valueAt(1), CLOSE);
			assertEquals(3.5f, track.valueAt(7), CLOSE);
		}

		@Test
		@DisplayName("hold does not move until the next key, and then it jumps")
		void holdHolds() {
			Track track = Track.of("свет", Channels.SCALE)
				.with(new Key(0, 1, Key.Ease.HOLD))
				.with(new Key(10, 5, Key.Ease.HOLD));

			assertEquals(1f, track.valueAt(0), CLOSE);
			assertEquals(1f, track.valueAt(9.99), CLOSE, "still nothing a hair before");
			assertEquals(5f, track.valueAt(10), CLOSE, "and everything at the key");
		}

		@Test
		@DisplayName("a curve leaving a hold starts level rather than at a borrowed slope")
		void aCurveAfterAHoldDoesNotLeanOnIt() {
			// The held stretch says nothing about where the next one is heading. Left
			// to the ordinary rule the curve would take its opening slope from a
			// neighbour it has no relationship with, and lurch backwards out of the
			// hold before going forwards.
			Track track = Track.of("матрос", Channels.Y)
				.with(new Key(0, 100, Key.Ease.HOLD))
				.with(new Key(10, 0, Key.Ease.SMOOTH))
				.with(new Key(20, 10, Key.Ease.SMOOTH));

			for (double at = 10; at <= 20; at += 0.5) {
				float value = track.valueAt(at);
				assertTrue(value >= -CLOSE && value <= 10 + CLOSE,
					"the curve left its keys' range at " + at + ": " + value);
			}
		}

		@Test
		@DisplayName("a key put where one already is replaces it")
		void keysAreOnePerTick() {
			Track track = Track.of("матрос", Channels.YAW)
				.with(Key.at(5, 10))
				.with(Key.at(5, 90));

			assertEquals(1, track.keys().size(), "adjusting a pose is not adding a key");
			assertEquals(90f, track.valueAt(5), CLOSE);
		}

		@Test
		@DisplayName("keys handed over in the wrong order sort themselves out")
		void keysComeBackInOrder() {
			Track track = new Track("матрос", Channels.YAW,
				List.of(Key.at(30, 3), Key.at(10, 1), Key.at(20, 2)));

			assertEquals(List.of(10, 20, 30),
				track.keys().stream().map(Key::at).toList());
		}

		@Test
		@DisplayName("two keys claiming one tick are repaired rather than believed")
		void duplicateTicksAreRepaired() {
			Track track = new Track("матрос", Channels.YAW,
				List.of(Key.at(10, 1), Key.at(10, 2)));

			assertEquals(1, track.keys().size());
			assertEquals(1f, track.keys().get(0).value(), CLOSE, "the earlier one is kept");
		}

		@Test
		@DisplayName("dragging a key onto another one replaces it")
		void movingOntoAKeyReplacesIt() {
			Track track = Track.of("матрос", Channels.YAW)
				.with(Key.at(0, 0))
				.with(Key.at(10, 10))
				.with(Key.at(20, 20));

			Track moved = track.moved(0, 20);

			assertEquals(2, moved.keys().size());
			assertNull(moved.keyAt(0));
			assertEquals(0f, moved.keyAt(20).value(), CLOSE, "the dragged key won");
		}

		@Test
		@DisplayName("shifting moves the whole performance and nothing else")
		void shiftingMovesEverything() {
			Track track = Track.of("матрос", Channels.YAW)
				.with(Key.at(0, 1))
				.with(Key.at(10, 2))
				.shifted(15);

			assertEquals(List.of(15, 25), track.keys().stream().map(Key::at).toList());
			assertEquals(1f, track.valueAt(15), CLOSE, "and the values are untouched");
		}

		@Test
		@DisplayName("a key cannot be before the beginning")
		void ticksAreNotNegative() {
			assertEquals(0, Key.at(-40, 1).at());
			assertEquals(0, Track.of("матрос", Channels.YAW)
				.with(Key.at(10, 1)).shifted(-100).firstMoment());
		}
	}

	@Nested
	@DisplayName("the parts")
	class Cast {

		@Test
		@DisplayName("a part with nobody in it is an ordinary state")
		void anEmptyPartIsFine() {
			Role role = Role.of("рулевой", Role.Kind.CHARACTER);
			assertFalse(role.cast(), "nobody is playing this yet");
			assertTrue(role.boundTo("0-0-0-0-1").cast());
		}

		@Test
		@DisplayName("removing a part takes its tracks with it")
		void removingAPartTakesItsTracks() {
			Scene scene = Scene.empty("корабль")
				.with(Role.of("рулевой", Role.Kind.CHARACTER))
				.with(Role.of("штурвал", Role.Kind.OBJECT))
				.keyed("рулевой", Channels.YAW, Key.at(0, 0))
				.keyed("штурвал", Channels.of("wheel", Channels.TURN_Z), Key.at(0, 0));

			Scene left = scene.withoutRole("рулевой");

			assertNull(left.role("рулевой"));
			assertTrue(left.tracksOf("рулевой").isEmpty(), "no rows belonging to nobody");
			assertEquals(1, left.tracks().size(), "and nothing else went");
		}

		@Test
		@DisplayName("renaming a part brings its tracks along in one step")
		void renamingCarriesTheTracks() {
			Scene scene = Scene.empty("корабль")
				.with(Role.of("матрос", Role.Kind.CHARACTER))
				.keyed("матрос", Channels.YAW, Key.at(4, 90));

			Scene renamed = scene.renaming("матрос", "боцман");

			assertNull(renamed.role("матрос"));
			assertNotNull(renamed.role("боцман"));
			// Done as two steps there would be a moment where the tracks point at a
			// name nothing answers to.
			assertEquals(90f, renamed.valueAt("боцман", Channels.YAW, 4, -1), CLOSE);
		}

		@Test
		@DisplayName("a part added twice under one name is one part")
		void namesAreUnique() {
			Scene scene = Scene.empty("корабль")
				.with(Role.of("матрос", Role.Kind.CHARACTER))
				.with(Role.of("матрос", Role.Kind.CHARACTER).boundTo("abc"));

			assertEquals(1, scene.cast().size());
			assertEquals("abc", scene.role("матрос").bound(), "the later one won");
		}

		@Test
		@DisplayName("renaming onto a name already taken does nothing")
		void renamingRefusesACollision() {
			Scene scene = Scene.empty("корабль")
				.with(Role.of("матрос", Role.Kind.CHARACTER))
				.with(Role.of("боцман", Role.Kind.CHARACTER));

			assertEquals(scene, scene.renaming("матрос", "боцман"));
		}
	}

	@Nested
	@DisplayName("the scene")
	class Whole {

		@Test
		@DisplayName("keying makes the track when there was not one")
		void keyingMakesTheTrack() {
			Scene scene = Scene.empty("корабль")
				.keyed("штурвал", Channels.of("wheel", Channels.TURN_Z), Key.at(0, 0));

			assertNotNull(scene.track("штурвал", Channels.of("wheel", Channels.TURN_Z)));
			assertEquals(1, scene.tracks().size());
		}

		@Test
		@DisplayName("what the scene does not mention, it does not move")
		void whatIsNotAnimatedIsLeftAlone() {
			Scene scene = Scene.empty("корабль").keyed("матрос", Channels.YAW, Key.at(0, 90));

			// The fallback is the pose the character already had. Answering zero here
			// is how an arm that nobody animated snaps to its side on the first frame.
			assertEquals(37f, scene.valueAt("матрос", Channels.PITCH, 0, 37), CLOSE);
			assertEquals(37f, scene.valueAt("никого", Channels.YAW, 0, 37), CLOSE);
			assertEquals(90f, scene.valueAt("матрос", Channels.YAW, 0, 37), CLOSE);
		}

		@Test
		@DisplayName("deleting the last key leaves the row behind")
		void anEmptyTrackSurvives() {
			Scene scene = Scene.empty("корабль")
				.keyed("матрос", Channels.YAW, Key.at(5, 1))
				.unkeyed("матрос", Channels.YAW, 5);

			Track track = scene.track("матрос", Channels.YAW);
			assertNotNull(track, "the row is what you put the next key into");
			assertFalse(track.speaks());
		}

		@Test
		@DisplayName("a scene is at least a tick long, and keys past the end are kept")
		void lengthIsItsOwn() {
			Scene scene = Scene.empty("корабль")
				.keyed("матрос", Channels.YAW, Key.at(400, 1))
				.lengthened(-5);

			assertEquals(1, scene.length(), "a scene of no length is not a scene");
			// Shortening is not deleting: the key is still there, simply not reached.
			assertEquals(400, scene.lastMoment());
			assertNotNull(scene.track("матрос", Channels.YAW).keyAt(400));
		}

		@Test
		@DisplayName("the last moment counts how long a line stays up, not when it starts")
		void lastMomentCoversCues() {
			Scene scene = Scene.empty("корабль").with(Cue.text(100, "Земля!", 60));
			assertEquals(160, scene.lastMoment());
		}
	}

	@Nested
	@DisplayName("the cues")
	class Cues {

		@Test
		@DisplayName("a cue on a tick boundary fires once, not once per step")
		void cuesFireOnce() {
			Scene scene = Scene.empty("корабль")
				.with(Cue.sound(10, "ambient.wave"))
				.with(Cue.text(20, "Земля!", 40));

			// Playback remembers the last tick it dealt with and asks for everything
			// since. Both ends included, every cue sitting on a boundary would come
			// back twice — once ending one step and once starting the next.
			assertEquals(1, scene.between(5, 10).size());
			assertEquals(0, scene.between(10, 15).size(), "and not again");
			assertEquals(2, scene.between(0, 20).size(), "a longer step catches both");
		}

		@Test
		@DisplayName("scrubbing backwards plays nothing")
		void goingBackwardsIsSilent() {
			Scene scene = Scene.empty("корабль").with(Cue.sound(10, "ambient.wave"));
			assertTrue(scene.between(30, 5).isEmpty());
		}

		@Test
		@DisplayName("a line is on the screen from its moment until its time is up")
		void textShowsForItsLength() {
			Scene scene = Scene.empty("корабль").with(Cue.text(100, "Земля!", 60));

			assertTrue(scene.showingAt(99).isEmpty());
			assertEquals(1, scene.showingAt(100).size(), "up on the moment it starts");
			assertEquals(1, scene.showingAt(159).size());
			assertTrue(scene.showingAt(160).isEmpty(), "and gone when its time is up");
		}

		@Test
		@DisplayName("cues come back in the order they happen, however they were added")
		void cuesAreOrdered() {
			Scene scene = Scene.empty("корабль")
				.with(Cue.sound(50, "b"))
				.with(Cue.sound(10, "a"))
				.with(Cue.sound(30, "c"));

			assertEquals(List.of("a", "c", "b"), scene.cues().stream().map(Cue::what).toList());
		}
	}

	@Nested
	@DisplayName("turning")
	class Wheel {

		@Test
		@DisplayName("passing three hundred and sixty carries on forwards")
		void aWheelDoesNotSpinBackwards() {
			// The failure this exists for: recorded wrapped, the series reads
			// 350, 355, 0, 5 and every reasonable playback sends the wheel three
			// hundred and fifty degrees backwards in a twentieth of a second.
			float[] straightened = Turning.continued(new float[] { 350, 355, 0, 5 });

			assertEquals(350f, straightened[0], CLOSE);
			assertEquals(355f, straightened[1], CLOSE);
			assertEquals(360f, straightened[2], CLOSE);
			assertEquals(365f, straightened[3], CLOSE);
			for (int i = 1; i < straightened.length; i++) {
				assertTrue(straightened[i] > straightened[i - 1], "the wheel went backwards");
			}
		}

		@Test
		@DisplayName("a wheel really turned back does go back")
		void goingBackIsStillGoingBack() {
			// Five to zero is five degrees back and no wrap at all; the wrap is the
			// step after it, and it has to come out as another five rather than as
			// three hundred and fifty-five forwards.
			float[] straightened = Turning.continued(new float[] { 5, 0, 355, 350 });

			assertEquals(0f, straightened[1], CLOSE);
			assertEquals(-5f, straightened[2], CLOSE, "backwards through zero");
			assertEquals(-10f, straightened[3], CLOSE);
			for (int i = 1; i < straightened.length; i++) {
				assertEquals(-5f, straightened[i] - straightened[i - 1], CLOSE,
					"the wheel changed speed at step " + i);
			}
		}

		@Test
		@DisplayName("several revolutions accumulate instead of folding into one")
		void manyTurnsAccumulate() {
			// Interpolating angles "the short way round" would make this impossible
			// to say at all: go round twice is not a thing a short way can mean.
			float[] readings = new float[61];
			for (int i = 0; i < readings.length; i++) readings[i] = Turning.wrapped(i * 18f);
			float[] straightened = Turning.continued(readings);

			assertEquals(1080f, straightened[60], CLOSE, "three whole turns");
			for (int i = 1; i < straightened.length; i++) {
				assertEquals(18f, straightened[i] - straightened[i - 1], CLOSE);
			}
		}

		@Test
		@DisplayName("a straightened recording interpolates as an even climb")
		void theWheelPlaysBackSmoothly() {
			float[] straightened = Turning.continued(new float[] { 350, 355, 0, 5 });
			Track track = Track.of("штурвал", Channels.of("wheel", Channels.TURN_Z))
				.with(Key.at(0, straightened[0]))
				.with(Key.at(1, straightened[1]))
				.with(Key.at(2, straightened[2]))
				.with(Key.at(3, straightened[3]));

			// Halfway between the two readings that straddle the wrap.
			assertEquals(357.5f, track.valueAt(1.5), 1e-2f);
		}

		@Test
		@DisplayName("wrapping is for showing a number, not for keeping one")
		void wrappingIsForReading() {
			assertEquals(0f, Turning.wrapped(1080f), CLOSE);
			assertEquals(350f, Turning.wrapped(-10f), CLOSE);
			assertEquals(5f, Turning.wrapped(365f), CLOSE);
		}

		@Test
		@DisplayName("only the channels measured in degrees count as angles")
		void anglesAreKnownByName() {
			assertTrue(Channels.isAngle(Channels.YAW));
			assertTrue(Channels.isAngle(Channels.of("wheel", Channels.TURN_Z)));
			assertFalse(Channels.isAngle(Channels.X));
			assertFalse(Channels.isAngle(Channels.of("wheel", Channels.SHIFT_X)));
		}
	}

	@Nested
	@DisplayName("channel names")
	class Naming {

		@Test
		@DisplayName("a bone's channel says which bone and which number")
		void boneChannelsComeApart() {
			String channel = Channels.of("wheel", Channels.TURN_Z);

			assertTrue(Channels.isBone(channel));
			assertEquals("wheel", Channels.boneOf(channel));
			assertEquals(Channels.TURN_Z, Channels.fieldOf(channel));
		}

		@Test
		@DisplayName("a bone with a dot in its name still comes apart correctly")
		void dottedBoneNamesSurvive() {
			String channel = Channels.of("рука.левая", Channels.TURN_X);
			assertEquals("рука.левая", Channels.boneOf(channel));
			assertEquals(Channels.TURN_X, Channels.fieldOf(channel));
		}

		@Test
		@DisplayName("a channel belonging to the whole thing has no bone")
		void wholeThingChannelsHaveNoBone() {
			assertFalse(Channels.isBone(Channels.YAW));
			assertEquals("", Channels.boneOf(Channels.YAW));
			assertEquals(Channels.YAW, Channels.fieldOf(Channels.YAW));
		}
	}
}
