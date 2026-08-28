package com.mopicmp.npcstudio.brain;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.entity.NpcEntity;

/**
 * Carrying out what a behaviour graph decided.
 *
 * <h2>The split this makes visible</h2>
 *
 * Half the effects the language already has need a player: give an item, take
 * one, run a command as somebody. The other half are about the character alone:
 * play this animation, wear this face.
 *
 * A conversation always has both, so the distinction never had to be drawn. A
 * character standing in an empty room has only the second, and the ones that
 * need a player are not merely unimplemented here — they are meaningless, and
 * saying so out loud is better than doing nothing quietly.
 *
 * This is also where the verbs of the third step will land: walk there, look at
 * that, crouch, fire. They are all this shape — the graph decided, and something
 * already written in java does it.
 */
public final class Deeds {

	private Deeds() { }

	public static void doTo(Effect effect, NpcEntity npc) {
		switch (effect) {
			case Effect.PlayAnimation(String animation, int ticks) -> npc.playGesture(animation, ticks);
			case Effect.Express(String expression, int ticks) -> npc.express(expression, ticks);

			// The verbs. Each is one line here because the hard part is already
			// written and is not a decision: routes, necks and bowstrings are
			// physics, and the graph's whole job was to say which and at what.
			case Effect.WalkTo(String mark, float pace) -> {
				if (com.mopicmp.npcstudio.dialogue.Mark.POST.equals(mark) && npc.post() == null) {
					npc.markPost();
				}
				npc.walkTo(Marks.feet(npc, mark), pace);
			}
			case Effect.Halt _ -> npc.halt();
			case Effect.Guard(boolean up) -> npc.guard(up);
			case Effect.LookAt(String mark) -> npc.lookAt(mark);
			case Effect.Strike(String mark) -> npc.strikeAt(mark);
			case Effect.Fire(String mark) -> {
				// How long to hold it is the weapon's business rather than the
				// author's. A graph that had to name a number of ticks would be a
				// graph that has to be rewritten for every bow in every mod.
				npc.fireAt(mark, com.mopicmp.npcstudio.foe.Draw.longEnoughFor(0.95f));
			}
			case Effect.PlaySound(String sound, float volume, float pitch) ->
				NpcStudio.LOGGER.warn("A graph asked for the sound \"{}\"; sounds are not wired up yet.",
					sound);
			case Effect.GiveItem _, Effect.TakeItem _, Effect.RunCommand _,
					Effect.PlaceStructure _ ->
				// Not "not done yet". There is no player in the room, and these three
				// are all about one. A graph that wants them is a graph that should
				// have been hung on a conversation.
				NpcStudio.LOGGER.warn(
					"A behaviour graph asked for {}, which only means something to a player.",
					effect.getClass().getSimpleName());
		}
	}
}
