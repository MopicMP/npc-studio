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
		return heading(x, y, z, ARRIVED);
	}

	/**
	 * The same, told how near the end counts as the end.
	 *
	 * <h2>Because a block and a half is an answer about doorways</h2>
	 *
	 * {@link #ARRIVED} is right for going somewhere: nobody stands on an exact
	 * spot, and stopping a stride short of a doorway is what a person does. It is
	 * wrong for closing on somebody, because a blow reaches about a block — so the
	 * walk called itself finished while she was still out of range, the graph asked
	 * again, and the walk finished again on the same tick. Two fighters stood a
	 * metre apart doing that to each other for ever.
	 *
	 * Passed in rather than decided here, because how near is near enough is a fact
	 * about what she means to do when she gets there, and walking does not know
	 * what that is.
	 */
	public double[] heading(double x, double y, double z, double arrived) {
		while (at < path.size()) {
			int[] mark = path.get(at);
			double toX = mark[0] + 0.5;
			double toZ = mark[2] + 0.5;
			double flat = Math.sqrt((toX - x) * (toX - x) + (toZ - z) * (toZ - z));

			boolean last = at == path.size() - 1;
			double near = last ? arrived : CLOSE_ENOUGH;
			// The height has to agree as well, or a character standing on a roof
			// counts as having arrived at the doorway underneath it.
			boolean level = Math.abs(mark[1] - y) < 1.6;
			if (flat < near && (level || !last)) {
				at++;
				continue;
			}
			// Or if it is simply behind us. Being near a waypoint is not the only way
			// to be done with it — a character shoved past one, or one who cut a
			// corner wide, would otherwise turn round and walk back to it, and then
			// forward again. That is half of what "runs, stops, runs, stops" was.
			if (!last && closer(path.get(at + 1), x, z) < flat) {
				at++;
				continue;
			}
			return new double[] { toX, mark[1], toZ };
		}
		return null;
	}

	/** How far a waypoint is, flat, from a point. */
	private static double closer(int[] mark, double x, double z) {
		double dx = mark[0] + 0.5 - x;
		double dz = mark[2] + 0.5 - z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	// ---------------------------------------------------------------- the pace

	/**
	 * How fast she is going, nought to one, easing towards how fast she wants to.
	 *
	 * <h2>Why a body cannot change speed in one tick</h2>
	 *
	 * It was reported as too sharp a change from standing to running, and there are
	 * two separate things wrong with that and they need separate fixes. This is the
	 * first: mass. Nothing with legs goes from nought to a run in a twentieth of a
	 * second, and a character that does reads as a puppet being dragged rather than
	 * a person deciding to move.
	 *
	 * The second is that there was no walk. Standing and running were the only two
	 * states, so any journey at all was a sprint — see {@link #WANDERING}.
	 *
	 * Slowing is quicker than speeding up, because stopping is: you can plant your
	 * feet a good deal faster than you can get going.
	 */
	private float pace;

	/** How much of full pace can be gained or lost in a tick. */
	private static final float QUICKENS = 0.045f;
	private static final float SLOWS = 0.09f;

	/** An unhurried walk, which is what going to look at something is. */
	public static final float WANDERING = 0.45f;

	/** And a run, for when whatever it was cannot wait. */
	public static final float HURRYING = 1f;

	/**
	 * Eases the pace towards what is wanted and hands back where it got to.
	 *
	 * Called every tick whether walking or not, because coming to a stop is a
	 * movement too and a character who stops dead is as wrong as one who starts
	 * dead.
	 */
	public float pacing(float wanted) {
		float towards = Math.clamp(wanted, 0f, 1f);
		float most = towards > pace ? QUICKENS : SLOWS;
		pace += Math.clamp(towards - pace, -most, most);
		if (pace < 0.02f) pace = 0;
		return pace;
	}

	public float pace() {
		return pace;
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
