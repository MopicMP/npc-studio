package com.mopicmp.npcstudio.map;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Taking a copy of the ground, and putting it back down somewhere else.
 *
 * <h2>What this is for</h2>
 *
 * The author builds a lesson by hand, wherever it is convenient to build, and the mod
 * has to lay it out again inside a location. That is the one thing the language did not
 * already have — everything else a location needs was built before it: what it grants is
 * a standing property, what it permits is a condition, what starts it is a trigger.
 *
 * <h2>Why the game's own format</h2>
 *
 * Because it already does the hard half — a palette, block entities, entities — and
 * because a file written here can be opened by a vanilla structure block, which makes
 * this a tool that fits what people already use rather than a second way of doing it.
 *
 * The 48-block limit people associate with structures is the structure <em>block's</em>
 * limit, not the format's. Nothing here is bounded by it.
 *
 * <h2>What is bounded, and by whom</h2>
 *
 * Putting blocks down costs per block, so there has to be a ceiling — and in 26.2 the
 * game made that ceiling a game rule, {@code max_block_modifications}, defaulting to
 * 32768. Rules are per dimension now, which means the right answer is to ask the world
 * being written to rather than to invent a number here: a location that needs a bigger
 * one can set it in its own rules, and that is what a location's rules are for.
 *
 * This replaces guessing. It also makes the refusal useful, because the thing to do
 * about it is named by the refusal itself.
 *
 * <h2>What does not travel, and is not pretended to</h2>
 *
 * Named places. Those are absolute coordinates in a world save, and making them follow a
 * copy is its own job — see docs/locations.md. A capture that quietly took them would
 * produce twenty copies all pointing at one coordinate.
 */
public final class Regions {

	private Regions() { }

	/** Where the blocks live: {@code generated/npc_studio/structures/region/<file>.nbt}. */
	public static Identifier file(String file) {
		return NpcStudio.id("region/" + file);
	}

	/** What happened when somebody asked for a copy. */
	public sealed interface Taken {

		/** Kept, this big. */
		record Kept(String name, Vec3i size, long blocks) implements Taken { }

		/**
		 * More blocks than this world will put down in one go.
		 *
		 * Refused at capture rather than at placement, because a copy that can be taken
		 * and never laid out is worse than no copy: it looks like it worked.
		 */
		record TooBig(long blocks, int allowed) implements Taken { }

		/** No name, or a name longer than a list can show. */
		record BadName(String name) implements Taken { }

		/** This world already keeps as many copies as it may. */
		record TooMany(int most) implements Taken { }

		/** The game would not write the file. */
		record NotWritten(String name) implements Taken { }
	}

	/**
	 * How many blocks this world will put down in one act.
	 *
	 * Asked of the world rather than held as a constant, because the rule is per
	 * dimension and a location may well raise it for itself.
	 */
	public static int allowance(ServerLevel level) {
		return level.getGameRules().get(GameRules.MAX_BLOCK_MODIFICATIONS);
	}

	/** Whether a name is one a person can be shown and a list can hold. */
	public static boolean nameable(String name) {
		return name != null && !name.isBlank() && name.length() <= WorldRegions.LONGEST;
	}

	/**
	 * Takes a copy of everything between two corners, characters included.
	 *
	 * <h2>Why the characters come with it</h2>
	 *
	 * Because a lesson is mostly characters. Ground without them is scenery, and
	 * standing them up again by hand after every placement would be the whole of the
	 * work — which was the author's answer when asked: blocks, characters, and the
	 * documents they use.
	 *
	 * Players are left where they are: the game's own capture skips them, which is what
	 * anybody standing inside their own build would want.
	 *
	 * @param name what a person will call this, in whatever letters they like
	 */
	public static Taken capture(ServerLevel level, BlockPos one, BlockPos two, String name) {
		if (!nameable(name)) return new Taken.BadName(name);

		BlockPos low = new BlockPos(
			Math.min(one.getX(), two.getX()),
			Math.min(one.getY(), two.getY()),
			Math.min(one.getZ(), two.getZ()));
		Vec3i size = new Vec3i(
			Math.abs(one.getX() - two.getX()) + 1,
			Math.abs(one.getY() - two.getY()) + 1,
			Math.abs(one.getZ() - two.getZ()) + 1);

		long blocks = (long) size.getX() * size.getY() * size.getZ();
		int allowed = allowance(level);
		if (blocks > allowed) return new Taken.TooBig(blocks, allowed);

		WorldRegions store = WorldRegions.of(level);
		WorldRegions.Region had = store.at(name);
		// Taking it again keeps the file it already had, so the copy is replaced rather
		// than joined by a second one — and anything pointing at the old file goes on
		// pointing at the same ground, now corrected.
		String into = had != null ? had.file() : store.freshFile();
		if (had == null && store.count() >= WorldRegions.MOST) {
			return new Taken.TooMany(WorldRegions.MOST);
		}

		var manager = level.getServer().getStructureManager();
		StructureTemplate template = manager.getOrCreate(file(into));
		template.fillFromWorld(level, low, size, true, java.util.List.of());
		template.setAuthor(name);
		if (!manager.save(file(into))) return new Taken.NotWritten(name);

		store.put(name, new WorldRegions.Region(into, size,
			had == null ? java.util.List.of() : had.documents()));
		return new Taken.Kept(name, size, blocks);
	}

	/** How big the copy under this name is, or null when there is none. */
	public static Vec3i size(ServerLevel level, String name) {
		WorldRegions.Region region = WorldRegions.of(level).at(name);
		return region == null ? null : region.size();
	}

	/**
	 * Lays a copy down with its low corner at a point.
	 *
	 * <h2>Why nothing is cleared first</h2>
	 *
	 * Because a location is laid out into ground that is empty — that is what the
	 * dimension's void generator is for — and clearing first would double the cost of
	 * the one act that has a ceiling on it. Laying one build over another is not a
	 * thing this is for, and pretending to support it would be the slow kind of lie.
	 *
	 * @return true when the copy was there and went down
	 */
	public static boolean place(ServerLevel level, BlockPos at, String name) {
		WorldRegions.Region region = WorldRegions.of(level).at(name);
		if (region == null) return false;

		var found = level.getServer().getStructureManager().get(file(region.file()));
		if (found.isEmpty()) {
			// The note outlived the file: somebody deleted it from the world folder, or
			// the world was copied without generated/. Said out loud rather than left as
			// a location that lays out nothing, which is the same silence everything
			// here is built to break.
			NpcStudio.LOGGER.warn("the ground for \"{}\" is noted but its file {} is gone",
				name, file(region.file()));
			return false;
		}

		// Told to the people watching, not to the blocks around. A lesson goes down into
		// empty ground, so there are no neighbours to react — asking every block to
		// reconsider its shape would be a second pass over the whole region for nothing.
		return found.get().placeInWorld(level, at, at, new StructurePlaceSettings(),
			level.getRandom(), Block.UPDATE_CLIENTS);
	}

	/** Forgets a copy, and takes its blocks off the disk with it. */
	public static boolean forget(ServerLevel level, String name) {
		WorldRegions.Region gone = WorldRegions.of(level).drop(name);
		if (gone == null) return false;
		level.getServer().getStructureManager().remove(file(gone.file()));
		return true;
	}

}
