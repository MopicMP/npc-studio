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
		NpcStudioNet.register();
		DialogueRegistry.registerBuiltIn();

		// Dialogues come from datapacks, so they reload with /reload — a writer
		// iterating on a scene should not have to restart the server to see a
		// changed line.
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new DialogueLoader());

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
			NpcCommands.register(dispatcher));

		// The line on screen has a much shorter life than the bookmark behind it:
		// it belongs to a player standing in front of an NPC, and has to come down
		// when either of those stops being true.
		ServerTickEvents.END_SERVER_TICK.register(DialogueDisplay::tick);

		// Dialogues written in-game live in the world, so they only exist once a
		// world is open. Without this they would be invisible to the registry
		// until somebody saved one again.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			WorldDialogues.of(server.overworld()).publish();
			// The costume library belongs to the world, so it opens when the world
			// does and closes with it — the same life as the dialogues beside it.
			com.mopicmp.npcstudio.wardrobe.Wardrobes.open(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.close());
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			DialogueDisplay.forget(handler.getPlayer()));

		LOGGER.info("NPC Studio ready");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
