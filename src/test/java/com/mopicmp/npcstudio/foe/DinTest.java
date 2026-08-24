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

	// ------------------------------------------------------------- what matters

	@Test
	@DisplayName("an explosion out-ranks the rubble it makes")
	void theCraterDoesNotOutShoutTheBang() {
		// The fault this whole separation exists for. Dynamite makes one loud bang
		// and then a great many quiet ones as each broken block reports itself, from
		// the crater at your feet. Ranked by loudness the nearby rubble won, and a
		// character stood staring at the floor while a hole appeared behind her.
		float bang = Din.urgencyFor(DYNAMITE, false);
		float rubble = Din.urgencyFor(PLACING, false);
		assertTrue(bang > rubble, bang + " against " + rubble);

		// And loudness alone would have said the opposite once distance is counted.
		float bangHeard = Noise.heard(40, Din.loudnessFor(DYNAMITE), Din.carriesFor(DYNAMITE));
		float rubbleHeard = Noise.heard(3, Din.loudnessFor(PLACING), Din.carriesFor(PLACING));
		assertTrue(rubbleHeard > bangHeard,
			"rubble at three blocks is genuinely louder than dynamite at forty: "
				+ rubbleHeard + " against " + bangHeard);
	}

	@Test
	@DisplayName("a fight across a courtyard beats a door beside you")
	void dangerBeatsFurniture() {
		assertTrue(Din.urgencyFor(1f, true) > Din.urgencyFor(1f, false));
	}

	@Test
	@DisplayName("every noise is worth at least a glance")
	void nothingIsWorthNothing() {
		// Without a floor a character would ignore footsteps entirely once she had
		// heard one door, which is a strange sort of vigilance.
		assertTrue(Din.urgencyFor(FOOTSTEP, false) > 0);
		for (float volume : new float[] { 0.1f, 0.5f, 1f, 2f, 4f, 10f }) {
			float urgency = Din.urgencyFor(volume, false);
			assertTrue(urgency > 0 && urgency <= 1, volume + " came to " + urgency);
		}
	}

	// ------------------------------------------------------- ears are not eyes

	@Test
	@DisplayName("a loud noise beside you is placed almost exactly")
	void closeAndLoudIsWellPlaced() {
		assertTrue(Din.vagueness(1f, 2) < 1, "came to " + Din.vagueness(1f, 2));
	}

	@Test
	@DisplayName("a faint noise far off could be anywhere")
	void farAndFaintIsVague() {
		// It was reported that she knew exactly where a sound came from, which is not
		// something anybody can do. It matters beyond realism: a character who places
		// a noise precisely has in effect seen you, and then hearing stops being the
		// lesser sense the whole arrangement depends on it being.
		double vague = Din.vagueness(0.1f, 40);
		assertTrue(vague > 5, "forty blocks off and barely heard came to " + vague);
		assertTrue(vague <= 12, "and not so vague as to be useless: " + vague);
	}

	@Test
	@DisplayName("vagueness grows with distance and shrinks with loudness")
	void vaguenessIsOrdered() {
		assertTrue(Din.vagueness(0.5f, 20) > Din.vagueness(0.5f, 5));
		assertTrue(Din.vagueness(0.2f, 20) > Din.vagueness(0.9f, 20));
	}

	@Test
	@DisplayName("the same guess comes back every time it is asked")
	void guessingIsSteady() {
		// It has to be, or the head twitches about a point sixty times a second. The
		// guess is worked out from the noise rather than drawn afresh.
		var at = new net.minecraft.world.phys.Vec3(10, 64, 10);
		var rumour = new Din.Rumour(at, 0.5f, 32, 0.5f, 100);
		var from = new net.minecraft.world.phys.Vec3(0, 64, 0);
		var once = Din.guessAt(rumour, 6, from);
		var again = Din.guessAt(rumour, 6, from);
		assertEquals(once.x, again.x, 1e-9);
		assertEquals(once.z, again.z, 1e-9);
	}

	@Test
	@DisplayName("two characters in different places guess differently")
	void everybodyGuessesForThemselves() {
		var at = new net.minecraft.world.phys.Vec3(10, 64, 10);
		var rumour = new Din.Rumour(at, 0.5f, 32, 0.5f, 100);
		var here = Din.guessAt(rumour, 6, new net.minecraft.world.phys.Vec3(0, 64, 0));
		var there = Din.guessAt(rumour, 6, new net.minecraft.world.phys.Vec3(30, 64, 5));
		assertTrue(here.x != there.x || here.z != there.z,
			"they are standing in different places and should be wrong differently");
	}

	@Test
	@DisplayName("the guess is never wrong about how high up it was")
	void heightIsNeverGuessed() {
		// Being wrong about the height has a character staring into the ground or up
		// at the sky, which reads as broken rather than as approximate. It is also
		// the one direction ears are genuinely good at: a noise from below sounds
		// like it came from below.
		var at = new net.minecraft.world.phys.Vec3(10, 64, 10);
		var rumour = new Din.Rumour(at, 0.2f, 32, 0.5f, 100);
		for (int i = 0; i < 20; i++) {
			var guess = Din.guessAt(rumour, 12,
				new net.minecraft.world.phys.Vec3(i, 64, i * 2));
			assertEquals(64, guess.y, 1e-9);
		}
	}

	@Test
	@DisplayName("the guess stays inside the vagueness it was given")
	void theGuessIsBounded() {
		var at = new net.minecraft.world.phys.Vec3(0, 64, 0);
		var rumour = new Din.Rumour(at, 0.3f, 32, 0.5f, 7);
		for (int i = 0; i < 200; i++) {
			var guess = Din.guessAt(rumour, 5, new net.minecraft.world.phys.Vec3(i, 64, -i));
			assertTrue(guess.distanceTo(at) <= 5.0001, "wandered to " + guess.distanceTo(at));
		}
	}
}
