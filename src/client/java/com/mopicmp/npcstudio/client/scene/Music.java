package com.mopicmp.npcstudio.client.scene;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.client.mixin.SoundDeviceAccess;
import com.mopicmp.npcstudio.client.mixin.SoundEngineAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.sounds.SoundSource;

/**
 * A piece of music somebody put in a folder, played against the scene's clock.
 *
 * <h2>Why this does not go through the game's sound system at all</h2>
 *
 * Because that system is built for sounds a resource pack knows about. A sound
 * has to be declared in a {@code sounds.json}, given a name in a registry, and
 * shipped inside a pack — and adding one afterwards means writing a pack,
 * registering a provider for it and reloading every resource in the game. For a
 * file somebody has just dropped into a folder, all of that is machinery in the
 * way of an answer.
 *
 * Underneath it, though, the game has exactly what is wanted and nothing more: a
 * decoder that takes any stream of ogg, and a device that lends out a channel to
 * whoever asks. So a file on disk is opened, decoded and played, with no pack, no
 * registry and no reload — drop a file in, it is in the list on the next look.
 *
 * <h2>One at a time, and it belongs to the playhead</h2>
 *
 * A scene has one piece of music going, and which one is decided by the last cue
 * the cursor has passed. That makes music a <em>state</em> rather than an event:
 * rewind past the cue and it stops, run forward over it and it starts from as far
 * in as the cursor has gone, close the scene and it is gone.
 *
 * A state of the <em>playhead</em>, though, and not merely of the cursor — which
 * was got wrong and is worth writing down. A paused playhead is not a playhead
 * moving slowly: there is nothing for a piece of music to be in step with, so
 * nothing plays. Otherwise opening a scene with a cue at nought started the track
 * at once and ran it for ever against a timeline standing still. See
 * {@code Playing.follow}.
 *
 * <h2>The honest cost: seeking means decoding</h2>
 *
 * An ogg stream cannot be jumped into. Landing in the middle of a piece therefore
 * means decoding everything before it and throwing it away — quick for a few
 * seconds and not free for a few minutes. It is done on a thread of its own so
 * the game does not stop while it happens, and the result is that scrubbing far
 * into a long track catches up a moment later rather than instantly.
 */
public final class Music {

	/** Where the files live: a folder beside the world saves, not inside a pack. */
	private static final String FOLDER = "npcstudio/music";

	private static final String SUFFIX = ".ogg";

	/**
	 * How near the cursor has to be to the start of a cue to count as "at the
	 * beginning", in ticks.
	 *
	 * Below this the piece is started from its beginning rather than sought into.
	 * Playing normally always lands inside this, so the ordinary case never pays
	 * for the seek — and a hand that scrubs to almost exactly the cue gets the same
	 * answer as one that scrubs to it.
	 */
	private static final int NEAR = 4;

	private Music() { }

	public static Path folder() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(FOLDER);
	}

	/**
	 * The pieces there are, by name and without the suffix.
	 *
	 * Read from the folder each time rather than remembered. It is a directory
	 * listing of a folder with a handful of things in it, asked for when somebody
	 * opens a menu — and remembering it would mean a file dropped in while the game
	 * is running does not appear, which is exactly the workflow this is for.
	 */
	public static List<String> names() {
		List<String> found = new ArrayList<>();
		Path where = folder();
		if (!Files.isDirectory(where)) return found;
		try (var files = Files.list(where)) {
			files.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(name -> name.toLowerCase().endsWith(SUFFIX))
				.map(name -> name.substring(0, name.length() - SUFFIX.length()))
				.sorted()
				.forEach(found::add);
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.warn("Could not read {}: {}", where, unreadable.toString());
		}
		return found;
	}

	/**
	 * How long a piece is, in seconds, or nought when it cannot be worked out.
	 *
	 * <h2>Why this had to exist</h2>
	 *
	 * Because a piece of music was a name and a mark one pixel wide, and nothing
	 * else. There was no way to see how long it ran, where it ended against the
	 * animation, or whether it fitted the scene at all — which was reported, and
	 * fairly, as there being nothing to set.
	 *
	 * Read out of the file's own headers rather than by decoding it; see
	 * {@link Ogg}. Remembered afterwards because a list of pieces asks for every
	 * one of them each time it is drawn, and a file's length does not change while
	 * the game is open.
	 */
	public static double seconds(String named) {
		if (named == null || named.isEmpty()) return 0;
		Double known = lengths.get(named);
		if (known != null) return known;
		double found = Ogg.seconds(folder().resolve(named + SUFFIX));
		lengths.put(named, found);
		return found;
	}

	private static final java.util.Map<String, Double> lengths = new java.util.HashMap<>();

	/** Forgotten when the folder is looked at again, in case a file was replaced. */
	public static void forgetLengths() {
		lengths.clear();
	}

	/** How long a piece runs in the scene's own ticks. */
	public static int ticks(String named) {
		return (int) Math.round(seconds(named) * 20);
	}

	// ------------------------------------------------------------------ playing

	private static Channel channel;

	/**
	 * What the scene is asking for, whether or not it is audible yet.
	 *
	 * <h2>The bug this pair replaces</h2>
	 *
	 * These used to be set only alongside a live channel, and the test for "already
	 * playing the right thing" was {@code name matches && from matches && channel
	 * != null}. Which is a race against the decoding, and a race that cannot be
	 * won: seeking into an ogg means decoding everything before the point wanted,
	 * on a thread, and the tick comes round every fifty milliseconds. Any seek
	 * longer than that found {@code channel == null}, concluded that the wrong
	 * thing was playing, and started over — cancelling the decode that was about to
	 * finish. Every tick. For ever.
	 *
	 * So it worked from the very beginning of a piece, where there is nothing to
	 * seek past and a file opens in a few milliseconds, and it could not work
	 * anywhere else. Rewind ten seconds into a track and the music does not stop:
	 * it restarts twenty times a second and never once reaches the speakers.
	 *
	 * What is <em>wanted</em> and what is <em>audible</em> are two different facts,
	 * so they are two different fields now. A decode in flight is a piece already
	 * asked for, and nothing asks again.
	 */
	private static String wantedName = "";
	private static int wantedFrom = Integer.MIN_VALUE;

	/** Whether a file is being opened and sought into at this moment. */
	private static boolean starting;

	/**
	 * Where the cursor was when we last looked.
	 *
	 * How a rewind is told from ordinary playing. Nothing else can tell them apart:
	 * the scene asks for the same piece from the same cue either way, and the only
	 * difference is that the cursor moved further in one tick than a tick is worth.
	 */
	private static double sawAt;

	/**
	 * How far the cursor may move in one tick before it counts as having been moved
	 * by hand, in ticks.
	 *
	 * Ordinary playing advances one. Four leaves room for a tick the machine was
	 * late for, and is far short of any jump somebody makes on purpose.
	 */
	private static final double JUMP = 4;

	/**
	 * Which request is the current one.
	 *
	 * Counted up on every change, and carried by the thread doing the decoding, so
	 * that an answer arriving for a moment the cursor has already left is dropped
	 * rather than played. Comparing the name and the tick instead would be wrong in
	 * the one case that matters: scrub away and straight back, and the stale answer
	 * looks current.
	 */
	private static int asked;

	/** What is audible, or empty. */
	public static String nowPlaying() {
		return channel == null ? "" : wantedName;
	}

	/**
	 * Makes the right piece be playing, or none.
	 *
	 * Called every tick with what the scene wants. Doing nothing is the common
	 * case: the same piece, from the same cue, with the cursor where one more tick
	 * of playing would have put it.
	 *
	 * @param named where in the folder, or empty for silence
	 * @param from  the tick the cue sits at, so that the same piece cued twice is
	 *              two different things to be playing
	 * @param at    where the cursor is, in ticks
	 */
	public static void want(String named, int from, double at) {
		Next next = decide(wantedName, wantedFrom, sawAt, named, from, at);
		sawAt = at;
		switch (next) {
			case SILENCE -> stop();
			case LEAVE -> pump();
			case RESTART -> {
				stop();
				sawAt = at;
				start(named, from, Math.max(0, (at - from) / 20.0));
			}
		}
	}

	/** What to do with the music this tick. */
	public enum Next { LEAVE, RESTART, SILENCE }

	/**
	 * The whole decision, with nothing in it that needs a sound device.
	 *
	 * <h2>Why this is out here on its own</h2>
	 *
	 * Because it has been wrong twice, in two ways that both sounded like a broken
	 * file. Once it played while the scene was paused, so the track ran on against a
	 * timeline standing still; once it treated "no channel yet" as "the wrong thing
	 * is playing", so any seek longer than a tick restarted itself for ever and
	 * never made a sound. Neither was a mistake about audio. Both were three
	 * comparisons.
	 *
	 * So the three comparisons are here, where they can be checked without a
	 * speaker, and the part that talks to the device is left with nothing to decide.
	 *
	 * @param wantedName what has already been asked for, or empty
	 * @param wantedFrom the cue that was asked for
	 * @param sawAt      where the cursor was when it was last looked at
	 * @param named      the piece the scene wants now, or empty for silence
	 * @param from       the cue it belongs to
	 * @param at         where the cursor is now
	 */
	public static Next decide(String wantedName, int wantedFrom, double sawAt,
			String named, int from, double at) {
		if (named == null || named.isEmpty()) return Next.SILENCE;
		if (!named.equals(wantedName) || from != wantedFrom) return Next.RESTART;
		// A cursor that moved further than a tick is worth was moved by a hand, and
		// the music goes with it. Without this a rewind left the piece playing on from
		// wherever it had got to, against an animation that had gone back — the other
		// half of "it does not match the timeline".
		return Math.abs(at - sawAt) > JUMP ? Next.RESTART : Next.LEAVE;
	}

	/**
	 * Which device lent us the channel we are holding.
	 *
	 * <h2>The crash this exists for</h2>
	 *
	 * Somebody plugged in headphones. The game noticed — "System default audio
	 * device has changed" — tore the sound engine down and built a new one, and
	 * every channel the old device had lent out went with it. We were still holding
	 * one. The next tick found it stopped, tried to hand it back, and handed it to a
	 * device that had never heard of it: {@code IllegalStateException: Tried to
	 * release unknown channel}, on the client tick, which is a crash.
	 *
	 * A channel is only ever given back to the device it came from. If the device
	 * has been replaced the channel is simply let go: it belongs to something that
	 * no longer exists, and there is nothing left to return it to.
	 */
	private static Library lentBy;

	/** Stops whatever is going, and forgets what was wanted. */
	public static void stop() {
		if (channel == null && !starting && wantedName.isEmpty()) return;
		asked++;
		wantedName = "";
		wantedFrom = Integer.MIN_VALUE;
		starting = false;
		sawAt = 0;
		handBack();
	}

	/**
	 * Gives the channel up while keeping what was wanted.
	 *
	 * The difference from stopping, and it is the whole of how a finished piece
	 * stays finished: the device gets its channel back, and the scene goes on
	 * knowing which piece it has already played.
	 */
	private static void handBack() {
		Channel had = channel;
		Library from = lentBy;
		channel = null;
		lentBy = null;
		if (had == null) return;

		// Everything below is talking to a device that may already be gone. None of
		// it is worth a crash: this is background music for a scene, and the worst
		// honest outcome is silence until the next cue.
		try {
			had.stop();
			if (from != null && library().orElse(null) == from) from.releaseChannel(had);
		} catch (RuntimeException gone) {
			complain(gone);
		}
	}

	/** Said once. A device that has gone would produce this on every tick otherwise. */
	private static boolean complained;

	private static void complain(RuntimeException about) {
		if (complained) return;
		complained = true;
		NpcStudio.LOGGER.warn("The sound device would not take the scene's music back: {}",
			about.toString());
	}

	/**
	 * Keeps a streaming channel fed.
	 *
	 * A streamed sound holds a few seconds at a time and has to be topped up; the
	 * game does this for its own sounds on its own tick and knows nothing about
	 * ours. Missed, the music plays for four seconds and stops, which sounds like a
	 * broken file rather than like a missing call.
	 */
	private static void pump() {
		if (channel == null) return;
		// A device that has been swapped out from under us makes every one of these
		// throw. Letting go is the whole recovery: the scene still wants this piece,
		// so the next tick finds nothing playing and starts it again on the new
		// device, which is exactly what somebody plugging in headphones expects.
		try {
			channel.updateStream();
			channel.setVolume(volume());
			// Run out rather than replaced, so the channel goes back and the piece
			// stays named. Which is what stops a finished track from starting again:
			// the next tick still recognises it as the piece that was asked for, and
			// a piece already asked for is never asked for twice. Clearing the name
			// here instead would decode the whole file, discover there is nothing
			// past the end, and do it again — silently, twenty times a second.
			if (channel.stopped()) handBack();
		} catch (RuntimeException gone) {
			complain(gone);
			channel = null;
			lentBy = null;
			wantedName = "";
			wantedFrom = Integer.MIN_VALUE;
			starting = false;
		}
	}

	private static float volume() {
		var options = Minecraft.getInstance().options;
		return options.getSoundSourceVolume(SoundSource.MUSIC)
			* options.getSoundSourceVolume(SoundSource.MASTER);
	}

	/**
	 * Opens a file and starts it, skipping the first {@code seconds}.
	 *
	 * Decoding happens on a thread of its own, because seeking into an ogg means
	 * decoding everything before the point wanted — a minute into a track is a
	 * minute of decoding, and the game must not stop for it. The channel is taken
	 * on the render thread afterwards, where the sound device expects to be spoken
	 * to.
	 */
	private static void start(String named, int from, double seconds) {
		// Named before the file is looked for, and that ordering matters: a cue
		// pointing at a file somebody has deleted would otherwise leave nothing
		// recorded as wanted, so the next tick would decide the wrong thing was
		// playing and try again — a missing file checked twenty times a second for as
		// long as the cursor sits past the cue.
		wantedName = named;
		wantedFrom = from;
		starting = false;
		int mine = ++asked;

		Path file = folder().resolve(named + SUFFIX);
		if (!Files.isRegularFile(file)) return;
		starting = true;

		Thread thread = new Thread(() -> {
			AudioStream stream = open(file, seconds);
			if (stream == null) {
				// The file would not open. The piece stays named rather than being
				// cleared, so the next tick does not try it again — and again, twenty
				// times a second, for as long as the cursor sits past the cue.
				Minecraft.getInstance().execute(() -> {
					if (mine == asked) starting = false;
				});
				return;
			}
			Minecraft.getInstance().execute(() -> {
				// Somebody may have scrubbed again while this was decoding, and the
				// answer is for a moment the cursor has already left. Dropping it is
				// right: whatever is wanted now has asked for itself.
				if (mine != asked) {
					close(stream);
					return;
				}
				starting = false;
				attach(stream);
			});
		}, "npc-studio-music");
		thread.setDaemon(true);
		thread.start();
	}

	private static AudioStream open(Path file, double seconds) {
		try {
			InputStream bytes = new BufferedInputStream(Files.newInputStream(file));
			JOrbisAudioStream stream = new JOrbisAudioStream(bytes);
			if (seconds > 0.01) skip(stream, seconds);
			return stream;
		} catch (Exception unreadable) {
			NpcStudio.LOGGER.warn("Could not play {}: {}", file, unreadable.toString());
			return null;
		}
	}

	/**
	 * Throws away the first part of a piece, because there is no other way in.
	 *
	 * Counted in samples off the stream's own format rather than in chunks, since
	 * a chunk is however much the decoder felt like handing over and two files can
	 * disagree about that.
	 */
	private static void skip(JOrbisAudioStream stream, double seconds) throws IOException {
		var format = stream.getFormat();
		long wanted = (long) (seconds * format.getSampleRate() * format.getChannels());
		long[] seen = { 0 };
		while (seen[0] < wanted) {
			boolean more = stream.readChunk(sample -> seen[0]++);
			if (!more) return;
		}
	}

	private static void attach(AudioStream stream) {
		var library = library();
		if (library.isEmpty()) {
			close(stream);
			return;
		}
		Channel taken;
		try {
			taken = library.get().acquireChannel(Library.Pool.STREAMING);
		} catch (RuntimeException refused) {
			complain(refused);
			close(stream);
			return;
		}
		if (taken == null) {
			close(stream);
			return;
		}
		// Remembered beside the channel, because giving it back to anything else is
		// the crash this whole guard is about. See {@link #lentBy}.
		lentBy = library.get();
		try {
			// Attached to the listener rather than to a place, and with no falling off
			// with distance: this is the scene's music, not a gramophone standing on
			// the deck. A camera that flies would otherwise carry it in and out of
			// earshot.
			taken.setRelative(true);
			taken.disableAttenuation();
			taken.setSelfPosition(net.minecraft.world.phys.Vec3.ZERO);
			taken.setVolume(volume());
			taken.attachBufferStream(stream);
			taken.play();
			channel = taken;
		} catch (RuntimeException gone) {
			// The device went away between being asked for a channel and being given
			// the stream. Rare and entirely possible: this runs a moment after a file
			// finished decoding on another thread.
			complain(gone);
			lentBy = null;
			close(stream);
		}
	}

	private static java.util.Optional<Library> library() {
		var manager = Minecraft.getInstance().getSoundManager();
		if (!(manager instanceof SoundEngineAccess reach)) return java.util.Optional.empty();
		var engine = reach.npcStudio$engine();
		if (!(engine instanceof SoundDeviceAccess device)) return java.util.Optional.empty();
		return java.util.Optional.ofNullable(device.npcStudio$library());
	}

	private static void close(AudioStream stream) {
		try {
			stream.close();
		} catch (IOException ignored) {
			// Nothing to be done and nothing to say: the file is going away either way.
		}
	}

	/** Whether the cursor is near enough to a cue to be at its beginning. */
	public static boolean atStart(int cue, double at) {
		return at - cue < NEAR;
	}
}
