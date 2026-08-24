package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An export begins at the beginning, and playing does not.
 *
 * <h2>How this was found</h2>
 *
 * Four films came out of one fifty-four second scene, three and a half seconds
 * long, then forty-two, then fifty-one, then fifty-three — and nothing in the
 * scene had changed between them. What changed was where the playhead happened to
 * be sitting when the button was pressed.
 *
 * Starting a scene carries on from the cursor, which is exactly right for
 * playing: you scrub to the bit you are working on and press play. It is exactly
 * wrong for exporting, and it fails silently — the film is simply missing its
 * first half, and the only sign was the count, which is against the whole scene
 * and therefore said "thirty per cent" when the third one finished.
 *
 * <h2>What is pinned</h2>
 *
 * The rule, on the playhead itself, both ways round: that playing from the middle
 * stays in the middle, and that an export must not use that rule.
 */
class ExportFromTheTopTest {

	private static final int LENGTH = 1077;

	@Test
	@DisplayName("playing carries on from the cursor, which is why exporting may not")
	void playingKeepsItsPlace() {
		// The behaviour an export inherited by accident. Correct here and only here.
		Playhead middle = Playhead.resting().scrubbedTo(754, LENGTH);
		assertEquals(754, middle.started(LENGTH).head().at(), 1e-6,
			"playing from the middle should stay in the middle");
	}

	@Test
	@DisplayName("scrubbing to the top first is what makes the film the whole scene")
	void exportingRewinds() {
		// What the export does now: put the cursor at nought, then start. From there
		// the two rules agree, because the top is where "from the top" begins.
		Playhead was = Playhead.resting().scrubbedTo(754, LENGTH);
		Playhead rewound = was.scrubbedTo(0, LENGTH);
		assertEquals(0, rewound.started(LENGTH).head().at(), 1e-6);
	}

	@Test
	@DisplayName("the frames written are the whole scene, not a share of it")
	void theCountMatchesTheFilm() {
		// The arithmetic behind "it finished at thirty per cent". The count is against
		// the whole scene, so a film that began at the cursor could never reach it —
		// and the shortfall was exactly how far in the cursor was.
		int frames = (int) Math.ceil(LENGTH * 30.0 / Scene.RATE);
		assertEquals(1616, frames);

		int startedAt = 754;
		int written = (int) Math.ceil((LENGTH - startedAt) * 30.0 / Scene.RATE);
		assertTrue(written < frames / 2, "a film begun there is less than half the scene");
		assertEquals(30, Math.round(written * 100.0 / frames),
			"and the count would read thirty per cent when it finished");
	}

	@Test
	@DisplayName("a looping cursor at the end does not rewind itself either")
	void theEndIsStillRewound() {
		// The case that produced the three second film: the cursor parked near the
		// end. Starting there rewinds by itself only when looping is off — and it is
		// on by default, so the one case that might have saved an export from the
		// cursor does not. Which is the whole argument for rewinding on purpose.
		Playhead atEnd = Playhead.resting().scrubbedTo(LENGTH, LENGTH);
		assertTrue(atEnd.loops(), "looping is on by default, so nothing rewinds itself");
		assertEquals(LENGTH, atEnd.started(LENGTH).head().at(), 1e-6,
			"it carried on from the end, which is a film of nothing");
		assertEquals(0, atEnd.scrubbedTo(0, LENGTH).started(LENGTH).head().at(), 1e-6,
			"and rewinding first is what makes it the whole scene");
	}
}
