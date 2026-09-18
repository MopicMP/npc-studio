package com.mopicmp.npcstudio.map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Which lesson something is standing in, if any.
 *
 * <h2>Why this is one sentence with a name on it</h2>
 *
 * Because it is asked from a dozen places and it must give the same answer to all of
 * them. A player's triggers, a character's document, the list a window shows — all three
 * are the same question, and three copies of it would be three chances for one of them to
 * drift and for a lesson to half-leak.
 *
 * <h2>Why it is asked of a position rather than of a person</h2>
 *
 * Because most of the things that ask are not people. A character standing in a copy has
 * to find her own lesson's documents, and she has no account to be looked up under. A
 * block somebody clicked has even less. All of them have somewhere they are.
 *
 * It also means nothing has to be remembered: there is no list of who went where to be
 * kept in step with where they actually are. Walking out of a lesson is leaving it, by
 * construction, with nothing to update.
 *
 * <h2>Why everywhere else is the map</h2>
 *
 * Empty is the answer for the overworld, for the nether, for a modded world somebody else
 * added, and for the lessons' world in the gaps between copies. That is the same word a
 * document uses for "I belong to this map", so the two compare equal without anybody
 * remembering that nothing is a case.
 */
public final class Whereabouts {

	private Whereabouts() { }

	/** The lesson this thing is inside, or empty for the map's own ground. */
	public static String of(Entity who) {
		return who == null ? "" : at(who.level(), who.blockPosition());
	}

	/** The lesson this point is inside, or empty for the map's own ground. */
	public static String at(net.minecraft.world.level.Level level,
			net.minecraft.core.BlockPos where) {
		// The cheap question first, and it throws away everything on nearly every call:
		// only one world has lessons in it, and almost nobody is ever standing there.
		if (!Lesson.is(level) || !(level instanceof ServerLevel server)) return "";
		return Copies.of(server).at(where);
	}
}
