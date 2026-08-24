package com.mopicmp.npcstudio.foe;

import java.util.List;

/**
 * Following a path that has already been found.
 *
 * <h2>Separate from the finding, on purpose</h2>
 *
 * Finding a way is expensive and happens rarely; walking along one happens every
 * tick. Keeping them apart is what lets the search run five times a second at
 * most while the legs move smoothly — the same trick as looking and the alarm
 * meter, for the same reason.
 *
 * It also puts the two kinds of failure in different places. Not finding a route
 * is a fact about the world; getting stuck on a fence post while following one is
 * a fact about the walking, and only the second one needs watching for.
 */
public final class Walk {

	/** How close counts as having arrived at a waypoint. */
	public static final double CLOSE_ENOUGH = 0.7;

	/** And at the destination, which is looser: nobody stands on an exact spot. */
	public static final double ARRIVED = 1.6;

	/**
	 * How long of getting nowhere counts as being stuck, in ticks.
	 *
	 * A second and a half. Long enough that a character squeezing past a doorframe
	 * is not called stuck, short enough that one wedged against a fence does not
	 * stand there pushing at it until somebody notices.
	 */
	public static final int STUCK_AFTER = 30;

	/** How little movement in a tick counts as none. */
	private static final double CREEPING = 0.01;

	private List<int[]> path = List.of();
	private int at;
	private int stuck;
	private double wasX;
	private double wasZ;

	/** Starts along a new path, or stops if it is empty. */
	public void follow(List<int[]> found) {
		path = found == null ? List.of() : found;
		at = 0;
		stuck = 0;
	}

	public void stop() {
		path = List.of();
		at = 0;
		stuck = 0;
	}

	public boolean walking() {
		return at < path.size();
	}

	public boolean stuck() {
		return stuck >= STUCK_AFTER;
	}

	/** How far along, for a readout: which waypoint out of how many. */
	public int reached() {
		return at;
	}

	public int waypoints() {
		return path.size();
	}

	/**
	 * Where to head this tick, or null when there is nowhere left to go.
	 *
	 * <h2>Why waypoints are stepped past rather than walked to</h2>
	 *
	 * A path is a list of block centres and nobody walks through block centres —
	 * they cut the corners, which is what makes walking look like walking. So a
	 * waypoint is dropped as soon as it is near enough, and the character is already
	 * heading for the next one before reaching it.
	 *
	 * More than one may be dropped in a tick. A path along a straight corridor has a
	 * waypoint every block, and a character rounding a corner into it should not
	 * spend six ticks ticking them off from a standstill.
	 */
	public double[] heading(double x, double y, double z) {
		while (at < path.size()) {
			int[] mark = path.get(at);
			double toX = mark[0] + 0.5;
			double toZ = mark[2] + 0.5;
			double flat = Math.sqrt((toX - x) * (toX - x) + (toZ - z) * (toZ - z));

			boolean last = at == path.size() - 1;
			double near = last ? ARRIVED : CLOSE_ENOUGH;
			// The height has to agree as well, or a character standing on a roof
			// counts as having arrived at the doorway underneath it.
			boolean level = Math.abs(mark[1] - y) < 1.6;
			if (flat < near && (level || !last)) {
				at++;
				continue;
			}
			return new double[] { toX, mark[1], toZ };
		}
		return null;
	}

	/**
	 * Notices that nothing is happening.
	 *
	 * Measured from where the character actually is rather than from what it was
	 * asked to do, because those are exactly the two things that disagree when
	 * somebody is walking into a wall.
	 */
	public void moved(double x, double z) {
		double went = Math.sqrt((x - wasX) * (x - wasX) + (z - wasZ) * (z - wasZ));
		wasX = x;
		wasZ = z;
		if (!walking()) {
			stuck = 0;
			return;
		}
		stuck = went < CREEPING ? stuck + 1 : 0;
	}
}
