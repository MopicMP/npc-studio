package com.mopicmp.npcstudio.brain;

import com.mopicmp.npcstudio.dialogue.Mark;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.foe.Lead;

import net.minecraft.world.entity.LivingEntity;
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

	/**
	 * As far as one character can be from another and still be found.
	 *
	 * <h2>Why it is generous, and why it can afford to be</h2>
	 *
	 * This is not eyesight. A graph asking about kin is asking who else is about,
	 * and how close is close enough is the graph's own business — it has
	 * {@code kin.distance} for exactly that. A tight number here would be us
	 * deciding, badly, on everybody's behalf: a boss who should notice an
	 * intruder across an arena and a guard who should not care past the doorway
	 * are the same question with different answers, and only the author knows
	 * which they are writing.
	 *
	 * It was thirty-two, chosen to keep the cost down, and that was the wrong
	 * thing to trade. The cost is now paid once a tick rather than once a
	 * question — see {@link #kin} — so the number can be what it should be.
	 */
	public static final double WITHIN = 64.0;

	/**
	 * The living thing a mark names, or null when it names a place or nothing.
	 *
	 * Needed because you cannot hit a coordinate. A lead is a guess at a place
	 * even when it came from seeing somebody, so it deliberately answers null:
	 * striking at where you thought somebody was is not a thing to build on.
	 */
	public static LivingEntity creature(NpcEntity npc, String mark) {
		return switch (told(npc, mark)) {
			case Mark.PLAYER -> nearest(npc);
			case Mark.KIN -> kin(npc);
			default -> null;
		};
	}

	/**
	 * Reads {@code target} back as whatever the call named.
	 *
	 * <h2>The one loop worth guarding against</h2>
	 *
	 * A call that passes {@code target} along — which is exactly what a skill
	 * calling another skill would write, and is the obvious thing to write — would
	 * otherwise ask itself the same question for ever. So the indirection is
	 * followed once and no further: a target of "target" names nothing, which is
	 * the same answer a skill nobody called gets.
	 */
	private static String told(NpcEntity npc, String mark) {
		if (!Mark.TARGET.equals(mark)) return mark;
		String was = npc.doingAt();
		return Mark.TARGET.equals(was) ? Mark.NOTHING : was;
	}

	/**
	 * The nearest other character running the same graph, in sight.
	 *
	 * Line of sight is required, so two of them either side of a wall do not
	 * fight through it. Everything else about the choosing - how close is close
	 * enough, whether to approach - belongs to the graph.
	 */
	/**
	 * The nearest other character running the same graph, in sight.
	 *
	 * <h2>Remembered for the tick it was worked out in</h2>
	 *
	 * A scenario waiting on kin asks every tick; so does the reading beside it,
	 * and so does every verb pointed at kin. Three sweeps of a hundred-and-twenty
	 * block box per character per tick is not a price worth paying three times
	 * for one answer that cannot have changed in between.
	 */
	public static NpcEntity kin(NpcEntity npc) {
		if (npc.graphId().isEmpty()) return null;
		if (npc.kinKnown(npc.tickCount)) return npc.kinRemembered();
		NpcEntity found = lookForKin(npc);
		npc.rememberKin(npc.tickCount, found);
		return found;
	}

	private static NpcEntity lookForKin(NpcEntity npc) {
		NpcEntity best = null;
		double closest = WITHIN * WITHIN;
		var near = npc.getBoundingBox().inflate(WITHIN);
		for (NpcEntity other : npc.level().getEntitiesOfClass(NpcEntity.class, near)) {
			if (other == npc || !other.isAlive()) continue;
			if (!npc.graphId().equals(other.graphId())) continue;
			double away = npc.distanceToSqr(other);
			if (away >= closest || !npc.hasLineOfSight(other)) continue;
			closest = away;
			best = other;
		}
		return best;
	}

	/** Where to walk to, or null when the mark names nothing at the moment. */
	public static Vec3 feet(NpcEntity npc, String wanted) {
		String mark = told(npc, wanted);
		return switch (mark) {
			case Mark.LEAD -> {
				Lead lead = npc.watch().lead();
				yield lead == null ? null : lead.at();
			}
			case Mark.PLAYER -> {
				Player player = nearest(npc);
				yield player == null ? null : player.position();
			}
			case Mark.KIN -> {
				NpcEntity other = kin(npc);
				yield other == null ? null : other.position();
			}
			case Mark.POST -> npc.post();
			default -> null;
		};
	}

	/** Where to look, which is a face when there is one. */
	public static Vec3 eyes(NpcEntity npc, String mark) {
		LivingEntity somebody = creature(npc, mark);
		if (somebody != null) return somebody.getEyePosition();
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
