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
				// A mark that is a person is walked at rather than walked to, so that
				// arriving is judged against them. The graph does not have to say which
				// — it named a mark, and whether that mark breathes is not a fact an
				// author should have to write down twice.
				if (Marks.creature(npc, mark) != null) npc.walkAt(mark, pace);
				else npc.walkTo(Marks.feet(npc, mark), pace);
			}
			// A whole path rather than one place. Named, so that the node which
			// ordered it asks about its own and not about somebody else's — and so
			// that the same order arriving again every tick is recognised as the one
			// she is already carrying out rather than as a fresh start.
			case Effect.Follow(String order, com.mopicmp.npcstudio.dialogue.Route path) ->
				npc.follow(order, path);
			case Effect.Arrived(String order) -> npc.tookArrival(order);
			case Effect.GoHome(boolean walking) -> npc.goHome(walking);
			case Effect.Halt _ -> npc.halt();
			case Effect.Guard(boolean up) -> npc.guard(up);
			// A character giving herself a property — faster, tougher, larger. Only her
			// own: a brain has no player in the room, and one asking for the player's is
			// a graph hung on the wrong thing. Said out loud rather than quietly done to
			// her instead, which would be a scene where the wrong body got the boon.
			case Effect.Trait trait -> {
				if (trait.whose() == Effect.Trait.Whose.PLAYER) {
					NpcStudio.LOGGER.warn(
						"A behaviour graph asked to give the player \"{}\"; there is no player here.",
						trait.name());
				} else {
					com.mopicmp.npcstudio.dialogue.runtime.Traits.put(npc, trait);
				}
			}
			case Effect.LookAt(String mark) -> npc.lookAt(mark);
			// Measured against where she was placed, like every other step in this mod,
			// and worked out on her rather than here — the anchor is hers and this class
			// has no business holding a second opinion about where it is.
			case Effect.Appear(com.mopicmp.npcstudio.dialogue.Route.Point where) ->
				npc.appearAt(where);
			case Effect.Strike(String mark) -> npc.strikeAt(mark);
			case Effect.Fire(String mark) -> {
				// How long to hold it is the weapon's business rather than the
				// author's. A graph that had to name a number of ticks would be a
				// graph that has to be rewritten for every bow in every mod.
				npc.fireAt(mark, com.mopicmp.npcstudio.foe.Draw.longEnoughFor(0.95f));
			}
			// The two that are about the world rather than about anybody in it. A character
			// alone in a room can put a label over a door, and there is no player anywhere
			// in the act — so these are done here rather than warned about, which is what
			// the arm below would otherwise have said about them untruthfully.
			//
			// Measured from her, like every other place a brain names: a sign written as
			// two steps in front is two steps in front of wherever she is standing.
			case Effect.Show(String name, com.mopicmp.npcstudio.dialogue.Shown what,
					com.mopicmp.npcstudio.dialogue.Route.Point where) -> {
				if (npc.level() instanceof net.minecraft.server.level.ServerLevel level) {
					com.mopicmp.npcstudio.show.Showing.put(level, place(npc), name, what,
						// No values: a brain has no player whose variables could fill them,
						// and a name left standing shows on the wall as itself, which is
						// the honest answer rather than a blank.
						what.filled(java.util.Map.of()), middleOf(npc, where));
				}
			}
			case Effect.Unshow(String name) -> {
				if (npc.level() instanceof net.minecraft.server.level.ServerLevel level) {
					com.mopicmp.npcstudio.show.Showing.take(level, place(npc), name);
				}
			}
			case Effect.PlaySound(String sound, float volume, float pitch) ->
				NpcStudio.LOGGER.warn("A graph asked for the sound \"{}\"; sounds are not wired up yet.",
					sound);
			case Effect.GiveItem _, Effect.TakeItem _, Effect.RunCommand _,
					Effect.PlaceStructure _, Effect.Wall _, Effect.Hold _, Effect.Send _,
					Effect.PutBlock _, Effect.Portrait _ ->
				// Not "not done yet". There is no player in the room, and these are all
				// about one. A graph that wants them is a graph that should have been
				// hung on a conversation.
				//
				// A wall is here for a reason worth saying, because it looks like it is
				// about the map rather than about a person: what makes it safe is that
				// it belongs to a conversation and comes down when the conversation does.
				// A brain has no conversation, so it has nothing that ever ends — and a
				// wall raised by one would stand in that corridor until somebody dug it
				// out by hand.
				NpcStudio.LOGGER.warn(
					"A behaviour graph asked for {}, which only means something to a player.",
					effect.getClass().getSimpleName());
		}
	}

	/**
	 * Which place's names a character's holograms belong to.
	 *
	 * The one she is standing in, which on the map is no place in particular and inside a
	 * lesson is that guest's own copy. The same question a document asks about a player,
	 * asked of a body instead — see {@link com.mopicmp.npcstudio.map.Whereabouts}.
	 */
	private static String place(NpcEntity npc) {
		return com.mopicmp.npcstudio.map.Whereabouts.of(npc);
	}

	/** The middle of the block a point names, measured from where she was placed. */
	private static net.minecraft.world.phys.Vec3 middleOf(NpcEntity npc,
			com.mopicmp.npcstudio.dialogue.Route.Point where) {
		var anchor = npc.home();
		int[] to = com.mopicmp.npcstudio.dialogue.Route.world(where,
			net.minecraft.util.Mth.floor(anchor.x),
			net.minecraft.util.Mth.floor(anchor.y),
			net.minecraft.util.Mth.floor(anchor.z),
			npc.homeFacing());
		return net.minecraft.world.phys.Vec3.atCenterOf(
			new net.minecraft.core.BlockPos(to[0], to[1], to[2]));
	}
}
