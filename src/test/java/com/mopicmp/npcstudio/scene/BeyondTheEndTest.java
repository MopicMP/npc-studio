package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Putting things past the end of a scene, which is allowed and has to stay so.
 *
 * <h2>Why this is pinned rather than prevented</h2>
 *
 * "I cannot set the time past five seconds" was reported about the box that types
 * a caption's moment, and the box has no limit in it at all. What was five seconds
 * was the <em>scene</em>: five is what an empty one is made with, and until
 * recently nothing anywhere could change that.
 *
 * The tempting fix is to clamp a cue to the scene's length, so that nothing can
 * ever be put where it will not be reached. That is the wrong way round twice
 * over. Shortening a scene would then quietly delete the end of the script; and
 * writing a line at ten seconds is the ordinary way of discovering that the scene
 * wants to be longer, which is a thing to be told rather than stopped from doing.
 *
 * So the format allows it, the panels say when it has happened, and there is a
 * button that lengthens the scene to fit. What is checked here is the allowing —
 * because a clamp is exactly the sort of "safety" somebody adds later while
 * tidying, and it would take the script with it.
 */
class BeyondTheEndTest {

	@Test
	@DisplayName("a line may be written past the end and is not moved or dropped")
	void aLineSurvivesPastTheEnd() {
		// Ten seconds of caption under a five second scene: the state somebody is in
		// the moment before they lengthen it.
		Scene scene = Scene.empty("корабль").with(Cue.text(200, "Земля!", 60));
		assertEquals(100, scene.length(), "an empty scene is five seconds");
		assertEquals(200, scene.cues().get(0).at(), "and the line stayed where it was put");

		// Unreached, though, which is the part the panel has to say out loud.
		assertTrue(scene.showingAt(99).isEmpty());
		assertTrue(scene.cues().get(0).at() >= scene.length(), "past the end");
	}

	@Test
	@DisplayName("shortening a scene keeps what is now past its end")
	void shorteningDeletesNothing() {
		// The reason a clamp would be wrong rather than merely unhelpful. Dragging the
		// end in for a moment must not throw away the second half of the script.
		Scene full = Scene.empty("корабль").lengthened(600)
			.with(Cue.text(500, "Наконец-то.", 40))
			.keyed("матрос", Channels.YAW, Key.at(400, 90));

		Scene cut = full.lengthened(100);
		assertEquals(1, cut.cues().size(), "the line is still in the file");
		assertEquals(500, cut.cues().get(0).at());
		assertEquals(90, cut.valueAt("матрос", Channels.YAW, 400, -1), 1e-4,
			"and so is the key");

		// And it all comes back the moment the scene is long enough again.
		assertEquals(1, cut.lengthened(600).showingAt(510).size());
	}

	@Test
	@DisplayName("the length to stretch to is the end of the last line, not its start")
	void stretchingReachesTheEndOfTheLine() {
		// What the button in the caption panel works out. Stretching to the start
		// would leave the last line cut off mid-sentence — visible for a frame and
		// then gone, which reads as a bug rather than as a short scene.
		Scene scene = Scene.empty("корабль")
			.with(Cue.text(200, "Земля!", 60))
			.with(Cue.text(100, "Тише.", 40));

		int end = 0;
		for (Cue line : scene.cues()) end = Math.max(end, line.ends());
		assertEquals(260, end);

		Scene stretched = scene.lengthened(end);
		assertEquals(1, stretched.showingAt(259).size(), "the last line is still up");
		assertTrue(stretched.showingAt(260).isEmpty(), "and ends exactly with the scene");
	}
}
