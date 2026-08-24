package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Darkness and cover: the two things about where you are standing.
 */
class ThicketTest {

	// ------------------------------------------------------------------ the dark

	@Test
	@DisplayName("full light is full sight and darkness is nearly none")
	void lightRuns() {
		assertEquals(1f, Sight.byLight(15), 1e-4);
		assertEquals(Sight.IN_THE_DARK, Sight.byLight(0), 1e-4);
	}

	@Test
	@DisplayName("darkness never makes anybody literally invisible")
	void darknessIsNotInvisibility() {
		// A floor of zero is a cheat waiting to be found: dig a one-block hole, stand
		// in it, disappear at any range. Even in the dark a moving shape at arm's
		// length registers.
		assertTrue(Sight.byLight(0) > 0);
		for (int light = 0; light <= 15; light++) {
			assertTrue(Sight.byLight(light) > 0 && Sight.byLight(light) <= 1,
				"at light " + light + ": " + Sight.byLight(light));
		}
	}

	@Test
	@DisplayName("the curve is steep at the bottom and flat at the top, like an eye")
	void eyesAdapt() {
		// Going from a torchlit room to a bright one barely changes what you can make
		// out; going from a dim one to pitch dark changes everything. A straight line
		// from nought to fifteen gets both of those wrong.
		float lowStep = Sight.byLight(2) - Sight.byLight(0);
		float highStep = Sight.byLight(15) - Sight.byLight(13);
		assertTrue(lowStep > highStep * 2, lowStep + " against " + highStep);
	}

	@Test
	@DisplayName("more light is never less sight")
	void brighterIsAlwaysBetter() {
		for (int light = 1; light <= 15; light++) {
			assertTrue(Sight.byLight(light) >= Sight.byLight(light - 1), "at " + light);
		}
	}

	@Test
	@DisplayName("night out of doors is worse than day and far better than a cave")
	void theThreeCasesThatMatter() {
		// Sky light drops to about four at night, which is the number this has to be
		// sensible at: noticeably harder, nowhere near blind.
		float day = Sight.byLight(15);
		float night = Sight.byLight(4);
		float cave = Sight.byLight(0);
		assertTrue(night < day * 0.75f, "night came to " + night);
		assertTrue(night > cave * 2, "and is well above a dark cave: " + night);
	}

	@Test
	@DisplayName("darkness shortens the range rather than switching sight off")
	void darknessIsARange() {
		// Multiplied into a strength that already falls with distance, so somebody in
		// the dark is seen close and not far — which is what darkness does.
		Sight eyes = Sight.ORDINARY;
		float closeInTheDark = eyes.strength(3, 0) * Sight.byLight(0);
		float farInTheDark = eyes.strength(20, 0) * Sight.byLight(0);
		assertTrue(closeInTheDark > farInTheDark * 2, closeInTheDark + " / " + farInTheDark);
	}

	// -------------------------------------------------------------------- cover

	@Test
	@DisplayName("standing in wheat hides your legs, which nobody was looking at")
	void legsInItIsNearlyNothing() {
		// A head and shoulders above a field is a head and shoulders, and that is the
		// part anybody looks at.
		assertEquals(Thicket.SHALLOW, Thicket.concealment(true, false), 1e-4);
		assertTrue(Thicket.SHALLOW < 0.35f, "it came to " + Thicket.SHALLOW);
	}

	@Test
	@DisplayName("crouching puts your head in it, and that is the whole mechanic")
	void crouchingIsTheMechanic() {
		// Worth a keypress and worth discovering rather than being told.
		float standing = Thicket.concealment(true, false);
		float crouched = Thicket.concealment(true, true);
		assertTrue(crouched > standing * 3, standing + " then " + crouched);
		assertEquals(Thicket.DEEP, crouched, 1e-4);
	}

	@Test
	@DisplayName("cover never makes anybody invisible either")
	void coverIsNotInvisibility() {
		assertTrue(Thicket.DEEP < 1f, "deep cover came to " + Thicket.DEEP);
		assertEquals(0f, Thicket.concealment(false, false), 1e-4, "and bare ground is bare");
	}

	// ------------------------------------------------------------ the other half

	@Test
	@DisplayName("crossing a field takes away the benefit of creeping")
	void rustlingIsAFloor() {
		// The trade the whole class exists for. You can crouch through a field and be
		// almost invisible, and you will be heard doing it, and there is nothing to be
		// done about that except go round.
		float creeping = Noise.loudness(0.05, true, false, false);
		float inTheWheat = Thicket.rustling(creeping, true);
		assertTrue(inTheWheat > creeping * 4, creeping + " becomes " + inTheWheat);
		assertTrue(Noise.heard(8, inTheWheat) > 0, "and is audible across a field");
		assertEquals(0f, Noise.heard(8, creeping), 1e-4, "where creeping on stone is not");
	}

	@Test
	@DisplayName("a field is no louder than the man running through it")
	void itIsAFloorAndNotAnAddition() {
		// Added on it would make a running man in wheat slightly louder than one on
		// stone, which is true and dull.
		float running = Noise.loudness(0.28, false, true, false);
		assertEquals(running, Thicket.rustling(running, true), 1e-4);
	}

	@Test
	@DisplayName("standing still in a field is as quiet as standing still anywhere")
	void stillnessIsStillSilent() {
		// A field that hissed at a motionless man would be a strange field, and it
		// would also make hiding in one pointless.
		assertEquals(0f, Thicket.rustling(0f, false), 1e-4);
	}

	@Test
	@DisplayName("the field gives and takes at once")
	void theWholeTrade() {
		// Both halves in one place, because the point is not either of them: it is
		// that crossing a field becomes a decision rather than an obvious yes.
		float hidden = Thicket.concealment(true, true);
		float heard = Thicket.rustling(Noise.loudness(0.05, true, false, false), true);
		assertTrue(hidden > 0.5f, "well hidden");
		assertTrue(heard > 0.15f, "and well heard");
	}
}
