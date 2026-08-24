package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leaning a torso back without leaving the legs behind.
 *
 * <h2>What was reported</h2>
 *
 * "I only wanted to lean the torso back a little, not to have it fly out from
 * under the legs." Which is what the player model does on its own: the torso is
 * a sibling of the head, the arms and the legs, and its pivot is at the neck. So
 * turning it swings the waist out from over the hips and leaves everything else
 * hanging in the air where the torso used to be.
 *
 * <h2>What is pinned here</h2>
 *
 * The two things that can be silently wrong. The hip line has to stay exactly
 * still — that is the whole of "connected to the legs" — and the composition of
 * two rotations has to survive being taken back out as the three numbers a model
 * part holds. That second one is the real trap: the call that builds a rotation
 * takes {@code (z, y, x)} and the call that reads it back hands over
 * {@code (x, y, z)}, and having those the wrong way round produces a character
 * who leans by turning inside out.
 */
class LeaningTest {

	/** How far the hips are below the torso's own pivot, in model pixels. */
	private static final float DROP = 12;

	/** A lean of fifteen degrees backwards. */
	private static Quaternionf back() {
		return Leaning.turned((float) Math.toRadians(0), 0, (float) Math.toRadians(-15), 0, 0, 0);
	}

	@Test
	@DisplayName("the hip line does not move, however far the torso leans")
	void theHipsStayPut() {
		// The torso's own origin is at the neck and the hips are DROP below it. After
		// the correction the geometry has to turn about the hips — so the point at
		// the hips is the one point that comes out where it went in. If it does not,
		// the body has left the legs, which is the report.
		for (int degrees = -60; degrees <= 60; degrees += 5) {
			Quaternionf lean = Leaning.turned(0, 0, (float) Math.toRadians(degrees), 0, 0, 0);
			float[] offset = Leaning.offset(lean, DROP);

			// Where the hip point of the torso ends up: the part is moved by the
			// offset and then everything of it turns about its own origin.
			Vector3f hip = lean.transform(new Vector3f(0, DROP, 0))
				.add(offset[0], offset[1], offset[2]);

			assertEquals(0, hip.x, 1e-4, "the hips slid sideways at " + degrees);
			assertEquals(DROP, hip.y, 1e-4, "the hips slid up or down at " + degrees);
			assertEquals(0, hip.z, 1e-4, "the hips slid forwards at " + degrees);
		}
	}

	@Test
	@DisplayName("leaning back moves the shoulders back, not the waist forward")
	void theLeanGoesTheRightWay() {
		// A negative turn about X is a backwards lean, the same sign every part of
		// this mod uses for it. The neck is DROP above the hips, so leaning back
		// carries it backwards — which on this model's axes is +z.
		float[] neck = Leaning.carried(back(), new float[] { 0, DROP, 0 }, new float[] { 0, 0, 0 });

		assertTrue(neck[2] > 0.1f, "the neck went to z " + neck[2] + " rather than backwards");
		assertTrue(neck[1] > 0.1f, "and it should also drop a little, not rise");
		assertEquals(0, neck[0], 1e-4, "a lean back is not a lean sideways");
	}

	@Test
	@DisplayName("what is carried stays exactly as far from the hips as it was")
	void nothingIsStretched() {
		// Rigid, which is the whole of what "stays connected" means: a lean cannot
		// pull a neck apart however far it goes, and a shoulder cannot drift out of
		// its socket.
		float[] hip = { 0, DROP, 0 };
		float[][] parts = { { 0, 0, 0 }, { -5, 2, 0 }, { 5, 2, 0 } };

		for (int degrees = -90; degrees <= 90; degrees += 7) {
			Quaternionf lean = Leaning.turned(
				(float) Math.toRadians(degrees / 3.0), (float) Math.toRadians(degrees / 2.0),
				(float) Math.toRadians(degrees), 0, 0, 0);
			for (float[] part : parts) {
				float was = away(hip, part);
				float now = away(hip, Leaning.carried(lean, hip, part));
				assertEquals(was, now, 1e-3, "a part stretched at " + degrees);
			}
		}
	}

	@Test
	@DisplayName("two turns come back out as three numbers a model part can hold")
	void theCompositionSurvivesBeingTakenApart() {
		// The trap: rotationZYX takes (z, y, x) and getEulerAnglesZYX hands back
		// (x, y, z). Written the wrong way round this still compiles, still produces
		// three plausible numbers, and turns the character inside out.
		float[][] leans = { { 0, 0, -0.3f }, { 0.2f, -0.4f, 0.1f }, { -0.7f, 0.5f, 0.9f } };
		float[][] owns = { { 0, 0, 0 }, { 0.1f, 0.2f, -0.3f }, { -0.5f, 0.6f, 0.2f } };

		for (float[] lean : leans) {
			Quaternionf turned = Leaning.turned(lean[0], lean[1], lean[2], 0, 0, 0);
			for (float[] own : owns) {
				float[] both = Leaning.then(turned, own[0], own[1], own[2]);

				// Rebuilt the way a model part will rebuild it, and compared against
				// what the two rotations actually come to.
				Quaternionf rebuilt = new Quaternionf().rotationZYX(both[2], both[1], both[0]);
				Quaternionf wanted = new Quaternionf(turned)
					.mul(new Quaternionf().rotationZYX(own[0], own[1], own[2]));

				// A quaternion and its negative are the same rotation, so the two are
				// compared by what they do rather than by their four numbers.
				for (Vector3f point : new Vector3f[] {
						new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1) }) {
					Vector3f a = rebuilt.transform(new Vector3f(point));
					Vector3f b = wanted.transform(new Vector3f(point));
					assertEquals(b.x, a.x, 1e-4);
					assertEquals(b.y, a.y, 1e-4);
					assertEquals(b.z, a.z, 1e-4);
				}
			}
		}
	}

	@Test
	@DisplayName("a torso nobody turned is a torso nobody moved")
	void nothingHappensForNothing() {
		// The correction runs whenever a scene keys the torso, including keying it to
		// where it already was. That has to come out as no change at all, or holding
		// a pose would drift a little every time it was written down.
		Quaternionf still = Leaning.turned(0.3f, -0.2f, 0.5f, 0.3f, -0.2f, 0.5f);
		float[] offset = Leaning.offset(still, DROP);

		assertEquals(0, offset[0], 1e-4);
		assertEquals(0, offset[1], 1e-4);
		assertEquals(0, offset[2], 1e-4);

		float[] head = Leaning.carried(still, new float[] { 0, DROP, 0 }, new float[] { 0, 0, 0 });
		assertEquals(0, head[0], 1e-4);
		assertEquals(0, head[1], 1e-4);
		assertEquals(0, head[2], 1e-4);
	}

	private static float away(float[] from, float[] to) {
		float dx = to[0] - from[0];
		float dy = to[1] - from[1];
		float dz = to[2] - from[2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
