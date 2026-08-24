package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Using a colour as a filter rather than as paint.
 *
 * <h2>What went wrong, and why the arithmetic is the fix</h2>
 *
 * Setting the sky's colour changed the dome overhead and left the horizon in the
 * old colour, because the horizon is the fog and the fog is computed somewhere
 * else. Painting the chosen colour straight onto the fog would have fixed the
 * horizon and broken two other things at once: the fog is what darkens at night
 * and what greys out in a storm, so a painted fog is a horizon exactly as bright
 * at midnight as at noon.
 *
 * So the colour is normalised to its own brightest channel and multiplied in.
 * Three properties have to hold for that to be safe, and all three are the sort
 * that break silently:
 *
 * <ul>
 * <li>White does nothing at all — otherwise every scene that has never mentioned
 *     the sky looks different from before.</li>
 * <li>A hue keeps its brightness — otherwise a dark green sky drags the whole
 *     world towards black and it reads as a broken shader rather than as a
 *     colour choice.</li>
 * <li>Alpha is not a colour — the glow at the horizon carries its strength there,
 *     and multiplying it would make dawn fade in and out with the hue.</li>
 * </ul>
 */
class FilterTest {

	@Test
	@DisplayName("white is not a tint, and neither is any other grey")
	void whiteChangesNothing() {
		// The property everything else rests on: a scene that has said nothing about
		// the sky reads as white here, so this is the ordinary case and it must be
		// exactly the identity rather than nearly it.
		assertArray(new float[] { 1, 1, 1 }, Filter.of(0xFFFFFF));
		assertFalse(Filter.changes(Filter.of(0xFFFFFF)));
		assertEquals(0xFF3C6E21, Filter.over(0xFF3C6E21, Filter.of(0xFFFFFF)));

		// And a grey is a white as far as a filter is concerned: it has no shape, so
		// it has nothing to impose. Dimming is a job for a light, not for the air.
		assertArray(new float[] { 1, 1, 1 }, Filter.of(0x404040));
		assertFalse(Filter.changes(Filter.of(0x101010)));
	}

	@Test
	@DisplayName("a colour keeps its brightest channel and imposes only its shape")
	void aColourIsAShape() {
		// The reporter's own sky: 56, 144, 54. Green is the brightest and stays whole,
		// so anything seen through it keeps its green and loses most of its red and
		// blue — a green horizon rather than a dark one.
		float[] green = Filter.of(0x389036);
		assertEquals(1f, green[1], 1e-6, "the brightest channel is whole");
		assertEquals(56 / 144f, green[0], 1e-6);
		assertEquals(54 / 144f, green[2], 1e-6);
		assertTrue(Filter.changes(green));

		// Which means a pale fog stays pale. That is the whole point: the fog is what
		// carries distance and time of day, so it must come out of this as bright as
		// it went in.
		int fog = Filter.over(0xFFC0D8FF, green);
		assertEquals(0xD8, (fog >> 8) & 0xFF, "the green channel is untouched");
		assertTrue(((fog >> 16) & 0xFF) < 0xC0, "red is pulled down");
		assertTrue((fog & 0xFF) < 0xFF, "and so is blue");
	}

	@Test
	@DisplayName("the alpha is not a colour and never moves")
	void alphaSurvives() {
		// The glow at the horizon carries its strength in the alpha, and the sun's
		// disc carries how far through the day it is in the same place. Touching it
		// would make dawn fade in and out with the choice of a hue.
		float[] red = Filter.of(0xFF0000);
		assertEquals(0x80, (Filter.over(0x80FFFFFF, red) >>> 24) & 0xFF);
		assertEquals(0x00, (Filter.over(0x00FFFFFF, red) >>> 24) & 0xFF);
		assertEquals(0xFF, (Filter.over(0xFFFFFFFF, red) >>> 24) & 0xFF);
	}

	@Test
	@DisplayName("a pure hue puts out the other two entirely")
	void aPureHue() {
		assertEquals(0xFF9A0000, Filter.over(0xFF9A5533, Filter.of(0xFF0000)));
		assertEquals(0xFF005500, Filter.over(0xFF9A5533, Filter.of(0x00FF00)));
		assertEquals(0xFF000033, Filter.over(0xFF9A5533, Filter.of(0x0000FF)));
	}

	@Test
	@DisplayName("black is a sky with no light in it, and says so")
	void blackIsBlack() {
		// The one colour with no shape to take. Multiplying everything to nothing is
		// the honest reading of "the sky is black" and is what somebody who set it
		// wants — the alternative would be dividing by nought.
		float[] none = Filter.of(0x000000);
		assertArray(new float[] { 0, 0, 0 }, none);
		assertTrue(Filter.changes(none));
		assertEquals(0xFF000000, Filter.over(0xFFFFFFFF, none));
	}

	private static void assertArray(float[] wanted, float[] got) {
		assertEquals(wanted.length, got.length);
		for (int i = 0; i < wanted.length; i++) assertEquals(wanted[i], got[i], 1e-6, "at " + i);
	}
}
