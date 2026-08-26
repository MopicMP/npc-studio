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

	/**
	 * One tick of thinking, or of standing still, which is most of them.
	 *
	 * <h2>Two graphs, and the order they go in</h2>
	 *
	 * The scenario first, then the skill it has running. That order is the whole
	 * of the answer to "who wins when both order the body in one tick": the skill
	 * does, because it acted last. Which is right — the skill is the specialist
	 * and the scenario is what chose it. A scenario that wants the body for itself
	 * stops the skill and then takes it.
	 */
	public static void tick(NpcEntity npc) {
		if (npc.graphId().isEmpty()) return;

		Dialogue graph = DialogueRegistry.get(npc.graphId()).orElse(null);
		if (graph != null) {
			if (!npc.stillWaiting()) run(npc, graph, false);
			// Asked even while the scenario is standing on a timer. A skill that only
			// got a turn when its caller did would be a fight that happens in bursts
			// of one tick every three seconds.
			//
			// Stepped against the document the skill actually came out of, which is
			// not always hers: a skill called out of a library lives in the library.
			if (!npc.doingNow().isEmpty() && !npc.skillStillWaiting()) {
				Dialogue book = whereTheSkillLives(npc, graph);
				if (book != null) run(npc, book, true);
			}
			return;
		}
		if (npc.stillWaiting()) return;
		// Named a graph that is not there. Said once and then forgotten, because
		// this runs twenty times a second and a missing graph would otherwise fill
		// the log faster than anybody could read the first line.
		//
		// The name is left alone. It used to be cleared here, back when it named a
		// second document nobody else wanted; now it names the character's only one,
		// and erasing somebody's assignment because a pack has not finished loading
		// is a way to lose authorship rather than a way to recover.
		NpcStudio.LOGGER.error("NPC has no graph called \"{}\"; it stands still.", npc.graphId());
		npc.brainTrouble("no graph called \"" + npc.graphId() + "\"");
		npc.waitFor(100);
	}

	/**
	 * Runs one of the two bookmarks forward.
	 *
	 * @param skill which one: the character's own scenario, or the segment it has
	 *              running. They share a graph and a world and differ only in
	 *              where they are and what they were told.
	 */
	private static void run(NpcEntity npc, Dialogue graph, boolean skill) {
		DialogueState mind = skill ? npc.doingState() : npc.mind(graph);
		if (mind == null) return;
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
				npc.brainTrouble("graph failed: " + fault.getMessage());
				// Parked rather than unassigned. The document is the character's only
				// one now, so throwing it away to stop an exception would be curing a
				// broken graph by deleting the author's work.
				npc.waitFor(100);
				return;
			}

			for (Effect effect : step.effects()) {
				Deeds.doTo(effect, npc);
			}
			for (DialogueEngine.Call call : step.calls()) {
				answer(npc, graph, call);
			}
			mind = step.state();
			if (skill) npc.setDoingState(mind);
			else npc.remember(mind);

			switch (step.screen()) {
				case DialogueEngine.Screen.Waiting(int ticks) -> {
					// Nought means she is watching for something and wants asking
					// again next tick, which is the ordinary case and not a wait at
					// all. Anything more is a timer.
					if (skill) npc.waitDoing(ticks); else npc.waitFor(ticks);
					return;
				}
				case DialogueEngine.Screen.Finished _ -> {
					// A skill that reaches its end has finished, and puts itself down.
					// That is the other way out of a call, and the one a skill with a
					// natural ending — pick that up, say that line — will use.
					if (skill) {
						npc.stopDoing();
						return;
					}
					// A scenario that ends is a character who has finished her business
					// and stands there. Leaving the bookmark on `end` means she is not
					// asked again — one meant to go on says so by leading back to its
					// own beginning.
					npc.waitFor(1);
					return;
				}
				// She has reached something that needs a person, and there is not one.
				// So she stops here and waits, which is the whole of what a character
				// with a conversation and nothing else to do should be seen doing.
				//
				// This used to be an error that switched the brain off, and that was
				// right while a brain was a second document chosen on purpose to run
				// by itself — speaking in one was a mistake. Now there is one document
				// and most of them are conversations, so speaking is the ordinary
				// case: an NPC who says "hello" would have logged an error twenty
				// times a second for standing in a field.
				//
				// The conversation itself is not this bookmark. It runs from `start`
				// on its own, kept per player and per character, so two people can be
				// mid-sentence with her at once and neither is this.
				case DialogueEngine.Screen.Line _, DialogueEngine.Screen.Choice _ -> {
					if (skill) {
						// A skill is different, and here the old refusal still holds. It
						// was called by something that is not a person and cannot answer,
						// so it would wait for ever with nobody able to say why.
						NpcStudio.LOGGER.error(
							"Skill \"{}\" in \"{}\" speaks at \"{}\" — a skill has nobody to "
								+ "speak to.", npc.doingNow(), graph.id(), mind.currentNode());
						npc.brainTrouble("skill \"" + npc.doingNow() + "\" tries to speak");
						npc.stopDoing();
						return;
					}
					npc.waitFor(100);
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
	 * The document holding the nodes of the skill she is running.
	 *
	 * Hers unless the call named somebody else's. A library that has been deleted
	 * while a character was mid-skill puts the skill down rather than stepping her
	 * through a graph that is not the one she started in.
	 */
	private static Dialogue whereTheSkillLives(NpcEntity npc, Dialogue own) {
		if (npc.doingIn().isEmpty()) return own;
		Dialogue book = DialogueRegistry.get(npc.doingIn()).orElse(null);
		if (book != null) return book;
		NpcStudio.LOGGER.error("Skill \"{}\" came out of \"{}\", which is gone.",
			npc.doingNow(), npc.doingIn());
		npc.brainTrouble("library \"" + npc.doingIn() + "\" is gone");
		npc.stopDoing();
		return null;
	}

	/**
	 * Starts or stops a skill, on behalf of whichever bookmark asked.
	 *
	 * A skill that is already the one running is not started again. Without that,
	 * a scenario written the obvious way — check every tick that she is fighting,
	 * and say so if not — would restart the fight from its first node twenty times
	 * a second, and she would never get past the first thing it does.
	 */
	private static void answer(NpcEntity npc, Dialogue graph, DialogueEngine.Call call) {
		if (!call.start()) {
			if (call.segment().equals(npc.doingNow())) npc.stopDoing();
			return;
		}
		if (call.segment().equals(npc.doingNow())) return;

		// A name may point into another document — "duel:fight" — which is what
		// makes a library of skills a thing that exists. A brain is exactly that: a
		// document written to hold skills, that no character carries, and that
		// dialogues call into.
		int colon = call.segment().indexOf(':');
		String from = colon < 0 ? "" : call.segment().substring(0, colon);
		String named = colon < 0 ? call.segment() : call.segment().substring(colon + 1);

		Dialogue book = graph;
		if (!from.isEmpty()) {
			book = DialogueRegistry.get(from).orElse(null);
			if (book == null) {
				npc.brainTrouble("there is no graph called \"" + from + "\"");
				NpcStudio.LOGGER.error("Graph \"{}\" calls into \"{}\", which does not exist.",
					graph.id(), from);
				return;
			}
		}

		String at = book.segment(named);
		if (at == null) {
			String said = "\"" + book.id() + "\" has no skill called \"" + named + "\"";
			if (book.segments().isEmpty()) said += " — it has none at all";
			npc.brainTrouble(said);
			NpcStudio.LOGGER.error("Graph \"{}\" has no segment called \"{}\".",
				book.id(), named);
			return;
		}
		npc.brainTrouble("");
		// Remembered by the whole name, so that stopping it says the same thing the
		// call did and a scenario cannot cancel a skill it did not start.
		npc.beginDoing(call.segment(), from, call.target(),
			DialogueState.beginning(book, at, java.util.Map.of(), npc.memory(), call.with()));
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
