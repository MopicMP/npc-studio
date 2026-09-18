package com.mopicmp.npcstudio.client.server;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Turning whatever a catalogue serves into something the game can draw.
 *
 * The game's own decoder is stb_image and reads PNG, JPEG and a few others.
 * A plugin catalogue serves whatever its authors uploaded, and measuring it
 * rather than assuming gave: mostly WebP, some PNG, a fair number of animated
 * GIFs, and the occasional SVG. So anything the game refuses is offered to
 * ImageIO — which knows GIF and BMP by itself and WebP through the decoder
 * shipped beside it — and comes back as a PNG, which every part of the game
 * already understands.
 *
 * SVG is not drawings, it is instructions for drawing, and nothing here will
 * ever read it. Those get a letter, and that is the end of it.
 *
 * <b>The WebP reader is registered by hand rather than found.</b> ImageIO
 * discovers plugins with a service loader over the application class path, and
 * inside a mod loader there is no such thing — the jar is nested inside ours.
 * Two lines of explicit registration work everywhere; hoping the scan finds it
 * works on a developer's machine and not in the wild.
 */
public final class Pictures {

	private Pictures() {
	}

	private static boolean ready;
	private static boolean broken;

	private static synchronized void prepare() {
		if (ready || broken) return;
		try {
			// Nothing here opens a window, but AWT decides whether it is allowed a
			// display the first time it is touched, and on some systems that is a
			// slow and pointless question.
			System.setProperty("java.awt.headless", "true");
			IIORegistry.getDefaultInstance().registerServiceProvider(
				new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
			ready = true;
		} catch (Throwable missing) {
			// A build without the decoder still runs; the browser simply draws
			// letters where those pictures would be.
			broken = true;
			NpcStudio.LOGGER.warn("No WebP decoder available: {}", missing.toString());
		}
	}

	/**
	 * The same picture as a PNG, or null if nothing here can read it.
	 *
	 * An animated GIF comes back as its first frame, which is what a
	 * twenty-four pixel tile in a list wants anyway.
	 */
	public static byte[] toPng(byte[] bytes) {
		prepare();
		if (bytes == null || bytes.length == 0) return null;
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null) return null;
			ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
			// Written as ARGB whatever came in, so a GIF's transparency and a
			// WebP's alpha both survive into the texture.
			BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
			flat.getGraphics().drawImage(image, 0, 0, null);
			ImageIO.write(flat, "png", out);
			return out.toByteArray();
		} catch (Exception unreadable) {
			NpcStudio.LOGGER.debug("Could not decode a picture: {}", unreadable.toString());
			return null;
		}
	}
}
