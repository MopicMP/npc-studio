package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A part that starts over while the scene around it goes on.
 *
 * <h2>The shape this is for</h2>
 *
 * Three characters on a deck, each doing five seconds of movement that has no
 * sixth second — the sixth second is the first one again. The film is as long as
 * the music, which is a minute, and the words on the screen keep changing the
 * whole way through.
 *
 * Looping the scene is the obvious answer and is no answer: it would make all
 * three the same length, restart the music every five seconds and put the first
 * subtitle back on the screen twelve times. What repeats is one participant's
 * movement, so the number belongs to the participant.
 *
 * <h2>What has to hold</h2>
 *
 * That the fold happens in one place. Everything that reads a scene — playback,
 * the handles, the outlines, the capture, the panels — goes through
 * {@link Scene#valueAt}, so a loop applied there applies everywhere at once
 * rather than in whichever of them somebody remembered. A second copy of this
 * arithmetic anywhere else is how a character comes to be drawn in one pose and
 * clicked in another.
 */
class RepeatTest {

	/** A part that lifts an arm from nought to ninety over two seconds. */
	private static Scene deck(int repeat) {
		return Scene.empty("корабль").lengthened(20 * 60)
			.with(Role.of("матрос", Role.Kind.CHARACTER).repeating(repeat))
			.keyed("матрос", Channels.of("rightArm", Channels.TURN_X), Key.at(0, 0))
			.keyed("матрос", Channels.of("rightArm", Channels.TURN_X), Key.at(40, 90));
	}

	private static float armAt(Scene scene, double tick) {
		return scene.valueAt("матрос", Channels.of("rightArm", Channels.TURN_X), tick, -1);
	}

	@Test
	@DisplayName("a part with no loop plays once and then holds")
	void nothingChangesWithoutALoop() {
		// The behaviour every scene has had until now, and it has to be exactly
		// unchanged: a part that says nothing about repeating must read the same at
		// every moment it used to.
		Scene once = deck(0);
		assertEquals(0, armAt(once, 0), 1e-4);
		assertEquals(90, armAt(once, 40), 1e-4);
		assertEquals(90, armAt(once, 400), 1e-4, "past the last key it holds");
		assertEquals(90, armAt(once, 1200), 1e-4);
	}

	@Test
	@DisplayName("a looping part reads the same at every lap")
	void itComesRoundAgain() {
		// Five seconds of movement under a minute of scene. The twelfth lap has to be
		// the first one, exactly, or a shot filmed at fifty seconds does not match the
		// one judged at two.
		Scene looped = deck(100);
		for (int lap = 0; lap < 12; lap++) {
			double from = lap * 100;
			assertEquals(armAt(looped, 0), armAt(looped, from), 1e-4, "lap " + lap + " start");
			assertEquals(armAt(looped, 40), armAt(looped, from + 40), 1e-4, "lap " + lap + " middle");
			assertEquals(armAt(looped, 99), armAt(looped, from + 99), 1e-4, "lap " + lap + " end");
		}
	}

	@Test
	@DisplayName("the first lap is untouched, so nothing is folded that need not be")
	void theFirstLapIsItself() {
		Scene looped = deck(100);
		for (double tick = 0; tick < 100; tick += 0.5) {
			assertEquals(armAt(deck(0), tick), armAt(looped, tick), 1e-6, "at " + tick);
		}
	}

	@Test
	@DisplayName("a loop folds one part and leaves the rest of the scene alone")
	void onlyThatPartRepeats() {
		// The whole reason this is on the part and not the scene. The camera has to be
		// free to travel for the full minute while the sailor sways in five seconds.
		Scene mixed = deck(100)
			.with(Role.of("камера", Role.Kind.CAMERA))
			.keyed("камера", Channels.X, Key.at(0, 0))
			.keyed("камера", Channels.X, Key.at(1200, 120));

		assertEquals(armAt(mixed, 0), armAt(mixed, 500), 1e-4, "the sailor has come round");
		float early = mixed.valueAt("камера", Channels.X, 0, -1);
		float late = mixed.valueAt("камера", Channels.X, 500, -1);
		assertTrue(late > early + 10,
			"the camera went round with him: " + early + " then " + late);
	}

	@Test
	@DisplayName("cues are the scene's own clock and are never folded")
	void thewordsKeepChanging() {
		// The other half of what was asked for. Subtitles run the length of the music
		// and a part that repeats must not drag them back to the beginning with it.
		Scene said = deck(100)
			.with(Cue.text(0, "Земля!", 60))
			.with(Cue.text(600, "Наконец-то.", 60));

		assertEquals("Земля!", said.showingAt(10).get(0).what());
		assertTrue(said.showingAt(500).isEmpty(),
			"the first line came back with the sailor's loop");
		assertEquals("Наконец-то.", said.showingAt(610).get(0).what());
	}

	@Test
	@DisplayName("a loop survives being written down and read back")
	void itSurvivesTheFile() {
		Scene back = SceneIO.read(SceneIO.write(deck(100)));
		assertEquals(100, back.role("матрос").repeat());
		assertEquals(armAt(back, 0), armAt(back, 500), 1e-4);

		// And a part that plays once does not carry the field at all, so a file stays
		// readable rather than gaining a "repeat": 0 on every line of the cast.
		var cast = SceneIO.write(deck(0)).getAsJsonArray("cast").get(0).getAsJsonObject();
		assertTrue(!cast.has("repeat"));
	}

	@Test
	@DisplayName("a scene written before loops existed reads as no loop")
	void oldFilesStillOpen() {
		var json = com.google.gson.JsonParser.parseString("""
			{"name":"корабль","length":100,
			 "cast":[{"name":"матрос","kind":"CHARACTER","bound":""}],
			 "tracks":[],"cues":[]}
			""").getAsJsonObject();
		assertEquals(0, SceneIO.read(json).role("матрос").repeat());
	}
}
