package com.mopicmp.npcstudio.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Turning a recording into keys somebody can edit.
 *
 * <h2>The problem</h2>
 *
 * Recording writes a key every tick, because that is the only way to catch what a
 * hand did. Twenty keys a second is a faithful record and it is not an animation
 * anybody can work with: there is nothing to drag, nothing to retime, and
 * changing the middle of a movement means changing forty keys by hand. So the
 * dense record has to become a few keys with curves between them, and the whole
 * question is which few.
 *
 * <h2>Fit and check, rather than fit and hope</h2>
 *
 * The usual answer is Douglas-Peucker: drop the points that are near the straight
 * line through their neighbours. It is wrong here in a way that does not show up
 * until somebody watches the result, because the line it measures against is not
 * the line playback draws — playback draws a curve, and a curve through the kept
 * keys can leave the recording by much more than the straight line did, or by
 * much less.
 *
 * So the error is measured against the real thing. A candidate set of keys is
 * built into an actual {@link Track}, sampled at every tick of the recording, and
 * the worst disagreement is found. If it is over the tolerance, the tick where it
 * happened becomes a key and the whole thing is measured again. That terminates —
 * each round keeps one more key and the full set is exact — and what it
 * guarantees is the thing worth guaranteeing: <em>the animation that plays is
 * within the tolerance of what was recorded, everywhere</em>.
 *
 * It costs more than one pass. Not enough to matter: a minute of recording is
 * twelve hundred ticks and settles into a few dozen keys, which is a few tens of
 * thousands of samples, done once when somebody presses a button.
 */
public final class Thinning {

	/**
	 * How far the played curve may stray from the recording, by kind of channel.
	 *
	 * <h2>These are units, not measurements</h2>
	 *
	 * Said plainly because it matters which they are. Half a degree is roughly the
	 * finest turn worth authoring by hand, and a tenth of a model pixel is finer
	 * than the grid a model is drawn on — so both are "below what the format can
	 * meaningfully say" rather than "measured to be invisible". A real number needs
	 * real recordings to measure against, and there are none yet. When there are,
	 * this is the constant to revisit rather than the algorithm.
	 */
	public static final float ANGLE_TOLERANCE = 0.5f;
	public static final float PLACE_TOLERANCE = 1f / 160f;
	public static final float PLAIN_TOLERANCE = 0.01f;

	private Thinning() { }

	/** What a channel of this kind may stray by. */
	public static float toleranceFor(String channel) {
		if (Channels.isAngle(channel)) return ANGLE_TOLERANCE;
		String field = Channels.fieldOf(channel);
		// A fold is measured in degrees and is not an angle, and the difference is
		// worth the extra line. "Is it an angle" is asked by recording, and it means
		// "can it pass through a full turn and come out the other side" — a fold
		// cannot, it is clamped well short of one. "How far may it stray" is a
		// different question with the same answer: half a degree, because that is
		// what half a degree looks like whatever the number is called.
		if (field.equals(Channels.BEND)) return ANGLE_TOLERANCE;
		if (field.equals(Channels.X) || field.equals(Channels.Y) || field.equals(Channels.Z)
			|| field.startsWith("shift")) {
			return PLACE_TOLERANCE;
		}
		return PLAIN_TOLERANCE;
	}

	public static Track tidied(Track track) {
		return thinned(track, toleranceFor(track.channel()));
	}

	/**
	 * The fewest keys whose curve stays within {@code tolerance} of this one's.
	 *
	 * The ends are always kept. Not an optimisation — a movement that starts where
	 * the recording started and ends where it ended is the one thing somebody will
	 * check, and an algorithm free to move the first key is free to start the whole
	 * performance somewhere else.
	 */
	public static Track thinned(Track track, float tolerance) {
		List<Key> keys = track.keys();
		if (keys.size() <= 2 || tolerance <= 0) return track;

		TreeSet<Integer> kept = new TreeSet<>();
		kept.add(0);
		kept.add(keys.size() - 1);

		// At most one round per key it could ever keep, so a tolerance nothing can
		// meet ends with the recording itself rather than with a loop.
		for (int round = 0; round < keys.size(); round++) {
			Track candidate = track.with(chosen(keys, kept));
			int worst = -1;
			float worstBy = tolerance;
			for (int i = 1; i < keys.size() - 1; i++) {
				if (kept.contains(i)) continue;
				Key key = keys.get(i);
				float off = Math.abs(candidate.valueAt(key.at()) - key.value());
				if (off > worstBy) {
					worstBy = off;
					worst = i;
				}
			}
			if (worst < 0) return candidate;
			kept.add(worst);
		}
		return track;
	}

	private static List<Key> chosen(List<Key> keys, TreeSet<Integer> kept) {
		List<Key> few = new ArrayList<>(kept.size());
		for (int at : kept) few.add(keys.get(at));
		return few;
	}

	/**
	 * Every track of a scene, thinned by its own kind's tolerance.
	 *
	 * By kind rather than by one number, because the numbers are not comparable: a
	 * degree is a small angle and a block is an enormous distance, and a single
	 * tolerance is either useless for one or destructive for the other.
	 */
	public static Scene tidied(Scene scene) {
		List<Track> tidy = new ArrayList<>(scene.tracks().size());
		for (Track track : scene.tracks()) tidy.add(tidied(track));
		return scene.withTracks(tidy);
	}

	/** The same, for one participant — for tidying what was just recorded and no more. */
	public static Scene tidied(Scene scene, String subject) {
		List<Track> tidy = new ArrayList<>(scene.tracks().size());
		for (Track track : scene.tracks()) {
			tidy.add(track.subject().equals(subject) ? tidied(track) : track);
		}
		return scene.withTracks(tidy);
	}

	/** How many keys a scene holds, which is the number worth showing before and after. */
	public static int keyCount(Scene scene) {
		int total = 0;
		for (Track track : scene.tracks()) total += track.keys().size();
		return total;
	}
}
