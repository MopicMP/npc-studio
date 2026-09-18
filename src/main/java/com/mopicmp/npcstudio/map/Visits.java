package com.mopicmp.npcstudio.map;

import java.util.List;
import java.util.Set;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.Location;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Going into a lesson and coming back out.
 *
 * <h2>The rule everything here is built from</h2>
 *
 * The author's, and it is stricter than it sounds while being much less work than it
 * sounds: leaving and coming back resets the place completely, whatever happened and
 * whatever it led to — blocks broken, documents rewritten, a conversation abandoned
 * halfway through a line.
 *
 * A place that is always rebuilt has nothing to undo. There is no rollback of the ground,
 * no repair of a half-run thread, no question about what a guest may be allowed to break.
 * Every one of those would have been real work, and the rule deletes all of them.
 *
 * <h2>The hub is not a special case in the code</h2>
 *
 * It is a location whose {@link Location#resets()} is off. Its ground goes down once and
 * is never cleared, so it is the one thing here that survives; everything else about it —
 * being somewhere you stand, having documents of its own — is what every location has.
 *
 * Written as a branch instead, the hub would be the single piece of the map nobody could
 * ask about in the same words as the rest, and the first thing anybody would want is a
 * second one.
 *
 * <h2>Why leaving cannot fail</h2>
 *
 * Because the ways of ending up in the lessons' world are not all ways somebody chose:
 * logging out inside one, a server restart, a location deleted while a guest stood in it.
 * All of those end the same way — out, at the hub or at the world's own spawn — and a
 * leave that could refuse would leave somebody stuck in a void with no ground and no
 * command that helps.
 */
public final class Visits {

	private Visits() { }

	/**
	 * Where the hub's ground stands.
	 *
	 * Clear of the row of copies, which grows from nought upwards along X and never goes
	 * negative. So any negative X is safe for ever, and this one is far enough out that
	 * a hub of any sensible size never meets the first lesson.
	 */
	public static final BlockPos HUB_AT = new BlockPos(-1024, Copies.FLOOR, 0);

	/**
	 * Where there is somewhere to stand and build, before anything has been built.
	 *
	 * <h2>Why this has to exist</h2>
	 *
	 * Because without it the way in is a circle. The hub is a location, a location lays
	 * out ground, ground is a copy taken of something somebody built — and there was
	 * nowhere to build it that was not already inside the thing being built. The first
	 * report of this was the plainest possible: "it says there is no hub, but let me in
	 * so I can start building one".
	 *
	 * <h2>Why it is not a lesson</h2>
	 *
	 * Nothing is copied here and nothing is swept away. It is a floor in an empty world,
	 * outside every copy — which also means somebody standing on it is, as far as the
	 * rest of the mod is concerned, on the map's own ground, and sees the map's own
	 * documents. That is what an author wants and a guest never sees.
	 */
	public static final BlockPos WORKSHOP_AT = new BlockPos(-4096, Copies.FLOOR, 0);

	/** How wide the floor under the workshop is, so there is room to lay something out. */
	private static final int WORKSHOP_WIDE = 32;

	/** Where somebody stands in a copy: on its floor, a step in from the low corner. */
	private static BlockPos standing(BlockPos origin) {
		return origin.offset(1, 1, 1);
	}

	/**
	 * The location that is the hub, or null when this map has not made one.
	 *
	 * The hub is the one whose ground is never rebuilt, and a map is meant to have
	 * exactly one. Two is not refusable by the validator — it only ever sees one
	 * document at a time — so it is settled here, out loud, by taking the first by name
	 * and saying that is what happened.
	 */
	public static Dialogue hub() {
		Dialogue found = null;
		for (String name : DialogueRegistry.names("")) {
			Dialogue graph = DialogueRegistry.get(name).orElse(null);
			if (graph == null || !graph.isLocation()) continue;
			if (graph.location().map(Location::resets).orElse(true)) continue;
			if (found == null) {
				found = graph;
			} else {
				NpcStudio.LOGGER.warn(
					"Two places say they are the hub, \"{}\" and \"{}\"; taking \"{}\". "
						+ "A map has one hub, and the other should be set to rebuild.",
					found.id(), graph.id(), found.id());
			}
		}
		return found;
	}

	/**
	 * Puts somebody on the workshop floor, laying it if it is not there yet.
	 *
	 * The floor is checked for rather than remembered, because it is the one thing here
	 * that nothing ever takes away: if the blocks are there, it is there. A note about it
	 * would be a second thing to keep in step with the first, for no gain.
	 *
	 * @return false only when there is no lessons' world at all
	 */
	public static boolean toWorkshop(ServerPlayer player) {
		ServerLevel lessons = Lesson.level(player.level().getServer());
		if (lessons == null) return false;

		BlockPos stand = WORKSHOP_AT.offset(WORKSHOP_WIDE / 2, 1, WORKSHOP_WIDE / 2);
		// Only when there would be nothing to stand on, and even then only into air.
		//
		// Both halves are repairs of the same report, and it was the worst kind: somebody
		// built on this floor and every entry to the world replaced their own floor with
		// stone again.
		//
		// The first version asked "is the corner block air" and took that for "is the
		// platform there". A proxy, and a bad one — a build that does not reach the corner
		// leaves it air — and having wrongly decided nothing was there, it filled all
		// thousand blocks over whatever was. Two mistakes that only destroy anything
		// together, which is why it survived being written.
		//
		// So the question is now the one that actually matters — is there somewhere to
		// stand — and the answer never takes a block away from anybody.
		if (lessons.getBlockState(stand.below()).isAir()) lay(lessons);
		send(player, lessons, stand);
		return true;
	}

	/**
	 * Fills the gaps in the workshop floor, and nothing else.
	 *
	 * Air only. Anything standing here was put here by somebody, and this has no way of
	 * telling their floor from its own — so it does not try, and never replaces.
	 */
	private static void lay(ServerLevel lessons) {
		var floor = Blocks.SMOOTH_STONE.defaultBlockState();
		for (BlockPos at : BlockPos.betweenClosed(WORKSHOP_AT,
				WORKSHOP_AT.offset(WORKSHOP_WIDE - 1, 0, WORKSHOP_WIDE - 1))) {
			if (lessons.getBlockState(at).isAir()) {
				lessons.setBlock(at, floor, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			}
		}
	}

	/**
	 * Puts somebody at the hub, laying its ground first if it is not already standing.
	 *
	 * @return false when this map has no hub, or its ground could not be laid out
	 */
	public static boolean toHub(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel lessons = Lesson.level(server);
		if (lessons == null) return false;

		Dialogue place = hub();
		if (place == null) return false;
		String region = place.location().map(Location::region).orElse("");

		Copies copies = Copies.of(lessons);
		Copies.Copy standing = copies.hubStanding();
		if (standing == null || !standing.location().equals(place.id())) {
			// Either nothing is up yet, or the hub was changed to a different document
			// since. Either way what is standing is not this, so it goes and this is
			// laid out in its place.
			if (standing != null) {
				clear(lessons, standing);
				copies.drop(standing.guest());
			}
			Vec3i size = Regions.size(lessons, region);
			if (size == null) {
				NpcStudio.LOGGER.warn("The hub \"{}\" names ground \"{}\", which this world "
					+ "has no copy of.", place.id(), region);
				return false;
			}
			if (!Regions.place(lessons, HUB_AT, region)) return false;
			Home from = home(player);
			standing = new Copies.Copy(place.id(), Lesson.HUB_KEEPER, HUB_AT, size,
				from.world(), from.at(), true);
			copies.put(standing);
		}

		send(player, lessons, standing(HUB_AT));
		return true;
	}

	/**
	 * Lays a lesson out for one person and puts them in it.
	 *
	 * Whatever they had before is cleared first, because one person is in one lesson at
	 * a time and the old ground would otherwise stand in the row with nobody in it.
	 *
	 * @return false when there is no such place, or it has no ground yet
	 */
	public static boolean enter(ServerPlayer player, String location) {
		MinecraftServer server = player.level().getServer();
		ServerLevel lessons = Lesson.level(server);
		if (lessons == null) return false;

		Dialogue place = DialogueRegistry.get(location).orElse(null);
		if (place == null || !place.isLocation()) return false;
		if (!place.location().map(Location::resets).orElse(true)) return toHub(player);

		String region = place.location().map(Location::region).orElse("");
		Vec3i size = Regions.size(lessons, region);
		if (size == null) return false;

		Copies copies = Copies.of(lessons);
		// Where they came from is taken before anything moves them, and kept from the
		// copy they are leaving if they are already inside — otherwise going from one
		// lesson to another would make "back" mean the first lesson.
		Copies.Copy had = copies.forGuest(player.getUUID());
		Home back = had != null ? new Home(had.backIn(), had.backAt()) : home(player);
		if (had != null && !had.sticks()) clear(lessons, had);
		if (had != null) copies.drop(player.getUUID());

		BlockPos origin = copies.free(size, seeing(server));
		if (!Regions.place(lessons, origin, region)) return false;
		copies.put(new Copies.Copy(place.id(), player.getUUID(), origin, size,
			back.world(), back.at(), false));

		send(player, lessons, standing(origin));
		return true;
	}

	/**
	 * Takes somebody out, and takes their copy down behind them.
	 *
	 * The hub's ground is left standing, which is what makes it the hub.
	 *
	 * @return false only when they were not in one
	 */
	public static boolean leave(ServerPlayer player) {
		ServerLevel lessons = Lesson.level(player.level().getServer());
		if (lessons == null) return false;

		Copies copies = Copies.of(lessons);
		Copies.Copy had = copies.forGuest(player.getUUID());
		Home back = had != null ? new Home(had.backIn(), had.backAt()) : home(player);
		if (had != null) {
			if (!had.sticks()) clear(lessons, had);
			copies.drop(player.getUUID());
		}

		ServerLevel to = player.level().getServer().getLevel(back.world());
		if (to == null) to = player.level().getServer().overworld();
		send(player, to, back.at());
		return had != null;
	}

	/**
	 * Somebody arrived already standing in the lessons' world.
	 *
	 * Which is a thing that happens: they logged out inside one, and copies do not
	 * survive a restart. The author's rule answers it without a second mechanism —
	 * coming back is a reset — so they are put at the hub, or failing that at the
	 * world's own spawn, which is at least somewhere with ground under it.
	 */
	public static void arrived(ServerPlayer player) {
		if (!Lesson.is(player.level())) return;
		if (toHub(player)) return;
		NpcStudio.LOGGER.info(
			"{} arrived inside the lessons' world with no hub to put them in; "
				+ "sending them where the world says to respawn.",
			player.getGameProfile().name());
		Home spawn = worldSpawn(player.level().getServer());
		ServerLevel to = player.level().getServer().getLevel(spawn.world());
		send(player, to == null ? player.level().getServer().overworld() : to, spawn.at());
	}

	/**
	 * Where the world itself says people belong.
	 *
	 * The last resort, for somebody who has to be put somewhere and whose own way back
	 * is not known — and it is the game's own answer to that question rather than a
	 * guess at coordinates, which on a map built anywhere but the origin would be a
	 * guess landing in the sea.
	 */
	private static Home worldSpawn(MinecraftServer server) {
		var respawn = server.overworld().getLevelData().getRespawnData();
		return new Home(respawn.dimension(), respawn.pos());
	}

	/** A world and a spot in it: where somebody goes when they leave. */
	private record Home(ResourceKey<Level> world, BlockPos at) { }

	/**
	 * Somebody's connection went while they were inside one.
	 *
	 * Their ground goes down with them. Not out of tidiness: it would otherwise hold a
	 * stretch of the row against nobody, and the author's rule says coming back is a
	 * reset anyway — so there is nothing standing there worth keeping.
	 *
	 * They are not moved, because there is nobody left to move. Where they come back to
	 * is answered by {@link #arrived}, which is the same answer for somebody who left
	 * this way and somebody the server restarted out from under.
	 */
	public static void parted(ServerPlayer player) {
		if (player == null || player.level().getServer() == null) return;
		ServerLevel lessons = Lesson.level(player.level().getServer());
		if (lessons == null) return;
		Copies copies = Copies.of(lessons);
		Copies.Copy had = copies.forGuest(player.getUUID());
		if (had == null || had.sticks()) return;
		clear(lessons, had);
		copies.drop(player.getUUID());
	}

	/**
	 * Takes down every copy left standing by a server that did not stop cleanly.
	 *
	 * The same sweep the walls get and for the same reason: the notes are in the save
	 * and the blocks are in the save, and nobody is inside any of them after a restart.
	 * The hub is deliberately not swept — see {@link Copies#dropEverything()}.
	 */
	public static void sweep(MinecraftServer server) {
		ServerLevel lessons = Lesson.level(server);
		if (lessons == null) return;
		Copies copies = Copies.of(lessons);
		List<Copies.Copy> going = copies.dropEverything();
		if (going.isEmpty()) return;
		NpcStudio.LOGGER.info("Taking down {} lesson(s) left standing by a world that did "
			+ "not stop cleanly.", going.size());
		for (Copies.Copy copy : going) clear(lessons, copy);
	}

	/**
	 * Takes a copy's ground away, and everything that was standing on it.
	 *
	 * The characters go with the blocks. They were laid out by the copy and belong to
	 * it, and a lesson taken down that left its cast standing in the void would leave
	 * them there for ever — nothing would ever ask about them again.
	 *
	 * Players are not among them, for the same reason the game's own capture skips
	 * them: somebody standing here is somebody to move, not something to delete.
	 */
	public static void clear(ServerLevel lessons, Copies.Copy copy) {
		BlockPos low = copy.origin();
		BlockPos high = low.offset(copy.size().getX() - 1, copy.size().getY() - 1,
			copy.size().getZ() - 1);

		for (Entity standing : lessons.getEntities((Entity) null,
				AABB.encapsulatingFullBlocks(low, high),
				thing -> !(thing instanceof net.minecraft.world.entity.player.Player))) {
			standing.discard();
		}

		var air = Blocks.AIR.defaultBlockState();
		for (BlockPos at : BlockPos.betweenClosed(low, high)) {
			if (!lessons.getBlockState(at).isAir()) {
				lessons.setBlock(at, air, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			}
		}
	}

	/** How far anybody on this server can see, in blocks, which is how wide a gap must be. */
	private static int seeing(MinecraftServer server) {
		return (server.getPlayerList().getViewDistance() + 1) * 16;
	}

	private static Home home(ServerPlayer player) {
		// Somebody already inside has no home worth recording — theirs was taken when
		// they first came in. This is only ever asked of somebody standing outside.
		return Lesson.is(player.level())
			? worldSpawn(player.level().getServer())
			: new Home(player.level().dimension(), player.blockPosition());
	}

	private static void send(ServerPlayer player, ServerLevel to, BlockPos at) {
		player.teleportTo(to, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
			Set.of(Relative.X_ROT, Relative.Y_ROT), player.getYRot(), player.getXRot(), true);
	}
}
