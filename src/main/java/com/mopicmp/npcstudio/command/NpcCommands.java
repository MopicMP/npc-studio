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
			.then(Commands.literal("watch")
				.executes(context -> watch(context, null))
				.then(Commands.literal("on").executes(context -> watch(context, true)))
				.then(Commands.literal("off").executes(context -> watch(context, false))))
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

	/**
	 * Turns the nearest NPC into something that keeps an eye out, and reads it back.
	 *
	 * <h2>Why a command and why it also reports</h2>
	 *
	 * Scaffolding, like the rest of this file — the switch belongs in the character
	 * panel and will move there. It reports as well as sets because noticing is
	 * invisible from outside: a character that has seen you and one that has not
	 * are the same character standing still, and the only difference is a number.
	 * Without a way to read that number the first question after every change would
	 * be "did it work", answerable only by staring.
	 *
	 * With no argument it says where things stand, which is the form actually used
	 * while testing: walk about, run it, see what the character thinks.
	 *
	 * @param wanted true or false to set it, null to only ask
	 */
	private static int watch(CommandContext<CommandSourceStack> context, Boolean wanted)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		if (wanted != null) npc.setWatchful(wanted);
		if (!npc.watchful()) {
			source.sendSuccess(() -> Component.literal("That NPC is not watching."), false);
			return 1;
		}

		var watch = npc.watch();
		var quarry = watch.quarry();

		// What the caller themselves sounds like from here, whether or not they are
		// the one being watched. This is the line that would have saved a round trip:
		// hearing had never worked at all, and from outside that is indistinguishable
		// from hearing that does not reach far enough.
		if (source.getEntity() instanceof net.minecraft.world.entity.player.Player asking) {
			float loud = watch.loudnessOf(asking);
			float reaching = watch.hearing(asking);
			source.sendSuccess(() -> Component.literal(
				"you: " + Math.round(loud * 100) + "% loud, reaching her at "
					+ Math.round(reaching * 100) + "%"
					+ (reaching <= 0 && loud > 0 ? " (inaudible from there)" : "")), false);
		}
		// The alarm as a percentage rather than a fraction: this is read at a glance
		// while walking backwards, and "62%" is legible where "0.6183" is not.
		// How much of the world's own racket is currently within earshot — the line
		// that says whether the sound hooks are firing at all, which is otherwise
		// indistinguishable from nothing having happened.
		long now = npc.level().getGameTime();
		int noises = 0;
		for (var rumour : com.mopicmp.npcstudio.foe.Din.since(now)) {
			if (com.mopicmp.npcstudio.foe.Noise.heard(npc.position().distanceTo(rumour.at()),
					rumour.loudness(), rumour.carries()) > 0) {
				noises++;
			}
		}
		final int within = noises;
		if (within > 0) {
			source.sendSuccess(() -> Component.literal(
				within + " noise" + (within == 1 ? "" : "s") + " in earshot"), false);
		}

		String said = "watching — " + watch.mood()
			+ ", alarm " + Math.round(watch.alarm() * 100) + "%"
			+ (quarry == null ? ", nothing noticed"
				// Which sense it is going on, because the two mean very different
				// things and look identical from outside: seeing you is what makes it
				// certain, hearing you never can.
				: (watch.bySight() ? ", sees " : ", hears ") + quarry.getName().getString()
					+ " at " + Math.round(npc.distanceTo(quarry)) + " blocks");
		source.sendSuccess(() -> Component.literal(said), false);
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
