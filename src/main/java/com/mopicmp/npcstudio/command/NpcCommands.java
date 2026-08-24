package com.mopicmp.npcstudio.command;

import java.util.Comparator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueEditing;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime;
import com.mopicmp.npcstudio.entity.ModelObject;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.entity.NpcStudioEntities;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * The mod's own commands, under one root.
 *
 * Everything lives under {@code /npc} rather than being scattered among
 * vanilla's commands, so a map maker can tell at a glance which mod a command
 * belongs to — the same reason WorldEdit puts everything behind {@code //}.
 *
 * Permissions are not our business. Server owners already have plugins that
 * grant and deny commands, so gating {@code /npc} there is enough, and writing
 * our own permission system would only be a second thing for them to configure.
 */
public final class NpcCommands {

	private NpcCommands() { }

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("spawn")
				.executes(context -> spawn(context, "Steve"))
				.then(Commands.argument("skin", StringArgumentType.word())
					.executes(context -> spawn(context, StringArgumentType.getString(context, "skin")))))
			.then(Commands.literal("dialogue")
				.then(Commands.argument("id", StringArgumentType.greedyString())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(DialogueRegistry.names(), builder))
					.executes(context -> assign(context, StringArgumentType.getString(context, "id")))))
			.then(Commands.literal("edit")
				.executes(context -> browse(context))
				.then(Commands.argument("id", StringArgumentType.greedyString())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(DialogueRegistry.names(), builder))
					.executes(context -> edit(context, StringArgumentType.getString(context, "id")))))
			.then(Commands.literal("model")
				.then(Commands.argument("name", StringArgumentType.word())
					.executes(context -> place(context,
						StringArgumentType.getString(context, "name")))))
			.then(Commands.literal("settings")
				.executes(NpcCommands::settings))
			.then(Commands.literal("answer")
				.then(Commands.argument("option", IntegerArgumentType.integer(0))
					.executes(context -> answer(context, IntegerArgumentType.getInteger(context, "option"))))));
	}

	/**
	 * Puts a model where the caller stands.
	 *
	 * A command rather than a button in the modelling window, and that is a
	 * deliberate first step rather than an oversight: only the server may add an
	 * entity to a world, so a button would need a packet, and a packet needs
	 * deciding who may send it and what happens when the name is one this server
	 * has never heard of. The command answers all three by being a command —
	 * permissions are already checked above, and the name is whatever was typed.
	 *
	 * The name is not checked against anything here. The server has no models: a
	 * model is a document on the client that drew it, so what the server stores is
	 * a name and what a client without that model sees is nothing. Refusing names
	 * the server cannot verify would mean refusing all of them.
	 */
	private static int place(CommandContext<CommandSourceStack> context, String name) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Vec3 at = source.getPosition();

		ModelObject object = ModelObject.TYPE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		if (object == null) {
			source.sendFailure(Component.literal("Could not make the object."));
			return 0;
		}
		object.snapTo(at.x, at.y, at.z, source.getRotation().y, 0);
		object.setModel(name);
		level.addFreshEntity(object);

		source.sendSuccess(() -> Component.literal("Placed " + name + "."), true);
		return 1;
	}

	/**
	 * Places an NPC where the caller stands, wearing the named player's skin.
	 *
	 * Facing the opposite way to the caller on purpose: an NPC that spawns
	 * looking at you reads as placed, whereas one facing the same way looks like
	 * it was dropped there by mistake.
	 */
	private static int spawn(CommandContext<CommandSourceStack> context, String skin) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Vec3 where = source.getPosition();

		NpcEntity npc = NpcStudioEntities.NPC.create(level, EntitySpawnReason.COMMAND);
		if (npc == null) {
			source.sendFailure(Component.literal("Could not create the NPC."));
			return 0;
		}

		ServerPlayer caller = source.getPlayer();
		float facing = caller == null ? 0f : caller.getYRot() + 180f;
		npc.snapTo(where.x, where.y, where.z, facing, 0f);
		npc.setSkin(skin);
		level.addFreshEntity(npc);

		source.sendSuccess(() -> Component.literal("Placed an NPC wearing " + skin + "'s skin."), true);
		return 1;
	}

	/**
	 * Opens the editor.
	 *
	 * With no name it starts a new dialogue; the server picks one that is free,
	 * so pressing this twice does not overwrite the first attempt.
	 */
	/** With no name, show what there is rather than guessing. */
	private static int browse(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		DialogueEditing.browse(context.getSource().getPlayerOrException());
		return 1;
	}

	private static int edit(CommandContext<CommandSourceStack> context, String id)
			throws CommandSyntaxException {
		DialogueEditing.open(context.getSource().getPlayerOrException(), id);
		return 1;
	}

	// Dialogue names carry a namespace, so they contain a colon — and Brigadier's
	// word() refuses one, which made every datapack dialogue impossible to type.
	// greedyString takes the rest of the line, which is right for a trailing
	// argument and accepts anything a name can hold.

	/**
	 * Opens the settings of the nearest NPC.
	 *
	 * The screen is not built here — the server sends the character's details and
	 * the client puts them on screen. That way there is one description of an NPC
	 * travelling in one direction, and no chance of a screen showing something the
	 * server does not think is true.
	 */
	private static int settings(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC nearby."));
			return 0;
		}
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
			source.getPlayerOrException(), com.mopicmp.npcstudio.net.NpcEditing.describe(npc));
		return 1;
	}

	/** Gives the nearest NPC a conversation to hold. */
	private static int assign(CommandContext<CommandSourceStack> context, String dialogueId)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		if (DialogueRegistry.get(dialogueId).isEmpty()) {
			source.sendFailure(Component.literal("No dialogue called \"" + dialogueId + "\". Known: "
				+ String.join(", ", DialogueRegistry.names())));
			return 0;
		}
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		npc.setDialogueId(dialogueId);
		source.sendSuccess(() -> Component.literal("That NPC now holds \"" + dialogueId + "\"."), true);
		return 1;
	}

	/**
	 * Answers the question the nearest NPC is asking.
	 *
	 * A command because the dialogue screen does not exist yet. It goes away the
	 * moment the interface does — nobody should be typing numbers at an NPC.
	 */
	private static int answer(CommandContext<CommandSourceStack> context, int option)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		DialogueRuntime.choose(source.getPlayerOrException(), npc, option);
		return 1;
	}

	/**
	 * The NPC the caller is standing next to.
	 *
	 * Eight blocks, and the closest one wins. Picking by proximity rather than by
	 * what the player is looking at is deliberate: aiming at a specific NPC in a
	 * crowd is fiddly, and these commands are scaffolding for testing, not the
	 * editor they will eventually be replaced by.
	 */
	private static NpcEntity nearest(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return source.getLevel()
			.getEntitiesOfClass(NpcEntity.class, player.getBoundingBox().inflate(8.0))
			.stream()
			.min(Comparator.comparingDouble(npc -> npc.distanceToSqr(player)))
			.orElse(null);
	}
}
