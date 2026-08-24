package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The clock and the file, checked without starting the game.
 *
 * The cursor is here because "did that sound play once" is not a thing anybody
 * can see by watching — a cue fired twice on the same frame sounds exactly like
 * a cue fired once and slightly wrong. The file is here because a scene that
 * fails to load is a scene somebody spent an afternoon on.
 */
class PlaybackTest {

	private static final float CLOSE = 1e-4f;

	/** Every cue the step went past, in order. */
	private static List<Cue> fired(Scene scene, Playhead.Step step) {
		List<Cue> heard = new ArrayList<>();
		for (Playhead.Span span : step.covered()) heard.addAll(scene.between(span.after(), span.upTo()));
		return heard;
	}

	@Nested
	@DisplayName("the cursor")
	class Cursor {

		@Test
		@DisplayName("a paused cursor stays where it is and passes nothing")
		void pausedIsPaused() {
			Playhead.Step step = Playhead.resting().advanced(10, 100);

			assertEquals(0, step.head().at(), CLOSE);
			assertTrue(step.covered().isEmpty());
		}

		@Test
		@DisplayName("speed is a multiplier on the time that passed")
		void speedScalesTheStep() {
			Playhead head = Playhead.resting().playing(true).atSpeed(0.5f);
			assertEquals(5, head.advanced(10, 100).head().at(), CLOSE);
			assertEquals(20, head.atSpeed(2).advanced(10, 100).head().at(), CLOSE);
		}

		@Test
		@DisplayName("scrubbing plays nothing, in either direction")
		void scrubbingIsLooking() {
			Playhead head = Playhead.resting().playing(true).scrubbedTo(80, 100);

			assertEquals(80, head.at(), CLOSE);
			// Dragging back across a sound is not the sound happening again, and
			// dragging forwards over one is not it happening early.
			assertEquals(20, head.scrubbedTo(20, 100).at(), CLOSE);
			assertEquals(0, head.scrubbedTo(-50, 100).at(), CLOSE, "and it cannot go before the start");
			assertEquals(100, head.scrubbedTo(400, 100).at(), CLOSE, "or past the end");
		}

		@Test
		@DisplayName("a scene that does not loop stops at its end")
		void withoutLoopingItStops() {
			Playhead.Step step = Playhead.resting().playing(true).looping(false)
				.advanced(30, 20);

			assertEquals(20, step.head().at(), CLOSE);
			assertFalse(step.head().playing(), "and it says it has finished");
		}

		@Test
		@DisplayName("a looping scene comes round with the remainder, not from zero")
		void loopingKeepsTheRemainder() {
			Playhead.Step step = Playhead.resting().playing(true).advanced(23, 20);

			// Landing on zero instead would drop three ticks every lap, which over a
			// looping scene is a drift nobody can find by looking.
			assertEquals(3, step.head().at(), CLOSE);
			assertTrue(step.head().playing());
		}
	}

	@Nested
	@DisplayName("cues along the way")
	class Firing {

		private final Scene scene = Scene.empty("корабль")
			.lengthened(20)
			.with(Cue.sound(0, "начало"))
			.with(Cue.sound(10, "волна"))
			.with(Cue.sound(20, "конец"));

		@Test
		@DisplayName("a cue on a boundary fires once across two steps")
		void onceAcrossTwoSteps() {
			Playhead head = Playhead.resting().playing(true).scrubbedTo(9.5, 20);

			Playhead.Step first = head.advanced(1, 20);
			assertEquals(List.of("волна"), fired(scene, first).stream().map(Cue::what).toList());

			// The second step starts exactly where the first ended. Both ends
			// included, this is where the sound would be heard for the second time.
			Playhead.Step second = first.head().advanced(1, 20);
			assertTrue(fired(scene, second).isEmpty(), "and not again on the next step");
		}

		@Test
		@DisplayName("the first tick of a scene belongs to every lap")
		void theOpeningTickFiresEachLap() {
			// No ordinary step can cover it: a span begins after where the cursor
			// already was, so tick zero is stepped over on the way out of it.
			assertEquals(List.of("начало"),
				fired(scene, Playhead.resting().begun()).stream().map(Cue::what).toList());

			Playhead.Step round = Playhead.resting().playing(true).scrubbedTo(19, 20).advanced(2, 20);
			assertEquals(List.of("конец", "начало"),
				fired(scene, round).stream().map(Cue::what).toList(),
				"the end of one lap and the start of the next, in that order");
		}

		@Test
		@DisplayName("a freeze longer than the scene plays it through once, not forty times")
		void aFreezeIsNotAConcert() {
			// The game was alt-tabbed for five seconds. Playing every cue of every lap
			// at once is a burst of overlapping sounds and it is nobody's intent.
			Playhead.Step step = Playhead.resting().playing(true).advanced(100, 20);

			assertEquals(3, fired(scene, step).size(), "each cue once, and once only");
			assertEquals(0, step.head().at(), CLOSE, "landing where the arithmetic says");
			assertTrue(step.head().playing());
		}

		@Test
		@DisplayName("a freeze in a scene that does not loop leaves it finished")
		void aFreezeCanEndTheScene() {
			Playhead.Step step = Playhead.resting().playing(true).looping(false).advanced(100, 20);

			assertEquals(20, step.head().at(), CLOSE);
			assertFalse(step.head().playing());
		}

		@Test
		@DisplayName("unpausing in the middle picks up where it was and sounds nothing")
		void resumingIsNotStarting() {
			Playhead stopped = Playhead.resting().playing(true).scrubbedTo(10, 20).playing(false);

			Playhead.Step step = stopped.started(20);

			assertEquals(10, step.head().at(), CLOSE, "not back to the top");
			assertTrue(step.head().playing());
			// Otherwise pausing and unpausing sounds whatever cue you are standing on,
			// every time.
			assertTrue(fired(scene, step).isEmpty());
		}

		@Test
		@DisplayName("playing a finished scene starts it again rather than doing nothing")
		void playingAFinishedSceneRewinds() {
			Playhead finished = Playhead.resting().looping(false).scrubbedTo(20, 20);

			Playhead.Step step = finished.started(20);

			assertEquals(0, step.head().at(), CLOSE);
			assertTrue(step.head().playing());
			assertEquals(List.of("начало"), fired(scene, step).stream().map(Cue::what).toList());
		}

		@Test
		@DisplayName("a slow step that crosses no tick passes nothing")
		void aStepInsideOneTickIsSilent() {
			Playhead head = Playhead.resting().playing(true).scrubbedTo(10.2, 20);
			assertTrue(fired(scene, head.advanced(0.5, 20)).isEmpty());
		}
	}

	@Nested
	@DisplayName("the file")
	class Written {

		private Scene aSceneWithEverythingInIt() {
			return Scene.empty("палуба")
				.lengthened(240)
				.with(Role.of("рулевой", Role.Kind.CHARACTER).boundTo("11111111-2222-3333-4444-555555555555"))
				.with(Role.of("штурвал", Role.Kind.OBJECT))
				.with(Role.of("камера", Role.Kind.CAMERA))
				.keyed("рулевой", Channels.X, Key.at(0, 12.5f))
				.keyed("рулевой", Channels.X, new Key(40, 13.75f, Key.Ease.LINEAR))
				.keyed("штурвал", Channels.of("wheel", Channels.TURN_Z),
					new Key(0, 0, Key.Ease.HOLD))
				.keyed("штурвал", Channels.of("wheel", Channels.TURN_Z), Key.at(120, 1080))
				.with(Cue.sound(20, "npc_studio.wave"))
				.with(Cue.text(60, "Земля!", 40));
		}

		@Test
		@DisplayName("a scene written and read back is the same scene")
		void roundTrips() {
			Scene before = aSceneWithEverythingInIt();
			Scene after = SceneIO.read(SceneIO.write(before));

			assertEquals(before, after, "everything, down to the eases and the binding");
		}

		@Test
		@DisplayName("it survives a trip through actual text")
		void roundTripsThroughText() {
			Scene before = aSceneWithEverythingInIt();
			String text = SceneIO.write(before).toString();
			Scene after = SceneIO.read(JsonParser.parseString(text).getAsJsonObject());

			assertEquals(before, after);
			assertEquals(1080f, after.valueAt("штурвал",
				Channels.of("wheel", Channels.TURN_Z), 120, -1), CLOSE);
		}

		@Test
		@DisplayName("an empty file reads as an empty scene rather than as a failure")
		void emptyIsFine() {
			Scene scene = SceneIO.read(new JsonObject());

			assertNotNull(scene);
			assertTrue(scene.cast().isEmpty());
			assertTrue(scene.tracks().isEmpty());
			assertEquals(Scene.RATE * 5, scene.length());
		}

		@Test
		@DisplayName("one bad key does not lose the performance around it")
		void oneBadKeyIsOneBadKey() {
			// These files sit in a world folder where somebody will open them in an
			// editor. Refusing the whole scene over one mistyped line is worse than
			// loading it with one line missing.
			JsonObject json = JsonParser.parseString("""
				{
				  "name": "палуба",
				  "length": 100,
				  "cast": [{"name": "рулевой", "kind": "CHARACTER"}],
				  "tracks": [{"subject": "рулевой", "channel": "yaw",
				    "keys": [[0, 0, "LINEAR"], ["мусор"], [20, 90, "LINEAR"]]}]
				}""").getAsJsonObject();

			Scene scene = SceneIO.read(json);
			Track track = scene.track("рулевой", "yaw");

			assertNotNull(track);
			assertEquals(2, track.keys().size());
			assertEquals(45f, track.valueAt(10), CLOSE, "and what is left still plays");
		}

		@Test
		@DisplayName("an ease nobody recognises is smooth rather than a refusal")
		void anUnknownEaseIsTheDefault() {
			JsonObject json = JsonParser.parseString("""
				{"tracks": [{"subject": "a", "channel": "yaw", "keys": [[0, 0, "wobbly"]]}]}""")
				.getAsJsonObject();

			assertEquals(Key.Ease.SMOOTH,
				SceneIO.read(json).track("a", "yaw").keys().get(0).ease());
		}

		@Test
		@DisplayName("a track belonging to nobody is dropped rather than given an owner")
		void anOwnerlessTrackIsDropped() {
			JsonObject json = JsonParser.parseString("""
				{"tracks": [{"channel": "yaw", "keys": [[0, 90]]},
				            {"subject": "a", "keys": [[0, 90]]}]}""").getAsJsonObject();

			// Inventing an owner would put somebody's arm somewhere they never asked.
			assertTrue(SceneIO.read(json).tracks().isEmpty());
		}
	}
}
