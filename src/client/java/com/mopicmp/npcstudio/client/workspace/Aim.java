package com.mopicmp.npcstudio.client.workspace;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the player is looking at, for the ring that opens without the workspace.
 *
 * <h2>Why this exists beside the viewport's own picking</h2>
 *
 * The viewport picks along a ray built from a pixel of a panel, because that is
 * what a mouse over a rendered scene means. In ordinary play there is no panel and
 * no cursor: there is a crosshair, and the ray is the player's own line of sight.
 * The two agree about everything after the ray — the same slack on a plain box,
 * the same rule that a built model is picked against its parts rather than the
 * slab around it — because disagreeing would mean pointing at the same character
 * two different ways depending on which window is open.
 *
 * <h2>Why not the game's own hit result</h2>
 *
 * {@code Minecraft.hitResult} stops at the player's reach, which is about five
 * blocks. Placing a named point is done by looking across a courtyard at the
 * doorway it belongs on, so a reach that stops at arm's length would make the
 * whole gesture useless exactly where it is most wanted.
 */
public final class Aim {

	private Aim() { }

	/**
	 * As far as the crosshair carries at a piece of ground.
	 *
	 * Long, and that length is the point: a place is put on a doorway by looking
	 * across the courtyard at it, so a reach that stopped at arm's length would make
	 * the gesture useless exactly where it is most wanted.
	 */
	private static final double REACH = 96;

	/**
	 * And as far as it carries at a character, which is not ours to choose.
	 *
	 * Everything offered for a character is something the server has to be asked to
	 * do, so pointing further than the server will act is a menu whose every entry is
	 * refused. The two used to disagree — this reached ninety-six and the server
	 * stopped at sixty-four — and the band between them was a ring that opened, looked
	 * right, and did nothing.
	 *
	 * The server's bound is the definition, because the client is the half that can be
	 * replaced by anything at all. It is now larger than this reach, so nothing here
	 * is cut short; the cap stays because the agreement is the point, not the number,
	 * and it should go on holding if either side moves.
	 */
	private static final double REACH_AT_SOMEBODY =
		Math.sqrt(com.mopicmp.npcstudio.bench.Bench.RANGE_SQUARED);

	/** The slack a plain bounding box gets, the viewport's own number. */
	private static final double GRACE = 0.15;

	/** What the crosshair is on, in the same order a click in the viewport uses. */
	public static Under under() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return Under.NOTHING;

		Vec3 from = client.player.getEyePosition();
		Vec3 to = from.add(client.player.getViewVector(1f).scale(REACH));

		// A block first, so that a character standing behind a wall is not picked
		// through it. The viewport does the opposite — it has no player and its camera
		// is meant to see past walls — and the difference is deliberate: in ordinary
		// play the world is solid.
		var block = client.level.clip(new ClipContext(from, to,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
		if (block.getType() == HitResult.Type.BLOCK) to = block.getLocation();

		Entity hit = pick(client, from, to);
		if (hit instanceof com.mopicmp.npcstudio.entity.SceneCamera) return Under.camera(hit);
		if (hit instanceof com.mopicmp.npcstudio.entity.ModelObject) return Under.object(hit);
		if (hit != null) return Under.character(hit);

		if (block.getType() == HitResult.Type.BLOCK) {
			return Under.ground(block.getBlockPos(), block.getLocation());
		}
		return Under.NOTHING;
	}

	/**
	 * The ground the crosshair is on, with nothing else considered.
	 *
	 * <h2>Why there is a second way of pointing at all</h2>
	 *
	 * Because {@link #under} answers "what is this about", and creatures win that
	 * question by design — a ring opened on a character is about the character, and
	 * the floor she is standing on is not what anybody meant.
	 *
	 * Drawing a route is the opposite question and the same gesture. Every point of
	 * it is a piece of floor, and the first one anybody wants is the floor the
	 * character is standing on — which was the one square of ground in the world
	 * that could not be pointed at, because she was in the way. Reported exactly
	 * that way: <em>"I wanted to put the first mark on the spawn, and could not,
	 * because of the NPC standing on it."</em>
	 *
	 * Anything with a body is skipped, not only ours: a dropped item, a boat, a cow
	 * asleep on the path are all equally not the floor.
	 */
	public static Under ground() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return Under.NOTHING;

		Vec3 from = client.player.getEyePosition();
		Vec3 to = from.add(client.player.getViewVector(1f).scale(REACH));

		// Outlines rather than colliders, so that a carpet, a slab, a pressure plate
		// and a patch of snow are floors like any other. A route drawn across a rug
		// stopped at the rug otherwise, and the reason would have been invisible.
		var block = client.level.clip(new ClipContext(from, to,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
		if (block.getType() != HitResult.Type.BLOCK) return Under.NOTHING;
		return Under.ground(block.getBlockPos(), block.getLocation());
	}

	private static Entity pick(Minecraft client, Vec3 from, Vec3 to) {
		// Never past what the server will act on, however far the block behind is.
		double carries = Math.min(from.distanceTo(to), REACH_AT_SOMEBODY);
		if (carries <= 0) return null;
		to = from.add(to.subtract(from).normalize().scale(carries));

		Entity nearest = null;
		double nearestAway = Double.MAX_VALUE;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity == client.player) continue;
			double away = Picking.reach(
				entity instanceof com.mopicmp.npcstudio.entity.ModelObject object
					? object.collidersInWorld() : null,
				entity.getBoundingBox(), GRACE, from, to);
			if (Double.isNaN(away) || away >= nearestAway) continue;
			nearestAway = away;
			nearest = entity;
		}
		return nearest;
	}
}
