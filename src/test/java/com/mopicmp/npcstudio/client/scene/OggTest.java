package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How long a piece of music is, measured without listening to it.
 *
 * <h2>Why this is measured at all</h2>
 *
 * Because a piece of music in a scene was a name and a mark one pixel wide.
 * Nothing said how long it ran, so nothing could say where it ended against the
 * animation — and "there is nothing to set" was a fair description of that.
 *
 * <h2>Why it is not decoded</h2>
 *
 * A four minute piece takes four minutes to measure that way, on the thread that
 * asked, for every file in the folder, every time a list is opened. An Ogg file
 * already carries the answer: each page records how many samples have been
 * decoded by the end of it, so the last page of the stream holds the total.
 *
 * That is the part worth pinning. Two little-endian reads at offsets counted out
 * of a specification is exactly the sort of arithmetic that is off by one field
 * and still produces a plausible-looking number — which would show up as a bar of
 * the wrong length on the ruler and nowhere else.
 */
class OggTest {

	/** The track kept for this, and the folder a running game reads from. */
	private static Optional<Path> anOgg() {
		for (Path where : new Path[] {
				Path.of("run", "npcstudio", "music"),
				Path.of("..", "test", "music") }) {
			if (!Files.isDirectory(where)) continue;
			try (var listing = Files.list(where)) {
				var found = listing.filter(p -> p.getFileName().toString().endsWith(".ogg"))
					.findFirst();
				if (found.isPresent()) return found;
			} catch (IOException unreadable) {
				// Try the next place.
			}
		}
		return Optional.empty();
	}

	@Test
	@DisplayName("a real file is measured, and the answer agrees with its own size")
	void aRealFile() {
		Optional<Path> file = anOgg();
		Assumptions.assumeTrue(file.isPresent(),
			"no ogg to measure — drop one in run/npcstudio/music and this test means something");

		double seconds = Ogg.seconds(file.get());
		assertTrue(seconds > 0, "the length could not be read at all");

		// Cross-checked against the one thing known independently: how many bytes
		// there are. A wrong field would give a length off by orders of magnitude —
		// reading the sample rate as the channel count, say — and the implied bitrate
		// is what catches that. Vorbis in the wild sits between about forty-five and
		// five hundred kilobits.
		try {
			double kbps = Files.size(file.get()) * 8.0 / seconds / 1000;
			assertTrue(kbps > 32 && kbps < 700,
				"the length implies " + Math.round(kbps) + " kbps, which is not a real file");
		} catch (IOException unreadable) {
			// The size is a cross-check rather than the test; without it the assertion
			// above still says the reading worked.
		}
	}

	@Test
	@DisplayName("anything that is not an ogg is measured as nothing, not as a guess")
	void rubbishIsNotGuessedAt(@TempDir Path folder) throws IOException {
		// A length is a nicety: it sits beside a name and draws a bar. A file that
		// will not parse is still a file the decoder may play perfectly well, so the
		// honest answer is "unknown" and never an invented number.
		Path empty = Files.createFile(folder.resolve("empty.ogg"));
		assertEquals(0, Ogg.seconds(empty), 1e-9);

		Path rubbish = Files.write(folder.resolve("rubbish.ogg"),
			"this is not an ogg file, it is a sentence about one".getBytes());
		assertEquals(0, Ogg.seconds(rubbish), 1e-9);

		assertEquals(0, Ogg.seconds(folder.resolve("nothing-here.ogg")), 1e-9);
	}

	@Test
	@DisplayName("a page carrying no finished packet is not read as an enormous length")
	void theEmptyPageMarkerIsNotALength() {
		// A page with nothing completed on it reports minus one, which as an unsigned
		// reading is eighteen quintillion samples — four hundred thousand years of
		// music, and a bar drawn to the far side of the sun.
		var page = java.nio.ByteBuffer.allocate(27).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		page.put(new byte[] { 'O', 'g', 'g', 'S' });
		page.put((byte) 0).put((byte) 0);
		page.putLong(-1L);
		page.position(27).flip();
		assertEquals(0, Ogg.lastGranule(page));
	}

	@Test
	@DisplayName("a length is said the way somebody reads one")
	void minutesAndSeconds() {
		assertEquals("0:07", Ogg.said(7));
		assertEquals("0:56", Ogg.said(55.95));
		assertEquals("1:00", Ogg.said(60));
		assertEquals("3:04", Ogg.said(184.2));
		// Unknown is a question mark rather than nought, because "0:00" is a claim
		// about the file and this is an admission about us.
		assertEquals("?", Ogg.said(0));
		assertEquals("?", Ogg.said(-1));
	}
}
