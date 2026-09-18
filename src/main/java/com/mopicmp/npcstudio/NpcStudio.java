package com.mopicmp.npcstudio;

import com.mopicmp.npcstudio.command.NpcCommands;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueDisplay;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueLoader;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;
import com.mopicmp.npcstudio.dialogue.runtime.WorldDialogues;
import com.mopicmp.npcstudio.entity.NpcStudioEntities;
import com.mopicmp.npcstudio.net.NpcStudioNet;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NpcStudio implements ModInitializer {
	public static final String MOD_ID = "npc_studio";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		NpcStudioEntities.register();
		// What one particular stack of something remembers. Registered before anything
		// can load an item: a component nobody registers is a component that silently is
		// not there, and a sword would come back from the save having forgotten.
		com.mopicmp.npcstudio.dialogue.runtime.Kept.register();
		NpcStudioNet.register();
		DialogueRegistry.registerBuiltIn();

		// Dialogues come from datapacks, so they reload with /reload — a writer
		// iterating on a scene should not have to restart the server to see a
		// changed line.
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new DialogueLoader());

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
			NpcCommands.register(dispatcher);
			// The way into a lesson, and — more to the point — the way back out of one
			// when whatever is inside has gone wrong.
			com.mopicmp.npcstudio.command.LessonCommands.register(dispatcher);
		});

		// The line on screen has a much shorter life than the bookmark behind it:
		// it belongs to a player standing in front of an NPC, and has to come down
		// when either of those stops being true.
		ServerTickEvents.END_SERVER_TICK.register(DialogueDisplay::tick);
		// A conversation standing at a node that has not finished happening. Free when
		// there are none, which is nearly always — and it is what makes "say a line,
		// walk to the gate, say another" a thing anybody can write.
		ServerTickEvents.END_SERVER_TICK.register(
			com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime::tick);

		// Somebody used a block, and a map's own rules get to answer before the game's.
		//
		// This is the whole of "give the player an ability the game has not got": a
		// campfire wants a flint and steel to light and a shovel to put out, so "use it
		// to toggle it" is not a rule vanilla has — and a rule is exactly what a graph
		// is for.
		//
		// Both hands' worth of the event, because the game asks the block first when the
		// hand is empty and asks the item first when it is not, and a rule about a block
		// should not care which. Returning a result stops the game doing its own thing;
		// returning null lets it through, which is what a rule written to happen
		// *beside* vanilla asks for.
		net.fabricmc.fabric.api.event.player.BlockEvents.USE_WITHOUT_ITEM.register(
			(state, level, at, player, hit) -> {
				if (!(player instanceof net.minecraft.server.level.ServerPlayer server)) return null;
				// A hologram standing in front of the block gets the click first, and only
				// as far as the block: one hanging behind a wall is not pressable through
				// it, which is what anybody looking at the wall expects.
				if (pressedFirst(server, hit.getLocation().distanceTo(server.getEyePosition()))) {
					return net.minecraft.world.InteractionResult.SUCCESS;
				}
				return com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime.used(server, at)
					? net.minecraft.world.InteractionResult.SUCCESS : null;
			});

		// And an item used in the air, which is what a rule about a kind of item answers.
		// Separate from the two above because the game asks a different question — this
		// one is not about a block at all — and because a document about things wants the
		// stack itself as its subject, not the place it was pointed at.
		net.fabricmc.fabric.api.event.player.ItemEvents.USE.register(
			(level, player, hand) -> {
				if (!(player instanceof net.minecraft.server.level.ServerPlayer server)) return null;
				// Nothing was hit, so a press carries as far as an arm does.
				if (pressedFirst(server, server.blockInteractionRange())) {
					return net.minecraft.world.InteractionResult.SUCCESS;
				}
				return com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime.usedItem(server, hand)
					? net.minecraft.world.InteractionResult.SUCCESS : null;
			});
		net.fabricmc.fabric.api.event.player.BlockEvents.USE_ITEM_ON.register(
			(stack, state, level, at, player, hand, hit) -> {
				if (!(player instanceof net.minecraft.server.level.ServerPlayer server)) return null;
				if (pressedFirst(server, hit.getLocation().distanceTo(server.getEyePosition()))) {
					return net.minecraft.world.InteractionResult.SUCCESS;
				}
				return com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime.used(server, at)
					? net.minecraft.world.InteractionResult.SUCCESS : null;
			});

		// Dialogues written in-game live in the world, so they only exist once a
		// world is open. Without this they would be invisible to the registry
		// until somebody saved one again.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			WorldDialogues.of(server.overworld()).publish();
			// Says so if the lessons' world did not arrive — a datapack turned off, or a
			// world whose packs were edited. Otherwise that shows up as locations quietly
			// having nowhere to be laid out.
			com.mopicmp.npcstudio.map.Lesson.settle(server);
			// Lessons left standing by a world that did not stop cleanly. Nobody is
			// inside any of them after a restart — the notes and the blocks are both in
			// the save, and only the notes say which blocks are ours.
			com.mopicmp.npcstudio.map.Visits.sweep(server);
			// Walls left standing from last time, swept before anybody can walk into
			// them. A wall belongs to a conversation and no conversation survives a
			// restart, so anything recorded here means the last run of this world did
			// not get to stop — a crash, or the power going. The blocks are still in
			// the save; the list beside them is how we know which ones are ours.
			var walls = com.mopicmp.npcstudio.dialogue.runtime.Walls.of(server.overworld());
			if (walls.count() > 0) {
				LOGGER.info("Sweeping up {} wall(s) left standing by a world that did not stop cleanly.",
					walls.count());
				walls.dropEverything(
					com.mopicmp.npcstudio.dialogue.runtime.Walls.in(server.overworld()));
			}
			// The costume library belongs to the world, so it opens when the world
			// does and closes with it — the same life as the dialogues beside it.
			com.mopicmp.npcstudio.wardrobe.Wardrobes.open(server);
			com.mopicmp.npcstudio.wardrobe.Portraits.open(server);
			// And the layout sheets that say how those portraits are assembled. Beside
			// the pictures rather than in a config folder, for the reason written at
			// PuppetShelf: config does not travel with the map.
			com.mopicmp.npcstudio.puppet.Puppets.open(server);
			// And the models, for the same reason and one more: an object standing in
			// a world is part of that world, so the drawing behind it has to travel
			// with the world rather than sit in one person's config folder.
			com.mopicmp.npcstudio.model.ServerModels.open(server);
			// And the scenes, which are the strongest case of the three: a scene
			// names the characters in it by ids this world hands out, so anywhere
			// else it is a list of pointers to nothing.
			com.mopicmp.npcstudio.scene.ServerScenes.open(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			// Before anything else, because it writes blocks and the world is about to
			// be saved. A wall is the one thing this mod leaves in somebody else's
			// world, and leaving one is invisible: no picture, full collision, and
			// nothing anywhere saying it is there.
			com.mopicmp.npcstudio.dialogue.runtime.Walls.of(server.overworld())
				.dropEverything(com.mopicmp.npcstudio.dialogue.runtime.Walls.in(server.overworld()));
			com.mopicmp.npcstudio.wardrobe.Wardrobes.close();
			com.mopicmp.npcstudio.wardrobe.Portraits.close();
			com.mopicmp.npcstudio.puppet.Puppets.close();
			com.mopicmp.npcstudio.model.ServerModels.close();
			com.mopicmp.npcstudio.scene.ServerScenes.close();

			// And the fight recorder, if one was left running.
			//
			// It is not a document of this world — it writes beside the game rather than
			// inside the save — but it counts in this world's ticks. Left rolling into
			// the next one, its own limit is measured from a moment that has not
			// happened there, so the recording that should stop itself after twenty
			// seconds instead never stops and quietly goes on writing.
			//
			// Stopping it also writes out what it has, which is the point of having
			// recorded it: leaving a world in the middle of a fight is exactly when the
			// evidence is worth keeping.
			if (com.mopicmp.npcstudio.foe.Tape.rolling()) {
				LOGGER.info("Fight tape stopped with the world: {}",
					com.mopicmp.npcstudio.foe.Tape.stop());
			}
		});

		// Everything the world knows how to draw, handed over on the way in. Before
		// this, a player who had not drawn a model saw an empty patch of air where
		// somebody else's ship was, and there was no way for them to ever see it.
		// Somebody who logged out inside a lesson. Copies do not survive a restart, so
		// they would arrive standing in a void with no ground under them — and the
		// author's own rule already answers it: coming back is a reset, so they are put
		// at the hub.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			com.mopicmp.npcstudio.map.Visits.arrived(handler.getPlayer()));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (String name : com.mopicmp.npcstudio.model.ServerModels.names()) {
				String json = com.mopicmp.npcstudio.model.ServerModels.written(name);
				if (json != null) {
					sender.sendPacket(new com.mopicmp.npcstudio.net.NpcPayloads.ModelDocument(
						name, json));
				}
			}
			// The scenes as well, for the reason a scene exists at all: it has to be
			// the same one it was before the world was closed.
			for (String name : com.mopicmp.npcstudio.scene.ServerScenes.names()) {
				String json = com.mopicmp.npcstudio.scene.ServerScenes.written(name);
				if (json != null) {
					sender.sendPacket(new com.mopicmp.npcstudio.net.ScenePayloads.Document(
						name, json));
				}
			}
		});

		// What the map asks of somebody who has just appeared. Sent rather than waited
		// for, because the client that needs it most is the one nobody will ever open
		// a panel on: a player, arriving, whose brightness and drawing distance the
		// map has an opinion about.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			com.mopicmp.npcstudio.net.MapEditing.send(handler.getPlayer()));

		// And the standing properties, at once rather than on the next beat.
		//
		// The sweep inside it is the point: an attribute modifier lives in the player's
		// own saved data and survives a restart, so a rule deleted from a document while
		// they were away would otherwise leave its property on them for ever, with
		// nothing anywhere to say why. Same shape as a wall left standing, same answer.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime.settle(handler.getPlayer()));

		// The named places, on the way in and unasked. They are drawn in the world
		// rather than in a panel, so waiting for a panel to open would mean a map's
		// points are invisible to anybody who has not opened the workspace.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			com.mopicmp.npcstudio.net.SpotEditing.send(handler.getPlayer()));

		// And again on every arrival in a different one, because places belong to a
		// level rather than to the map. Without this a client keeps whichever list it
		// was last handed: walk through a portal and the overworld's points are still
		// on the screen, drawn at those coordinates in the nether — markers standing in
		// places nobody put them, which is worse than none.
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
			.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
				com.mopicmp.npcstudio.net.SpotEditing.send(player));
		// A death moves a player too, and by a route that is not a level change: the
		// old body is replaced rather than carried, so the packet has to be aimed at
		// the new one.
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN
			.register((oldPlayer, newPlayer, alive) ->
				com.mopicmp.npcstudio.net.SpotEditing.send(newPlayer));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			DialogueDisplay.forget(handler.getPlayer());
			// Their lesson goes down with them. Not out of tidiness: the ground would
			// otherwise hold a stretch of the row against nobody, and coming back is a
			// reset anyway, so there is nothing in it worth keeping.
			com.mopicmp.npcstudio.map.Visits.parted(handler.getPlayer());
			com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime.left(handler.getPlayer());
			// A scene half sent when somebody's connection went is a scene nobody is
			// going to finish, and holding its pieces is holding memory for nothing.
			com.mopicmp.npcstudio.net.SceneEditing.forget(handler.getPlayer());
		});

		LOGGER.info("NPC Studio ready");
	}

	/**
	 * Whether a graph was waiting to be told this was pressed.
	 *
	 * Asked before the block rules and before the game itself, because a hologram hangs in
	 * front of things: a menu on a wall would otherwise be a wall being clicked. It answers
	 * no unless some graph of this player's is actually standing on a wait for the thing
	 * under the crosshair, so a label that is only a label lets the click through to
	 * whatever is behind it.
	 */
	private static boolean pressedFirst(net.minecraft.server.level.ServerPlayer player,
			double reach) {
		return com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime.pressed(player, reach);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
