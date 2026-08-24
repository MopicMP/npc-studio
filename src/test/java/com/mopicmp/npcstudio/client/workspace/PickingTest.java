package com.mopicmp.npcstudio.client.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Clicking a character who is standing on a ship.
 *
 * <h2>The failure this was written for</h2>
 *
 * Every click in the scene window chose the ship. Not sometimes and not near the
 * edges — every one, including clicks squarely on somebody's face, which is the
 * detail that says what it was: a bounding box round a whole vessel is entered
 * by the ray a long way before anything on board is reached, so "whichever box
 * is met first" answers the ship for the entire quarter of the sky the ship is
 * in. A character on the deck is inside that box and can never be in front of
 * it.
 *
 * The numbers below are the shape of the thing that was reported: a hull, a mast
 * standing well behind it, and a character between the two.
 */
class PickingTest {

	/** A ship: a deck low down and a mast at the back of it. */
	private static final List<AABB> SHIP = List.of(
		new AABB(-6, 0, -10, 6, 1, 10),
		new AABB(-1, 1, 4, 1, 12, 6));

	/** The box round all of it, which is what the picking used to ask about. */
	private static final AABB HULL = new AABB(-6, 0, -10, 6, 12, 10);

	/** Somebody standing on the deck, in front of the mast. */
	private static final AABB CHARACTER = new AABB(-0.3, 1, -0.3, 0.3, 2.8, 0.3);

	/** The camera, out in front and a little above, looking at the character. */
	private static final Vec3 EYE = new Vec3(0, 6, -25);

	private static Vec3 towards(Vec3 at) {
		return EYE.add(at.subtract(EYE).normalize().scale(120));
	}

	@Test
	@DisplayName("a character on the deck is in front of the ship, not behind it")
	void theCharacterWins() {
		Vec3 aim = towards(new Vec3(0, 2, 0));

		double ship = Picking.reach(SHIP, HULL, 0, EYE, aim);
		double person = Picking.reach(null, CHARACTER, 0.15, EYE, aim);

		assertFalse(Double.isNaN(person), "the ray has to reach the character at all");
		assertTrue(person < ship,
			"the character is at " + person + " and the ship at " + ship
				+ ", so the click still lands on the ship");
	}

	@Test
	@DisplayName("and it was the box round the ship that made that impossible")
	void theWholeBoxIsTheFault() {
		// The same aim, measured the old way. This is not a regression test for the
		// fix — it is the evidence for the diagnosis, and it fails if somebody ever
		// decides the box was innocent.
		Vec3 aim = towards(new Vec3(0, 2, 0));

		double whole = Picking.reach(null, HULL, 0, EYE, aim);
		double person = Picking.reach(null, CHARACTER, 0.15, EYE, aim);

		assertTrue(whole < person,
			"asking the box round the ship put it in front of the character, as reported");
	}

	@Test
	@DisplayName("the ship is still clickable where there is nobody in the way")
	void theShipIsStillPickable() {
		// Aimed at the deck well to one side of anybody standing on it.
		Vec3 aim = towards(new Vec3(4, 0.5, 0));

		double ship = Picking.reach(SHIP, HULL, 0, EYE, aim);
		double person = Picking.reach(null, CHARACTER, 0.15, EYE, aim);

		assertFalse(Double.isNaN(ship), "the deck has to be reachable");
		assertTrue(Double.isNaN(person), "and nobody is standing there");
	}

	@Test
	@DisplayName("something genuinely in front still wins, parts or no parts")
	void whatIsInFrontStaysInFront() {
		// A character standing at the far end of the deck, with the mast between
		// them and the camera. Aimed straight at them, so nothing about the miss
		// could be the aim.
		AABB behind = new AABB(-0.3, 1, 8, 0.3, 2.8, 8.6);
		Vec3 aim = towards(new Vec3(0, 1.9, 8.3));

		double ship = Picking.reach(SHIP, HULL, 0, EYE, aim);
		double person = Picking.reach(null, behind, 0.15, EYE, aim);

		assertFalse(Double.isNaN(ship));
		assertTrue(ship < person, "a mast in the way is in the way");
	}

	@Test
	@DisplayName("a model nobody has measured falls back to its box rather than vanishing")
	void anUnmeasuredModelStillAnswers() {
		// Parts are worked out on a tick; an object that has just arrived has none.
		// It has to stay clickable in the meantime, or a freshly placed model would
		// be a thing you can see and cannot select.
		Vec3 aim = towards(new Vec3(0, 2, 0));
		assertTrue(Picking.meetsAny(List.of(), HULL, 0, EYE, aim));
		assertTrue(Picking.meetsAny(null, HULL, 0, EYE, aim));
	}

	@Test
	@DisplayName("a ray that meets nothing says so rather than answering nought")
	void nothingIsNotZero() {
		// Nought would be "right at the camera", which is the nearest thing there
		// can be — so a miss reported as nought wins every comparison it is in.
		Vec3 away = EYE.add(new Vec3(0, 1, 0).scale(120));
		assertTrue(Double.isNaN(Picking.reach(SHIP, HULL, 0, EYE, away)));
		assertTrue(Double.isNaN(Picking.reach(null, CHARACTER, 0.15, EYE, away)));
	}
}
