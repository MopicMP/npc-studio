package com.mopicmp.npcstudio.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * One number over time: whose it is, which number, and the keys along it.
 *
 * <h2>Why a track is a single number rather than a pose</h2>
 *
 * A bone's rotation is three tracks, not one. That looks like extra bookkeeping
 * and it buys three things worth more than the tidiness it costs.
 *
 * The first is that thinning a recording works per number. A wheel being turned
 * by hand wobbles in one axis and is perfectly still in the other two; a pose
 * keyed as a unit has to keep a key whenever <em>any</em> of its numbers moves,
 * so the two still axes get a key every time the turning one does.
 *
 * The second is that the arithmetic in here is scalar, which means it can be
 * read, and tested, and argued about. Curve fitting over a tuple is where
 * animation code goes to become unreviewable.
 *
 * The third is that it is what the formats this will have to speak already do —
 * {@code .bbmodel} animations and Minecraft's own animation definitions both
 * keep a keyframe list per component.
 *
 * <h2>Why the channel is a string</h2>
 *
 * Because the set is not closed. Bone names are whatever somebody called them,
 * and the list of animatable things is going to grow — field of view, how loud
 * something is, how faded a line of text is. An enum would have to be edited for
 * each, and would still not cover bones. See {@link Channels} for the names that
 * are already spoken for.
 */
public record Track(String subject, String channel, List<Key> keys) {

	/**
	 * Keys arrive sorted and one to a tick, whatever they were handed as.
	 *
	 * A repair rather than an operation: the editing methods below never produce
	 * either problem, so this is here for keys read from a file somebody has been
	 * editing by hand, and for the moment a test decides to be careless. Where two
	 * share a tick the earlier one in the list is kept, because the alternative —
	 * silently preferring whichever was appended last — makes the outcome depend
	 * on how the list was built.
	 */
	public Track {
		List<Key> tidy = new ArrayList<>(keys);
		tidy.sort((one, other) -> Integer.compare(one.at(), other.at()));
		List<Key> once = new ArrayList<>(tidy.size());
		for (Key key : tidy) {
			if (!once.isEmpty() && once.get(once.size() - 1).at() == key.at()) continue;
			once.add(key);
		}
		keys = List.copyOf(once);
	}

	public static Track of(String subject, String channel) {
		return new Track(subject, channel, List.of());
	}

	/** Whether this track has anything to say. An empty one leaves its subject alone. */
	public boolean speaks() {
		return !keys.isEmpty();
	}

	public boolean is(String otherSubject, String otherChannel) {
		return subject.equals(otherSubject) && channel.equals(otherChannel);
	}

	public int indexAt(int tick) {
		for (int i = 0; i < keys.size(); i++) {
			if (keys.get(i).at() == tick) return i;
		}
		return -1;
	}

	public Key keyAt(int tick) {
		int at = indexAt(tick);
		return at < 0 ? null : keys.get(at);
	}

	public Track with(List<Key> replacement) {
		return new Track(subject, channel, replacement);
	}

	/**
	 * Puts a key down, replacing whatever was at that tick.
	 *
	 * Replacing rather than refusing, because putting a key where one already is
	 * is not a mistake — it is what happens every time somebody adjusts a pose
	 * they have already keyed, which is most of what authoring is.
	 */
	public Track with(Key key) {
		List<Key> changed = new ArrayList<>(keys);
		int at = indexAt(key.at());
		if (at < 0) changed.add(key);
		else changed.set(at, key);
		return with(changed);
	}

	public Track without(int tick) {
		int at = indexAt(tick);
		if (at < 0) return this;
		List<Key> left = new ArrayList<>(keys);
		left.remove(at);
		return with(left);
	}

	/** Drags a key along the timeline. Landing on another one replaces it. */
	public Track moved(int from, int to) {
		Key key = keyAt(from);
		if (key == null || from == to) return this;
		return without(from).with(key.moved(to));
	}

	/** Slides everything, for when a whole performance starts too early. */
	public Track shifted(int by) {
		if (by == 0) return this;
		List<Key> moved = new ArrayList<>(keys.size());
		for (Key key : keys) moved.add(key.moved(key.at() + by));
		return with(moved);
	}

	public int firstMoment() {
		return keys.isEmpty() ? 0 : keys.get(0).at();
	}

	public int lastMoment() {
		return keys.isEmpty() ? 0 : keys.get(keys.size() - 1).at();
	}

	// ------------------------------------------------------------- the sampling

	/**
	 * What this number is at a moment, which may be between two ticks.
	 *
	 * Outside the keys it holds rather than extrapolating. Extrapolating a curve
	 * past its last key is how an arm that finished a wave carries on into the
	 * floor: the data says nothing about that stretch of time, and holding is the
	 * only reading of "nothing" that cannot surprise anybody.
	 *
	 * An empty track answers zero, which is meaningless on its own — ask
	 * {@link #speaks()} first, or use {@link Scene#valueAt} and give it something
	 * to fall back on.
	 */
	public float valueAt(double time) {
		if (keys.isEmpty()) return 0;

		Key first = keys.get(0);
		if (time <= first.at()) return first.value();
		Key last = keys.get(keys.size() - 1);
		if (time >= last.at()) return last.value();

		int i = segment(time);
		Key a = keys.get(i);
		Key b = keys.get(i + 1);
		if (a.ease() == Key.Ease.HOLD) return a.value();

		double span = b.at() - a.at();
		double t = (time - a.at()) / span;
		if (a.ease() == Key.Ease.LINEAR) {
			return (float) (a.value() + (b.value() - a.value()) * t);
		}
		return smooth(i, a, b, t, span);
	}

	/** The largest index whose key is at or before this moment. */
	private int segment(double time) {
		int low = 0;
		int high = keys.size() - 1;
		while (low < high) {
			int middle = (low + high + 1) >>> 1;
			if (keys.get(middle).at() <= time) low = middle;
			else high = middle - 1;
		}
		return Math.min(low, keys.size() - 2);
	}

	/**
	 * A Catmull-Rom segment, written with tangents that know about uneven spacing.
	 *
	 * <h2>Why not the textbook version</h2>
	 *
	 * The usual form takes the tangent at a key as half the difference between its
	 * neighbours, which quietly assumes the keys are evenly spaced. They never are
	 * — a recording thinned down puts keys where the motion changed, not on a
	 * grid. Feed the uniform form three collinear keys at 0, 5 and 40 and the
	 * curve between them bulges, so a movement that is dead straight in the data
	 * comes out wandering. Dividing by the neighbours' actual distance apart
	 * instead makes a straight line exactly straight at any spacing, which is a
	 * property worth having and one a test can hold on to.
	 *
	 * At the ends there is no outer neighbour, so the key itself stands in and the
	 * tangent becomes the plain slope of the one segment there is. That is also
	 * what stops the first and last stretches from overshooting, which is where an
	 * overshoot is most likely to be a foot through the deck.
	 */
	private float smooth(int i, Key a, Key b, double t, double span) {
		double m0 = slopeAt(i) * span;
		double m1 = slopeAt(i + 1) * span;

		// A key whose left-hand neighbour holds is the start of a fresh curve: the
		// held stretch says nothing about where this one should be heading, and
		// borrowing a tangent from it makes a curve that leaves the hold at a slope
		// it never had.
		if (i > 0 && keys.get(i - 1).ease() == Key.Ease.HOLD) m0 = b.value() - a.value();

		double t2 = t * t;
		double t3 = t2 * t;
		double h00 = 2 * t3 - 3 * t2 + 1;
		double h10 = t3 - 2 * t2 + t;
		double h01 = -2 * t3 + 3 * t2;
		double h11 = t3 - t2;
		return (float) (h00 * a.value() + h10 * m0 + h01 * b.value() + h11 * m1);
	}

	/**
	 * How steeply the curve passes through a key, in value per tick.
	 *
	 * <h2>The bug this replaces, in one sentence</h2>
	 *
	 * A darkening held at six tenths from nought to ten seconds and then dropped at
	 * thirteen did not stay at six tenths: it rose to nearly sixty-five hundredths
	 * in the middle of the flat stretch, went dark and came back, for no reason
	 * visible anywhere in the scene.
	 *
	 * <h2>Why a plain Catmull-Rom curve does that</h2>
	 *
	 * Because the slope at a key was taken from its two neighbours and nothing else.
	 * At the second key the neighbours are six tenths behind and two tenths ahead,
	 * so the slope there is downwards — and a curve that must leave the first key at
	 * six tenths, arrive at the second at six tenths, and be heading downwards when
	 * it gets there has no choice but to go up in between and come back. The
	 * arithmetic is right and the answer is nonsense, which is the worst kind of
	 * bug: nothing to see in the document, only in the picture.
	 *
	 * <h2>The rule</h2>
	 *
	 * The one every animation tool arrives at, and the reason "auto clamped" is the
	 * default handle in all of them:
	 *
	 * <ul>
	 * <li>If the two segments meeting at a key go opposite ways — or either of them
	 *     is flat — the key is a peak, a trough or the corner of a plateau, and the
	 *     curve passes through it <b>level</b>. That alone fixes the reported case.</li>
	 * <li>Otherwise the slope is the neighbours' own, held to three times the
	 *     shallower of the two segments. Fritsch and Carlson's condition, and it is
	 *     what guarantees the curve never leaves the range its keys describe.</li>
	 * </ul>
	 *
	 * The cost is honest: a motion that really does want to overshoot — a bounce, a
	 * whip — has to say so with a key, rather than getting it as a side effect of
	 * where its neighbours happen to be.
	 */
	private double slopeAt(int i) {
		Key key = keys.get(i);
		Key before = i > 0 ? keys.get(i - 1) : null;
		Key after = i + 1 < keys.size() ? keys.get(i + 1) : null;
		if (before == null && after == null) return 0;

		// At an end there is only one segment, so the curve leaves along it. Which
		// makes a track of two keys exactly a straight line, as it always was.
		if (before == null) return slope(key, after);
		if (after == null) return slope(before, key);

		double left = slope(before, key);
		double right = slope(key, after);
		if (left * right <= 0) return 0;

		double both = slope(before, after);
		double limit = 3 * Math.min(Math.abs(left), Math.abs(right));
		return Math.clamp(both, -limit, limit);
	}

	/** The straight slope between two keys, in value per tick. */
	private static double slope(Key from, Key to) {
		double span = to.at() - from.at();
		return span <= 0 ? 0 : (to.value() - from.value()) / span;
	}
}
