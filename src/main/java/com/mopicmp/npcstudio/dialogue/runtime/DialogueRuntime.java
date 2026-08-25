package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.Map;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueEngine;
import com.mopicmp.npcstudio.dialogue.DialogueState;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.ShowChoicePayload;
import com.mopicmp.npcstudio.net.ShowLinePayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Ties the engine to the world: who is talking to whom, and what happens next.
 *
 * The engine itself knows nothing about players or entities — it takes a
 * bookmark and returns a new one. Everything that has to touch Minecraft lives
 * here, which is why the engine can be tested without a game and this class can
 * stay small.
 *
 * State is read and written through {@link DialogueSaveData}, which keeps the
 * three kinds apart: where the player is, what the player has done, and what
 * the world is like. Only the first is forgotten when a conversation ends.
 */
public final class DialogueRuntime {

	private DialogueRuntime() { }

	/**
	 * A click on an NPC: start the conversation, or take one more step through it.
	 *
	 * There is no separate "begin" and "continue" from the outside, which is the
	 * point. The bookmark decides: no bookmark means start, a bookmark on a line
	 * means move on, a bookmark on a choice means show that choice again — which
	 * is what makes Esc a pause rather than a cancel.
	 */
	public static void talkTo(ServerPlayer player, NpcEntity npc) {
		Dialogue dialogue = DialogueRegistry.get(npc.dialogueId()).orElse(null);
		if (dialogue == null) {
			player.sendSystemMessage(Component.literal("This NPC has no dialogue called \""
				+ npc.dialogueId() + "\".").withStyle(ChatFormatting.RED));
			return;
		}

		DialogueSaveData store = DialogueSaveData.of(player.level());
		DialogueState state = load(store, player, npc, dialogue);

		DialogueEngine.Input input;
		if (state == null) {
			state = fresh(store, player, dialogue);
			input = new DialogueEngine.Input.Begin();
		} else if (dialogue.node(state.currentNode()) instanceof Node.Line
				&& DialogueDisplay.isShowing(player, npc)) {
			// Only move on if the player can actually see the line they are moving
			// on from. If the bar timed out or they walked away, the click brings
			// the same line back instead — otherwise the conversation would advance
			// past something they never read.
			input = new DialogueEngine.Input.Advance();
		} else {
			// Sitting on a choice, or picking a dropped thread back up.
			input = new DialogueEngine.Input.Begin();
		}

		step(player, npc, dialogue, state, input);
	}

	/** The player answered a question. */
	public static void choose(ServerPlayer player, NpcEntity npc, int option) {
		Dialogue dialogue = DialogueRegistry.get(npc.dialogueId()).orElse(null);
		if (dialogue == null) return;

		DialogueState state = load(DialogueSaveData.of(player.level()), player, npc, dialogue);
		if (state == null) return;

		step(player, npc, dialogue, state, new DialogueEngine.Input.Pick(option));
	}

	/**
	 * Rebuilds where the player was, or reports there is nowhere.
	 *
	 * The three parts come from three places, which is the point: the position
	 * from the bookmark, the progress from the player, the world from the world.
	 * Only the position disappears when a conversation ends.
	 *
	 * Declared types come from the dialogue as it is now rather than from
	 * anything saved. A writer may have changed a variable's type since, and
	 * honouring the old one would leave the state disagreeing with its own script.
	 */
	private static DialogueState load(DialogueSaveData store, ServerPlayer player,
			NpcEntity npc, Dialogue dialogue) {
		String node = store.bookmark(player.getUUID(), npc.getUUID());
		if (node == null) return null;
		return new DialogueState(node,
			store.varsOf(player.getUUID()),
			store.worldVars(),
			store.visitedBy(player.getUUID()),
			dialogue.variableTypes());
	}

	/** A fresh conversation still starts with everything the player already knows. */
	private static DialogueState fresh(DialogueSaveData store, ServerPlayer player, Dialogue dialogue) {
		return new DialogueState(dialogue.start(),
			store.varsOf(player.getUUID()),
			store.worldVars(),
			store.visitedBy(player.getUUID()),
			dialogue.variableTypes());
	}

	private static void step(ServerPlayer player, NpcEntity npc, Dialogue dialogue,
			DialogueState state, DialogueEngine.Input input) {
		DialogueSaveData store = DialogueSaveData.of(player.level());
		DialogueEngine.Step step;
		try {
			step = DialogueEngine.step(dialogue, state, input, worldFor(player));
		} catch (DialogueEngine.DialogueFault fault) {
			// A broken dialogue must not take the player down with it. The bookmark
			// is dropped so a second click starts cleanly instead of hitting the
			// same wall, and the reason goes to the log where it can be fixed.
			store.clearBookmark(player.getUUID(), npc.getUUID());
			NpcStudio.LOGGER.error("Dialogue \"{}\" failed: {}", dialogue.id(), fault.getMessage());
			player.sendSystemMessage(Component.literal("That conversation is broken; it has been reset.")
				.withStyle(ChatFormatting.RED));
			return;
		}

		for (Effect effect : step.effects()) {
			apply(effect, player, npc);
		}

		// Progress and world state are written back whatever the screen turns out
		// to be — including when the conversation just ended. That is exactly the
		// case where it matters: what a conversation taught must outlive it.
		store.remember(player.getUUID(), step.state().playerVars(),
			step.state().visited(), step.state().worldVars());

		switch (step.screen()) {
			case DialogueEngine.Screen.Line line -> {
				store.putBookmark(player.getUUID(), npc.getUUID(), step.state().currentNode());
				ServerPlayNetworking.send(player, new ShowLinePayload(
					npc.getId(), line.speaker(), line.text(), line.mode().ordinal()));
				DialogueDisplay.showing(player, npc, line.mode() == Presentation.FULLSCREEN);
			}
			case DialogueEngine.Screen.Choice choice -> {
				store.putBookmark(player.getUUID(), npc.getUUID(), step.state().currentNode());
				ServerPlayNetworking.send(player, new ShowChoicePayload(
					npc.getId(), choice.speaker(), choice.prompt(), choice.mode().ordinal(),
					choice.options().stream()
						.map(o -> new ShowChoicePayload.Option(o.index(), o.label(),
							o.colour() == null ? "" : o.colour()))
						.toList()));
				DialogueDisplay.showing(player, npc, choice.mode() == Presentation.FULLSCREEN);
			}
			case DialogueEngine.Screen.Finished _ -> {
				// The conversation is over, so the bookmark goes: the next click
				// should start again rather than resume something that ended. The
				// variables it set stay — that is the difference between finishing a
				// conversation and never having had it.
				store.clearBookmark(player.getUUID(), npc.getUUID());
				DialogueDisplay.hide(player);
			}
			// A graph may stand still and wait — that is what behaviour graphs do
			// all day. A conversation cannot: there is a player on the other side
			// of it looking at a box, and nothing here is going to come back and
			// wake it. So it is refused where it can be seen and read, rather than
			// leaving somebody staring at a screen that will never change.
			case DialogueEngine.Screen.Waiting _ -> {
				store.clearBookmark(player.getUUID(), npc.getUUID());
				NpcStudio.LOGGER.error(
					"Dialogue \"{}\" waits at \"{}\" — a conversation cannot wait, "
						+ "only a behaviour graph can.", dialogue.id(), step.state().currentNode());
				player.sendSystemMessage(Component.literal(
					"That conversation waits for something; it has been reset.")
					.withStyle(ChatFormatting.RED));
				DialogueDisplay.hide(player);
			}
		}
	}


	/** The only questions a condition may ask about the world. */
	private static Condition.World worldFor(ServerPlayer player) {
		return (itemId, count) -> {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
			int found = 0;
			for (ItemStack stack : player.getInventory()) {
				if (stack.is(item)) found += stack.getCount();
			}
			return found >= count;
		};
	}

	private static void apply(Effect effect, ServerPlayer player, NpcEntity npc) {
		switch (effect) {
			case Effect.GiveItem(String id, int count) -> {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
				ItemStack stack = new ItemStack(item, count);
				// Drop what will not fit rather than deleting it: an unnoticed loss
				// of a quest reward is far worse than an item on the floor.
				if (!player.getInventory().add(stack)) {
					player.drop(stack, false);
				}
			}
			case Effect.TakeItem(String id, int count) -> {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
				int left = count;
				for (ItemStack stack : player.getInventory()) {
					if (left <= 0) break;
					if (!stack.is(item)) continue;
					int taken = Math.min(left, stack.getCount());
					stack.shrink(taken);
					left -= taken;
				}
			}
			// Run as the server rather than as the player: a dialogue may need to do
			// things the player has no right to do, and the alternative — handing
			// the player elevated rights for a moment — is far easier to abuse.
			case Effect.RunCommand(String command) ->
				player.level().getServer().getCommands().performPrefixedCommand(
					player.createCommandSourceStack()
						.withMaximumPermission(PermissionSet.ALL_PERMISSIONS),
					command);
			case Effect.PlayAnimation(String animation, int ticks) -> npc.playGesture(animation, ticks);
			case Effect.Express(String expression, int ticks) -> npc.express(expression, ticks);
			case Effect.PlaySound _, Effect.PlaceStructure _ ->
				NpcStudio.LOGGER.debug("effect {} is not built yet", effect);
		}
	}
}
