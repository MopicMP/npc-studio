package com.mopicmp.npcstudio.foe;

import static com.mopicmp.npcstudio.foe.Alarm.Mood.ALERT;
import static com.mopicmp.npcstudio.foe.Alarm.Mood.CALM;
import static com.mopicmp.npcstudio.foe.Alarm.Mood.SUSPICIOUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hearing, and the rule that keeps it from making guards omniscient.
 */
class NoiseTest {

	private static final double WALKING_PACE = 0.12;

	@Test
	@DisplayName("standing still is silent whatever the keys say")
	void stillnessIsSilent() {
		// Checked against actual movement rather than the sprint flag: a player
		// holding sprint against a wall is not sprinting anywhere.
		assertEquals(0f, Noise.loudness(0, false, true, false), 1e-4);
		assertEquals(0f, Noise.loudness(0.001, false, false, false), 1e-4);
	}

	@Test
	@DisplayName("running is loud, walking is moderate, creeping is nearly nothing")
	void thethreePaces() {
		float running = Noise.loudness(0.28, false, true, false);
		float walking = Noise.loudness(WALKING_PACE, false, false, false);
		float creeping = Noise.loudness(0.05, true, false, false);

		assertTrue(running > walking && walking > creeping,
			running + " / " + walking + " / " + creeping);
		assertTrue(creeping > 0, "not quite nothing — a perfect stealth has no tension in it");
		assertTrue(creeping < 0.1f, "but nearly");
	}

	@Test
	@DisplayName("crouching beats sprinting when both are held")
	void crouchingWins() {
		// Because that is what the body is doing. A crouched player with the sprint
		// key down is creeping, and the loudness has to agree with the animation.
		assertEquals(Noise.loudness(0.05, true, false, false),
			Noise.loudness(0.05, true, true, false), 1e-4);
	}

	@Test
	@DisplayName("water is loud even when you are trying not to be")
	void waterIsLoud() {
		assertTrue(Noise.loudness(0.05, true, false, true)
			> Noise.loudness(0.05, true, false, false));
	}

	@Test
	@DisplayName("sound carries further than sight and fades with distance")
	void soundCarries() {
		assertTrue(Noise.EARSHOT > Sight.ORDINARY.range(),
			"you hear the room next door and you cannot see it");

		float close = Noise.heard(2, 1f);
		float far = Noise.heard(20, 1f);
		assertTrue(close > far, close + " then " + far);
		assertEquals(0f, Noise.heard(Noise.EARSHOT, 1f), 1e-4);
		assertEquals(0f, Noise.heard(5, 0f), 1e-4, "silence carries nothing");
	}

	@Test
	@DisplayName("a walker is still audible at ten blocks")
	void notInaudibleInTheMiddle() {
		// The reason the falloff is by the square root and not linear: with a linear
		// fall everything past ten blocks was silent in practice, and a hearing that
		// only works when you are already visible is not hearing.
		float heard = Noise.heard(10, Noise.loudness(WALKING_PACE, false, false, false));
		assertTrue(heard > 0.05f, "at ten blocks a walker came to " + heard);
	}

	@Test
	@DisplayName("sound alone never makes a character certain")
	void hearingStopsShortOfCertainty() {
		// The whole of what makes hiding worth doing. Run past a guard behind a wall
		// for as long as you like: he knows something is there and never knows what.
		float alarm = 0;
		Alarm.Mood mood = CALM;
		for (int i = 0; i < 400; i++) {
			alarm = Alarm.next(alarm, 1f, Noise.CEILING);
			mood = Alarm.moodOf(alarm, mood);
		}
		assertEquals(Noise.CEILING, alarm, 1e-4);
		assertEquals(SUSPICIOUS, mood, "hunted, and not caught");
	}

	@Test
	@DisplayName("the ceiling caps rising and never drags an alert character down")
	void theCeilingDoesNotPushDown() {
		// A guard who has seen you and then loses sight while still hearing you must
		// forget at the ordinary rate, from where she was — not be yanked back to
		// merely suspicious by the sound of your feet.
		float alarm = 1f;
		Alarm.Mood mood = Alarm.moodOf(alarm, SUSPICIOUS);
		assertEquals(ALERT, mood);

		alarm = Alarm.next(alarm, 1f, Noise.CEILING);
		assertEquals(1f, alarm, 1e-4, "held, not lowered");
		assertEquals(ALERT, Alarm.moodOf(alarm, mood));
	}

	@Test
	@DisplayName("creeping past a guard at a distance goes unnoticed, however long for")
	void creepingWorks() {
		// The scene this whole class exists for, and it caught a real fault: the
		// alarm has no leak, so a sound too faint to mean anything still added up.
		// Creeping four blocks behind somebody made her suspicious after two and a
		// half seconds and would eventually have taken her to the ceiling.
		//
		// Twenty seconds here rather than three, because "quiet enough for a while"
		// is not quiet. Sneaking must not be a race against a counter.
		float alarm = 0;
		Alarm.Mood mood = CALM;
		float loudness = Noise.loudness(0.05, true, false, false);
		for (int i = 0; i < 400; i++) {
			alarm = Alarm.next(alarm, Noise.heard(4, loudness), Noise.CEILING);
			mood = Alarm.moodOf(alarm, mood);
		}
		assertEquals(CALM, mood, "twenty seconds of creeping raised the alarm to " + alarm);
		assertEquals(0f, alarm, 1e-4);
	}

	@Test
	@DisplayName("but creeping right up against one is a gamble")
	void creepingCloseIsHeard() {
		// The threshold has to leave creeping meaning something, or the constant for
		// it is decoration. At arm's length it is audible.
		assertTrue(Noise.heard(1, Noise.loudness(0.05, true, false, false)) > 0,
			"creeping at one block should still be audible");
		assertEquals(0f, Noise.heard(8, Noise.loudness(0.05, true, false, false)), 1e-4,
			"and at eight blocks it should not");
	}

	@Test
	@DisplayName("running past the same guard does not")
	void runningDoesNot() {
		float alarm = 0;
		Alarm.Mood mood = CALM;
		float loudness = Noise.loudness(0.28, false, true, false);
		for (int i = 0; i < 20; i++) {
			alarm = Alarm.next(alarm, Noise.heard(4, loudness), Noise.CEILING);
			mood = Alarm.moodOf(alarm, mood);
		}
		assertEquals(SUSPICIOUS, mood, "one second of running raised the alarm to " + alarm);
	}
}
