package com.mopicmp.npcstudio.map;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The world locations are laid out in.
 *
 * <h2>Why a dimension of its own rather than a corner of the map</h2>
 *
 * Because a lesson is put back the way its author built it every time somebody walks in,
 * and doing that in a corner of somebody's overworld means rebuilding ground they may
 * have come to care about — at coordinates that are theirs, next to things that are
 * theirs. A separate world has no neighbours to damage.
 *
 * It also gives the one thing per-visitor copies need and a shared map cannot offer:
 * room. Copies are laid out side by side and thrown away on the way out, and doing that
 * where nothing else lives means no rule about what is safe to overwrite.
 *
 * <h2>Why the ground is nothing</h2>
 *
 * The generator is flat with no layers, which is the void: only what was laid out is
 * there. Two reasons, and the second is the one that matters. It is the clearest
 * possible background for looking at a build — the author's own ask, "чтобы отображение
 * было чётче". And nothing is generated, so entering costs what the copy costs and not a
 * chunk of terrain nobody will look at.
 *
 * <h2>What the dimension decides, and what it cannot</h2>
 *
 * It decides what must be the same for everybody in it: the light, the sky, whether time
 * moves, whether anything spawns. Those are the honest contents of a dimension type.
 *
 * What it cannot decide is anything that differs between two locations, because every
 * location lives in this one world — see docs/locations.md, which names that problem
 * rather than pretending the two halves are one.
 *
 * <h2>Why it has an ordinary sky, after trying not to</h2>
 *
 * The first shape of this had no sky at all: no skylight, no skybox, and ambient light at
 * full strength, on the reasoning that a viewer showing a model lights it from nowhere.
 * The first person to stand in it reported absolute darkness.
 *
 * Ambient light is not a light source. It is a floor on the client's light map, and
 * {@code Level} never reads it — what lights a world with no sky is the environment
 * attribute {@code gameplay/sky_light_level}, which is why the Nether is gloomy rather
 * than black. Neither of those was set here, so nothing was lit by anything.
 *
 * There was a second fault standing behind the first, and the same report found it: a
 * world that names no clock is a world {@code /time} refuses to work in — the Nether is
 * like this as well. So somebody standing in the dark could not even ask for daylight.
 *
 * Both are answered by being ordinary: a sky, the overworld's clock, and the timelines
 * bound to it. The cost is worth saying out loud — clocks in 26.2 belong to the server,
 * so setting the time here sets it in the overworld too. A clock of its own would mean
 * shipping a copy of the game's own day timeline bound to it, nine kilobytes of keyframes
 * that would go stale on the next version, and that is a trade worth making once there is
 * a way to try it.
 *
 */
public final class Lesson {

	private Lesson() { }

	/**
	 * Who the hub's ground is noted under.
	 *
	 * Copies are filed by the guest they were laid out for, and the hub has no guest: it
	 * belongs to everybody and is never taken down. So it is filed under a name of its
	 * own rather than under whoever happened to walk in first — otherwise that person
	 * leaving would look like the hub leaving with them.
	 */
	public static final java.util.UUID HUB_KEEPER =
		java.util.UUID.nameUUIDFromBytes("npc_studio:hub".getBytes(
			java.nio.charset.StandardCharsets.UTF_8));

	/**
	 * The one world lessons are laid out in.
	 *
	 * A key rather than a level, because a key is valid before the server exists and a
	 * level is not — and this is named in places that run at load time.
	 */
	public static final ResourceKey<Level> LEVEL =
		ResourceKey.create(Registries.DIMENSION, NpcStudio.id("lesson"));

	/**
	 * The world itself, or null when there is none.
	 *
	 * Null is a real answer and not a fault: a server whose datapacks have been changed,
	 * or a world made before this existed and never reloaded, will not have it. Every
	 * caller has something sensible to do about that, and none of them should crash.
	 */
	public static ServerLevel level(MinecraftServer server) {
		return server == null ? null : server.getLevel(LEVEL);
	}

	/** Whether this is the lessons' world, asked of a level somebody is standing in. */
	public static boolean is(Level level) {
		return level != null && level.dimension().equals(LEVEL);
	}

	/**
	 * Says whether the lessons' world arrived with the server.
	 *
	 * Nothing to set up: the world is entirely described by its two files, and having
	 * nothing to do here is the point of that. What is worth doing is saying so when it
	 * is missing — a datapack turned off, or a world whose packs were edited — because
	 * the way that shows up otherwise is locations quietly having nowhere to be.
	 */
	public static void settle(MinecraftServer server) {
		if (level(server) == null) {
			NpcStudio.LOGGER.warn(
				"No lessons' world on this server: locations will have nowhere to be laid "
					+ "out. Expected the dimension {}.", LEVEL.identifier());
		}
	}
}
