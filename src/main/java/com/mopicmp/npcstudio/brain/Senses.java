package com.mopicmp.npcstudio.brain;

import com.mopicmp.npcstudio.dialogue.Sense;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.foe.Lead;

import net.minecraft.world.entity.player.Player;

/**
 * Everything a graph may ask a character about herself.
 *
 * <h2>Why these ten and not a hundred</h2>
 *
 * Because each one is traceable to something a character already works out, and
 * none of them was invented for the list. Seven of them exist because of the
 * four decisions still written in java — the ones step D has to move into a
 * graph — and a window that cannot express those would be a window built for
 * nothing.
 *
 * That is the rule for adding to this file: a reading earns its place by being
 * needed by a graph somebody is writing, not by seeming useful. The cost of a
 * wrong name here is higher than it looks, because graphs in the wild will use
 * it and renaming it later breaks them.
 *
 * <h2>Why they are named rather than typed</h2>
 *
 * A reading is a variable in the {@link com.mopicmp.npcstudio.dialogue.Scope#SENSE}
 * scope, so asking about one uses the comparison the language already has, with
 * its six operators, its type checking and its editor. Adding a reading is a
 * line in {@link #KNOWN} and a line in {@link #of}; it is not a change to the
 * language.
 */
public final class Senses {

	private Senses() { }

	/**
	 * Answers one reading for one character.
	 *
	 * Every case here must have a name in {@link Sense#KNOWN} and every name there
	 * must have a case here. A name with no case reads as false for ever, which
	 * looks exactly like a graph that does not work; a case with no name is
	 * refused by the validator before anybody can use it.
	 */
	public static Value of(NpcEntity npc, String name) {
		var watch = npc.watch();
		Lead lead = watch.lead();

		return switch (name) {
			// How frightened she is, nought to one. The number the mood is made of,
			// offered as well as the mood because a graph that wants a threshold of
			// its own should not have to accept ours.
			case Sense.ALARM -> new Value.Num(watch.alarm());
			// And ours, as a word: "calm", "suspicious", "alert".
			case Sense.MOOD -> new Value.Text(watch.mood().name().toLowerCase(java.util.Locale.ROOT));

			// Whether she is on to anything at all. Everything below is meaningless
			// without this, and reads as nought rather than as a lie.
			case Sense.LEAD -> new Value.Flag(lead != null);
			case Sense.LEAD_STRENGTH -> new Value.Num(lead == null ? 0 : lead.strength());
			case Sense.LEAD_URGENCY -> new Value.Num(lead == null ? 0 : lead.urgency());
			// Seen or only heard. The difference matters more than its size: seeing
			// is what makes her certain, and hearing never can.
			case Sense.LEAD_SEEN -> new Value.Flag(lead != null && lead.seen());
			case Sense.LEAD_DISTANCE -> new Value.Num(
				lead == null ? Sense.OUT_OF_MIND : npc.position().distanceTo(lead.at()));

			case Sense.WALKING -> new Value.Flag(npc.walking().walking());
			// Which round she is on, and which she has just come off. A flag cannot
			// say whose walk it is, and a route node waiting on the flag would move
			// on the moment any unrelated walk in the graph happened to end.
			case Sense.WALKING_TO -> new Value.Text(npc.walkingTo());
			case Sense.WALKED -> new Value.Text(npc.walked());
			// Whether there is anything in her main hand at all. Which weapon it is
			// belongs to the classification step and is not guessed at here.
			case Sense.ARMED -> new Value.Flag(!npc.getMainHandItem().isEmpty());
			// What sort of thing, and what it is called. The first is our guess and
			// the second is what lets an author overrule it with blocks when the
			// guess is wrong, which one day it will be.
			case Sense.WEAPON -> new Value.Text(com.mopicmp.npcstudio.foe.Arms
				.of(npc.getMainHandItem()).name().toLowerCase(java.util.Locale.ROOT));
			case Sense.HAND -> {
				var stack = npc.getMainHandItem();
				yield new Value.Text(stack.isEmpty() ? ""
					: net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(stack.getItem()).toString());
			}

			// The nearest person, which is what almost every graph anybody writes
			// first wants to know, and what makes one testable by walking up to her.
			case Sense.DOING -> new Value.Text(npc.doingNow());

			// Whoever this skill was told about. Asked through the mark rather than
			// worked out again here, so that "target" means one thing everywhere.
			case Sense.TARGET -> new Value.Flag(
				Marks.feet(npc, com.mopicmp.npcstudio.dialogue.Mark.TARGET) != null);
			case Sense.TARGET_DISTANCE -> {
				var at = Marks.feet(npc, com.mopicmp.npcstudio.dialogue.Mark.TARGET);
				yield new Value.Num(at == null ? Sense.OUT_OF_MIND : npc.position().distanceTo(at));
			}
			case Sense.IN_REACH -> new Value.Flag(npc.canReach(
				com.mopicmp.npcstudio.dialogue.Mark.TARGET));

			case Sense.PLAYER_DISTANCE -> new Value.Num(nearestPlayer(npc));

			// Somebody else running the same graph. Whether that makes them a
			// comrade or an opponent is the graph's own business — see Mark.KIN.
			case Sense.KIN -> new Value.Flag(Marks.kin(npc) != null);
			case Sense.KIN_DISTANCE -> {
				var other = Marks.kin(npc);
				yield new Value.Num(other == null ? Sense.OUT_OF_MIND : npc.distanceTo(other));
			}

			default -> new Value.Flag(false);
		};
	}

	private static double nearestPlayer(NpcEntity npc) {
		double nearest = Sense.OUT_OF_MIND;
		for (Player player : npc.level().players()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			nearest = Math.min(nearest, npc.distanceTo(player));
		}
		return nearest;
	}
}
