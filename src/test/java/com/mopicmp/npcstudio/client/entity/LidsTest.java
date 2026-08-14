package com.mopicmp.npcstudio.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a resting lid is a squint and never an illness.
 *
 * This is the one eye movement that is on all the time rather than in bursts, so
 * being slightly too much is not a moment somebody might miss — it is how every
 * character in the world looks all afternoon. The tests are therefore mostly
 * about restraint.
 */
class LidsTest {

	/** Ordinary indoor light leaves the eyes alone entirely. */
	@Test
	@DisplayName("ordinary light does not move the lids at all")
	void ordinaryLightIsLeftAlone() {
		for (int light = 0; light <= 12; light++) {
			assertEquals(0f, Lids.glare(light), 1e-5, "light " + light + " should not be a glare");
		}
	}

	/** Full sun brings them down, and gradually. */
	@Test
	@DisplayName("full sun brings the lids down a little, and gradually")
	void sunSquints() {
		assertTrue(Lids.glare(15) > 0.1f, "noon sun should be visible on the face");
		assertTrue(Lids.glare(15) < 0.35f,
			"a lid a third of the way down is a squint; further is a character who looks unwell");
		assertTrue(Lids.glare(13) < Lids.glare(14) && Lids.glare(14) < Lids.glare(15),
			"the squint should come on with the light rather than switch");
	}

	/** Daytime leaves them open; the small hours weigh them down. */
	@Test
	@DisplayName("the lids get heavy in the small hours and not in the day")
	void nightIsHeavy() {
		assertEquals(0f, Lids.drowse(6000), 1e-5, "nothing at noon");
		assertEquals(0f, Lids.drowse(12000), 1e-5, "nor at sunset");
		assertEquals(0f, Lids.drowse(0), 1e-5, "nor at dawn");
		assertTrue(Lids.drowse(18000) > 0.1f, "midnight should weigh on them");
	}

	/**
	 * And it comes on gradually, because a whole village closing its eyes in the
	 * same tick reads as a bug even when it is deliberate.
	 */
	@Test
	@DisplayName("night comes on gradually rather than switching at dusk")
	void nightIsARamp() {
		float last = Lids.drowse(12000);
		for (long time = 12000; time <= 24000; time += 100) {
			float now = Lids.drowse(time);
			assertTrue(Math.abs(now - last) < 0.02f, "the lids stepped at time " + time);
			last = now;
		}
	}

	/** Whatever the hour, and whatever the light, the eyes are still eyes. */
	@Test
	@DisplayName("the lids never rest more than a fifth of the way down")
	void theRestIsAlwaysSlight() {
		for (int light = 0; light <= 15; light++) {
			for (long time = 0; time < 24000; time += 137) {
				float rest = Lids.rest(light, time, 0f);
				assertTrue(rest >= 0f && rest <= 0.25f,
					"the lids rested at " + rest + " in light " + light + " at " + time);
			}
		}
	}

	/** The world's clock keeps counting past one day, and so must this. */
	@Test
	@DisplayName("the second night is like the first")
	void laterDaysAreTheSame() {
		for (long time = 0; time < 24000; time += 500) {
			assertEquals(Lids.drowse(time), Lids.drowse(time + 24000L * 37), 1e-5,
				"day thirty-seven should look like day one, at " + time);
		}
	}

	/**
	 * A wince snaps shut and lets go, which is the opposite of everything else.
	 *
	 * Every other movement here eases in so that nothing jumps. This one must not:
	 * a flinch that fades in is not a flinch, it is a character growing sleepy at
	 * the exact moment it was hit.
	 */
	@Test
	@DisplayName("a blow shuts the eyes at once and lets go slowly")
	void aWinceSnapsShut() {
		assertEquals(0f, Lids.wince(0), 1e-5, "an unhurt character does not wince");
		assertTrue(Lids.wince(10) > 0.95f, "the first tick of a blow should shut them");

		float last = Lids.wince(10);
		for (int hurt = 9; hurt >= 0; hurt--) {
			float now = Lids.wince(hurt);
			assertTrue(now <= last + 1e-5, "a wince should only ever be letting go");
			last = now;
		}
		assertEquals(0f, last, 1e-5, "and be gone by the time the count runs out");
	}

	/** Being unwell weighs on the lids, and stops short of shutting them. */
	@Test
	@DisplayName("illness lowers the lids without closing them")
	void illnessWeighs() {
		float well = Lids.rest(8, 6000, 0f);
		float ill = Lids.rest(8, 6000, 1f);

		assertEquals(0f, well, 1e-5, "a healthy character at noon has its eyes open");
		assertTrue(ill > 0.15f, "poison should show on the face: " + ill);
		assertTrue(ill < 0.4f,
			"a character with its eyes half shut is unconscious, not ill: " + ill);
	}

	/** Whatever is wrong, the resting lids never take the eyes away entirely. */
	@Test
	@DisplayName("nothing about resting lids ever shuts an eye")
	void restingNeverShuts() {
		for (int light = 0; light <= 15; light++) {
			for (long time = 0; time < 24000; time += 331) {
				for (float ill : new float[] { 0f, 0.5f, 1f, 5f }) {
					float rest = Lids.rest(light, time, ill);
					assertTrue(rest >= 0f && rest < 0.5f, "the lids rested at " + rest);
				}
			}
		}
	}
}
