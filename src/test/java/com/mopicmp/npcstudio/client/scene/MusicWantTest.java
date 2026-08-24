package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.client.scene.Music.Next;

/**
 * Whether the music carries on, starts again, or stops.
 *
 * <h2>Why three comparisons get a test of their own</h2>
 *
 * Because they have been wrong twice, and both times it sounded like a broken
 * file rather than like a mistake in a condition.
 *
 * The first time, music played whenever the cursor stood at or past a cue —
 * including while the scene was paused. Open a scene and the track started at
 * once and ran for ever against a timeline standing perfectly still. That one is
 * fixed above this, in what the scene asks for at all, and is not here.
 *
 * The second time is here. "Already playing the right thing" was tested as
 * <em>name matches, cue matches, and there is a live channel</em> — and a live
 * channel is exactly what there is not while a file is being sought into.
 * Seeking into an ogg means decoding everything before the point wanted, which
 * takes longer than the fifty milliseconds until the next tick; that tick found
 * no channel, concluded the wrong thing was playing, and started over, cancelling
 * the decode that was about to finish. Twenty times a second, for ever.
 *
 * It therefore worked from the very start of a piece, where there is nothing to
 * seek past, and could not work anywhere else. "I rewound a little and the music
 * stopped" is what that looks like from outside — and it had not stopped, it had
 * never once managed to begin.
 *
 * <h2>What must hold</h2>
 *
 * That a piece already asked for is never asked for twice, whether or not it has
 * reached the speakers; and that a cursor moved by a hand takes the music with it.
 */
class MusicWantTest {

	/** Nothing has been asked for yet. */
	private static final String NOTHING = "";
	private static final int NEVER = Integer.MIN_VALUE;

	@Test
	@DisplayName("a piece being sought into is not asked for again")
	void aDecodeInFlightIsLeftAlone() {
		// The bug, stated as the test that would have caught it. Twenty ticks pass
		// while a long seek finishes, and on every one of them the answer has to be
		// "leave it" — the piece is already asked for, whether or not a channel
		// exists yet, because that is not a fact this decision is allowed to see.
		for (int tick = 1; tick <= 20; tick++) {
			assertEquals(Next.LEAVE,
				Music.decide("море", 0, 200 + tick - 1, "море", 0, 200 + tick),
				"restarted on tick " + tick + " of the seek");
		}
	}

	@Test
	@DisplayName("ordinary playing changes nothing")
	void playingOnIsLeftAlone() {
		assertEquals(Next.LEAVE, Music.decide("море", 0, 0, "море", 0, 1));
		assertEquals(Next.LEAVE, Music.decide("море", 0, 419, "море", 0, 420));
		// And a tick the machine was late for is still ordinary playing rather than a
		// rewind. Three ticks in one is a stutter; nobody scrubs by three ticks.
		assertEquals(Next.LEAVE, Music.decide("море", 0, 100, "море", 0, 103));
	}

	@Test
	@DisplayName("a rewind takes the music back with it")
	void rewindingRestarts() {
		// The complaint that started this: the animation went back and the music did
		// not. Backwards by any real amount is a hand on the playhead.
		assertEquals(Next.RESTART, Music.decide("море", 0, 400, "море", 0, 100));
		assertEquals(Next.RESTART, Music.decide("море", 0, 400, "море", 0, 395));
		// And forwards by more than a tick is the same gesture in the other
		// direction, so it re-seeks rather than letting the music lag behind.
		assertEquals(Next.RESTART, Music.decide("море", 0, 100, "море", 0, 400));
	}

	@Test
	@DisplayName("a different piece, or the same piece cued again, starts over")
	void aDifferentRequestRestarts() {
		assertEquals(Next.RESTART, Music.decide("море", 0, 100, "буря", 0, 101));
		// The same file cued twice is two different things to be playing — that is
		// the whole reason the cue's own tick is part of what is asked for.
		assertEquals(Next.RESTART, Music.decide("море", 0, 100, "море", 80, 101));
		assertEquals(Next.RESTART, Music.decide(NOTHING, NEVER, 0, "море", 0, 0));
	}

	@Test
	@DisplayName("no piece means silence, whatever was playing")
	void nothingMeansSilence() {
		// An empty name is how a scene says "and here the music stops", and it is
		// also what a scene with no cue before the cursor comes to.
		assertEquals(Next.SILENCE, Music.decide("море", 0, 100, "", 0, 101));
		assertEquals(Next.SILENCE, Music.decide("море", 0, 100, null, 0, 101));
		assertEquals(Next.SILENCE, Music.decide(NOTHING, NEVER, 0, "", 0, 0));
	}
}
