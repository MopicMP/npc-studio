package com.mopicmp.npcstudio.client.workspace;

import static com.mopicmp.npcstudio.client.workspace.Landing.BLOCK;
import static com.mopicmp.npcstudio.client.workspace.Landing.SCENE;
import static com.mopicmp.npcstudio.client.workspace.Landing.WORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where an edit lands, as arithmetic.
 *
 * <h2>Why this is worth a test rather than a reading</h2>
 *
 * Because getting it wrong is silent. A slider that writes into the wrong place
 * does not throw and does not draw anything red; it looks exactly like a slider
 * that worked, and the loss shows up later as a scene that has a key nobody put
 * there or a sunset that was never saved. Everything about the receiver that can
 * be decided without a game is decided in {@code choose}, {@code of} and
 * {@code records}, and all three are pure functions of their arguments for
 * exactly this reason.
 */
class LandingTest {

	@Test
	@DisplayName("with nothing pinned it follows the work")
	void itFollowsTheWork() {
		assertEquals(WORLD, Landing.choose(null, false, false),
			"nothing open, so the world");
		assertEquals(SCENE, Landing.choose(null, true, false),
			"a scene open means somebody is building a scene");
		assertEquals(BLOCK, Landing.choose(null, false, true),
			"a node selected and no scene means somebody is building a dialogue");
	}

	@Test
	@DisplayName("the scene wins over the node when both are up")
	void theSceneWins() {
		// The graph sits above the timeline on purpose, so both are usually on
		// screen and a node is usually selected merely because somebody looked at
		// it. Guessing the other way would quietly put keys into dialogue nodes.
		assertEquals(SCENE, Landing.choose(null, true, true));
	}

	@Test
	@DisplayName("a pin overrides the guess")
	void aPinOverrides() {
		// Turning the sky orange for the world while a scene happens to be open is
		// an ordinary thing to want, and without the pin it is impossible.
		assertEquals(WORLD, Landing.choose(WORLD, true, true));
		assertEquals(BLOCK, Landing.choose(BLOCK, true, true));
	}

	@Test
	@DisplayName("a pin at something that is not there is not obeyed")
	void anImpossiblePinFallsBack() {
		// Pin the scene, then close it. There is nothing left to write into, and the
		// dangerous answer is to go on saying SCENE — every edit after that would be
		// accepted by the interface and dropped on the floor.
		assertEquals(WORLD, Landing.choose(SCENE, false, false));
		assertEquals(WORLD, Landing.choose(BLOCK, false, false));
	}

	@Test
	@DisplayName("a costume is never a keyframe, whatever is open")
	void charactersAlwaysLandInTheWorld() {
		// A character does not fade from one coat into another. There is no halfway
		// to interpolate towards, so this is not a limitation to be lifted later.
		for (Landing chosen : Landing.values()) {
			assertEquals(WORLD, Landing.of(Property.CHARACTER, chosen),
				"with the receiver at " + chosen);
		}
	}

	@Test
	@DisplayName("a curve only records where there is a clock")
	void curvesNeedAScene() {
		assertEquals(SCENE, Landing.of(Property.CURVE, SCENE));
		assertEquals(WORLD, Landing.of(Property.CURVE, WORLD),
			"applied and looked at, which is how a pose is set up before recording");
		assertEquals(WORLD, Landing.of(Property.CURVE, BLOCK),
			"a bone angle is not an action a dialogue node can carry");

		assertTrue(Landing.records(Property.CURVE, SCENE));
		assertFalse(Landing.records(Property.CURVE, WORLD),
			"and the panel has to say so, or an afternoon goes missing");
		assertFalse(Landing.records(Property.CURVE, BLOCK));
	}

	@Test
	@DisplayName("the sky and a moment go wherever the receiver is")
	void worldStateAndMomentsFollowTheReceiver() {
		// These two are the ones the whole mechanism is for: one panel, three
		// meanings, no second copy of the sliders anywhere.
		for (Landing chosen : Landing.values()) {
			assertEquals(chosen, Landing.of(Property.WORLD, chosen));
			assertEquals(chosen, Landing.of(Property.MOMENT, chosen));
			assertTrue(Landing.records(Property.WORLD, chosen));
			assertTrue(Landing.records(Property.MOMENT, chosen));
		}
	}

	@Test
	@DisplayName("every receiver a panel can be shown is one an edit can reach")
	void whatIsShownIsWhereItGoes() {
		// The promise the indicator makes: what a panel displays is where the next
		// movement of a slider will actually be written. A receiver that were
		// displayed and then overruled would break it in the confusing direction.
		for (Landing chosen : Landing.values()) {
			for (Property property : Property.values()) {
				Landing landed = Landing.of(property, chosen);
				assertEquals(landed, Landing.of(property, landed),
					property + " at " + chosen + " must settle in one step");
			}
		}
	}
}
