package com.mopicmp.npcstudio.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a pupil answers to the light, and never closes to nothing.
 *
 * The one failure worth guarding here is not subtle and would be shipped without
 * anybody noticing in a screenshot: a pupil that shrinks all the way leaves a
 * blank white eye, which does not read as bright light — it reads as a character
 * whose eyes have been taken out.
 */
class PupilsTest {

	private static final float FAR = 100f;

	/** Dark widens, light narrows, and that is the whole point of the thing. */
	@Test
	@DisplayName("a pupil is wider in the dark than in the light")
	void darkWidensThePupil() {
		float dark = Pupils.wide(1, 0f, 0, FAR);
		float dim = Pupils.wide(1, 0f, 7, FAR);
		float bright = Pupils.wide(1, 0f, 15, FAR);

		assertTrue(dark > dim, "pitch dark should be wider than half lit: " + dark + " vs " + dim);
		assertTrue(dim > bright, "half lit should be wider than full sun: " + dim + " vs " + bright);
		assertTrue(dark > 0.5f, "in the dark the pupil should be plainly wide, not " + dark);
		assertTrue(bright < 0f, "in full sun it should be narrower than drawn, not " + bright);
	}

	/** It changes gradually with the light rather than in steps. */
	@Test
	@DisplayName("the light moves the pupil gradually")
	void theLightIsARamp() {
		float last = Pupils.wide(1, 0f, 0, FAR);
		for (int light = 1; light <= 15; light++) {
			float now = Pupils.wide(1, 0f, light, FAR);
			assertTrue(now < last, "the pupil should keep narrowing as it gets lighter");
			assertTrue(last - now < 0.2f, "the pupil stepped at light level " + light);
			last = now;
		}
	}

	/**
	 * A pupil never shuts entirely.
	 *
	 * The floor exists so that the brightest possible day still leaves an eye with
	 * something in it. Checked across everything that can push it down at once.
	 */
	@Test
	@DisplayName("a pupil never closes to nothing, however bright it is")
	void thePupilNeverVanishes() {
		for (int id = 1; id <= 30; id++) {
			for (int tick = 0; tick < 400; tick++) {
				float wide = Pupils.wide(id, tick, 15, FAR);
				assertTrue(wide > -0.55f, "the pupil closed to " + wide + ", which is a blank eye");
				assertTrue(wide <= 1f, "and it must never be wider than the eye: " + wide);
			}
		}
	}

	/** Somebody coming close widens the eyes, and it is a ramp, not a switch. */
	@Test
	@DisplayName("eyes widen at somebody who has come close")
	void attentionWidensThem() {
		assertTrue(Pupils.attention(1f) > 0.2f, "at arm's length the eyes should widen");
		assertEquals(0f, Pupils.attention(20f), 1e-5, "and not at somebody across a clearing");

		float last = Pupils.attention(0f);
		for (int step = 0; step <= 100; step++) {
			float now = Pupils.attention(step / 5f);
			assertTrue(now <= last + 1e-5, "attention should only fall as somebody walks away");
			assertTrue(last - now < 0.05f, "attention stepped at " + (step / 5f) + " blocks");
			last = now;
		}
	}

	/** The flutter is small, and it is a flutter rather than a pulse. */
	@Test
	@DisplayName("the unsteadiness is too small to watch")
	void theFlutterIsSlight() {
		float most = 0;
		for (int id = 1; id <= 10; id++) {
			for (int tick = 0; tick < 2000; tick++) {
				most = Math.max(most, Math.abs(Pupils.flutter(id, tick)));
			}
		}
		assertTrue(most > 0.01f, "a flutter of nothing is not a flutter");
		assertTrue(most < 0.06f, "a pupil that visibly pulses looks like a heartbeat: " + most);
	}

	/** And it never jumps: the pupil is a size, not a state. */
	@Test
	@DisplayName("the pupil never jumps between one frame and the next")
	void theSizeIsSmooth() {
		float frame = 1f / 3f;
		for (int id = 1; id <= 20; id++) {
			float last = Pupils.wide(id, 0f, 4, 5f);
			for (int step = 1; step < 3600; step++) {
				float now = Pupils.wide(id, step * frame, 4, 5f);
				assertTrue(Math.abs(now - last) < 0.02f,
					"the pupil jumped " + Math.abs(now - last) + " in one frame");
				last = now;
			}
		}
	}

	/** Two characters side by side do not breathe together. */
	@Test
	@DisplayName("neighbours do not pulse in step")
	void neighboursDoNotPulseTogether() {
		float apart = 0;
		for (int tick = 0; tick < 600; tick++) {
			apart = Math.max(apart, Math.abs(Pupils.flutter(4, tick) - Pupils.flutter(5, tick)));
		}
		assertTrue(apart > 0.01f, "neighbouring characters fluttered identically");
	}
}
