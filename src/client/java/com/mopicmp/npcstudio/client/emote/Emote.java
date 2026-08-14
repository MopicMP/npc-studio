package com.mopicmp.npcstudio.client.emote;

import java.util.EnumMap;
import java.util.Map;

/**
 * One animation, ready to be sampled.
 *
 * Emotes in this format are stored as a flat list of moves, and the same tick
 * appears many times over — one entry carrying a head, the next carrying a
 * torso. Keeping that shape would mean scanning the whole list to find what a
 * single limb is doing at a single moment. So it is turned inside out on load:
 * a track per limb per channel, each one sorted, each one sampled on its own.
 *
 * The credit belongs to SPEmotes — see the pack's entry in the README. Nothing
 * here alters an emote; this only reads what somebody else animated.
 */
public record Emote(
		String id,
		String name,
		String author,
		String description,
		boolean loop,
		int beginTick,
		int endTick,
		int stopTick,
		int returnTick,
		Map<Bone, Map<Channel, Track>> tracks) {

	/** The parts of a body an emote can move. */
	public enum Bone { HEAD, TORSO, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG, RIGHT_ITEM, LEFT_ITEM }

	/**
	 * What can be done to a part.
	 *
	 * The three offsets are in model pixels and the three angles in radians —
	 * every file in the pack sets {@code degrees} to false, so no conversion is
	 * needed, and the values run past a full turn in places because some emotes
	 * genuinely spin.
	 *
	 * {@code BEND} is the odd one. It folds a limb at its middle, which a vanilla
	 * model part cannot do because it is a single rigid box. It is read and
	 * carried so that nothing is silently dropped, and approximated when applied.
	 */
	public enum Channel { X, Y, Z, PITCH, YAW, ROLL, BEND }

	/**
	 * One channel of one limb over time.
	 *
	 * Parallel arrays rather than a list of objects: this is sampled fifty-six
	 * times a frame per character, and the arrays keep that free of allocation.
	 */
	public record Track(int[] ticks, float[] values, Easing[] easings) {

		/**
		 * The value at a moment, interpolated.
		 *
		 * The easing belongs to the keyframe the segment starts from, not the one
		 * it arrives at. That is what makes {@code CONSTANT} mean anything: it
		 * holds the value it is written on until the next keyframe replaces it.
		 */
		public float sample(float tick) {
			if (ticks.length == 0) return 0;
			if (tick <= ticks[0]) return values[0];
			int last = ticks.length - 1;
			if (tick >= ticks[last]) return values[last];

			int low = 0;
			int high = last;
			while (low + 1 < high) {
				int middle = (low + high) >>> 1;
				if (ticks[middle] <= tick) low = middle;
				else high = middle;
			}
			int span = ticks[high] - ticks[low];
			if (span <= 0) return values[high];
			float t = (tick - ticks[low]) / span;
			return values[low] + (values[high] - values[low]) * easings[low].apply(t);
		}
	}

	/** Only the shapes this pack actually uses; anything else is read as linear. */
	public enum Easing {
		LINEAR {
			@Override public float apply(float t) { return t; }
		},
		CONSTANT {
			@Override public float apply(float t) { return 0; }
		},
		EASEINSINE {
			@Override public float apply(float t) { return 1 - (float) Math.cos(t * Math.PI / 2); }
		},
		EASEINQUAD {
			@Override public float apply(float t) { return t * t; }
		},
		EASEINOUTQUAD {
			@Override public float apply(float t) {
				return t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
			}
		};

		public abstract float apply(float t);

		public static Easing of(String name) {
			if (name == null) return LINEAR;
			try {
				return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException unknown) {
				// A shape we have not met yet is closer to a straight line than to
				// nothing, and an emote that plays slightly wrong beats one that
				// refuses to load.
				return LINEAR;
			}
		}
	}

	/**
	 * Where the playhead sits after running for this long.
	 *
	 * Looping emotes come back to {@code returnTick} rather than to the start,
	 * because the first stretch is usually the character getting into position
	 * and repeating it makes the loop stutter.
	 */
	public float timeAt(float age) {
		float tick = beginTick + age;
		if (!loop) return Math.min(tick, stopTick);
		if (tick <= endTick) return tick;
		int cycle = endTick - returnTick;
		if (cycle <= 0) return endTick;
		return returnTick + (tick - endTick) % cycle;
	}

	/** How long one pass takes, for a preview that wants to show the whole thing. */
	public int length() {
		return Math.max(1, stopTick - beginTick);
	}

	/**
	 * How much of the emote its loop actually repeats.
	 *
	 * Fifteen emotes in the pack loop a single tick at the very end, which is how
	 * an author writes "play once and hold the last pose" in a format with no way
	 * to say so. It is the right thing in play and useless in a preview, where it
	 * looks exactly like an animation that has stopped working.
	 */
	public int cycle() {
		return loop ? Math.max(0, endTick - returnTick) : 0;
	}

	/** Whether looping it repeats enough of the movement to be worth watching. */
	public boolean loopsVisibly() {
		return cycle() >= 4;
	}

	public boolean finished(float age) {
		return !loop && beginTick + age > stopTick;
	}

	/**
	 * Samples every channel of every limb at one moment.
	 *
	 * A channel the emote never mentions comes back as not-a-number rather than
	 * as zero, and the difference is the whole reason this is written out. Each
	 * keyframe in this format carries exactly one channel of one limb, so an
	 * emote that only turns an arm has no track for where that arm is. Reading
	 * the silence as zero would move the arm to the middle of the body.
	 */
	public Pose poseAt(float tick) {
		Map<Bone, float[]> pose = new EnumMap<>(Bone.class);
		for (Map.Entry<Bone, Map<Channel, Track>> limb : tracks.entrySet()) {
			float[] values = new float[Channel.values().length];
			java.util.Arrays.fill(values, Float.NaN);
			for (Map.Entry<Channel, Track> channel : limb.getValue().entrySet()) {
				values[channel.getKey().ordinal()] = channel.getValue().sample(tick);
			}
			pose.put(limb.getKey(), values);
		}
		return new Pose(pose);
	}

	/** A body at one moment: per limb, one value per channel, or nothing said. */
	public record Pose(Map<Bone, float[]> limbs) {

		/** The value, or the given one where the emote has nothing to say. */
		public float or(Bone bone, Channel channel, float otherwise) {
			float[] values = limbs.get(bone);
			if (values == null) return otherwise;
			float value = values[channel.ordinal()];
			return Float.isNaN(value) ? otherwise : value;
		}

		public boolean has(Bone bone) {
			return limbs.containsKey(bone);
		}
	}
}
