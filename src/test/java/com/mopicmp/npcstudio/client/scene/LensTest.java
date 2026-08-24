package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning the wheel on the camera's lens.
 *
 * <h2>Why a ratio and not a number of degrees</h2>
 *
 * Because a lens is a ratio. Two degrees is the whole difference between a
 * telephoto and a longer one, and at a hundred and sixty it is nothing you can
 * see — so a fixed step gives a wheel that is unusably coarse at one end of the
 * range and does nothing at the other. The same proportion each notch feels the
 * same everywhere, which is how every camera and every viewport has ever done it.
 *
 * The clamp is the other half and it is the part that breaks quietly: without it
 * a few seconds of scrolling puts the angle at a hundredth of a degree or at
 * three hundred, and both of those are a frame nobody can get back to by hand.
 */
class LensTest {

	@Test
	@DisplayName("up narrows and down widens, as on every camera there has ever been")
	void upGoesCloser() {
		assertTrue(SceneCameras.zoomed(70, 1) < 70, "one notch up is a longer lens");
		assertTrue(SceneCameras.zoomed(70, -1) > 70, "and one notch down is a wider one");
	}

	@Test
	@DisplayName("a notch is the same proportion wherever it is turned")
	void everyNotchIsTheSameStep() {
		// The property a fixed number of degrees does not have: the step is worth the
		// same amount of visible change at both ends of the range.
		double near = SceneCameras.zoomed(20, 1) / 20.0;
		double far = SceneCameras.zoomed(120, 1) / 120.0;
		assertEquals(near, far, 1e-6, "the same ratio at twenty and at a hundred and twenty");
	}

	@Test
	@DisplayName("a notch back undoes a notch forward")
	void itComesBack() {
		// Somebody overshoots and scrolls back, and expects the number they had. Only
		// true away from the ends, which is exactly what the next test is about.
		assertEquals(70, SceneCameras.zoomed(SceneCameras.zoomed(70, 3), -3), 1e-3);
	}

	@Test
	@DisplayName("the wheel cannot be turned past a lens that still exists")
	void itIsClamped() {
		// Held rather than allowed to run away. A hundred notches in either direction
		// is a few seconds of scrolling, and an angle of a hundredth of a degree is a
		// frame nobody finds their way back from by hand.
		assertEquals(SceneCameras.NARROWEST, SceneCameras.zoomed(70, 100), 1e-6);
		assertEquals(SceneCameras.WIDEST, SceneCameras.zoomed(70, -100), 1e-6);
		assertEquals(SceneCameras.NARROWEST, SceneCameras.zoomed(SceneCameras.NARROWEST, 1), 1e-6);
		assertEquals(SceneCameras.WIDEST, SceneCameras.zoomed(SceneCameras.WIDEST, -1), 1e-6);
	}
}
