package com.mopicmp.npcstudio.brain;

import com.mopicmp.npcstudio.dialogue.Mark;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.foe.Lead;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Turning a name into a place.
 *
 * The dictionary to {@link Mark}'s vocabulary, kept apart for the same reason
 * {@link Senses} is kept apart from {@link com.mopicmp.npcstudio.dialogue.Sense}:
 * the names are needed by the validator and the editor, which must not touch
 * Minecraft, and the answers are needed here, which cannot avoid it.
 *
 * <h2>Feet and eyes are different places</h2>
 *
 * You walk to somebody's feet and you look at their face, and a character who
 * confuses the two either stares at the floor or tries to stand inside your
 * head. It is one line of difference and it is the whole difference between a
 * character who looks at you and one who looks past you.
 */
public final class Marks {

	private Marks() { }

	/** Where to walk to, or null when the mark names nothing at the moment. */
	public static Vec3 feet(NpcEntity npc, String mark) {
		return switch (mark) {
			case Mark.LEAD -> {
				Lead lead = npc.watch().lead();
				yield lead == null ? null : lead.at();
			}
			case Mark.PLAYER -> {
				Player player = nearest(npc);
				yield player == null ? null : player.position();
			}
			case Mark.POST -> npc.post();
			default -> null;
		};
	}

	/** Where to look, which is a face when there is one. */
	public static Vec3 eyes(NpcEntity npc, String mark) {
		if (Mark.PLAYER.equals(mark)) {
			Player player = nearest(npc);
			return player == null ? null : player.getEyePosition();
		}
		// A lead is a guess at a place rather than a creature, and it has already
		// been levelled to her own eye height by the watching — looking at the
		// ground under a noise is not what anybody means by looking at it.
		return feet(npc, mark);
	}

	private static Player nearest(NpcEntity npc) {
		Player best = null;
		double closest = Double.MAX_VALUE;
		for (Player player : npc.level().players()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			double away = npc.distanceToSqr(player);
			if (away < closest) {
				closest = away;
				best = player;
			}
		}
		return best;
	}
}
