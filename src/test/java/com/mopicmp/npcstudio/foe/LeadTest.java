package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * Which of the things a character has noticed wins, and how fast she turns.
 *
 * <h2>Why one kind of finding and not one per sense</h2>
 *
 * There used to be a branch per sense, each writing into its own fields, with
 * what she was actually looking at reassembled from those fields afterwards by a
 * second set of rules — and the two disagreed. An explosion would correctly
 * become the most important thing in the world, and then the reassembly, seeing
 * a person also on file, quietly returned the person. That was reported as two
 * separate faults and was one.
 */
class LeadTest {

	private static Lead noise(float strength, float urgency) {
		return Lead.noise(new Vec3(0, 64, 0), null, strength, urgency);
	}

	@Test
	@DisplayName("importance decides, and loudness only settles a tie")
	void urgencyBeatsStrength() {
		// The crater, as arithmetic. Falling rubble at your feet is genuinely louder
		// than an explosion forty blocks off, and is not what anybody would look at.
		Lead rubble = noise(0.9f, 0.2f);
		Lead bang = noise(0.2f, 1f);
		assertTrue(bang.beats(rubble));
		assertTrue(!rubble.beats(bang));

		Lead quiet = noise(0.3f, 0.5f);
		Lead loud = noise(0.8f, 0.5f);
		assertTrue(loud.beats(quiet), "and with nothing to choose on importance, the louder");
	}

	@Test
	@DisplayName("anything beats nothing")
	void anythingBeatsNothing() {
		assertTrue(noise(0.01f, 0.01f).beats(null));
	}

	@Test
	@DisplayName("a shot going past outranks any ordinary noise")
	void shotsAreUrgent() {
		// A bang means something happened; an arrow past your ear means somebody is
		// shooting at you, and there is no third thing to be doing about that.
		Lead shot = noise(0.4f, Shots.URGENCY);
		assertTrue(shot.beats(noise(1f, Din.urgencyFor(1f, false))));
		assertTrue(shot.beats(noise(1f, Din.urgencyFor(1f, true))));
	}

	@Test
	@DisplayName("a footstep never out-ranks a bang")
	void footstepsAreTheFloor() {
		// The crater problem in the other direction: the plainest evidence there is
		// must not be able to pull a head away from an explosion.
		Lead heard = noise(1f, 0.2f);
		assertTrue(noise(0.1f, Din.urgencyFor(4f, false)).beats(heard));
	}

	// ------------------------------------------------------------ turning speed

	@Test
	@DisplayName("a bang turns the head fast and a footstep does not")
	void speedFollowsUrgency() {
		// It used to be chosen by mood, which cannot say this: mood moves slowly by
		// design, so a dropped stick and a stick of dynamite turned her head at the
		// same rate. She whipped round at nothing and took her time about explosions.
		float atABang = noise(0.5f, 1f).turningAt();
		float atAStep = noise(0.5f, 0.2f).turningAt();
		assertTrue(atABang > atAStep * 2, atABang + " against " + atAStep);
	}

	@Test
	@DisplayName("even the least urgent turn finishes in a reasonable time")
	void nothingTakesForever() {
		// A character who takes ten seconds to look round has not reacted, and it was
		// asked for by name: a loud thing must not have her turning her head for ten
		// seconds afterwards, and quiet things must not turn it at the same rate.
		float slowest = noise(0.1f, 0f).turningAt();
		assertTrue(slowest > 0, "it has to move at all");
		assertTrue(180 / slowest <= 40,
			"half a turn at the slowest rate took " + (180 / slowest) + " ticks");
	}

	@Test
	@DisplayName("the fastest turn is quick without being instant")
	void theFastestIsStillATurn() {
		float quickest = noise(1f, 1f).turningAt();
		double ticks = 90 / quickest;
		assertTrue(ticks >= 3, "a right angle took " + ticks + " ticks, which is a snap");
		assertTrue(ticks <= 10, "and it took " + ticks + ", which is a stroll");
	}

	@Test
	@DisplayName("the shoulders always lag the head")
	void shouldersFollow() {
		for (float urgency = 0; urgency <= 1; urgency += 0.1f) {
			Lead lead = noise(0.5f, urgency);
			assertTrue(lead.squaringAt() < lead.turningAt(),
				"at urgency " + urgency);
		}
	}

	// ------------------------------------------------------------- where to look

	@Test
	@DisplayName("a noise is a place and never a track")
	void noisesDoNotBecomeTracks() {
		// Hearing somebody move behind a wall used to hand back their exact position
		// every tick, which is watching them through the wall by another name — and
		// when they stopped, the head snapped away as though nothing had happened.
		Lead heard = Lead.noise(new Vec3(3, 64, 4), null, 0.5f, 0.2f);
		assertTrue(!heard.seen());
		assertEquals(3, heard.at().x, 1e-9);
	}

	// -------------------------------------------------------------- back-tracing

	@Test
	@DisplayName("a shot points back the way it came")
	void tracingBackwards() {
		// An arrow that misses tells you where the archer is, and none of it through
		// your ears. Travelling east means the shooter is west.
		Vec3 at = new Vec3(10, 64, 0);
		Vec3 east = new Vec3(1, 0, 0);
		Vec3 from = Shots.firedFrom(at, east, Shots.BACK_ALONG);
		assertTrue(from.x < at.x, "it came from the west, at " + from);
		assertEquals(0, from.z, 1e-6);
		assertEquals(Shots.BACK_ALONG, at.distanceTo(from), 1e-6);
	}

	@Test
	@DisplayName("the bearing is what matters, not how fast it was going")
	void speedDoesNotChangeTheBearing() {
		Vec3 at = new Vec3(0, 64, 0);
		Vec3 slow = Shots.firedFrom(at, new Vec3(0.1, 0, 0.1), Shots.BACK_ALONG);
		Vec3 fast = Shots.firedFrom(at, new Vec3(3, 0, 3), Shots.BACK_ALONG);
		assertEquals(slow.x, fast.x, 1e-6);
		assertEquals(slow.z, fast.z, 1e-6);
	}

	@Test
	@DisplayName("a wall in the way shortens the guess to the wall")
	void aWallShortensIt() {
		// An arrow through a doorway says the archer is at the doorway, not sixteen
		// blocks beyond it through solid rock.
		Vec3 at = new Vec3(0, 64, 0);
		Vec3 east = new Vec3(1, 0, 0);
		Vec3 near = Shots.firedFrom(at, east, 4);
		assertEquals(4, at.distanceTo(near), 1e-6);
	}

	@Test
	@DisplayName("a shot going nowhere is not traced anywhere")
	void aStoppedArrowSaysNothing() {
		Vec3 at = new Vec3(5, 64, 5);
		assertEquals(at, Shots.firedFrom(at, Vec3.ZERO, Shots.BACK_ALONG));
	}

	@Test
	@DisplayName("one that nearly parts your hair startles more than a distant one")
	void closeShotsStartleMore() {
		assertTrue(Shots.startle(1) > Shots.startle(15));
		assertEquals(0f, Shots.startle(Shots.NOTICED_WITHIN), 1e-4,
			"and a battle far off is somebody else's business");
	}

	@Test
	@DisplayName("working it out takes a moment but not a pause")
	void deductionTakesTime() {
		// A character who snaps to the firing position on the frame the arrow passes
		// has not deduced anything, she has read its owner field — and it looks like
		// it, because no thought is that fast.
		assertTrue(Shots.WORKING_IT_OUT >= 5, "under a quarter second is not a thought");
		assertTrue(Shots.WORKING_IT_OUT <= 20, "over a second and the next arrow has landed");
	}
}
