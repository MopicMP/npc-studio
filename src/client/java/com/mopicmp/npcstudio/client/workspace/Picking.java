package com.mopicmp.npcstudio.client.workspace;

import java.util.List;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How far along a ray a thing is met, when the thing is not the box round it.
 *
 * <h2>Why this is not one line</h2>
 *
 * Because a placed model's bounding box is mostly air. A ship is a hull, a deck
 * and a mast, and the box round all three is a slab the size of the whole
 * vessel — so a ray aimed anywhere near it enters that slab long before it
 * reaches anything solid, and "nearest box entered" answers <b>the ship</b> for
 * every click in that quarter of the sky. A character standing on the deck is
 * inside the slab and therefore always behind it, and could not be selected at
 * all.
 *
 * That is not a new discovery about the shape. The object already keeps a box
 * per box of the model, worked out for exactly this reason on the collision
 * side, where the same lie was people standing on the air between the hull and
 * the mast. Picking simply never asked for them.
 *
 * So it asks. The parts are what somebody clicking can see, so the parts are
 * what a click is measured against, and a character in front of a mast is in
 * front of it.
 *
 * <h2>The grace, and who gets it</h2>
 *
 * A box may be given a little slack so that a thin thing can be hit without
 * taking three tries — a character is narrower than it looks and clicking one
 * from across a room is otherwise a small ordeal. Something built out of parts
 * gets none: it is as big as it looks, its surface is where it appears to be,
 * and slack on two hundred boxes would put a quarter-block halo round every
 * plank of a deck and win back all the clicks this was written to stop it
 * winning.
 */
public final class Picking {

	private Picking() { }

	/**
	 * How far along the ray this thing is first met, squared, or {@code NaN}.
	 *
	 * @param parts the boxes it is really made of, or null or empty when nobody
	 *              has worked them out — in which case the whole box answers, as
	 *              it always did
	 * @param whole the single box round it
	 * @param grace how much slack the whole box is given; parts are given none
	 */
	public static double reach(List<AABB> parts, AABB whole, double grace, Vec3 from, Vec3 to) {
		if (parts != null && !parts.isEmpty()) {
			double best = Double.NaN;
			for (AABB part : parts) {
				double away = meets(part, from, to);
				if (Double.isNaN(away)) continue;
				if (Double.isNaN(best) || away < best) best = away;
			}
			return best;
		}
		if (whole == null) return Double.NaN;
		return meets(grace > 0 ? whole.inflate(grace) : whole, from, to);
	}

	/** Whether the ray meets a thing at all. */
	public static boolean meetsAny(List<AABB> parts, AABB whole, double grace, Vec3 from, Vec3 to) {
		return !Double.isNaN(reach(parts, whole, grace, from, to));
	}

	private static double meets(AABB box, Vec3 from, Vec3 to) {
		var clip = box.clip(from, to);
		return clip.isEmpty() ? Double.NaN : from.distanceToSqr(clip.get());
	}
}
