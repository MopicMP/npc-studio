package com.mopicmp.npcstudio.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a glance goes the right way, moves smoothly, and never runs in step.
 *
 * The blink was written with the same care and one thing was never checked: that
 * two characters standing together do not do it together. It reads as a shop
 * window of mannequins the moment they do, and it is invisible in a screenshot —
 * so it is checked here rather than looked at.
 */
class GazingTest {

	/** Somebody off to one side is followed, once the head has run out of turn. */
	@Test
	@DisplayName("the eyes go the way the head could not")
	void theEyesTakeUpTheSlack() {
		assertTrue(Gazing.aside(1, 0f, 60f, true) > 0.9f,
			"an angle the head cannot reach should send the eyes fully right");
		assertTrue(Gazing.aside(1, 0f, -60f, true) < -0.9f, "and fully left the other way");
	}

	/**
	 * The following is a ramp, not a switch.
	 *
	 * Somebody walking past a character should be followed, rather than snapped at
	 * the moment they cross a line. This was a threshold to begin with and it is
	 * the same defect as the eye jumping a whole pixel: correct, and visibly
	 * mechanical.
	 */
	@Test
	@DisplayName("following somebody is a ramp rather than a switch")
	void theFollowingIsGradual() {
		float last = Gazing.ramp(0f);
		for (int degrees = 0; degrees <= 90; degrees++) {
			float now = Gazing.ramp(degrees);
			assertTrue(now >= last - 1e-5, "the eyes went back on themselves at " + degrees);
			assertTrue(now - last < 0.1f, "the eyes jumped at " + degrees + " degrees");
			last = now;
		}
		assertEquals(1f, last, 1e-5, "far enough round, the eyes should be all the way over");
	}

	/**
	 * A head that is already pointed at somebody leaves the eyes alone.
	 *
	 * Without this the eyes would sit in the corner permanently: the head turns
	 * towards whoever is near, so the angle it is short by is nought most of the
	 * time, and a rule that ignored that would be reading noise.
	 */
	@Test
	@DisplayName("a head that is already looking leaves the eyes free to wander")
	void aSmallSlackIsNotAGlance() {
		assertEquals(0f, Gazing.ramp(3f), 1e-5, "three degrees is the head's own smoothing");
		assertEquals(0f, Gazing.ramp(-11f), 1e-5, "and so is eleven");
	}

	/**
	 * Two characters standing together do not glance together.
	 *
	 * The failure this exists for is not a wrong answer, it is a synchronised one:
	 * a row of NPCs placed one after another has consecutive ids, and anything
	 * derived from the clock alone gives all of them the same eyes at the same
	 * moment.
	 */
	@Test
	@DisplayName("neighbours do not glance in step")
	void neighboursDoNotMoveTogether() {
		int together = 0;
		int counted = 0;
		for (int tick = 0; tick < 600; tick += 3) {
			for (int id = 1; id <= 8; id++) {
				counted++;
				if (Math.abs(Gazing.wander(id, tick) - Gazing.wander(id + 1, tick)) < 0.01f) together++;
			}
		}
		assertTrue(together < counted * 0.8,
			"neighbouring characters agreed " + together + " times out of " + counted);
	}

	/** A wandering eye rests in the middle far more often than it moves. */
	@Test
	@DisplayName("a wandering eye is mostly still")
	void theEyesAreMostlyStill() {
		int still = 0;
		int counted = 0;
		for (int id = 1; id <= 40; id++) {
			for (int tick = 0; tick < 2000; tick += 7) {
				counted++;
				if (Math.abs(Gazing.wander(id, tick)) < 0.01f) still++;
			}
		}
		assertTrue(still > counted / 2,
			"eyes darting about constantly look alarmed, not alive: " + still + " of " + counted);
		assertTrue(still < counted, "and an eye that never moves is the thing this replaces");
	}

	/**
	 * The eye slides: no frame in which it is suddenly somewhere else.
	 *
	 * The complaint this was rewritten for. Between one tick and the next the
	 * movement is a fraction of its travel, so what is seen is an eye moving
	 * rather than a picture being swapped.
	 */
	@Test
	@DisplayName("the eye never jumps between one frame and the next")
	void theMovementIsSmooth() {
		// Asked of frames rather than ticks, because frames are what anybody sees:
		// the renderer hands over the tick plus the fraction of it that has passed,
		// so at sixty frames a second this is sampled three times per tick. Asking
		// per tick is what let a movement through that jumped half its travel
		// between two of them, which is exactly the complaint this was rewritten
		// for.
		float frame = 1f / 3f;
		for (int id = 1; id <= 20; id++) {
			float last = Gazing.wander(id, 0f);
			for (int step = 1; step < 3600; step++) {
				float now = Gazing.wander(id, step * frame);
				assertTrue(Math.abs(now - last) < 0.4f,
					"character " + id + " jumped " + Math.abs(now - last)
						+ " of its travel in one frame, at tick " + (step * frame));
				last = now;
			}
		}
	}

	/**
	 * The same character asked twice at the same moment answers the same.
	 *
	 * Nothing is stored and nothing is sent, so every client works this out for
	 * itself — and they only agree with each other if the answer depends on
	 * nothing but the id and the clock.
	 */
	@Test
	@DisplayName("the same moment always gives the same answer")
	void theAnswerIsStable() {
		for (int tick = 0; tick < 200; tick++) {
			assertEquals(Gazing.wander(11, tick), Gazing.wander(11, tick));
		}
	}

	/** Both directions actually happen, on some character at some moment. */
	@Test
	@DisplayName("a wandering eye goes both ways")
	void bothDirectionsHappen() {
		boolean left = false;
		boolean right = false;
		for (int id = 1; id <= 30 && !(left && right); id++) {
			for (int tick = 0; tick < 1200; tick += 5) {
				float aside = Gazing.wander(id, tick);
				left |= aside < -0.9f;
				right |= aside > 0.9f;
			}
		}
		assertTrue(left && right, "the eyes only ever went one way");
	}

	/** And the eyes never ask for more travel than an eye has. */
	@Test
	@DisplayName("a glance never asks for more than the eye can give")
	void theGlanceStaysWithinOne() {
		for (int id = 1; id <= 20; id++) {
			for (int tick = 0; tick < 800; tick++) {
				for (float slack : new float[] { 0f, 20f, 90f, -90f }) {
					float aside = Gazing.aside(id, tick, slack, true);
					assertTrue(aside >= -1.0001f && aside <= 1.0001f, "asked for " + aside);
				}
			}
		}
	}
}
