package com.mopicmp.npcstudio.client.scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.scene.Playhead;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

/**
 * Writing a scene out as frames, on its own clock.
 *
 * <h2>Why not just record the screen</h2>
 *
 * Because the screen runs at whatever rate the machine manages, and a scene does
 * not. Capture what the window shows and every stutter is in the film: a chunk
 * loading becomes a hitch in a character's walk, and a slow moment — which is
 * exactly the moment somebody spent the afternoon on — comes out as a series of
 * jumps because the frames were far apart in time precisely where the movement
 * was slow.
 *
 * So the clock is turned round. Instead of the scene advancing because time
 * passed, it advances because a frame was written: one frame is worth
 * {@code 20 / fps} ticks, always, whether that frame took four milliseconds or
 * four hundred. The film comes out even at the chosen rate and the machine is
 * allowed to be as slow as it likes.
 *
 * That is the whole difference between this and screen recording, and it is the
 * reason to have it as well as the OBS route rather than instead of it.
 *
 * <h2>How it comes out</h2>
 *
 * A film, if there is an encoder to make one with — see {@link Video}. The frames
 * go straight from the framebuffer into ffmpeg's standard input and what lands on
 * disk is one file of a few megabytes.
 *
 * Numbered pictures are still here and are still what happens when ffmpeg is not
 * installed, because that has to work with nothing installed. They are no longer
 * the ordinary answer: five seconds of a scene as PNGs came to a hundred and
 * ninety-two megabytes, and somebody found out days later by opening the folder.
 *
 * No encoder is shipped. A mod that quietly bundles a codec is a mod with a
 * licensing problem rather than a feature, so ffmpeg is looked for and asked for
 * rather than carried.
 */
public final class Capture {

	/**
	 * Frames a second.
	 *
	 * Thirty, which is what this kind of thing is cut at, and it divides into the
	 * game's twenty ticks as two thirds of a tick per frame — a fraction the
	 * interpolation handles exactly, since the cursor was always a double for
	 * this reason. It is a constant rather than a setting because a setting with
	 * one plausible value is a question nobody wants asked.
	 */
	public static final int RATE = 30;

	private static final DateTimeFormatter STAMP =
		DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	/**
	 * How the film is to be written.
	 *
	 * A field rather than a setting in a file, for now: it is chosen where the
	 * capture is started and there is one of it. It falls back on its own if there
	 * is no encoder, which is the case that has to need no decision at all.
	 */
	private static Video.Preset preset = Video.Preset.MP4;

	public static Video.Preset preset() {
		return preset;
	}

	public static void preset(Video.Preset chosen) {
		if (!running) preset = chosen;
	}

	private static boolean running;
	/** The rectangle being written, in framebuffer pixels: left, top, wide, tall. */
	private static int[] cropped;
	private static Path folder;
	private static int frame;
	private static String said = "";

	private Capture() { }

	public static boolean running() {
		return running;
	}

	public static int frames() {
		return frame;
	}

	/** What happened, for the panel to show. Empty until something has. */
	public static String said() {
		return said;
	}

	/**
	 * How many frames a scene would come to, or nought if it must not be captured.
	 *
	 * <h2>Why this is asked before anything is written</h2>
	 *
	 * Because a capture is a hundred and fifty files and two hundred megabytes on
	 * disk, and that happened to somebody who pressed an icon to see what it did.
	 * A side effect that size has to be described before it is caused, so the count
	 * is worked out first and shown, and the press that starts it is the second
	 * one.
	 */
	public static int wouldWrite() {
		Scene scene = Playing.scene();
		if (scene == null) return 0;
		// A scene with nothing keyed and nothing cued is the same picture over and
		// over. There is no honest reason to write it two hundred times, and the
		// person doing it has always pressed the wrong thing.
		if (scene.tracks().isEmpty() && scene.cues().isEmpty()) return 0;
		return Math.max(1, (int) Math.ceil(scene.length() * (double) RATE / Scene.RATE));
	}

	/** Whether the next press would begin rather than arm. */
	public static boolean armed() {
		return armed;
	}

	private static boolean armed;

	/**
	 * Says what a capture would do, or takes it back.
	 *
	 * One press describes and the next one does it. Not a dialogue box, because
	 * this is a panel and a box over a panel is a mode; the button lights, the
	 * count appears beside it, and pressing anything else forgets the whole idea.
	 */
	public static void arm() {
		if (running) return;
		int frames = wouldWrite();
		if (frames == 0) {
			armed = false;
			said = Playing.scene() == null
				? "нет открытой сцены"
				: "в сцене нечего снимать — ни ключей, ни меток";
			return;
		}
		armed = true;
		int[] size = Framing.now().inPixels();
		said = frames + " кадр(ов), " + size[2] + "x" + size[3] + ", "
			+ preset.name().toLowerCase() + ", " + viewpoint()
			+ " — ещё раз снимать, shift — сменить формат";
	}

	/**
	 * Whose eye the film will be taken from, in words.
	 *
	 * Said before the capture rather than discovered after it. A scene with a
	 * camera in it is filmed through the camera and a scene without one is filmed
	 * from wherever the workspace happens to be looking — both are reasonable and
	 * they produce completely different films, so which one is about to happen is
	 * exactly the sort of thing the arming message exists to say.
	 */
	private static String viewpoint() {
		return SceneCameras.part() != null ? "через камеру" : "из текущего вида";
	}

	public static void disarm() {
		armed = false;
		said = "";
	}

	/**
	 * Starts at the top of the scene and takes it through once.
	 *
	 * Looping is turned off for the duration whatever it was set to, because a
	 * capture of a looping scene is a folder that fills the disk. It is put back
	 * afterwards: it is a setting somebody chose, not a thing this owns.
	 */
	public static void start() {
		Scene scene = Playing.scene();
		if (running || scene == null) return;
		armed = false;
		if (wouldWrite() == 0) return;

		Minecraft client = Minecraft.getInstance();
		String stamp = LocalDateTime.now().format(STAMP);
		folder = client.gameDirectory.toPath()
			.resolve("screenshots").resolve("npc_studio")
			.resolve(Playing.openName() + "-" + stamp);
		try {
			Files.createDirectories(folder);
		} catch (IOException unwritable) {
			said = "не могу писать в " + folder;
			NpcStudio.LOGGER.warn("Could not make a folder for the capture: {}",
				unwritable.toString());
			folder = null;
			return;
		}

		// The film, if there is anything to make one with. Falling back rather than
		// refusing: somebody who has not installed ffmpeg still gets their shot, as
		// frames, and is told why.
		Video.Preset wanted = preset;
		if (wanted.needsFfmpeg() && Video.ffmpeg().isEmpty()) {
			wanted = Video.Preset.FRAMES;
			said = "ffmpeg не найден — пишу кадрами";
		}
		writingFilm = false;
		if (wanted.needsFfmpeg()) {
			// The frame rather than the window: whatever the viewport shows is what is
			// written, so what was composed is what comes out. See {@link Framing}.
			cropped = Framing.now().inPixels();
			String refused = Video.open(wanted,
				folder.resolveSibling(folder.getFileName() + "." + wanted.suffix),
				cropped[2], cropped[3], RATE, music(scene));
			if (!refused.isEmpty()) {
				said = refused;
				folder = null;
				return;
			}
			writingFilm = true;
			// The folder was made a moment ago for frames that are no longer going to
			// be written into it. An empty folder left beside every film is litter,
			// and litter beside a film is how somebody comes to think frames are
			// still being written.
			try {
				Files.deleteIfExists(folder);
			} catch (IOException leftBehind) {
				NpcStudio.LOGGER.warn("Could not remove {}: {}", folder, leftBehind.toString());
			}
		}

		// From the top, always, and this was missing.
		//
		// Playing a scene carries on from wherever the cursor was left, which is right
		// for playing and wrong for exporting: an export that begins at the cursor is
		// a film with its first half cut off, and nothing says so. Four films came out
		// three and a half, forty-two, fifty-one and fifty-three seconds long from one
		// fifty-four second scene, purely by where the playhead happened to be sitting
		// — and the count, which is against the whole scene, read thirty per cent when
		// the third of them finished.
		//
		// Where the cursor was is put back afterwards. Somebody was working at that
		// moment and an export is not a reason to lose it.
		wasAt = Playing.head();
		Playing.head(Playing.head().scrubbedTo(0, scene.length()));
		looped = Playing.head().loops();
		// The panels stand aside only when they would be in the picture. Filming the
		// viewport they are outside the frame by definition and can stay up, which is
		// what lets somebody watch the shot being taken; filming the whole window they
		// are in it, so they go, and the count in the title bar is the only sign of
		// life. Remembered rather than assumed, because somebody may already have been
		// in the bare view when they pressed it.
		wasClean = com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.clean();
		if (!Framing.toViewport()) {
			com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.clean(true);
		}

		// And the film is taken through the scene's camera whenever the scene has
		// one. It was not, and that made the camera decorative: somebody could place
		// a shot, animate it, watch it — and then film whatever the workspace's own
		// camera happened to be pointing at. A scene with no camera goes on being
		// filmed from the current view, which is the only thing left to film from.
		wasThrough = SceneCameras.through();
		if (SceneCameras.part() != null) SceneCameras.through(true);

		frame = 0;
		total = wouldWrite();
		run++;
		awaiting = false;
		running = true;
		said = "";
		// Kept so it can be put back. Asking the window what it is called is not
		// possible, so what it is called is what we set it to — and the game's own
		// title is what it had before anybody pressed anything.
		if (wasCalled == null) wasCalled = "Minecraft " + net.minecraft.SharedConstants.getCurrentVersion().name();
		showProgress();
		Playing.head(Playing.head().looping(false));
		Playing.start();
	}

	/**
	 * The scene's music, as the encoder needs it: a file, a moment and a length.
	 *
	 * <h2>Why the film carries sound at all now</h2>
	 *
	 * Because it is a film. Every preset used to pass {@code -an} and the answer to
	 * "how do I export this" was "you get a silent video and you add the music
	 * yourself" — which makes the whole business of cueing a piece against the
	 * animation an exercise done twice, once here and once in an editor.
	 *
	 * A piece runs from its cue until the next one or the end of the scene, exactly
	 * as it does while the scene plays; the same rule, worked out in the same way,
	 * so what is heard in the export is what was heard while it was being made.
	 */
	private static java.util.List<Video.Track> music(Scene scene) {
		java.util.List<Video.Track> found = new java.util.ArrayList<>();
		for (com.mopicmp.npcstudio.scene.Cue cue : scene.cues()) {
			if (cue.kind() != com.mopicmp.npcstudio.scene.Cue.Kind.MUSIC) continue;
			if (cue.what().isEmpty() || cue.at() >= scene.length()) continue;
			java.nio.file.Path file = Music.folder().resolve(cue.what() + ".ogg");
			if (!java.nio.file.Files.isRegularFile(file)) continue;

			int until = scene.length();
			for (com.mopicmp.npcstudio.scene.Cue other : scene.cues()) {
				if (other.kind() == com.mopicmp.npcstudio.scene.Cue.Kind.MUSIC
					&& other.at() > cue.at()) {
					until = Math.min(until, other.at());
				}
			}
			found.add(new Video.Track(file, cue.at() / 20.0, (until - cue.at()) / 20.0));
		}
		return found;
	}

	/** What looping, the bare shot and the view were set to before the capture took them. */
	private static boolean looped;

	/** Whether the panels were already standing aside before the export. */
	private static boolean wasClean;

	/** Where the cursor was before the export took it to the top. */
	private static Playhead wasAt;
	private static boolean wasThrough;
	private static boolean writingFilm;

	public static void stop() {
		if (!running) return;
		running = false;
		showProgress();
		com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.clean(wasClean);
		Playing.stop();
		// Back where somebody was working, with looping as they had it.
		Playing.head((wasAt == null ? Playing.head() : wasAt).playing(false).looping(looped));
		wasAt = null;
		// Back to whichever view somebody was working in. Leaving it looking through
		// the camera would be a workspace that has stopped answering the letter keys
		// for a reason that ended when the film did.
		SceneCameras.through(wasThrough);
		Path made = writingFilm ? Video.file() : folder;
		Video.close();
		writingFilm = false;
		said = frame + " кадр(ов) → " + (made == null ? "?" : made.getFileName().toString());
		folder = made;
		// And in the log as well, with the whole path. The notice in the panel is
		// where somebody looks in the next few seconds; the log is where they look
		// the following day, when they are wondering what filled the disk.
		if (folder != null) {
			NpcStudio.LOGGER.info("Capture wrote {} frames into {}", frame, folder.toAbsolutePath());
		}
	}

	/**
	 * How far along the export is, as a share, or minus one when nothing is running.
	 *
	 * <h2>Why this is shown in the window's title bar</h2>
	 *
	 * Because a progress bar drawn in the window is a progress bar in the film. The
	 * frame that is written is the whole window, panels and all — which is why they
	 * are hidden for the duration — so anything put on the screen to say how far
	 * along it is would be recorded saying it, in every frame, for the length of the
	 * export.
	 *
	 * The title bar is outside the picture entirely. It is the one surface this
	 * program has that the camera cannot see, and on a long export — sixteen hundred
	 * frames with shaders on a laptop is not quick — knowing whether it is moving is
	 * the whole difference between waiting and wondering.
	 */
	public static float along() {
		if (!running) return -1;
		return Math.clamp(frame / (float) Math.max(1, total), 0f, 1f);
	}

	/** How many frames this export set out to write. */
	private static int total;

	/** What the window was called before the export took its title. */
	private static String wasCalled;

	private static void showProgress() {
		var window = Minecraft.getInstance().getWindow();
		if (window == null) return;
		if (!running) {
			if (wasCalled != null) window.setTitle(wasCalled);
			wasCalled = null;
			return;
		}
		int percent = Math.round(along() * 100);
		window.setTitle("NPC Studio — экспорт " + frame + "/" + Math.max(1, total)
			+ " (" + percent + "%)");
	}

	/**
	 * One frame: write what was just drawn, then move the scene on by a frame's
	 * worth of time.
	 *
	 * In that order, and the order is the point. The picture on the screen belongs
	 * to the cursor as it was when the frame was drawn; advancing first would file
	 * every frame one step late, which is invisible in a still and shows up in the
	 * film as everything happening a thirtieth of a second before its sound.
	 */
	public static void frame() {
		if (!running) return;
		Scene scene = Playing.scene();
		if (scene == null) {
			stop();
			return;
		}

		// Nothing moves while a picture is still on its way back from the graphics
		// device. See {@link #awaiting}: two in the air at once is what turned three
		// of four films into streaks.
		if (awaiting) {
			if (++waited > PATIENCE) {
				said = "кадр не вернулся с видеокарты — съёмка прервана";
				NpcStudio.LOGGER.warn("A frame never came back; stopping the capture");
				awaiting = false;
				stop();
			}
			return;
		}

		write();
		showProgress();
		Playhead.Step step = Playing.head().advanced(Scene.RATE / (double) RATE, scene.length());
		Playing.head(step.head());
		// The scene ran out. It says so itself by not playing any more, which is
		// what a scene that does not loop does when it reaches the end.
		if (!step.head().playing()) stop();
	}

	/**
	 * Asks for the frame that has just been drawn, and writes it when it arrives.
	 *
	 * <h2>Why only ever one at a time</h2>
	 *
	 * Because the picture does not come back when it is asked for. The game hands
	 * the request to the graphics device — {@code copyTextureToBuffer}, with a
	 * runnable for when the copy is done — so the answer arrives some frames later,
	 * and two answers can be in the air at once.
	 *
	 * Written straight from the callback, as this used to be, that means two frames
	 * pouring their rows into one pipe in whatever order the device felt like. The
	 * result is a film of horizontal streaks: not a codec fault, not a colour fault,
	 * simply two pictures interleaved a row at a time. Three exports came out that
	 * way and the fourth — a short one, where each readback happened to finish
	 * before the next was asked for — came out perfect, which is exactly the shape
	 * of a race.
	 *
	 * So the scene does not move on until the frame it is waiting for has been
	 * written. One request in the air, one writer, order guaranteed by there being
	 * nothing to reorder. It costs wall-clock time and cannot cost the film
	 * anything, because the film's clock is not the machine's — which is the same
	 * argument this whole class is built on.
	 */
	private static volatile boolean awaiting;

	/**
	 * Which export this is, counted up on every start.
	 *
	 * Carried by each request so that an answer arriving for an export that has
	 * already ended is dropped rather than acted on. There is always one in the air
	 * when the last frame goes in — the readback is asynchronous — so this is not an
	 * edge case, it happens every single time.
	 */
	private static int run;

	/** How many frames may pass with no answer before this is given up as stuck. */
	private static final int PATIENCE = 600;

	private static int waited;

	private static void write() {
		Minecraft client = Minecraft.getInstance();
		if (folder == null || client.gameRenderer == null) return;

		int number = frame;
		awaiting = true;
		waited = 0;
		try {
			int session = run;
			Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), picture -> {
				awaiting = false;
				// A picture that arrives after the export has finished belongs to
				// nothing. There is always one in the air when the last frame is
				// written, and letting it through found a closed film, a folder meant
				// for something else, and — because the outcome had already been
				// decided — the wrong branch entirely.
				if (session != run || !running) {
					picture.close();
					return;
				}
				if (!writingFilm) {
					Path file = folder.resolve(String.format("%05d.png", number));
					// Closed by whoever writes it, and that is the whole of the fix.
					// Closing it here while handing it to a thread that writes it later
					// is handing over something already freed: "Image is not allocated",
					// on a worker thread, which the game turns into a crash.
					//
					// Numbered, so the order these land in does not matter — the one
					// thing that lets this half stay on a pool of threads.
					Util.ioPool().execute(() -> {
						try (var image = picture) {
							image.writeToFile(file);
						} catch (IOException unwritable) {
							NpcStudio.LOGGER.warn("Could not write {}: {}", file,
								unwritable.toString());
						}
					});
					frame++;
					return;
				}
				try (var image = picture) {
					String failed = Video.write(image,
						cropped[0], cropped[1], cropped[2], cropped[3]);
					if (!failed.isEmpty()) {
						said = failed;
						stop();
						return;
					}
					frame++;
				}
			});
		} catch (RuntimeException failed) {
			// A capture that cannot read the frame is over; carrying on would fill a
			// folder with nothing and there would be no sign until somebody looked.
			awaiting = false;
			said = "кадр не снялся: " + failed;
			NpcStudio.LOGGER.warn("Could not take a frame: {}", failed.toString());
			stop();
		}
	}
}
