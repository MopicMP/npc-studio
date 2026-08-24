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
			com.mopicmp.npcstudio.wardrobe.Wardrobes.close();
			com.mopicmp.npcstudio.model.ServerModels.close();
			com.mopicmp.npcstudio.scene.ServerScenes.close();
		});

		// Everything the world knows how to draw, handed over on the way in. Before
		// this, a player who had not drawn a model saw an empty patch of air where
		// somebody else's ship was, and there was no way for them to ever see it.
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

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			DialogueDisplay.forget(handler.getPlayer());
			// A scene half sent when somebody's connection went is a scene nobody is
			// going to finish, and holding its pieces is holding memory for nothing.
			com.mopicmp.npcstudio.net.SceneEditing.forget(handler.getPlayer());
		});

		LOGGER.info("NPC Studio ready");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
