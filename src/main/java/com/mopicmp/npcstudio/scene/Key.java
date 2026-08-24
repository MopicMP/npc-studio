package com.mopicmp.npcstudio.scene;

/**
 * One number, said at one moment, and how it gets to the next one.
 *
 * <h2>Why the time is a whole tick</h2>
 *
 * Seconds as a float is the obvious choice and it is the one that goes wrong
 * quietly: a key placed at 0.3 comes back as 0.30000001, two keys that should
 * share a moment do not, and "is there already a key here" stops having an
 * answer. Ticks are what the game actually advances in, they are exact, and
 * twenty of them a second is finer than anybody places keys by hand.
 *
 * Sampling still takes a fractional time — see {@link Track#valueAt(double)} —
 * because drawing happens between ticks and frame capture happens on its own
 * clock. It is only the document that is whole.
 *
 * <h2>Why the ease belongs to the key it leaves</h2>
 *
 * A key has one outgoing rule and no incoming one. The alternative is a rule at
 * each end of every segment, which means two answers for one stretch of time and
 * a convention about which wins. This way a segment has exactly one owner: the
 * key on its left.
 */
public record Key(int at, float value, Ease ease) {

	/** How a key gets to the one after it. */
	public enum Ease {
		/** Nothing moves until the next key, and then it jumps. */
		HOLD,
		/** A straight line. */
		LINEAR,
		/**
		 * A curve that passes through both keys and leans on their neighbours.
		 *
		 * The default, because a pose held and then moved to another pose looks
		 * mechanical in a way that nothing physical does. Straight lines are still
		 * exactly straight through collinear keys, so choosing this costs nothing
		 * when the motion really is even.
		 */
		SMOOTH
	}

	public Key {
		at = Math.max(0, at);
	}

	public static Key at(int tick, float value) {
		return new Key(tick, value, Ease.SMOOTH);
	}

	public Key valued(float replacement) {
		return new Key(at, replacement, ease);
	}

	public Key eased(Ease replacement) {
		return new Key(at, value, replacement);
	}

	public Key moved(int tick) {
		return new Key(tick, value, ease);
	}
}
