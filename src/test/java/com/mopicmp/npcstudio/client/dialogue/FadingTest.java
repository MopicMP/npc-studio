package com.mopicmp.npcstudio.client.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.net.ShowPortraitPayload.Layer;

/**
 * A figure appearing and leaving a piece at a time.
 *
 * <h2>Why this is worth a test when it is only a fade</h2>
 *
 * Because every way of getting it wrong looks like a flicker, and a flicker in a scene
 * somebody is playing is the hardest kind of fault to describe, reproduce, or find by
 * looking. Asked as questions, each of them is one line.
 */
class FadingTest {

	private static final Layer BODY = new Layer("body", 0, 0);
	private static final Layer CALM = new Layer("calm", 140, 96);
	private static final Layer SAD = new Layer("sad", 140, 96);

	private static List<Fading.Held> held(long since, Layer... layers) {
		return java.util.Arrays.stream(layers)
			.map(layer -> new Fading.Held(layer, since)).toList();
	}

	@Test
	@DisplayName("a picture that was already there is not told it has just arrived")
	void stayingIsNotArriving() {
		// The fault this exists for. Reset on every change, the body would fade in from
		// nothing each time the face changed — a character blinking out and back once per
		// line, with nothing on screen to say why.
		var was = held(1000, BODY, CALM);
		var now = Fading.arriving(was, List.of(BODY, SAD), 5000);

		assertEquals(1000, now.get(0).since(), "the body never went anywhere");
		assertEquals(5000, now.get(1).since(), "and the new face has only just come");
		assertEquals(1f, Fading.through(now.get(0).since(), 5000), 0.001f);
		assertEquals(0f, Fading.through(now.get(1).since(), 5000), 0.001f);
	}

	@Test
	@DisplayName("a picture that has moved is a new picture, not the old one jumping")
	void movingCountsAsArriving() {
		// Sameness is the whole layer, corner included. Treated as the same thing, a
		// re-placed slot would snap across the figure at full strength; treated as new,
		// it fades, which is the smaller of the two artefacts by a long way.
		var was = held(1000, CALM);
		var now = Fading.arriving(was, List.of(new Layer("calm", 150, 96)), 5000);
		assertEquals(5000, now.get(0).since());
	}

	@Test
	@DisplayName("only what has gone starts leaving, and it starts leaving now")
	void departingIsWhatIsMissing() {
		var was = held(1000, BODY, CALM);
		var leaving = Fading.departing(was, List.of(BODY, SAD), 380, 637, 1, true, 5000);

		assertEquals(1, leaving.size(), "the body stayed, so only the face is going");
		assertEquals("calm", leaving.get(0).layer().picture());
		// How long it stood there has nothing to do with how long it takes to go.
		assertEquals(5000, leaving.get(0).since());
		// And it leaves in the shape it was being drawn in. A departing layer measured
		// against the arriving figure's canvas would slide across the screen as it faded.
		assertEquals(380, leaving.get(0).wide());
		assertEquals(1, leaving.get(0).side());
		assertTrue(leaving.get(0).mirrored());
	}

	@Test
	@DisplayName("something taken off and put straight back on stops leaving")
	void comingBackCancelsTheExit() {
		// Two copies of one picture at partial strength are darker than one at full, so
		// without this the cloak would visibly dip and recover.
		var leaving = List.of(new Fading.Going(CALM, 380, 637, 0, false, 5000));
		assertTrue(Fading.stillGoing(leaving, List.of(CALM), 5050).isEmpty());
		assertEquals(1, Fading.stillGoing(leaving, List.of(SAD), 5050).size());
	}

	@Test
	@DisplayName("a fade that is over is dropped rather than drawn at nothing for ever")
	void finishedFadesGoAway() {
		var leaving = List.of(new Fading.Going(CALM, 380, 637, 0, false, 5000));
		assertTrue(Fading.stillGoing(leaving, List.of(), 5000 + Fading.OVER).isEmpty());
		assertFalse(Fading.stillGoing(leaving, List.of(), 5001).isEmpty());
	}

	@Test
	@DisplayName("a clock that jumps cannot make a picture darker than nothing or brighter than itself")
	void theClockIsNotTrusted() {
		// It does jump: a world loads, a game is paused. Unclamped, this would ask the
		// screen to draw at a negative strength or at six times full.
		assertEquals(0f, Fading.through(9000, 1000), 0.001f);
		assertEquals(1f, Fading.through(1000, 9_000_000), 0.001f);

		assertEquals(0, Fading.tint(0f), "nothing to draw is said as nothing, not as a draw");
		assertEquals(0xFFFFFFFF, Fading.tint(1f), "and full strength tints nothing at all");
		assertEquals(0xFFFFFFFF, Fading.tint(4f));
	}
}
