package com.mopicmp.npcstudio.show;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pointing at a thing that hangs in the air.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * Because it is the whole of a press. There is no hit box in the world doing any of this —
 * see {@link Aim} for why there deliberately is not — so if this arithmetic is wrong, a
 * menu is a row of labels that cannot be pressed, and nothing anywhere says so.
 *
 * It is also arithmetic nobody can check by looking at a running game: "it did not press"
 * looks the same whether the ray missed, the graph was not waiting, or the thing was never
 * hung. Here the three are separate.
 */
class AimTest {

	/** Looking due east from the origin, which is the x axis. */
	private static final double[] EYE = { 0, 0, 0 };
	private static final double[] EAST = { 1, 0, 0 };

	private static String at(List<Aim> things, double reach) {
		return Aim.at(things, EYE[0], EYE[1], EYE[2], EAST[0], EAST[1], EAST[2], reach);
	}

	@Test
	@DisplayName("a thing straight ahead is the one pressed")
	void straightAhead() {
		assertEquals("menu.play", at(List.of(new Aim("menu.play", 3, 0, 0, 1)), 5));
	}

	@Test
	@DisplayName("a thing behind the eye is not pressed")
	void behindYou() {
		assertEquals("", at(List.of(new Aim("menu.play", -3, 0, 0, 1)), 5));
	}

	@Test
	@DisplayName("a thing past the end of the reach is not pressed")
	void outOfReach() {
		assertEquals("", at(List.of(new Aim("menu.play", 20, 0, 0, 1)), 5));
	}

	@Test
	@DisplayName("a thing off to the side is not pressed")
	void offToTheSide() {
		// A whole block sideways at scale one, which is well outside the ball. This is the
		// case that keeps a menu from answering for the row above the one aimed at.
		assertEquals("", at(List.of(new Aim("menu.play", 3, 0, 1, 1)), 5));
	}

	@Test
	@DisplayName("of two along the same line, the nearer takes the press")
	void theNearOneWins() {
		String pressed = at(List.of(
			new Aim("far", 4, 0, 0, 1),
			new Aim("near", 2, 0, 0, 1)), 5);
		assertEquals("near", pressed, "what somebody can see is what they pressed");
	}

	@Test
	@DisplayName("a larger thing is pressable from further off its middle")
	void sizeWidensIt() {
		// Half a block to the side. At scale one that is a miss; at scale four the ball is
		// wide enough to hold it — which is what makes a big title over a doorway aimable
		// without having to find its exact centre.
		assertEquals("", at(List.of(new Aim("small", 3, 0.5, 0, 1)), 5));
		assertEquals("big", at(List.of(new Aim("big", 3, 0.5, 0, 4)), 5));
	}

	@Test
	@DisplayName("standing inside one still presses it")
	void rightOnTopOfIt() {
		// A hologram a person has walked into is still a hologram they are looking at, and
		// a test that only accepted things in front of the eye would refuse this.
		assertEquals("menu.play", at(List.of(new Aim("menu.play", 0, 0, 0, 1)), 5));
	}

	@Test
	@DisplayName("nothing near is nothing pressed")
	void nothingThere() {
		assertEquals("", at(List.of(), 5));
	}
}
