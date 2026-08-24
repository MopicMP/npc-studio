package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning to look at something at the rate a person turns.
 *
 * <h2>The reported fault this pins down</h2>
 *
 * A character noticing you spun bodily on the spot, in one tick, pitch included
 * — because the game's own {@code lookAt} is not a turn but three instant
 * assignments. It read as the head snapping and, separately, as vision working
 * through the whole circle, the second following from the first: once the body
 * has spun to face you, you are in front of it.
 */
class NeckTest {

	@Test
	@DisplayName("a turn takes as many ticks as it needs and no more")
	void turningIsRateLimited() {
		// Ninety degrees at six a tick is fifteen ticks: three quarters of a second
		// to glance over your shoulder, not one frame.
		float head = 0;
		int ticks = 0;
		while (Math.abs(Neck.wrap(90 - head)) > 1e-3 && ticks < 100) {
			head = Neck.step(head, 90, 6);
			ticks++;
		}
		assertEquals(15, ticks);
		assertEquals(90, head, 1e-3);
	}

	@Test
	@DisplayName("the last step lands exactly and does not overshoot")
	void itDoesNotOvershoot() {
		// Clamping the difference rather than adding a fixed step is what makes this
		// true; a fixed step oscillates about the target for ever.
		assertEquals(90, Neck.step(88, 90, 6), 1e-4);
		assertEquals(90, Neck.step(92, 90, 6), 1e-4);
	}

	@Test
	@DisplayName("a neck goes the short way round")
	void itTakesTheShortWay() {
		// Facing 170 and asked for -170 is twenty degrees to the left, not three
		// hundred and forty to the right. Nobody looks over their shoulder the long
		// way round.
		assertEquals(176, Neck.step(170, -170, 6), 1e-3);
		assertEquals(-176, Neck.step(-170, 170, 6), 1e-3);
	}

	@Test
	@DisplayName("the body stays still while the head is comfortable")
	void theBodyDoesNotShuffle() {
		// A character following somebody across a doorway with her eyes must not
		// turn on the spot to do it. Seventy-five degrees is inside a human neck.
		assertEquals(0, Neck.overTurn(70, 0, 75), 1e-4);
		assertEquals(0, Neck.overTurn(-70, 0, 75), 1e-4);
		assertEquals(0, Neck.overTurn(0, 0, 75), 1e-4);
	}

	@Test
	@DisplayName("and turns exactly as far as the neck ran short, either way")
	void theBodyMakesUpTheDifference() {
		assertEquals(15, Neck.overTurn(90, 0, 75), 1e-4);
		assertEquals(-15, Neck.overTurn(-90, 0, 75), 1e-4);

		// Applied, the body ends up leaving the head exactly at its limit rather
		// than square on — which is what makes a character look like she is watching
		// over her shoulder instead of standing to attention.
		float body = 0 + Neck.overTurn(90, 0, 75);
		assertEquals(75, Neck.wrap(90 - body), 1e-4);
	}

	@Test
	@DisplayName("the body catches up over several ticks, behind the head")
	void theBodyLagsTheHead() {
		// Head first, body after, which is how people turn and is the difference
		// between glancing at a noise and squaring up to somebody.
		float head = 0;
		float body = 0;
		for (int i = 0; i < 60; i++) {
			head = Neck.step(head, 180, 15);
			float excess = Neck.overTurn(head, body, 75);
			if (excess != 0) body = Neck.step(body, body + excess, 5);
			// Applied last, exactly as the character does it. Without this line the
			// loop fails at the sixth tick with eighty-five degrees of neck, which is
			// how the missing clamp was found in the first place.
			head = Neck.held(head, body, 75);
			assertTrue(Math.abs(Neck.wrap(head - body)) <= 75.001f,
				"tick " + i + ": head " + head + " body " + body);
		}
		assertEquals(180, Math.abs(head), 1e-3, "the head got all the way round");
		assertTrue(Math.abs(Neck.wrap(head - body)) > 1,
			"and the body is still behind it, at " + body);
	}

	@Test
	@DisplayName("pitch is measured the game's way, down positive")
	void pitchMatchesTheGame() {
		assertEquals(0, Neck.pitchTo(0, 0, 0, 0, 0, 5), 1e-4, "level is nought");
		assertTrue(Neck.pitchTo(0, 5, 0, 0, 0, 5) > 0, "looking down is positive");
		assertTrue(Neck.pitchTo(0, 0, 0, 0, 5, 5) < 0, "looking up is negative");
		assertEquals(45, Neck.pitchTo(0, 5, 0, 0, 0, 5), 1e-3);
	}

	@Test
	@DisplayName("wrapping brings any angle into half a turn either side")
	void wrapping() {
		for (float angle = -1000; angle < 1000; angle += 7.3f) {
			float within = Neck.wrap(angle);
			assertTrue(within >= -180 && within < 180, angle + " wrapped to " + within);
		}
	}
}
