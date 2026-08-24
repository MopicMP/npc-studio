package com.mopicmp.npcstudio.foe;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * How far a sound has to travel through open air to get from there to here.
 *
 * <h2>Why a straight line is not good enough</h2>
 *
 * Because sound goes round corners and a straight line cannot. Stand in the next
 * room with the door open and a line from you to the guard goes through the
 * wall, so he hears you faintly through two blocks of stone — when what should
 * happen is that he hears you perfectly, through the doorway, because that is
 * where the sound actually goes.
 *
 * It was reported as the line working badly, and that is exactly right. The
 * single line is not a poor approximation of the answer; it is an answer to a
 * different question. Every wall reads as an obstruction whether or not there is
 * a way round it three feet to the left.
 *
 * <h2>What the reference mod does, and why this does something else</h2>
 *
 * Sound Physics Remastered casts several lines from points offset around the
 * source and takes the best of them. That helps — an edge case at a doorframe
 * stops being a cliff — but it is still lines, and lines a metre apart cannot
 * find a route round a corner ten metres long.
 *
 * It is also solving a different problem. It is deciding how a sound should
 * <em>sound</em> to a player who can already hear it, so being roughly right
 * quickly matters more than being right. Here the question is whether a guard
 * hears you at all, which is a yes or a no somebody's stealth run depends on.
 *
 * So the sound is let loose in the air instead and allowed to spread: a search
 * outwards from the listener through everything that is not a wall, until it
 * arrives at the noise or runs out of budget. Doors, corridors, corners and
 * staircases all work with nothing written about any of them, and what comes
 * back is how far the sound really travelled — which is the number the falloff
 * wanted all along.
 *
 * <h2>Kept affordable</h2>
 *
 * This only runs when the straight line is blocked. In the open — the common
 * case by a long way — the straight line is the answer and this is never called.
 * When it does run it is bounded twice over, by how many blocks it may look at
 * and by how far the sound may have gone, so a guard in a cave system cannot
 * quietly cost more than a guard in a field.
 */
public final class Airways {

	/**
	 * Somewhere a sound can pass through.
	 *
	 * An interface rather than a level, so that the search — which is the part with
	 * the interesting mistakes in it — can be asked about a maze written down in a
	 * test instead of about a world that has to be started.
	 */
	public interface Space {
		boolean open(int x, int y, int z);
	}

	/** How far a sound may be traced before it is treated as gone. */
	public static final double FURTHEST = 40;

	/**
	 * How many blocks may be looked at before giving up.
	 *
	 * A ceiling on the cost rather than on the answer, and the two fail differently:
	 * running out of distance means the sound genuinely did not arrive, running out
	 * of budget means we stopped looking. Both come back as "not reached", because
	 * from the guard's side they are the same thing — he did not hear it.
	 */
	public static final int LOOKED_AT = 1800;

	private Airways() { }

	/** One block, and how far the sound had gone by the time it got there. */
	private record Step(int x, int y, int z, double gone) { }

	/**
	 * The length of the shortest open path between two blocks, or -1.
	 *
	 * Searched from the listener outwards rather than from the noise, which makes no
	 * difference to the answer and a great deal to the cost: several players may be
	 * making a noise at once and there is only ever one pair of ears.
	 */
	public static double reach(Space space, int fromX, int fromY, int fromZ,
			int toX, int toY, int toZ, double furthest, int budget) {
		if (fromX == toX && fromY == toY && fromZ == toZ) return 0;

		Map<Long, Double> best = new HashMap<>();
		PriorityQueue<Step> waiting = new PriorityQueue<>((one, other) ->
			Double.compare(one.gone(), other.gone()));

		waiting.add(new Step(fromX, fromY, fromZ, 0));
		best.put(key(fromX, fromY, fromZ), 0d);
		int looked = 0;

		while (!waiting.isEmpty()) {
			Step here = waiting.poll();
			if (++looked > budget) return -1;

			Double already = best.get(key(here.x(), here.y(), here.z()));
			if (already != null && already < here.gone()) continue;

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						int x = here.x() + dx;
						int y = here.y() + dy;
						int z = here.z() + dz;

						// The noise itself is arrived at whether or not its own block is
						// open — somebody standing in a doorway or wading in a slab is
						// still making a noise, and refusing to reach them would be a
						// silence with no cause anybody could see.
						boolean arrived = x == toX && y == toY && z == toZ;
						if (!arrived && !space.open(x, y, z)) continue;

						// Diagonals cost what they are worth, which is why this is a
						// proper search and not a breadth-first walk: with every step
						// counted as one, a straight open corridor running at forty-five
						// degrees comes out half again as long as it is, and a guard in
						// it goes deaf for no reason a player could ever work out.
						double gone = here.gone() + Math.sqrt(dx * dx + dy * dy + dz * dz);
						if (gone > furthest) continue;
						if (arrived) return gone;

						long at = key(x, y, z);
						Double was = best.get(at);
						if (was != null && was <= gone) continue;
						best.put(at, gone);
						waiting.add(new Step(x, y, z, gone));
					}
				}
			}
		}
		return -1;
	}

	/** The same with the ordinary limits. */
	public static double reach(Space space, int fromX, int fromY, int fromZ,
			int toX, int toY, int toZ) {
		return reach(space, fromX, fromY, fromZ, toX, toY, toZ, FURTHEST, LOOKED_AT);
	}

	/**
	 * Three coordinates in one long, for a map that would otherwise allocate.
	 *
	 * Twenty-one bits each, which is a million blocks either way and rather more
	 * than a sound is going to travel.
	 */
	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
