package com.mopicmp.npcstudio.entity;

/**
 * A reading that is only believed once it has said the same thing for a while.
 *
 * <h2>What this is for</h2>
 *
 * How a character is moving is worked out from her speed, and speed is twitchy in a way
 * a character is not. A step down a slab is a tick or two of falling; a nudge is two
 * ticks of walking. Each of those is a real reading and none of them is a change of what
 * she is doing — but every one of them changed her animation, and a change of animation
 * starts it again from its first frame.
 *
 * Reported as a character whose animation "starts over" and "sometimes does not show".
 *
 * <h2>Why it is written as a thing rather than three fields</h2>
 *
 * Because it is a rule, not a state: a new reading is believed after so many ticks of
 * agreeing with itself, and going back to what was already believed cancels the count.
 * Written out where it is used, that rule is three fields and an if-else that has to be
 * read carefully to see that the second half is right. Written here it can be asked
 * questions, and the questions are the ones that were got wrong.
 *
 * @param <T> whatever is being read; it is compared with {@code equals}
 */
public final class Settled<T> {

	private final int after;

	private T believed;
	private T asking;
	private int askingFor;

	/**
	 * @param start what to believe before anything has been read
	 * @param after how many readings in a row a new answer needs. One means no waiting
	 *              at all, which is the honest way to switch this off.
	 */
	public Settled(T start, int after) {
		this.believed = start;
		this.asking = start;
		this.after = Math.max(1, after);
	}

	/**
	 * Takes one reading and gives back what is currently believed.
	 *
	 * <h2>The half that is easy to get wrong</h2>
	 *
	 * A reading that agrees with what is already believed does not merely fail to
	 * count — it <em>clears</em> the count. Without that, a character alternating
	 * between walking and standing every other tick would accumulate four walking
	 * readings over eight ticks and switch, which is the flicker arriving by a slower
	 * road.
	 */
	public T saw(T now) {
		if (believed.equals(now)) {
			asking = believed;
			askingFor = 0;
			return believed;
		}
		if (!asking.equals(now)) {
			asking = now;
			askingFor = 0;
		}
		if (++askingFor >= after) {
			believed = now;
			askingFor = 0;
		}
		return believed;
	}

	public T believed() {
		return believed;
	}

	/**
	 * Believes something at once, without waiting.
	 *
	 * For the cases where the answer is known rather than read — being placed, being
	 * teleported — where waiting would be waiting for evidence somebody has already
	 * given.
	 */
	public void settleOn(T now) {
		believed = now;
		asking = now;
		askingFor = 0;
	}
}
