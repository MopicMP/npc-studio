package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning the game's own sound volumes into something a character can act on.
 *
 * <h2>What has to hold</h2>
 *
 * The game's volumes are not on our scale — a footstep is about a seventh, a
 * block being placed is one, dynamite is four — and the mapping has to keep them
 * in that order while leaving room above an ordinary noise for an extraordinary
 * one. If a door closing is as loud as anything can be, dynamite means nothing.
 */
class DinTest {

	/** The volumes the game actually uses, for the things that were asked about. */
	private static final float FOOTSTEP = 0.15f;
	private static final float PLACING = 1f;
	private static final float BOWSHOT = 1f;
	private static final float DYNAMITE = 4f;

	@Test
	@DisplayName("the order of things is kept")
	void loudnessKeepsTheOrder() {
		assertTrue(Din.loudnessFor(FOOTSTEP) < Din.loudnessFor(PLACING));
		assertTrue(Din.loudnessFor(PLACING) < Din.loudnessFor(DYNAMITE));
	}

	@Test
	@DisplayName("an ordinary noise leaves room above it for an extraordinary one")
	void ordinaryIsNotMaximum() {
		// The whole reason the scaling is by less than the whole. A block placed at
		// full strength would mean a stick of dynamite could not be louder.
		assertTrue(Din.loudnessFor(PLACING) < 1f, "placing came to " + Din.loudnessFor(PLACING));
		assertEquals(1f, Din.loudnessFor(DYNAMITE), 1e-4);
	}

	@Test
	@DisplayName("a footstep is quiet and an arrow is not")
	void theNamedCases() {
		float step = Din.loudnessFor(FOOTSTEP);
		assertTrue(step > 0 && step < 0.2f, "a footstep came to " + step);
		assertTrue(Din.loudnessFor(BOWSHOT) > 0.5f);
	}

	@Test
	@DisplayName("dynamite crosses a valley and a pressure plate does not cross a room")
	void loudThingsCarryFurther() {
		// The game's own rule: an ordinary sound reaches sixteen blocks and a loud one
		// proportionally further. Taken rather than invented, so that anything added
		// in a later version already behaves sensibly.
		assertEquals(16, Din.carriesFor(PLACING), 1e-4);
		assertTrue(Din.carriesFor(DYNAMITE) >= 64, "dynamite carries " + Din.carriesFor(DYNAMITE));
		assertTrue(Din.carriesFor(FOOTSTEP) <= 16);
	}

	@Test
	@DisplayName("nothing carries absurdly far, whatever volume it claims")
	void thereIsACeilingOnCarrying() {
		// A mod is free to play a sound at volume one hundred, and a character on the
		// other side of the map reacting to it would be a bug in us rather than a
		// feature of theirs.
		assertTrue(Din.carriesFor(1000f) <= 160);
		assertTrue(Din.carriesFor(0.001f) >= 8, "and a whisper still has some range");
	}

	@Test
	@DisplayName("a block breaking is heard across a room and a footstep is not")
	void whatReachesWhere() {
		// The case that was asked about first: breaking a wall to get into somewhere
		// must not be silent to the people inside it.
		float breaking = Din.loudnessFor(1f);
		assertTrue(Noise.heard(10, breaking, Din.carriesFor(1f)) > 0,
			"breaking a block at ten blocks");
		assertEquals(0f, Noise.heard(10, Din.loudnessFor(FOOTSTEP), Din.carriesFor(FOOTSTEP)),
			1e-4, "a single footstep at ten blocks");
	}

	@Test
	@DisplayName("dynamite is heard from far further than anything else")
	void dynamiteIsHeardAcrossTheMap() {
		assertTrue(Noise.heard(50, Din.loudnessFor(DYNAMITE), Din.carriesFor(DYNAMITE)) > 0,
			"fifty blocks from a stick of dynamite");
		assertEquals(0f, Noise.heard(50, Din.loudnessFor(PLACING), Din.carriesFor(PLACING)), 1e-4,
			"and fifty blocks from somebody laying a block");
	}

	@Test
	@DisplayName("silence is written down as nothing at all")
	void silenceIsNotANoise() {
		assertEquals(0f, Din.loudnessFor(0f), 1e-4);
		assertEquals(0f, Din.loudnessFor(-1f), 1e-4);
	}
}
