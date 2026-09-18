package com.mopicmp.npcstudio.foe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finding a way to walk somewhere.
 *
 * <h2>Why this is ours and not the game's</h2>
 *
 * Not by choice. Vanilla pathfinding wants a {@code Mob} at every level, and it
 * was checked rather than assumed:
 *
 * <pre>
 * PathNavigation(Mob, Level)
 * PathFinder.findPath(PathNavigationRegion, Mob, Set&lt;BlockPos&gt;, float, int, float)
 * NodeEvaluator.prepare(PathNavigationRegion, Mob)
 * </pre>
 *
 * Our characters are {@code Avatar}s, because that is what gives them a player's
 * shape, skin, arms and model layers — and the entire client renderer hangs off
 * that. Re-parenting them to {@code Mob} would buy pathfinding at the cost of
 * risking everything that already works, including the animation that shipped.
 *
 * <h2>And it was most of the way written already</h2>
 *
 * {@link Airways} is a Dijkstra search over blocks with a budget and an interface
 * so it can be asked about a maze in a test. It was written to carry sound round
 * corners. The machinery is the same; the difference between "where can a sound
 * go" and "where can somebody walk" is the rules of a step — feet need something
 * under them, a body needs room above, and a person can climb one block and drop
 * several.
 *
 * So this is not a pathfinder written from nothing. It is that one, given legs.
 */
public final class Ways {

	/**
	 * What the world looks like to somebody's feet.
	 *
	 * Two questions rather than one, because "can I be here" and "can I stand on
	 * this" are different and a block can answer yes to both — a ladder, a slab — or
	 * to neither.
	 */
	public interface Ground {
		/**
		 * Whether a body can occupy this block: air, grass, an open door — and
		 * anything low enough to walk onto rather than into.
		 *
		 * <h2>The half of this that was missing, and what it cost</h2>
		 *
		 * It used to mean "nothing here at all", and a carpet is something. So a
		 * carpet, a layer of snow, a pressure plate — anything an inch high — was a
		 * wall, and the only place left to stand was the block above it. The route
		 * then said "stand at 65" while the body stood at 64.06, and the walking
		 * compared the two, found nearly a whole block of climb, and jumped. Every
		 * tick, for as long as she was heading that way.
		 *
		 * Reported three times as "he jumps all the time, and in great quantities",
		 * and I looked in the walking twice before looking here.
		 *
		 * What decides is her own step: a body walks onto anything lower than the
		 * step it can take, and into nothing higher. That is not a rule we are
		 * choosing — it is what a step height is — so the number comes from the
		 * character rather than from this file.
		 */
		boolean clear(int x, int y, int z);

		/** Whether feet can rest on top of this block. */
		boolean solid(int x, int y, int z);

		/**
		 * Whether this block holds somebody up without being underfoot.
		 *
		 * Water and ladders, and they are one question rather than two because they
		 * are the same question: is there something here that means I do not fall.
		 * Both were missing, and both were reported the same way — she cannot swim,
		 * and she cannot climb a ladder or scaffolding.
		 *
		 * Neither is a special case at the walking end. A route through water is a
		 * route; a route up a ladder is a column of blocks each one step above the
		 * last, which the search already knows how to do. All that was wrong is that
		 * neither counted as somewhere you could be.
		 */
		default boolean holdsUp(int x, int y, int z) {
			return false;
		}
	}

	/** How far a walk may be before it is not worth taking, in blocks of path. */
	public static final double FURTHEST = 64;

	/**
	 * And how many blocks may be looked at before giving up.
	 *
	 * <h2>What this number used to mean, and why it was the fault</h2>
	 *
	 * The search had no idea where it was going. It came off the queue by how far it
	 * had walked and nothing else, so it spread out in a disc from the character in
	 * every direction at once, and this budget was a radius: about thirty blocks of
	 * open ground, less with anything to climb, and then it gave up and reported that
	 * there was no way.
	 *
	 * That is what "half the time it walks the route and half the time it does not"
	 * looks like from the outside. Nothing about the route decided it — the distance
	 * did, and the two legs either side of thirty blocks behaved differently for a
	 * reason nothing anywhere said out loud.
	 *
	 * With the search pointed at the target — see {@code left} — the same budget goes
	 * several times further, because it walks down the corridor instead of filling
	 * every room off it. The number is unchanged on purpose: what changed is how much
	 * ground it buys.
	 */
	public static final int LOOKED_AT = 3000;

	/**
	 * What a block she has already been stopped by costs, over and above walking it.
	 *
	 * A price, not a wall. She has to be able to come back to it — sometimes the thing
	 * that stopped her is the only door there is, and a search forbidden to use it
	 * would report no way at all where a moment's patience would do. Priced this high,
	 * anything else within about ten blocks wins, which is what going round means.
	 */
	public static final double SHUNNED = 12;

	/** How far up a step may go. One block, because that is what a person climbs. */
	public static final int CLIMBS = 1;

	/**
	 * How far down one may go in a single step.
	 *
	 * Three, which is the height vanilla considers safe to jump from. Further and a
	 * character would cheerfully walk off a cliff to investigate a noise, arriving
	 * dead, which is a poor sort of investigation.
	 */
	public static final int DROPS = 3;

	private Ways() { }

	/**
	 * The heights a step is tried at, in the order that decides which is taken.
	 *
	 * <h2>Why the order matters now and did not before</h2>
	 *
	 * Only one height is ever offered per direction — the first that works, and then
	 * the loop below breaks — so this list, and not the cost, chooses.
	 *
	 * It used not to matter, and I reordered it once anyway, on a theory about
	 * jumping, and the test I wrote for the change passed against the old code as
	 * well. The reason was that no two heights could ever both work: standing level
	 * needed the block at walking height to be clear, and standing one higher needed
	 * that same block to be solid, and no block was both.
	 *
	 * A carpet is now both. Since {@link Ground#clear} began letting a body walk onto
	 * anything shorter than its own step, a rug is somewhere you can stand and also
	 * something you can stand on — so both heights work at once, and which is taken is
	 * this list. Level, then the one block a person climbs, then the drops.
	 *
	 * So the change I made for the wrong reason is the change this needs for the right
	 * one, and it is here on the second telling rather than the first.
	 */
	private static final int[] HEIGHTS = heights();

	private static int[] heights() {
		int[] order = new int[1 + CLIMBS + DROPS];
		int at = 0;
		order[at++] = 0;
		for (int up = 1; up <= CLIMBS; up++) order[at++] = up;
		for (int down = 1; down <= DROPS; down++) order[at++] = -down;
		return order;
	}

	/**
	 * One place the search is standing in, and the two numbers that order the queue.
	 *
	 * @param gone   what it cost to get here, which is the truth and is what the path
	 *               is finally measured by
	 * @param guess  that plus the distance still to go, which is only the order things
	 *               come off the queue in. It never enters a cost and never reaches
	 *               the path.
	 */
	private record Step(int x, int y, int z, double gone, double guess) { }

	/**
	 * How far is left, as the crow flies.
	 *
	 * <h2>Why this is allowed to be used at all</h2>
	 *
	 * Because it never overestimates, and that is the whole of what a search of this
	 * kind needs to keep finding the shortest way. The cheapest step across one block
	 * costs one and covers a distance of one; the cheapest diagonal costs 1.4142 and
	 * covers exactly that; a climb costs 1.4 and covers one. So no straight line
	 * between two points is ever dearer than the distance between them, and a search
	 * ordered by "gone so far, plus this" cannot be talked past the shortest route.
	 *
	 * If it were allowed to overestimate, the paths would quietly become slightly
	 * wrong — not broken, just a little longer than they should be, in a way nobody
	 * would ever report and nobody could see.
	 */
	private static double left(int x, int y, int z, int toX, int toY, int toZ) {
		double dx = toX - x;
		double dy = toY - y;
		double dz = toZ - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Whether somebody could be here with their feet in this block. */
	public static boolean standable(Ground ground, int x, int y, int z) {
		if (!ground.clear(x, y, z) || !ground.clear(x, y + 1, z)) return false;
		// Something underfoot, or something holding you up where you are: a floor, or
		// water to float in, or a ladder to hang from.
		return ground.solid(x, y - 1, z) || ground.holdsUp(x, y, z);
	}

	/**
	 * The way from here to there, or an empty list when there is none.
	 *
	 * The list starts at the first place to walk to and ends at the destination; the
	 * starting block is not in it, since a character is already standing on it.
	 */
	public static List<int[]> to(Ground ground, int fromX, int fromY, int fromZ,
			int toX, int toY, int toZ, double furthest, int budget) {
		return to(ground, fromX, fromY, fromZ, toX, toY, toZ, furthest, budget, Set.of());
	}

	/**
	 * The same, told which blocks she has already been stopped by.
	 *
	 * Those are made expensive rather than impassable — see {@link #SHUNNED}. A second
	 * search that simply repeats the first is no use to anybody: the whole reason for
	 * asking again is that the first answer did not work on the ground, and an answer
	 * that does not know why is the same answer.
	 */
	public static List<int[]> to(Ground ground, int fromX, int fromY, int fromZ,
			int toX, int toY, int toZ, double furthest, int budget, Set<Long> shun) {
		if (fromX == toX && fromY == toY && fromZ == toZ) return List.of();

		Map<Long, Double> best = new HashMap<>();
		Map<Long, long[]> cameFrom = new HashMap<>();
		// Ordered by what is spent plus what is left, rather than by what is spent. It
		// was the second, and the second is a search with its eyes shut: it walks out
		// in every direction equally and spends its whole budget on ground behind and
		// beside the character before it has looked at the ground in front.
		PriorityQueue<Step> waiting = new PriorityQueue<>((one, other) ->
			Double.compare(one.guess(), other.guess()));

		waiting.add(new Step(fromX, fromY, fromZ, 0,
			left(fromX, fromY, fromZ, toX, toY, toZ)));
		best.put(key(fromX, fromY, fromZ), 0d);
		int looked = 0;

		while (!waiting.isEmpty()) {
			Step here = waiting.poll();
			if (++looked > budget) return List.of();

			Double already = best.get(key(here.x(), here.y(), here.z()));
			if (already != null && already < here.gone()) continue;
			if (here.x() == toX && here.y() == toY && here.z() == toZ) {
				return unwind(cameFrom, here, fromX, fromY, fromZ);
			}

			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					// No cutting corners. A diagonal between two walls is a step through
					// the join between them, and a character taking it walks through
					// masonry — which looks like a bug because it is one.
					if (dx != 0 && dz != 0
						&& !(ground.clear(here.x() + dx, here.y(), here.z())
							&& ground.clear(here.x(), here.y(), here.z() + dz))) {
						continue;
					}

					// Straight up and straight down, which only a ladder or water
					// allows. Tried before the sideways steps so that a column is
					// climbed rather than walked round.
					for (int dy : new int[] { 1, -1 }) {
						if (dx != -1 || dz != -1) break;
						int y = here.y() + dy;
						if (!ground.holdsUp(here.x(), here.y(), here.z())
							&& !ground.holdsUp(here.x(), y, here.z())) {
							break;
						}
						if (!standable(ground, here.x(), y, here.z())) continue;
						offer(best, cameFrom, waiting, here,
							here.x(), y, here.z(), 1 + 0.4, furthest, toX, toY, toZ, shun);
					}

					for (int dy : HEIGHTS) {
						int x = here.x() + dx;
						int y = here.y() + dy;
						int z = here.z() + dz;
						if (!standable(ground, x, y, z)) continue;
						// Falling is only falling out of the air. Somebody in water or on
						// a ladder steps down one at a time and does not plummet.
						if (dy < -1 && (ground.holdsUp(here.x(), here.y(), here.z())
							|| ground.holdsUp(x, y, z))) {
							continue;
						}
						// Climbing needs headroom above where you started, or you are
						// standing up into a ceiling.
						if (dy > 0 && !ground.clear(here.x(), here.y() + 2, here.z())) continue;

						double flat = dx != 0 && dz != 0 ? 1.4142135623730951 : 1;
						// Climbing and dropping cost a little more than walking, so that
						// a route along the flat is preferred to one over the furniture.
						offer(best, cameFrom, waiting, here, x, y, z,
							flat + Math.abs(dy) * 0.4, furthest, toX, toY, toZ, shun);
						// The first height that works is the one taken, which is why the
						// order they are tried in is a list with a note on it.
						break;
					}
				}
			}
		}
		return List.of();
	}

	/** Puts one candidate step into the search, if it is worth having. */
	private static void offer(Map<Long, Double> best,
			Map<Long, long[]> cameFrom, PriorityQueue<Step> waiting, Step here,
			int x, int y, int z, double costs, double furthest,
			int toX, int toY, int toZ, Set<Long> shun) {
		long at = key(x, y, z);
		double gone = here.gone() + costs + (shun.contains(at) ? SHUNNED : 0);
		// Measured against what it cost, not against what it is guessed to cost. The
		// guess orders the queue and does nothing else; letting it into this comparison
		// would make how far she is willing to walk depend on which way she is facing.
		if (gone > furthest) return;
		Double was = best.get(at);
		if (was != null && was <= gone) return;
		best.put(at, gone);
		cameFrom.put(at, new long[] { here.x(), here.y(), here.z() });
		waiting.add(new Step(x, y, z, gone, gone + left(x, y, z, toX, toY, toZ)));
	}

	/** The same with the ordinary limits. */
	public static List<int[]> to(Ground ground, int fromX, int fromY, int fromZ,
			int toX, int toY, int toZ) {
		return to(ground, fromX, fromY, fromZ, toX, toY, toZ, FURTHEST, LOOKED_AT);
	}

	private static List<int[]> unwind(Map<Long, long[]> cameFrom, Step end,
			int fromX, int fromY, int fromZ) {
		List<int[]> backwards = new ArrayList<>();
		int x = end.x();
		int y = end.y();
		int z = end.z();
		while (x != fromX || y != fromY || z != fromZ) {
			backwards.add(new int[] { x, y, z });
			long[] before = cameFrom.get(key(x, y, z));
			if (before == null) break;
			x = (int) before[0];
			y = (int) before[1];
			z = (int) before[2];
		}
		List<int[]> forwards = new ArrayList<>(backwards.size());
		for (int i = backwards.size() - 1; i >= 0; i--) forwards.add(backwards.get(i));
		return List.copyOf(forwards);
	}

	/** The name a block goes by in the search, and so in a set of blocks to avoid. */
	public static long named(int x, int y, int z) {
		return key(x, y, z);
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
