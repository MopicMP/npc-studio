package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How much a capture would write, before it writes any of it.
 *
 * <h2>What happened</h2>
 *
 * A hundred and fifty files and a hundred and ninety-two megabytes, from one
 * press of an unlabelled icon, on a scene where nothing had been animated — so
 * every one of the hundred and fifty was the same picture. The person who pressed
 * it found out days later by opening the folder.
 *
 * Three separate things were wrong and only one of them was the button. The count
 * was never worked out in advance, so nothing could be said about it; a scene with
 * nothing in it was as capturable as any other, though the answer is always the
 * same frame repeated; and the whole side effect was one press away.
 *
 * The arithmetic is what is pinned here. The button is not testable and the
 * disk is not either, but "how many frames is this, and is it worth writing" is
 * an ordinary question with an ordinary answer, and it is the one that has to be
 * asked first.
 */
class CaptureGuardTest {

	/** Frames a second, as the capture cuts at. Kept here so the sum is visible. */
	private static final int RATE = 30;

	private static int wouldWrite(Scene scene) {
		if (scene == null) return 0;
		if (scene.tracks().isEmpty() && scene.cues().isEmpty()) return 0;
		return Math.max(1, (int) Math.ceil(scene.length() * (double) RATE / Scene.RATE));
	}

	private static Scene animated(int length) {
		return Scene.empty("корабль").lengthened(length)
			.keyed("рулевой", Channels.YAW, Key.at(0, 0));
	}

	@Test
	@DisplayName("a scene nobody animated is not worth a single frame")
	void nothingIsNotFilmed() {
		// The exact case that filled the disk: a scene opened, characters stood
		// where somebody wanted them, and not one key laid down. Every frame of that
		// is the same picture, so the honest count is nought.
		assertEquals(0, wouldWrite(Scene.empty("корабль")));
		assertEquals(0, wouldWrite(Scene.empty("корабль").lengthened(600)));
	}

	@Test
	@DisplayName("a scene with a line in it and no movement is still worth filming")
	void aCueIsEnough() {
		// Text appearing is a thing that changes between frames, even with nobody
		// moving. Refusing that would be refusing the case this whole tool was
		// started for — a title over a still shot.
		Scene said = Scene.empty("корабль").lengthened(40).with(Cue.text(10, "Земля!", 20));
		assertTrue(wouldWrite(said) > 0);
	}

	@Test
	@DisplayName("the count is the scene's own length at the rate it is cut")
	void theCountIsTheLength() {
		// Five seconds is a hundred ticks and a hundred and fifty frames — which is
		// exactly what landed on the reporter's disk, so the sum is right and the
		// fault was never the arithmetic.
		assertEquals(150, wouldWrite(animated(100)));
		assertEquals(30, wouldWrite(animated(20)), "one second");
		assertEquals(1800, wouldWrite(animated(1200)), "a minute");
	}

	@Test
	@DisplayName("the shortest possible scene still comes to more than nothing")
	void nothingRoundsToNothing() {
		// Nought frames means "refused". A scene that is merely very short has not
		// been refused, and the two must never be told apart by the same number —
		// so the count is rounded up and floored at one.
		//
		// A scene cannot actually be shorter than a tick: asking for nought gets one,
		// which the scene does to itself. That is worth pinning here rather than
		// assumed, because the arithmetic below divides by it.
		assertEquals(1, Scene.empty("к").lengthened(0).length(), "a scene is at least a tick");
		assertEquals(2, wouldWrite(animated(1)), "a tick is one and a half frames, rounded up");
		assertTrue(wouldWrite(animated(0)) > 0);
	}
}
