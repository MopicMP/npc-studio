package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Telling a passer-by from somebody coming for you, and getting used to a mill.
 *
 * <h2>The cases these are written against</h2>
 *
 * Named directly: a man running <em>away from</em> an explosion is not a threat
 * even holding a knife, and the same man running <em>at</em> you with the same
 * knife is the most urgent thing in the world. Nothing about him has changed
 * except his direction and where he is looking, so those had better be what the
 * arithmetic turns on.
 */
class MenaceTest {

	private static final double A_WALK = 0.12;
	private static final double A_SPRINT = 0.28;

	@Test
	@DisplayName("somebody minding their own business is barely worth noting")
	void aPasserByIsNothingMuch() {
		assertEquals(Menace.PASSER_BY, Menace.of(false, 0, false), 1e-4);
		assertEquals(Menace.PASSER_BY, Menace.of(false, -A_WALK, false), 1e-4,
			"and walking away is no more alarming than standing still");
	}

	@Test
	@DisplayName("a weapon alone is not much, because half the countryside carries an axe")
	void aWeaponAloneIsNotMuch() {
		float armed = Menace.of(true, 0, false);
		assertTrue(armed > Menace.PASSER_BY, "it counts for something");
		assertTrue(armed < 0.6f, "but not for much on its own: " + armed);
	}

	@Test
	@DisplayName("the man fleeing an explosion with a knife is ignored")
	void theFleeingManIsIgnored() {
		// The case as it was put. He is armed, he is running, and he is running away
		// — and the third fact is the one that settles it.
		float fleeing = Menace.of(true, -A_SPRINT, false);
		float charging = Menace.of(true, A_SPRINT, true);
		assertTrue(charging > fleeing * 1.8f,
			"fleeing " + fleeing + " against charging " + charging);
		assertTrue(fleeing < 0.6f, "and fleeing is not itself alarming: " + fleeing);
	}

	@Test
	@DisplayName("running at you with a weapon and looking at you is the worst there is")
	void bearingDownIsTheWorst() {
		assertEquals(Menace.BEARING_DOWN, Menace.of(true, A_SPRINT, true), 1e-4);
	}

	@Test
	@DisplayName("running past you is not running at you")
	void lookingAtYouIsWhatSeparatesThem() {
		// Somebody sprinting past and somebody sprinting at you are the same speed
		// and the same distance closing; where they are looking is the difference.
		float past = Menace.of(true, A_SPRINT, false);
		float at = Menace.of(true, A_SPRINT, true);
		assertTrue(at > past, past + " then " + at);
	}

	@Test
	@DisplayName("a sprint counts for more than a stroll")
	void speedOfApproachMatters() {
		assertTrue(Menace.of(false, A_SPRINT, true) > Menace.of(false, A_WALK, true));
	}

	@Test
	@DisplayName("an unarmed charge is still a charge, and still less than an armed one")
	void unarmedStillCounts() {
		float unarmed = Menace.of(false, A_SPRINT, true);
		assertTrue(unarmed > Menace.PASSER_BY);
		assertTrue(unarmed < Menace.of(true, A_SPRINT, true));
	}

	@Test
	@DisplayName("menace never leaves its bounds")
	void alwaysInRange() {
		for (int armed = 0; armed <= 1; armed++) {
			for (double closing = -1; closing <= 1; closing += 0.05) {
				for (int aiming = 0; aiming <= 1; aiming++) {
					float menace = Menace.of(armed == 1, closing, aiming == 1);
					assertTrue(menace >= 0 && menace <= Menace.BEARING_DOWN,
						armed + "/" + closing + "/" + aiming + " came to " + menace);
				}
			}
		}
	}

	@Test
	@DisplayName("being aimed at is judged generously, because it is judged at a glance")
	void aimIsGenerous() {
		// A weapon does not have to be pointed exactly to be pointed at you, and the
		// whole judgement is made from some distance away.
		assertTrue(Menace.aimedAt(0));
		assertTrue(Menace.aimedAt(25));
		assertTrue(!Menace.aimedAt(90), "and somebody facing across you is not aiming at you");
	}

	// --------------------------------------------------------- getting used to it

	@Test
	@DisplayName("the fifth piston in the same place is furniture")
	void repetitionDulls() {
		// A miller does not jump at his own mill. And a character who does is not
		// merely tiring to watch, she is exploitable: hold down a button and she
		// never looks anywhere else.
		float door = Din.urgencyFor(1f, false);
		assertEquals(1f, Din.dulledBy(1, door), 1e-4);
		assertTrue(Din.dulledBy(5, door) < 0.3f, "the fifth came to " + Din.dulledBy(5, door));
	}

	@Test
	@DisplayName("an explosion is an explosion the tenth time")
	void urgentThingsBarelyFade() {
		// The asymmetry is the whole point. Habituation that treated everything alike
		// would make somebody who blows a hole in a wall five times running invisible,
		// which is the opposite of what habituation is for.
		float bang = Din.urgencyFor(4f, false);
		assertTrue(Din.dulledBy(10, bang) > 0.9f,
			"the tenth explosion came to " + Din.dulledBy(10, bang));
	}

	@Test
	@DisplayName("nothing ever fades to literally nothing")
	void thereIsAlwaysAGlance() {
		// The fifth piston is still worth a glance; it is just not worth a turn.
		for (int heard = 1; heard < 40; heard++) {
			assertTrue(Din.dulledBy(heard, 0f) >= Din.FLOOR_OF_INTEREST,
				"at " + heard + " it came to " + Din.dulledBy(heard, 0f));
		}
	}

	@Test
	@DisplayName("familiarity only ever falls")
	void itNeverRecovers() {
		float door = Din.urgencyFor(1f, false);
		float before = 1;
		for (int heard = 1; heard < 20; heard++) {
			float now = Din.dulledBy(heard, door);
			assertTrue(now <= before + 1e-6, "went up at " + heard);
			before = now;
		}
	}
}
