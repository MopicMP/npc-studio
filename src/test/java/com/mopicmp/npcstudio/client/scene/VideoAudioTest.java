package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The music, on its way into the exported film.
 *
 * <h2>Why the film has sound at all</h2>
 *
 * Because it is a film. Every preset used to pass {@code -an} and the honest
 * answer to "how do I export this" was "you get a silent video and you put the
 * music back yourself" — which makes cueing a piece against the animation a job
 * done twice, once here and once in an editor, with the second one having to
 * guess at the first.
 *
 * <h2>Why a filter and not simply a second input</h2>
 *
 * A piece does not begin at the start of the film and does not run to its end. It
 * begins at its cue and stops when the next one begins; that is what a music cue
 * means in a scene, and the export has to mean the same thing or what is heard in
 * the file is not what was heard while it was made.
 *
 * The order inside the filter is the part worth pinning, because it is invisible
 * and wrong in a plausible way: {@code atrim} keeps the original timestamps on
 * what it cuts, so a piece trimmed and then delayed without resetting them in
 * between arrives at its own start time <em>plus</em> the delay.
 */
class VideoAudioTest {

	private static List<String> command(List<Video.Track> audio) {
		return Video.commandFor("ffmpeg", Video.Preset.MP4,
			Path.of("out", "scene.mp4"), 1920, 1080, 30, audio);
	}

	private static String after(List<String> command, String flag) {
		int at = command.indexOf(flag);
		return at < 0 || at + 1 >= command.size() ? null : command.get(at + 1);
	}

	@Test
	@DisplayName("a scene with no music is still written without an audio track")
	void silenceIsStillSilence() {
		List<String> plain = command(List.of());
		assertTrue(plain.contains("-an"), "a silent film must say so, or ffmpeg looks for a stream");
		assertTrue(!plain.contains("-filter_complex"));
		assertTrue(!plain.contains("-shortest"));
	}

	@Test
	@DisplayName("one piece is trimmed, reset and delayed, in that order")
	void onePiece() {
		// Cued at two seconds and heard for fifty. The order of the three steps is
		// the whole of it: trim, then reset the timestamps, then delay.
		String filter = Video.mix(List.of(new Video.Track(Path.of("море.ogg"), 2.0, 50.0)));
		assertEquals("[1:a]atrim=0:50.000,asetpts=PTS-STARTPTS,adelay=2000:all=1[a]", filter);

		List<String> command = command(List.of(new Video.Track(Path.of("море.ogg"), 2.0, 50.0)));
		// The frames are the first input and arrive on standard input, so the piece is
		// the second: the last "-i" in the command, which is what the filter calls 1.
		assertEquals("море.ogg", command.get(command.lastIndexOf("-i") + 1));
		assertEquals("0:v", after(command, "-map"));
		assertTrue(command.contains("[a]"), "and the mixed audio is mapped out");
		assertTrue(command.contains("-shortest"),
			"a piece longer than the scene must not extend the film");
		assertTrue(!command.contains("-an"));
	}

	@Test
	@DisplayName("two pieces are mixed without being quietened")
	void twoPieces() {
		// They never overlap — a cue replaces the one before it — so mixing is only a
		// way of putting two trimmed pieces on one timeline. ffmpeg's default is to
		// divide by the number of inputs, which would make one piece full volume and
		// two pieces half each for no reason anybody watching could work out.
		String filter = Video.mix(List.of(
			new Video.Track(Path.of("море.ogg"), 0, 30),
			new Video.Track(Path.of("буря.ogg"), 30, 24)));
		assertTrue(filter.contains("[1:a]atrim=0:30.000,"), filter);
		assertTrue(filter.contains("[2:a]atrim=0:24.000,"), filter);
		assertTrue(filter.contains("adelay=30000:all=1"), "the second starts where the first ends");
		assertTrue(filter.endsWith("[a0][a1]amix=inputs=2:normalize=0:dropout_transition=0[a]"),
			filter);
	}

	@Test
	@DisplayName("the inputs come before the filter that names them")
	void inputsFirst() {
		// ffmpeg numbers inputs in the order it is given them, and the filter refers
		// to them by number. An input added after the filter is an input the filter
		// cannot see, and the failure is a line in a log rather than a bad film.
		List<String> command = command(List.of(
			new Video.Track(Path.of("море.ogg"), 0, 30),
			new Video.Track(Path.of("буря.ogg"), 30, 24)));
		int filter = command.indexOf("-filter_complex");
		int last = command.lastIndexOf("-i");
		assertTrue(last < filter, "an input is declared after the filter that uses it");
	}
}
