package com.mopicmp.npcstudio.foe;

/**
 * Working the thing in her hands: when to start, how long to hold, when to let go.
 *
 * <h2>Why this is not an animation</h2>
 *
 * Because in this game drawing a bow is not a pose, it is a state — the entity
 * is <em>using</em> the item, the item counts the ticks, and the drawn bow that
 * appears on screen is a consequence of that rather than a picture laid over it.
 * Every mod that animated an NPC drawing a bow got a character miming, and the
 * arrow, when it came, came out of the chest.
 *
 * So there is nothing here about arms. There is only the timing, and the timing
 * is the whole of it: {@code startUsingItem}, wait, {@code releaseUsingItem}.
 *
 * <h2>Why the count is not kept here</h2>
 *
 * It is kept by the entity, which is asked for it every tick. Two counters for
 * one fact drift apart the moment anything unusual happens — a weapon knocked
 * out of a hand, a chunk unloading mid-draw — and the disagreement shows up as
 * a shot with the wrong power, which is invisible until somebody wonders why
 * the arrows fall short.
 */
public final class Draw {

	/** A full bow draw, in ticks. Vanilla's number, and a fair default for anything held. */
	public static final int FULLY = 20;

	/** Below this a loose is a fumble: the arrow drops short and is wasted. */
	public static final int LEAST = 4;

	/**
	 * Between one shot and the next.
	 *
	 * Not because the game needs it — the draw already takes a second — but
	 * because without it a character ordered to fire twice does so in the same
	 * tick, and two arrows leaving one bow at once reads as a bug even when the
	 * damage is right.
	 */
	public static final int BETWEEN = 6;

	/** What to do with the weapon this tick. */
	public enum Order {
		/** Carry on. */
		NOTHING,
		/** Begin using the item in hand. */
		START,
		/** Let it go, and let the item make the shot. */
		LOOSE,
		/** Stop using it without shooting — the order was withdrawn. */
		LET_GO
	}

	/** How long the ordered shot wants to be drawn, or -1 when none is ordered. */
	private int wanted = -1;

	/** Ticks left before another shot may be started. */
	private int cooling;

	/** Orders a shot, drawn for this long before it is loosed. */
	public void pull(int forTicks) {
		wanted = Math.max(LEAST, forTicks);
	}

	/** Withdraws the order, whether or not the draw had started. */
	public void ease() {
		wanted = -1;
	}

	public boolean pulling() {
		return wanted >= 0;
	}

	public int wanted() {
		return wanted;
	}

	public int cooling() {
		return cooling;
	}

	/**
	 * One tick of it.
	 *
	 * @param drawnFor how many ticks the item has been in use, or -1 if it is not
	 *                 being used — which is the entity's own count, not ours
	 */
	public Order tick(int drawnFor) {
		if (cooling > 0) cooling--;

		if (wanted < 0) {
			// Nothing ordered. If she is still holding a draw from a withdrawn order,
			// she lowers the bow rather than loosing at nothing.
			return drawnFor >= 0 ? Order.LET_GO : Order.NOTHING;
		}
		if (drawnFor < 0) {
			// Ordered but not started. The cooldown is checked here rather than at the
			// order, so that "fire" given during a cooldown is honoured a moment later
			// instead of being thrown away — which is what a person would do.
			return cooling > 0 ? Order.NOTHING : Order.START;
		}
		if (drawnFor < wanted) return Order.NOTHING;

		// One shot per order. Holding the trigger is a different thing and belongs to
		// whoever is giving the orders, not here.
		wanted = -1;
		cooling = BETWEEN;
		return Order.LOOSE;
	}

	// ------------------------------------------------------------------ the power

	/**
	 * What a draw of this length is worth, nought to one.
	 *
	 * <h2>Why vanilla's curve is written out again here</h2>
	 *
	 * Because the question this answers — <em>how long should she hold it?</em> —
	 * is asked before the shot, by whoever is deciding, and that decision has to be
	 * workable and testable without a running game. The shot itself never uses
	 * this: it asks the item, which is the only thing entitled to say.
	 *
	 * Mirrors {@code BowItem.getPowerForTime}. If it ever stops matching, what
	 * suffers is the choice of when to loose, not the arrow.
	 */
	public static float powerOf(int drawnFor) {
		if (drawnFor <= 0) return 0f;
		float f = drawnFor / (float) FULLY;
		f = (f * f + f * 2f) / 3f;
		return Math.min(f, 1f);
	}

	/**
	 * How long to hold for a shot worth at least this much.
	 *
	 * Counted up rather than solved, because the curve is short and the answer is
	 * wanted in whole ticks anyway — there is no such thing as holding for 16.4.
	 */
	public static int longEnoughFor(float power) {
		for (int held = LEAST; held < FULLY; held++) {
			if (powerOf(held) >= power) return held;
		}
		return FULLY;
	}
}
