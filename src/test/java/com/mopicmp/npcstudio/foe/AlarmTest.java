package com.mopicmp.npcstudio.foe;

import static com.mopicmp.npcstudio.foe.Alarm.Mood.ALERT;
import static com.mopicmp.npcstudio.foe.Alarm.Mood.CALM;
import static com.mopicmp.npcstudio.foe.Alarm.Mood.SUSPICIOUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Noticing over time: how fast, how slow to forget, and the twitch.
 */
class AlarmTest {

	/** Runs the meter for a while at a steady strength and hands back where it got to. */
	private static float over(int ticks, float strength, float from) {
		float alarm = from;
		for (int i = 0; i < ticks; i++) alarm = Alarm.next(alarm, strength);
		return alarm;
	}

	@Test
	@DisplayName("clear sight fills the meter in under a second")
	void fillsQuickly() {
		// Quick, because being spotted should be. Three quarters of a second is
		// enough to duck back and not enough to stroll past.
		assertTrue(over(14, 1f, 0) < 1f, "not yet at fourteen ticks");
		assertEquals(1f, over(15, 1f, 0), 1e-4, "and there by fifteen");
	}

	@Test
	@DisplayName("being forgotten takes far longer than being seen")
	void forgetsSlowly() {
		// The oldest silly behaviour there is: walk out of sight, count to one, walk
		// back in unnoticed. The ratio is what prevents it.
		assertTrue(over(30, 0f, 1f) > 0.4f,
			"a second and a half out of sight must not clear it");
		assertEquals(0f, over(60, 0f, 1f), 1e-4, "three seconds does");
	}

	@Test
	@DisplayName("a barely seen target takes much longer to notice")
	void weakSightIsSlow() {
		// The whole reason Sight returns a number. Somebody at the edge of vision
		// fills the meter at a tenth the rate, which is what sneaking is made of.
		float edge = over(15, 0.1f, 0);
		assertTrue(edge > 0 && edge < 0.15f, "fifteen ticks at a tenth came to " + edge);
	}

	@Test
	@DisplayName("the meter never leaves nought and one")
	void staysInRange() {
		assertEquals(1f, over(200, 1f, 0), 1e-4);
		assertEquals(0f, over(200, 0f, 1f), 1e-4);
	}

	@Test
	@DisplayName("moods climb calm, suspicious, alert")
	void moodsClimb() {
		assertEquals(CALM, Alarm.moodOf(0f, CALM));
		assertEquals(CALM, Alarm.moodOf(0.05f, CALM), "a twitch of nothing is nothing");
		assertEquals(SUSPICIOUS, Alarm.moodOf(0.3f, CALM));
		assertEquals(SUSPICIOUS, Alarm.moodOf(0.99f, CALM), "nearly sure is not sure");
		assertEquals(ALERT, Alarm.moodOf(1f, CALM));
	}

	@Test
	@DisplayName("alert holds on well past where it was reached")
	void alertHasHysteresis() {
		// Without the gap, a target sitting exactly at the edge of vision flips mood
		// several times a second — and it looks like a fault in the character rather
		// than in the arithmetic.
		assertEquals(ALERT, Alarm.moodOf(0.5f, ALERT), "half full and still alert");
		assertEquals(ALERT, Alarm.moodOf(0.36f, ALERT));
		assertEquals(SUSPICIOUS, Alarm.moodOf(0.34f, ALERT), "and lets go below a third");

		// The same number, two answers, depending on which way it was travelling.
		// That asymmetry is the feature, so it is asserted directly.
		assertEquals(ALERT, Alarm.moodOf(0.5f, ALERT));
		assertEquals(SUSPICIOUS, Alarm.moodOf(0.5f, CALM));
	}

	@Test
	@DisplayName("a flicker of lost sight does not drop the mood")
	void aFlickerIsIgnored() {
		// A target steps behind a lamp post for three ticks. Nothing downstream
		// should have to smooth this; the meter is the smoothing.
		float alarm = over(15, 1f, 0);
		assertEquals(ALERT, Alarm.moodOf(alarm, SUSPICIOUS));
		alarm = over(3, 0f, alarm);
		assertEquals(ALERT, Alarm.moodOf(alarm, ALERT), "still alert at " + alarm);
	}

	@Test
	@DisplayName("a whole approach, tick by tick, ends alert and clears afterwards")
	void theWholeStory() {
		// Walk in from the edge of vision, be noticed, walk out again. This is the
		// sequence somebody will actually perform when testing, so it is the one
		// asserted end to end.
		float alarm = 0;
		Alarm.Mood mood = CALM;

		// The far half of the approach, a quarter of a second at each step. Barely
		// seen the whole way, and it is worth asserting that this is *not* enough:
		// it is the difference between a guard who can be crept up on and one who
		// cannot, and the first draft of this test assumed wrongly that it was.
		for (float strength : new float[] { 0.05f, 0.05f, 0.2f, 0.4f, 0.7f }) {
			for (int i = 0; i < 5; i++) {
				alarm = Alarm.next(alarm, strength);
				mood = Alarm.moodOf(alarm, mood);
			}
		}
		assertEquals(SUSPICIOUS, mood,
			"twenty-five ticks of poor sight is a stirring, not a certainty; it stood at " + alarm);

		// And the last few steps, in the open. Now it closes.
		for (int i = 0; i < 12; i++) {
			alarm = Alarm.next(alarm, 1f);
			mood = Alarm.moodOf(alarm, mood);
		}
		assertEquals(ALERT, mood, "after walking into the open, the alarm stood at " + alarm);

		for (int i = 0; i < 60; i++) {
			alarm = Alarm.next(alarm, 0);
			mood = Alarm.moodOf(alarm, mood);
		}
		assertEquals(CALM, mood, "and after three seconds gone, " + alarm);
	}
}
