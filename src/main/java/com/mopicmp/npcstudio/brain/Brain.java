package com.mopicmp.npcstudio.brain;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueEngine;
import com.mopicmp.npcstudio.dialogue.DialogueState;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;
import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A character running a graph instead of being run by java.
 *
 * <h2>What is new here, honestly: nothing much</h2>
 *
 * The interpreter is the one that has been running conversations all along, and
 * it needed no change to run behaviour — it never knew there was a player, it
 * only knew it sometimes had to stop. Two node kinds gave it other reasons to
 * stop ({@code every} and {@code until}) and it was a behaviour language.
 *
 * So this file is small on purpose. It is the strap between an engine that
 * decides and a character that does, and if it ever grows a decision of its own
 * that decision is in the wrong place.
 *
 * <h2>Why the tick is not five times a second</h2>
 *
 * Because the graph says. A character standing at {@code every 100} costs one
 * countdown per tick and nothing else; one standing at {@code until} pays for
 * the question she is asking, and pays it where the author can see it. That was
 * your rule — whoever places the node decides its occasion — and it removes the
 * one thing we would otherwise have had to guess on everyone's behalf.
 */
public final class Brain {

	private Brain() { }

	/**
	 * How far the engine may run in one tick before we call it a runaway.
	 *
	 * The engine has its own, larger bound on nodes visited without waiting. This
	 * is the other half: a graph that legitimately waits, but waits for nought
	 * ticks, forever. That is not a broken graph, it is a busy one, and it would
	 * eat a tick without ever tripping the engine's guard.
	 */
	private static final int ROUNDS = 16;

	/** One tick of thinking, or of standing still, which is most of them. */
	public static void tick(NpcEntity npc) {
		if (npc.brainId().isEmpty()) return;

		if (npc.stillWaiting()) return;

		Dialogue graph = DialogueRegistry.get(npc.brainId()).orElse(null);
		if (graph == null) {
			// Named a graph that is not there. Said once and then forgotten, because
			// this runs twenty times a second and a missing graph would otherwise
			// fill the log faster than anybody could read the first line.
			NpcStudio.LOGGER.error("NPC has no graph called \"{}\"; its brain is off.",
				npc.brainId());
			npc.setBrainId("");
			return;
		}

		DialogueState mind = npc.mind(graph);
		Condition.World world = worldFor(npc);

		for (int round = 0; round < ROUNDS; round++) {
			DialogueEngine.Step step;
			try {
				step = DialogueEngine.step(graph, mind, new DialogueEngine.Input.Begin(), world);
			} catch (DialogueEngine.DialogueFault fault) {
				// A broken graph must not take the character down with it. The brain
				// goes off rather than throwing every tick for the rest of the world's
				// life, and the reason goes where it can be read.
				NpcStudio.LOGGER.error("Graph \"{}\" failed: {}", graph.id(), fault.getMessage());
				npc.setBrainId("");
				return;
			}

			for (Effect effect : step.effects()) {
				Deeds.doTo(effect, npc);
			}
			mind = step.state();
			npc.remember(mind);

			switch (step.screen()) {
				case DialogueEngine.Screen.Waiting(int ticks) -> {
					// Nought means she is watching for something and wants asking
					// again next tick, which is the ordinary case and not a wait at
					// all. Anything more is a timer.
					npc.waitFor(ticks);
					return;
				}
				case DialogueEngine.Screen.Finished _ -> {
					// A graph that ends is a character who has finished her business
					// and stands there. Leaving the bookmark on `end` means she is not
					// asked again — a graph meant to go on says so by leading back to
					// its own beginning.
					npc.waitFor(1);
					return;
				}
				// A behaviour graph is nobody's conversation. This is the mirror of
				// the refusal on the other side, where a dialogue that waits is
				// turned away for the same reason: each runtime can serve some of
				// the language, and saying which is kinder than half-working.
				case DialogueEngine.Screen.Line _, DialogueEngine.Screen.Choice _ -> {
					NpcStudio.LOGGER.error(
						"Graph \"{}\" speaks at \"{}\" — a behaviour graph has nobody to "
							+ "speak to. Use it as a dialogue instead.",
						graph.id(), mind.currentNode());
					npc.setBrainId("");
					return;
				}
			}
		}

		// Sixteen rounds in one tick and still going: the graph waits for nought
		// every time round a loop. Slow her right down rather than stopping her —
		// this is a graph that works and is merely expensive, and the author will
		// see a character thinking in slow motion, which is the correct complaint.
		NpcStudio.LOGGER.warn("Graph \"{}\" went round {} times in one tick.", graph.id(), ROUNDS);
		npc.waitFor(20);
	}

	/**
	 * The only questions a character may ask about the world.
	 *
	 * <h2>Why the same question means something different here</h2>
	 *
	 * In a conversation {@code hasItem} asks about the player's pockets, because
	 * a conversation is about the player. A character alone has no player to ask
	 * about, and the honest reading of "do you have one" is her own hands.
	 *
	 * That is not a compromise, it is the shape of the thing: a condition is a
	 * question asked of whoever the graph is about.
	 *
	 * The rest of the window is {@link Senses}, which is a different sort of
	 * question: not what she has but what she perceives. Those are read-only and
	 * live in their own scope — see {@link com.mopicmp.npcstudio.dialogue.Scope#SENSE}.
	 */
	public static Condition.World worldFor(NpcEntity npc) {
		return new Condition.World() {
			@Override
			public boolean hasItem(String itemId, int count) {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
				int found = 0;
				for (InteractionHand hand : InteractionHand.values()) {
					ItemStack stack = npc.getItemInHand(hand);
					if (stack.is(item)) found += stack.getCount();
				}
				return found >= count;
			}

			@Override
			public com.mopicmp.npcstudio.dialogue.Value sense(String name) {
				return Senses.of(npc, name);
			}
		};
	}
}
