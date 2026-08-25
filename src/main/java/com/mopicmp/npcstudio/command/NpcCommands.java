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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
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
					.executes(context -> answer(context, IntegerArgumentType.getInteger(context, "option")))))
			.then(Commands.literal("arm")
				.executes(context -> arm(context, EquipmentSlot.MAINHAND, true))
				.then(Commands.literal("off")
					.executes(context -> arm(context, EquipmentSlot.MAINHAND, false))))
			.then(Commands.literal("ammo")
				.executes(context -> arm(context, EquipmentSlot.OFFHAND, true))
				.then(Commands.literal("off")
					.executes(context -> arm(context, EquipmentSlot.OFFHAND, false))))
			.then(Commands.literal("endless")
				.then(Commands.literal("on").executes(context -> endless(context, true)))
				.then(Commands.literal("off").executes(context -> endless(context, false))))
			.then(Commands.literal("senses")
				.executes(NpcCommands::senses))
			.then(Commands.literal("brain")
				.executes(context -> brain(context, null))
				.then(Commands.literal("off").executes(context -> brain(context, "")))
				.then(Commands.argument("id", StringArgumentType.greedyString())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						DialogueRegistry.names(), builder))
					.executes(context -> brain(context, StringArgumentType.getString(context, "id")))))
			.then(Commands.literal("shoot")
				.executes(context -> shoot(context, com.mopicmp.npcstudio.foe.Draw.longEnoughFor(0.95f)))
				.then(Commands.argument("draw", IntegerArgumentType.integer(1, 200))
					.executes(context ->
						shoot(context, IntegerArgumentType.getInteger(context, "draw"))))));
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

		// What she is actually attending to, in full. Everything above is how she
		// feels; this is the one line that says what about — and every round trip so
		// far has been spent working that out from the outside by watching a head.
		var lead = watch.lead();
		if (lead != null) {
			String about = lead.seen() ? "watching " + (quarry == null ? "somebody"
					: quarry.getName().getString())
				: "a noise at " + Math.round(lead.at().x) + " "
					+ Math.round(lead.at().y) + " " + Math.round(lead.at().z);
			source.sendSuccess(() -> Component.literal(
				"on: " + about
					+ " — urgency " + Math.round(lead.urgency() * 100) + "%"
					+ ", strength " + Math.round(lead.strength() * 100) + "%"
					+ ", turning " + Math.round(lead.turningAt()) + "°/tick"), false);
		}

		// And whether she is on her way somewhere, which is the one thing that cannot
		// be told apart from standing still by looking at her for a moment.
		var walk = npc.walking();
		if (walk.walking()) {
			source.sendSuccess(() -> Component.literal(
				"walking — waypoint " + walk.reached() + " of " + walk.waypoints()
					+ (walk.stuck() ? ", STUCK" : "")), false);
		}

		String said = "watching — " + watch.mood()
			+ ", alarm " + Math.round(watch.alarm() * 100) + "%"
			+ (quarry == null ? ", nobody identified"
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
	 * Every reading a graph could ask for, and what she remembers.
	 *
	 * <h2>Why this exists at all</h2>
	 *
	 * Because a graph that reads the world is invisible from outside twice over.
	 * A character standing still may be waiting on a condition that is false, or
	 * on one that is false because the reading behind it is not what anybody
	 * thought — and from the outside those are the same character standing still.
	 *
	 * Every round trip so far has been spent working that sort of thing out by
	 * watching a body. This is the cheapest possible way not to.
	 */
	private static int senses(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		for (String name : com.mopicmp.npcstudio.dialogue.Sense.KNOWN) {
			var value = com.mopicmp.npcstudio.brain.Senses.of(npc, name);
			String shown = switch (value) {
				// Rounded, because these are read at a glance while walking backwards
				// and "0.6183" is not legible where "0.62" is.
				case com.mopicmp.npcstudio.dialogue.Value.Num(double d) ->
					String.format(java.util.Locale.ROOT, "%.2f", d);
				case com.mopicmp.npcstudio.dialogue.Value.Text(String t) -> t;
				case com.mopicmp.npcstudio.dialogue.Value.Flag(boolean f) -> f ? "yes" : "no";
			};
			source.sendSuccess(() -> Component.literal("  " + name + " = " + shown), false);
		}
		var memory = npc.memory();
		source.sendSuccess(() -> Component.literal(memory.isEmpty()
			? "she remembers nothing about herself"
			: "remembers: " + new java.util.TreeMap<>(memory)), false);
		return 1;
	}

	/**
	 * Gives the nearest NPC a graph to live by, takes it away, or says which.
	 *
	 * <h2>Why this is the same registry as dialogues</h2>
	 *
	 * Because it is the same language, and pretending otherwise would have meant
	 * two of everything: two loaders, two editors, two sets of node kinds that
	 * drift apart until a condition means one thing in a conversation and another
	 * in a brain. A graph is a graph; what differs is who is running it and what
	 * they can serve.
	 *
	 * Which is why the failures are worth naming: a conversation cannot wait, and
	 * a brain cannot speak. Both refuse loudly rather than half-working.
	 */
	private static int brain(CommandContext<CommandSourceStack> context, String wanted)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		if (wanted == null) {
			String has = npc.brainId();
			source.sendSuccess(() -> Component.literal(has.isEmpty()
				? "She has no brain — she only reacts. Known graphs: "
					+ String.join(", ", DialogueRegistry.names())
				: "Her brain is \"" + has + "\"."), false);
			return 1;
		}
		if (!wanted.isEmpty() && DialogueRegistry.get(wanted).isEmpty()) {
			source.sendFailure(Component.literal("No graph called \"" + wanted + "\". Known: "
				+ String.join(", ", DialogueRegistry.names())));
			return 0;
		}
		npc.setBrainId(wanted);
		source.sendSuccess(() -> Component.literal(wanted.isEmpty()
			? "Brain off." : "She now lives by \"" + wanted + "\"."), true);
		return 1;
	}

	/**
	 * Hands the nearest NPC whatever the caller is holding, or takes it away.
	 *
	 * <h2>Why it copies the caller's hand rather than naming an item</h2>
	 *
	 * Because the point of it is modded weapons, and a modded weapon is very often
	 * not identifiable by name alone. The guns in the pack that runs on this version
	 * are carrots on sticks carrying a page of components; typing that out is
	 * error-prone in a way that holding the thing is not, and holding it is the only
	 * way to be certain the NPC has the very item the mod means.
	 *
	 * Scaffolding, like everything else in this file. Giving a character a starting
	 * item belongs in the character panel, and choosing what is in the hand from one
	 * moment to the next belongs in the graph.
	 */
	private static int arm(CommandContext<CommandSourceStack> context, EquipmentSlot slot,
			boolean giving) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		String where = slot == EquipmentSlot.MAINHAND ? "hand" : "off hand";
		if (!giving) {
			npc.setItemSlot(slot, ItemStack.EMPTY);
			source.sendSuccess(() -> Component.literal("Emptied her " + where + "."), true);
			return 1;
		}

		ItemStack holding = source.getPlayerOrException().getMainHandItem();
		if (holding.isEmpty()) {
			source.sendFailure(Component.literal("You are not holding anything."));
			return 0;
		}
		// A copy, and the whole stack rather than one of it: arrows are ammunition
		// and a quiver of one is a quiver that is empty after the first shot.
		npc.setItemSlot(slot, holding.copy());
		source.sendSuccess(() -> Component.literal(
			"Her " + where + ": " + holding.getCount() + "x "
				+ holding.getHoverName().getString()), true);
		return 1;
	}

	private static int endless(CommandContext<CommandSourceStack> context, boolean on)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		npc.setEndless(on);
		source.sendSuccess(() -> Component.literal(on
			? "She never runs out." : "She spends what she has."), true);
		return 1;
	}

	/**
	 * Orders one shot, and says enough about it to tell a failure from a refusal.
	 *
	 * The three ways this goes wrong are an empty hand, no ammunition, and a weapon
	 * that simply does not do anything when used — and from outside, all three look
	 * like a character standing still. So the readout names the item, the ammunition
	 * and the draw before anything happens, and the shot itself is audible.
	 */
	private static int shoot(CommandContext<CommandSourceStack> context, int drawFor)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		NpcEntity npc = nearest(source);
		if (npc == null) {
			source.sendFailure(Component.literal("No NPC within 8 blocks."));
			return 0;
		}
		ItemStack weapon = npc.getMainHandItem();
		if (weapon.isEmpty()) {
			source.sendFailure(Component.literal("Her hand is empty — try /npc arm."));
			return 0;
		}
		if (weapon.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem ranged) {
			ItemStack ammo = com.mopicmp.npcstudio.foe.Firing.ammoFor(npc, ranged);
			if (ammo.isEmpty()) {
				source.sendFailure(Component.literal(
					"Nothing to shoot — try /npc ammo while holding arrows, or /npc endless on."));
				return 0;
			}
		}
		// Points her at whoever asked, once, at the order. Aiming is a later step and
		// an arrow that leaves the hand correctly but flies off at a wall proves
		// nothing — and the arrow goes where the *body* is pointing, not the head,
		// which is itself worth seeing before the aiming step is designed.
		ServerPlayer at = source.getPlayerOrException();
		float yaw = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(
			npc.getX(), npc.getZ(), at.getX(), at.getZ());
		Vec3 eye = npc.getEyePosition();
		Vec3 theirs = at.getEyePosition();
		npc.setYRot(yaw);
		npc.yBodyRot = yaw;
		npc.setYHeadRot(yaw);
		npc.setXRot(com.mopicmp.npcstudio.foe.Neck.pitchTo(
			eye.x, eye.y, eye.z, theirs.x, theirs.y, theirs.z));

		npc.fire(drawFor);
		source.sendSuccess(() -> Component.literal(
			"Firing " + weapon.getHoverName().getString()
				+ " — drawing " + drawFor + " ticks, "
				+ Math.round(com.mopicmp.npcstudio.foe.Draw.powerOf(drawFor) * 100) + "% power"), false);
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
