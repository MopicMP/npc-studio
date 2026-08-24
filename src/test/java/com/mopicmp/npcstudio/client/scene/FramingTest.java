package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rectangle the film occupies, and the two ways of measuring it.
 *
 * <h2>Why this exists at all</h2>
 *
 * The world is drawn across the whole window and the panels are laid over its
 * edges, so the viewport is the middle of the picture rather than the whole of
 * it. Everything that has to agree about "the frame" — where a title sits, how
 * far the fade reaches, which pixels are written — used to ask the window, and
 * therefore agreed about the wrong rectangle. A caption centred in the film was
 * off-centre in the hole being watched through.
 *
 * <h2>The part that is worth a test</h2>
 *
 * The conversion. The interface is measured in scaled units and the picture in
 * pixels, and the two differ by the interface scale — two or three, usually.
 * Crop with the wrong one and you take a quarter of the frame, which looks like
 * the camera moved rather than like a unit mistake.
 *
 * And the evenness: encoders refuse an odd width rather than rounding it, and a
 * viewport is an odd number of units across about half the time.
 */
class FramingTest {

	/** The conversion, written out here so the sum is visible rather than inferred. */
	private static int[] inPixels(Framing frame, int windowW, int windowH,
			int scaledW, int scaledH) {
		double scaleX = windowW / (double) scaledW;
		double scaleY = windowH / (double) scaledH;
		int x = (int) Math.round(frame.left() * scaleX);
		int y = (int) Math.round(frame.top() * scaleY);
		int w = (int) Math.round(frame.wide() * scaleX);
		int h = (int) Math.round(frame.tall() * scaleY);
		w = Math.min(w, windowW - x) & ~1;
		h = Math.min(h, windowH - y) & ~1;
		return new int[] { x, y, Math.max(2, w), Math.max(2, h) };
	}

	@Test
	@DisplayName("the interface scale is applied, so the crop is the pixels it looks like")
	void scaleIsApplied() {
		// A viewport at 180,30 measuring 440 by 250 units, on a window twice that
		// scale. Taking the units for pixels would crop a quarter of the frame from
		// the wrong corner, which reads as the camera having moved.
		Framing frame = new Framing(180, 30, 440, 250);
		int[] pixels = inPixels(frame, 1760, 1000, 880, 500);
		assertEquals(360, pixels[0]);
		assertEquals(60, pixels[1]);
		assertEquals(880, pixels[2]);
		assertEquals(500, pixels[3]);
	}

	@Test
	@DisplayName("an odd size is rounded down, because encoders refuse it outright")
	void sizesAreEven() {
		// ffmpeg does not round an odd width, it declines the whole job — and a
		// viewport is an odd number of units across about half the time.
		int[] pixels = inPixels(new Framing(0, 0, 441, 251), 441, 251, 441, 251);
		assertEquals(0, pixels[2] % 2, "width " + pixels[2]);
		assertEquals(0, pixels[3] % 2, "height " + pixels[3]);
		assertEquals(440, pixels[2]);
		assertEquals(250, pixels[3]);
	}

	@Test
	@DisplayName("the crop never reaches past the picture it is cut from")
	void itStaysInsideTheWindow() {
		// A panel dragged half off the edge is an ordinary state; a crop reaching
		// outside the framebuffer is a read past the end of the image.
		int[] pixels = inPixels(new Framing(700, 400, 400, 300), 960, 540, 960, 540);
		assertTrue(pixels[0] + pixels[2] <= 960, "right edge at " + (pixels[0] + pixels[2]));
		assertTrue(pixels[1] + pixels[3] <= 540, "bottom edge at " + (pixels[1] + pixels[3]));
	}

	@Test
	@DisplayName("a caption placed by fractions lands in the frame, not in the window")
	void captionsFollowTheFrame() {
		// The complaint, as arithmetic. Centred means centred on the film — which is
		// the viewport while one is showing — and the two differ by the width of
		// whatever panels are open.
		Framing viewport = new Framing(180, 30, 440, 250);
		float middleOfFrame = viewport.left() + viewport.wide() * 0.5f;
		assertEquals(400, middleOfFrame, 1e-4);

		Framing whole = new Framing(0, 0, 880, 500);
		assertEquals(440, whole.left() + whole.wide() * 0.5f, 1e-4,
			"and with the panels away it is the middle of the window");
		assertTrue(Math.abs(middleOfFrame - 440) > 20,
			"the two must differ, or there was nothing to fix");
	}
}
