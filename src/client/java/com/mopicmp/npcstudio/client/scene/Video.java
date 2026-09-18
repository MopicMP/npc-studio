package com.mopicmp.npcstudio.client.scene;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.Minecraft;

/**
 * A film, written a frame at a time straight into an encoder.
 *
 * <h2>Why this replaces a folder of pictures</h2>
 *
 * Because a folder of pictures is not the film, it is the raw material for one,
 * and it is enormous. Five seconds of a scene came to a hundred and fifty PNGs
 * and a hundred and ninety-two megabytes — for a shot nobody had animated yet.
 * The same five seconds through an encoder is a file of a few megabytes and there
 * is nothing left to join up afterwards.
 *
 * <h2>What was learned from ReplayMod rather than guessed</h2>
 *
 * The shape of this is taken from {@code com.replaymod.render}, read out of the
 * jar rather than remembered, because it has had years of other people's machines
 * to be wrong on. Three things came from there and each is worth naming:
 *
 * <ul>
 * <li><b>Raw frames on standard input.</b> Nothing is encoded by us and nothing
 *     touches the disk twice: the frame goes from the framebuffer into the
 *     encoder's stdin as raw bytes. Their input line is
 *     {@code -y -f rawvideo -pix_fmt bgra -s WxH -r FPS -i -} and so is ours,
 *     down to the pixel order.</li>
 * <li><b>The output half is a string somebody may edit.</b> They keep the input
 *     arguments and the output arguments apart, so that a person can put
 *     {@code h264_nvenc} in the second without being able to break the first.
 *     That is why they ship no NVENC preset and still support NVENC. We do ship
 *     one, because a card that has it is common enough to be worth a name.</li>
 * <li><b>Find the program, do not require a setting.</b> Their order is: the
 *     game's own folder first, then the path, then the usual places. Somebody who
 *     has ffmpeg gets it without being asked, and somebody who has not is told
 *     what to install rather than shown an empty box.</li>
 * </ul>
 *
 * <h2>What is deliberately not taken</h2>
 *
 * Their asynchronous readback — two pixel buffers ping-ponged so the copy of one
 * frame overlaps the drawing of the next — and their off-screen renderer, which
 * draws the world at whatever resolution the film wants rather than the window's.
 *
 * Both are real and both are optimisations, and we are the one kind of program
 * that can do without them: <b>the clock is the film's, not the machine's</b>. A
 * frame is a fixed slice of the scene however long it took to produce, so a slow
 * readback makes the export take longer and cannot put a stutter in the result.
 * Theirs has to be fast; ours only has to be right. The off-screen renderer is a
 * genuine gap and it is the next thing worth having — until then the film is the
 * size of the window.
 */
public final class Video {

	/** The input half. Never edited: it describes the bytes we are about to send. */
	private static final String INPUT = "-y -f rawvideo -pix_fmt bgra -s %dx%d -r %d -i -";

	/** How the film may be encoded. */
	public enum Preset {

		/**
		 * The ordinary one: h.264 at a quality rather than a bitrate.
		 *
		 * A constant quality rather than a constant bitrate, which is the one thing
		 * here that is not ReplayMod's. Theirs asks for a bitrate because a bitrate
		 * is what somebody uploading to a site is given as a limit. A scene being cut
		 * for its own sake wants to look the same whether it is a still shot or a
		 * storm, and that is what a quality does.
		 */
		MP4("mp4", "-c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p"),

		/** The same, encoded by an Nvidia card if there is one. Much faster, slightly larger. */
		MP4_NVENC("mp4", "-c:v h264_nvenc -preset p5 -cq 19 -pix_fmt yuv420p"),

		/** Nothing thrown away, for cutting further. Large. */
		MKV_LOSSLESS("mkv", "-c:v libx264 -preset ultrafast -qp 0"),

		/** For the web, where h.264 is sometimes not wanted. */
		WEBM("webm", "-c:v libvpx-vp9 -crf 30 -b:v 0 -pix_fmt yuv420p"),

		/**
		 * No encoder at all: numbered pictures, as before.
		 *
		 * Kept because it is the only one that works with nothing installed, and
		 * because somebody compositing frame by frame genuinely wants it. It is no
		 * longer the default and no longer what happens by accident.
		 */
		FRAMES("png", "");

		public final String suffix;
		public final String arguments;

		Preset(String suffix, String arguments) {
			this.suffix = suffix;
			this.arguments = arguments;
		}

		public boolean needsFfmpeg() {
			return this != FRAMES;
		}
	}

	private Video() { }

	// ------------------------------------------------------------- finding ffmpeg

	/**
	 * Where the encoder is, if it is anywhere.
	 *
	 * The game's own folder first, so that dropping ffmpeg beside the mod is enough
	 * and nothing has to be installed system-wide. Then the plain name, which the
	 * shell resolves against the path. Then the places it lands on machines where
	 * somebody used a package manager.
	 *
	 * Looked for once and remembered, because this runs from a button and the
	 * answer does not change while the game is open.
	 */
	private static Optional<String> found;

	public static Optional<String> ffmpeg() {
		if (found != null) return found;
		found = look();
		found.ifPresentOrElse(
			where -> NpcStudio.LOGGER.info("Found ffmpeg at {}", where),
			() -> NpcStudio.LOGGER.info("No ffmpeg found; films can only be written as frames"));
		return found;
	}

	/** Asked again, for when somebody has just installed it. */
	public static void lookAgain() {
		found = null;
	}

	private static Optional<String> look() {
		Path own = Minecraft.getInstance().gameDirectory.toPath().resolve("ffmpeg");
		for (Path candidate : List.of(
				own.resolve("bin").resolve("ffmpeg.exe"),
				own.resolve("bin").resolve("ffmpeg"),
				own.resolve("ffmpeg.exe"),
				own.resolve("ffmpeg"),
				Path.of("/usr/bin/ffmpeg"),
				Path.of("/usr/local/bin/ffmpeg"),
				Path.of("/opt/homebrew/bin/ffmpeg"))) {
			if (Files.isRegularFile(candidate)) return Optional.of(candidate.toString());
		}
		// And the plain name, which is right whenever it is on the path — asked by
		// running it, because "is this on the path" has no other honest answer.
		return runs("ffmpeg") ? Optional.of("ffmpeg") : Optional.empty();
	}

	private static boolean runs(String named) {
		Process asked = null;
		try {
			asked = new ProcessBuilder(named, "-version").redirectErrorStream(true).start();
			asked.getInputStream().readAllBytes();
			return asked.waitFor() == 0;
		} catch (IOException | InterruptedException notThere) {
			if (notThere instanceof InterruptedException) Thread.currentThread().interrupt();
			return false;
		} finally {
			// Tidying rather than a fault anybody has met: asking a program for its
			// version means it has already exited by the time we look. The one path that
			// does not is the interrupted one, where the wait is abandoned and the
			// process would be left running with nobody holding it.
			if (asked != null && asked.isAlive()) asked.destroy();
		}
	}

	// ------------------------------------------------------------------- writing

	private static Process running;
	private static OutputStream into;
	private static Path writing;
	private static byte[] line;

	public static boolean going() {
		return running != null;
	}

	public static Path file() {
		return writing;
	}

	/**
	 * Starts an encoder and points it at a file.
	 *
	 * @return what went wrong, or empty if it started
	 */
	public static String open(Preset preset, Path file, int across, int down, int fps) {
		return open(preset, file, across, down, fps, List.of());
	}

	public static String open(Preset preset, Path file, int across, int down, int fps,
			List<Track> audio) {
		close();
		Optional<String> where = ffmpeg();
		if (where.isEmpty()) return "ffmpeg не найден";

		// Odd sizes are refused by most encoders rather than rounded, and a window
		// is very often an odd number of pixels tall. Saying so beats a process that
		// starts and dies with a line in a log nobody opens.
		if (across % 2 != 0 || down % 2 != 0) {
			return "размер окна " + across + "x" + down + " — нужен чётный, измени окно";
		}

		List<String> command = commandFor(where.get(), preset, file, across, down, fps, audio);

		try {
			Files.createDirectories(file.getParent());
			// Everything the encoder says goes into a file beside the film. It is
			// chatty when it is happy and precise when it is not, and the precise part
			// is the only thing that ever explains a film that came out wrong.
			Process started = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(file.resolveSibling(fileNameOf(file) + ".log").toFile())
				.start();
			running = started;
			into = started.getOutputStream();
			writing = file;
			line = null;
			NpcStudio.LOGGER.info("Encoding into {} with: {}", file, String.join(" ", command));
			return "";
		} catch (IOException failed) {
			NpcStudio.LOGGER.warn("Could not start ffmpeg: {}", failed.toString());
			running = null;
			into = null;
			return "ffmpeg не запустился — смотри лог";
		}
	}

	/**
	 * The whole command, as the list a process is started from.
	 *
	 * A list and never a string. A film's path has spaces in it on most people's
	 * machines and a quoted string is then something that has to be unquoted by
	 * somebody — which is a parser, and a parser is where "it works on my machine"
	 * lives. ReplayMod keeps a string because it lets people edit it and has to;
	 * we do not, so we do not.
	 *
	 * Taken apart from starting anything so that what gets sent can be checked
	 * without a machine that has ffmpeg on it.
	 */
	public static List<String> commandFor(String ffmpeg, Preset preset, Path file,
			int across, int down, int fps) {
		return commandFor(ffmpeg, preset, file, across, down, fps, List.of());
	}

	/**
	 * One piece of music, where it starts in the film and how much of it is heard.
	 *
	 * @param file    the ogg on disk
	 * @param from    where in the film it begins, in seconds
	 * @param lasting how long it is heard before the next piece or the end, in seconds
	 */
	public record Track(Path file, double from, double lasting) { }

	/**
	 * The whole command, as the list a process is started from.
	 *
	 * A list and never a string. A film's path has spaces in it on most people's
	 * machines and a quoted string is then something that has to be unquoted by
	 * somebody — which is a parser, and a parser is where "it works on my machine"
	 * lives. ReplayMod keeps a string because it lets people edit it and has to;
	 * we do not, so we do not.
	 *
	 * Taken apart from starting anything so that what gets sent can be checked
	 * without a machine that has ffmpeg on it.
	 */
	public static List<String> commandFor(String ffmpeg, Preset preset, Path file,
			int across, int down, int fps, List<Track> audio) {
		List<String> command = new ArrayList<>();
		command.add(ffmpeg);
		command.addAll(List.of(String.format(INPUT, across, down, fps).split(" ")));

		// Every piece of music as its own input, after the frames. Inputs first and
		// all together, because ffmpeg numbers them in the order it is given them and
		// the filter below names them by number.
		for (Track track : audio) command.addAll(List.of("-i", track.file().toString()));

		if (audio.isEmpty()) {
			command.add("-an");
		} else {
			command.addAll(List.of("-filter_complex", mix(audio)));
			command.addAll(List.of("-map", "0:v", "-map", "[a]"));
			command.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
			// The film is as long as the frames. A piece of music running past the end
			// of the scene is cut there rather than extending the file, which is what
			// the panel has been warning about all along.
			command.add("-shortest");
		}

		for (String argument : preset.arguments.split(" ")) {
			if (!argument.isEmpty()) command.add(argument);
		}
		command.add(file.toString());
		return command;
	}

	/**
	 * The filter that puts the music where the scene put it.
	 *
	 * <h2>Why a filter and not simply a second input</h2>
	 *
	 * Because a piece does not start at the beginning and does not run to the end.
	 * It starts at its cue and stops when the next one begins — that is what a music
	 * cue means in a scene — so each one is trimmed to the length it is actually
	 * heard for and then delayed to where it is heard.
	 *
	 * Trimmed before delayed, and the timestamps reset in between: {@code atrim}
	 * keeps the original timings on what it cuts, so a piece trimmed and then
	 * delayed without resetting would arrive at its own start time plus the delay.
	 *
	 * Mixed rather than concatenated, because concatenation cannot leave a gap and a
	 * scene with silence in the middle of it is ordinary. Mixing without
	 * normalisation, because ffmpeg's default is to divide by the number of inputs —
	 * which would make one piece full volume and two pieces half each, for no reason
	 * anybody watching could work out.
	 */
	static String mix(List<Track> audio) {
		boolean alone = audio.size() == 1;
		StringBuilder filter = new StringBuilder();
		for (int i = 0; i < audio.size(); i++) {
			Track track = audio.get(i);
			long delay = Math.max(0, Math.round(track.from() * 1000));
			filter.append('[').append(i + 1).append(":a]");
			if (track.lasting() > 0) {
				filter.append(String.format(java.util.Locale.ROOT, "atrim=0:%.3f,", track.lasting()));
			}
			filter.append("asetpts=PTS-STARTPTS,adelay=").append(delay).append(":all=1");
			// One piece needs no mixing, so it is labelled as the output directly.
			// Built this way rather than by trimming a label off the end afterwards,
			// which is how the first version of this came out one bracket short.
			filter.append(alone ? "[a]" : "[a" + i + "];");
		}
		if (alone) return filter.toString();

		for (int i = 0; i < audio.size(); i++) filter.append("[a").append(i).append(']');
		filter.append("amix=inputs=").append(audio.size())
			.append(":normalize=0:dropout_transition=0[a]");
		return filter.toString();
	}

	private static String fileNameOf(Path file) {
		String named = file.getFileName().toString();
		int dot = named.lastIndexOf('.');
		return dot < 0 ? named : named.substring(0, dot);
	}

	/**
	 * One frame, as the bytes the encoder was promised.
	 *
	 * Blue, green, red, alpha, a row at a time from the top — which is the order
	 * named in the input arguments, and the reason those two are written next to
	 * each other rather than in two files. Built by hand out of whole pixels rather
	 * than handed the image's own buffer, because that buffer's layout is the
	 * game's business and has changed before.
	 *
	 * @return what went wrong, or empty
	 */
	public static String write(NativeImage picture) {
		return write(picture, 0, 0, picture.getWidth(), picture.getHeight());
	}

	/**
	 * One frame, cropped to the rectangle the film actually occupies.
	 *
	 * <h2>Why a crop and not a second render</h2>
	 *
	 * Because the pixels are already right. The viewport is a hole in the panels
	 * through which the window's own picture shows, drawn with the window's own
	 * camera — so keeping only those pixels is an honest crop of a photograph:
	 * same perspective, same lens, fewer of them. Drawing the world a second time
	 * into a window of its own is the other answer and it is six mixins into the
	 * middle of the renderer.
	 *
	 * The crop is what makes the panels safe to leave up while filming, which is
	 * what makes it possible to watch the shot being taken.
	 */
	public static String write(NativeImage picture, int fromX, int fromY,
			int across, int down) {
		if (into == null) return "";
		// Held to what the picture actually has. A window resized mid-export would
		// otherwise be read past its end, which is a crash rather than a bad frame.
		across = Math.min(across, picture.getWidth() - fromX);
		down = Math.min(down, picture.getHeight() - fromY);
		if (across <= 0 || down <= 0) return "";
		if (line == null || line.length != across * 4) line = new byte[across * 4];

		try {
			for (int y = 0; y < down; y++) {
				for (int x = 0; x < across; x++) {
					int pixel = picture.getPixel(fromX + x, fromY + y);
					int at = x * 4;
					line[at] = (byte) (pixel & 0xFF);
					line[at + 1] = (byte) ((pixel >> 8) & 0xFF);
					line[at + 2] = (byte) ((pixel >> 16) & 0xFF);
					line[at + 3] = (byte) ((pixel >>> 24) & 0xFF);
				}
				into.write(line);
			}
			return "";
		} catch (IOException gone) {
			// The encoder has died, which is nearly always a bad argument. There is no
			// recovering a film whose middle is missing, so this stops rather than
			// carrying on writing into nothing.
			NpcStudio.LOGGER.warn("ffmpeg stopped taking frames: {}", gone.toString());
			close();
			return "ffmpeg закрылся — смотри лог рядом с файлом";
		}
	}

	/**
	 * Closes the pipe and waits for the encoder to finish the file.
	 *
	 * Waited for rather than left to itself: the last seconds of a film are still
	 * inside the encoder when the last frame goes in, and a process killed at that
	 * moment leaves a file that plays up to nearly the end and then stops.
	 */
	public static void close() {
		if (running == null) return;
		try {
			if (into != null) into.close();
			running.waitFor();
		} catch (IOException | InterruptedException interrupted) {
			if (interrupted instanceof InterruptedException) Thread.currentThread().interrupt();
			running.destroy();
		}
		running = null;
		into = null;
	}
}
