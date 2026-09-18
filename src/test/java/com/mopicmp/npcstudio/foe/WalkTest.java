package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Getting going, keeping going, and stopping.
 *
 * <h2>The three complaints these are written against</h2>
 *
 * Too sharp a change from standing to running; no walk in between; and a
 * character who runs, stops for a second, runs again and stops. The first two
 * are the pace; the third was a walk being thrown away and rebuilt.
 */
class WalkTest {

	private static Walk walking(int... alongX) {
		Walk walk = new Walk();
		List<int[]> path = new java.util.ArrayList<>();
		for (int x : alongX) path.add(new int[] { x, 0, 0 });
		walk.follow(path);
		return walk;
	}

	@Test
	@DisplayName("nothing goes from standing to running in a tick")
	void paceEasesIn() {
		// A character that does reads as a puppet being dragged rather than as a
		// person deciding to move.
		Walk walk = new Walk();
		assertTrue(walk.pacing(Walk.HURRYING) < 0.2f, "one tick in: " + walk.pace());

		int ticks = 1;
		while (walk.pace() < 0.99f && ticks < 200) {
			walk.pacing(Walk.HURRYING);
			ticks++;
		}
		assertTrue(ticks > 12, "reaching a run took " + ticks + " ticks");
		assertTrue(ticks < 40, "and it should not take " + ticks);
	}

	@Test
	@DisplayName("there is a walk between standing and running")
	void thereIsAWalk() {
		// The other half of the same complaint, and a different fault: standing and
		// running were the only two states, so any journey at all was a sprint.
		assertTrue(Walk.WANDERING > 0 && Walk.WANDERING < Walk.HURRYING);

		Walk walk = new Walk();
		for (int i = 0; i < 60; i++) walk.pacing(Walk.WANDERING);
		assertEquals(Walk.WANDERING, walk.pace(), 0.02f);
	}

	@Test
	@DisplayName("stopping is quicker than starting, because it is")
	void stoppingIsQuicker() {
		Walk walk = new Walk();
		for (int i = 0; i < 60; i++) walk.pacing(Walk.HURRYING);

		int stopping = 0;
		while (walk.pace() > 0 && stopping < 200) {
			walk.pacing(0);
			stopping++;
		}
		int starting = 0;
		while (walk.pace() < 0.99f && starting < 200) {
			walk.pacing(Walk.HURRYING);
			starting++;
		}
		assertTrue(stopping < starting, "stopping " + stopping + ", starting " + starting);
	}

	@Test
	@DisplayName("the pace comes to rest at nought rather than creeping for ever")
	void itActuallyStops() {
		Walk walk = new Walk();
		for (int i = 0; i < 60; i++) walk.pacing(Walk.HURRYING);
		for (int i = 0; i < 60; i++) walk.pacing(0);
		assertEquals(0f, walk.pace(), 1e-6);
	}

	// ------------------------------------------------------------ the waypoints

	@Test
	@DisplayName("a waypoint is dropped once it is near enough, not stood on")
	void waypointsAreSteppedPast() {
		// Nobody walks through block centres; they cut the corners, which is what
		// makes walking look like walking.
		Walk walk = walking(1, 2, 3);
		double[] first = walk.heading(1.4, 0, 0.5);
		assertEquals(2.5, first[0], 1e-6, "already heading for the second");
	}

	@Test
	@DisplayName("several waypoints can be dropped in one tick")
	void severalAtOnce() {
		// A corridor has a waypoint every block, and a character rounding a corner
		// into it should not spend six ticks ticking them off from a standstill.
		//
		// The path runs well past where she is standing, on purpose: within a block
		// and a half of the *last* waypoint she has arrived rather than dropped it,
		// which is deliberate and is a different test.
		Walk walk = walking(1, 2, 3, 4, 5, 6, 7, 8);
		double[] heading = walk.heading(4.4, 0, 0.5);
		assertEquals(5.5, heading[0], 1e-6);
		assertTrue(walk.reached() >= 4, "it dropped " + walk.reached());
	}

	@Test
	@DisplayName("arriving ends the walk")
	void arriving() {
		Walk walk = walking(1, 2);
		assertTrue(walk.walking());
		assertEquals(null, walk.heading(2.5, 0, 0.5));
		assertTrue(!walk.walking());
	}

	@Test
	@DisplayName("standing on the roof above the door is not arriving at the door")
	void heightHasToAgree() {
		Walk walk = new Walk();
		walk.follow(List.of(new int[] { 3, 0, 0 }));
		assertTrue(walk.heading(3.5, 6, 0.5) != null,
			"six blocks above it is not there yet");
	}

	// --------------------------------------------------------------- being stuck

	@Test
	@DisplayName("getting nowhere for long enough counts as stuck")
	void gettingStuck() {
		Walk walk = walking(1, 2, 3);
		for (int i = 0; i < Walk.STUCK_AFTER + 1; i++) walk.moved(0.5, 0.5);
		assertTrue(walk.stuck());
	}

	@Test
	@DisplayName("squeezing past a doorframe is not being stuck")
	void aMomentOfNothingIsFine() {
		Walk walk = walking(1, 2, 3);
		for (int i = 0; i < 10; i++) walk.moved(0.5, 0.5);
		assertTrue(!walk.stuck());
		walk.moved(1.0, 0.5);
		for (int i = 0; i < 10; i++) walk.moved(1.0, 0.5);
		assertTrue(!walk.stuck(), "the count starts again once anything happens");
	}

	@Test
	@DisplayName("closing the last stretch on somebody is not the same as arriving at a place")
	void walkingAtSomebodyGetsCloserThanWalkingToASpot() {
		// The bug this is written against froze two fighters facing each other.
		//
		// A blow reaches about a block, so a fighter has to close to about a block.
		// The last waypoint of a route is dropped as soon as she is within ARRIVED of
		// it — a block and a half, which is right for a doorway and is further away
		// than a punch lands. So the walk reported itself finished while she was
		// still out of range, the graph asked for a walk again, and the walk finished
		// again on the same tick. Neither of them was wrong and nobody moved.
		Walk walk = walking(4);
		double standingAt = 3.0;

		assertEquals(null, walk.heading(standingAt, 0, 0.5),
			"a block and a half short counts as arrived, which is the whole trouble");

		Walk closing = walking(4);
		double[] step = closing.heading(standingAt, 0, 0.5, 0.3);
		assertTrue(step != null, "asked to get closer, she still has somewhere to go");
		assertTrue(step[0] > standingAt, "and it is forwards: " + step[0]);
	}

	@Test
	@DisplayName("standing still on purpose is not being stuck")
	void notWalkingIsNotStuck() {
		Walk walk = new Walk();
		for (int i = 0; i < 100; i++) walk.moved(0.5, 0.5);
		assertTrue(!walk.stuck());
	}

	// ------------------------------------------------- arriving, and giving up

	/**
	 * Whether she is there.
	 *
	 * <h2>Why this question is worth a test of its own</h2>
	 *
	 * Because the route used not to ask it. A walk ends two ways — she gets there,
	 * or she has been pushing at a wall for a second and a half — and the route
	 * treated both as arriving. So a character caught on a corner stepped on to the
	 * next point; caught on the <em>last</em> point she stopped where she stood, and
	 * the graph moved on believing the round was walked.
	 *
	 * That is the shape of the report: she catches on the walls and then stands still
	 * without reaching the end. Nothing in it looks like an error, which is why the
	 * arithmetic is out here where it can be read.
	 */
	@Test
	@DisplayName("standing on the block is being there")
	void onIt() {
		int[] mark = { 10, 64, 10 };
		// The middle of the block, which is where a body ends up.
		assertTrue(Walk.reached(mark, 10.5, 64, 10.5, Walk.ARRIVED));
	}

	@Test
	@DisplayName("a block away is still being there, because nobody stands on a point")
	void nearEnough() {
		int[] mark = { 10, 64, 10 };
		// ARRIVED is deliberately loose — a character who stops a foot short of a
		// doorway has arrived at the doorway — and this is the number the route uses
		// to decide the same thing, so the two cannot drift.
		assertTrue(Walk.reached(mark, 11.5, 64, 10.5, Walk.ARRIVED));
	}

	@Test
	@DisplayName("wedged against the wall two blocks short is not being there")
	void shortOfIt() {
		int[] mark = { 10, 64, 10 };
		// The case the whole thing is about. She stopped because she could not get
		// any further, and calling that an arrival is what lost the rest of the round.
		assertFalse(Walk.reached(mark, 7.5, 64, 10.5, Walk.ARRIVED));
	}

	@Test
	@DisplayName("standing on the roof is not being at the doorway underneath")
	void aboveIt() {
		int[] mark = { 10, 64, 10 };
		// Flat distance alone says she is there. A route up a tower would then call
		// every landing the top, and one over a bridge would call the water under it
		// the far bank.
		assertTrue(Walk.reached(mark, 10.5, 64, 10.5, Walk.ARRIVED));
		assertFalse(Walk.reached(mark, 10.5, 68, 10.5, Walk.ARRIVED));
		assertFalse(Walk.reached(mark, 10.5, 60, 10.5, Walk.ARRIVED));
	}

	@Test
	@DisplayName("trying again is bounded, because some places cannot be reached at all")
	void triesAreBounded() {
		// A wall built across a round since it was drawn is a point no number of
		// searches reaches. The retry has to be worth having and has to end: three is
		// about five seconds of trying, and then she goes on to the next point rather
		// than standing there for the life of the world.
		assertTrue(Walk.TRIES >= 1, "one try is no retry at all");
		assertTrue(Walk.TRIES <= 5, "more than this is a character who looks lost");
	}
}
