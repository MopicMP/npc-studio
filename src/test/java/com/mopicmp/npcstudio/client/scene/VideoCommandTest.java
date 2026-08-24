package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line the encoder is actually started with.
 *
 * <h2>Why a test for a string</h2>
 *
 * Because it is not a string, it is a contract with another program, and the
 * other program is not here to complain. Get the pixel format wrong and the film
 * comes out with the colours swapped; get the size wrong and it comes out sheared
 * — and both of those look like a bug in the drawing rather than in an argument
 * list. The failure lands a long way from the cause.
 *
 * The shape is ReplayMod's, read out of their jar rather than remembered:
 * {@code -y -f rawvideo -pix_fmt bgra -s WxH -r FPS -i -} on the way in, the
 * codec on the way out. What this pins is that the input half keeps saying
 * exactly what {@link Video#write} actually sends.
 */
class VideoCommandTest {

	private static List<String> command(Video.Preset preset) {
		return Video.commandFor("ffmpeg", preset, Path.of("out", "scene.mp4"), 1920, 1080, 30);
	}

	@Test
	@DisplayName("the input half describes the bytes the writer really sends")
	void theInputMatchesTheWriter() {
		List<String> command = command(Video.Preset.MP4);
		String said = String.join(" ", command);

		// Four bytes a pixel, blue first — which is what the writer builds by hand.
		// If one of the two is ever changed the other has to change with it, and this
		// is the line that says so out loud.
		assertTrue(said.contains("-f rawvideo"), said);
		assertTrue(said.contains("-pix_fmt bgra"), said);
		assertTrue(said.contains("-s 1920x1080"), said);
		assertTrue(said.contains("-r 30"), said);
		assertTrue(said.contains("-i -"), "the frames come in on standard input: " + said);
	}

	@Test
	@DisplayName("the file is one argument, however many spaces are in its path")
	void theNameIsNotSplit() {
		List<String> command = Video.commandFor("ffmpeg", Video.Preset.MP4,
			Path.of("C:", "Мои сцены", "на корабле.mp4"), 640, 480, 30);

		assertEquals(command.get(command.size() - 1),
			Path.of("C:", "Мои сцены", "на корабле.mp4").toString(),
			"a path with spaces was taken apart");
		for (String argument : command) {
			assertFalse(argument.isEmpty(), "an empty argument would shift everything after it");
		}
	}

	@Test
	@DisplayName("every preset that needs an encoder names one, and the one that does not says so")
	void everyPresetIsCoherent() {
		for (Video.Preset preset : Video.Preset.values()) {
			if (preset.needsFfmpeg()) {
				assertTrue(preset.arguments.contains("-c:v"),
					preset + " goes to ffmpeg without naming a codec");
				assertTrue(command(preset).size() > 10, preset + " came out too short");
			} else {
				assertTrue(preset.arguments.isEmpty(), preset + " has arguments and no encoder");
			}
			assertFalse(preset.suffix.isEmpty(), preset + " has no file suffix");
		}
	}

	@Test
	@DisplayName("the frames preset still names a suffix, because it names a folder")
	void framesAreStillAnAnswer() {
		// It is the only one that works with nothing installed, so it has to stay
		// coherent even though it goes nowhere near this command.
		assertFalse(Video.Preset.FRAMES.needsFfmpeg());
		assertEquals("png", Video.Preset.FRAMES.suffix);
	}
}
