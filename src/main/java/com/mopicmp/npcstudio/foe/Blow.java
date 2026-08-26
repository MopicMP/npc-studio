package com.mopicmp.npcstudio.foe;

/**
 * One swing, as an interval rather than a moment.
 *
 * <h2>Why a blow has parts</h2>
 *
 * Because that is the whole of the difference between a fight and two figures
 * waving. Ours was a moment: the graph said strike, the damage landed in the
 * same tick, and the arm flailed independently. Nothing was placed in time, so
 * nothing could read as having weight.
 *
 * Epic Fight's answer, read off its jar, is one class — {@code AttackAnimation
 * .Phase} — with six numbers: {@code start, antic, preDelay, contact, recovery,
 * end}. The names differ from these; the idea does not. A swing winds up, is
 * briefly dangerous, and takes time to come back from, and a character is
 * committed to all three.
 *
 * <h2>What committed means, and why it is not a bug</h2>
 *
 * Once begun, a blow finishes. She does not walk out of it, retarget it, or
 * have it cancelled by her own graph. That costs her half a second of not
 * reacting, and buying that is the point: a blow you can call off never had any
 * weight to begin with. It is the one thing Epic Fight never compromises on.
 *
 * <h2>Nothing here knows about Minecraft</h2>
 *
 * Deliberately. Whether the blade reaches anybody is a question about the world;
 * <em>when</em> to ask it is arithmetic, and arithmetic is worth being able to
 * test without a game.
 */
public final class Blow {

	/**
	 * How long each part of a swing lasts, in ticks.
	 *
	 * @param windUp   before the blade is dangerous — the part that telegraphs
	 * @param contact  how long it stays dangerous. More than one tick because a
	 *                 sweep passes through a space rather than arriving at a point
	 * @param recovery after, still committed and no longer dangerous
	 */
	public record Shape(int windUp, int contact, int recovery) {

		public Shape {
			windUp = Math.max(0, windUp);
			contact = Math.max(1, contact);
			recovery = Math.max(0, recovery);
		}

		public int length() {
			return windUp + contact + recovery;
		}
	}

	/** Where a swing has got to. */
	public enum Phase {
		/** Not swinging. */
		READY,
		/** Winding up. Committed, harmless. */
		WIND_UP,
		/** The blade is passing through. Committed, dangerous. */
		CONTACT,
		/** Coming back. Committed, harmless. */
		RECOVERY
	}

	/**
	 * How long after a blow ends before the chain resets to its first swing.
	 *
	 * A second. Keep hitting and the swings run one into another; stop, and the
	 * next fight starts from the first blow rather than halfway through somebody
	 * else's combination.
	 */
	public static final int CHAIN_HOLDS = 20;

	private Shape shape;
	private int at = -1;

	/** Which blow of the chain the next one will be, counting from nought. */
	private int link;
	private int sinceEnded = CHAIN_HOLDS;

	/**
	 * Starts a swing, unless one is already going.
	 *
	 * @return false when she is already committed, which the caller should treat
	 *         as "not yet" rather than as a failure
	 */
	public boolean begin(Shape swing) {
		if (at >= 0) return false;
		shape = swing;
		at = 0;
		return true;
	}

	/** One tick of it, and where that leaves her. */
	public Phase tick() {
		if (at < 0) {
			// The chain only forgets while she is not swinging, so a long recovery
			// does not by itself break a combination.
			if (sinceEnded < CHAIN_HOLDS && ++sinceEnded >= CHAIN_HOLDS) link = 0;
			return Phase.READY;
		}

		Phase now = phaseAt(at);
		at++;
		if (at >= shape.length()) {
			at = -1;
			sinceEnded = 0;
			// The next blow is the next in the chain. Counted on ending rather than
			// on beginning, so that the blow now landing is the one whose animation
			// was chosen for it.
			link++;
		}
		return now;
	}

	private Phase phaseAt(int tick) {
		if (tick < shape.windUp()) return Phase.WIND_UP;
		if (tick < shape.windUp() + shape.contact()) return Phase.CONTACT;
		return Phase.RECOVERY;
	}

	/** Whether she is in the middle of a blow and may not be given other orders. */
	public boolean committed() {
		return at >= 0;
	}

	/** How far into the swing, for an animation to be held in step with it. */
	public int into() {
		return Math.max(at, 0);
	}

	/**
	 * Which blow of a chain comes next, wrapped to however many there are.
	 *
	 * The chain is what stops every blow being the same blow. Three sword swings
	 * ship with the animation pack, named 1, 2 and 3, which is not a coincidence:
	 * packs are made for combinations because fights look like combinations.
	 */
	public int link(int outOf) {
		return outOf <= 0 ? 0 : Math.floorMod(link, outOf);
	}

	/** Gives up mid-swing. Only the world may do this — her own graph may not. */
	public void drop() {
		at = -1;
		sinceEnded = CHAIN_HOLDS;
		link = 0;
	}
}
