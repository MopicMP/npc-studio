package com.mopicmp.npcstudio.foe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

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
		/** Whether a body can occupy this block: air, grass, an open door. */
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

	/** And how many blocks may be looked at before giving up. */
	public static final int LOOKED_AT = 3000;

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

	private record Step(int x, int y, int z, double gone) { }

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
		if (fromX == toX && fromY == toY && fromZ == toZ) return List.of();

		Map<Long, Double> best = new HashMap<>();
		Map<Long, long[]> cameFrom = new HashMap<>();
		PriorityQueue<Step> waiting = new PriorityQueue<>((one, other) ->
			Double.compare(one.gone(), other.gone()));

		waiting.add(new Step(fromX, fromY, fromZ, 0));
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
						offer(ground, best, cameFrom, waiting, here,
							here.x(), y, here.z(), 1 + 0.4, furthest);
					}

					for (int dy = CLIMBS; dy >= -DROPS; dy--) {
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
						offer(ground, best, cameFrom, waiting, here, x, y, z,
							flat + Math.abs(dy) * 0.4, furthest);
						// The first height that works is the one taken. Falling is
						// preferred to climbing only because the loop counts down, which
						// is arbitrary and does not matter: the cost decides, not this.
						break;
					}
				}
			}
		}
		return List.of();
	}

	/** Puts one candidate step into the search, if it is worth having. */
	private static void offer(Ground ground, Map<Long, Double> best,
			Map<Long, long[]> cameFrom, PriorityQueue<Step> waiting, Step here,
			int x, int y, int z, double costs, double furthest) {
		double gone = here.gone() + costs;
		if (gone > furthest) return;
		long at = key(x, y, z);
		Double was = best.get(at);
		if (was != null && was <= gone) return;
		best.put(at, gone);
		cameFrom.put(at, new long[] { here.x(), here.y(), here.z() });
		waiting.add(new Step(x, y, z, gone));
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

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
