package com.mopicmp.npcstudio.client;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.client.dialogue.DialogueClientState;
import com.mopicmp.npcstudio.client.dialogue.CutsceneScreen;
import com.mopicmp.npcstudio.client.dialogue.DialogueCamera;
import com.mopicmp.npcstudio.client.dialogue.DialogueHud;
import com.mopicmp.npcstudio.client.dialogue.DialogueScreen;
import com.mopicmp.npcstudio.client.editor.DialogueListScreen;
import com.mopicmp.npcstudio.client.editor.GraphEditorScreen;
import com.mopicmp.npcstudio.client.editor.EditorState;
import com.mopicmp.npcstudio.client.emote.EmoteLibrary;
import com.mopicmp.npcstudio.client.entity.ClientNpcEntity;
import com.mopicmp.npcstudio.client.entity.NpcRenderer;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.entity.NpcStudioEntities;
import com.mopicmp.npcstudio.net.AnswerPayload;
import com.mopicmp.npcstudio.net.CloseDialoguePayload;
import com.mopicmp.npcstudio.net.EditorPayloads;
import com.mopicmp.npcstudio.net.ShowChoicePayload;
import com.mopicmp.npcstudio.net.ShowLinePayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.server.packs.PackType;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.renderer.entity.EntityRenderer;

public class NpcStudioClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Swap in the client subclass before anything can spawn. The entity type
		// reads this at spawn time, so it has to be set during client init and not
		// later — an NPC that loaded first would come out as the server class and
		// then fail to render, which looks like a rendering bug rather than an
		// ordering one.
		NpcEntity.factory = ClientNpcEntity::new;

		// Only the index is read here — a list of names, not a hundred and seventy
		// megabytes of keyframes. The movement of an emote is parsed the first time
		// something plays it, so a session that never opens the picker never pays
		// for the pack at all.
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
			new SimpleSynchronousResourceReloadListener() {
				@Override
				public net.minecraft.resources.Identifier getFabricId() {
					return NpcStudio.id("emotes");
				}

				@Override
				public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
					EmoteLibrary.reload(manager);
				}
			});

		// `false` is the wide model. Vanilla builds one renderer per model type and
		// picks between them; a single wide one is enough until slim NPCs are a
		// thing we actually support, and guessing at the plumbing early would only
		// mean rewriting it.
		//
		// The cast is unavoidable and it is safe, but only for one reason: the
		// entity type is declared over NpcEntity while the renderer is declared
		// over ClientNpcEntity, and generics will not bridge that on their own.
		// Every NPC that exists on this side really is a ClientNpcEntity — the
		// factory above guarantees it — so the two always agree at run time. If
		// that factory line is ever removed, this is where it will fail, with a
		// class cast in the middle of rendering rather than an error at startup.
		EntityRendererRegistry.register(NpcStudioEntities.NPC, context -> {
			@SuppressWarnings("unchecked")
			EntityRenderer<NpcEntity, ?> renderer =
				(EntityRenderer<NpcEntity, ?>) (EntityRenderer<?, ?>)
					new NpcRenderer(context, false);
			return renderer;
		});

		com.mopicmp.npcstudio.client.workspace.WorkspaceKey.register();

		// A placed model draws itself from the client's own store of documents; the
		// entity only says which one. See ModelObject for why the geometry does not
		// travel.
		// Nothing is drawn for the camera; its marker is an overlay, like the
		// handles. See CameraRenderer for why the empty renderer has to exist.
		EntityRendererRegistry.register(com.mopicmp.npcstudio.entity.SceneCamera.TYPE,
			com.mopicmp.npcstudio.client.entity.CameraRenderer::new);
		EntityRendererRegistry.register(com.mopicmp.npcstudio.entity.ModelObject.TYPE,
			com.mopicmp.npcstudio.client.model.ModelObjectRenderer::new);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			com.mopicmp.npcstudio.client.model.ModelStore.forget());

		ClientPlayNetworking.registerGlobalReceiver(ShowLinePayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				DialogueClientState.show(payload);
				present(context.client());
			}));
		ClientPlayNetworking.registerGlobalReceiver(ShowChoicePayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				DialogueClientState.show(payload);
				present(context.client());
			}));
		ClientPlayNetworking.registerGlobalReceiver(CloseDialoguePayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				DialogueClientState.close();
				if (DialogueScreen.isOpen() || CutsceneScreen.isOpen()) context.client().setScreenAndShow(null);
			}));

		// A model the world knows about, arriving. Held rather than written: this
		// client's models folder is the author's workspace, and filling it with
		// everything anybody else has ever drawn would be taking it over. Held is
		// enough — it is what the renderer reads from.
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.NpcPayloads.ModelDocument.TYPE,
			(payload, context) -> context.client().execute(() -> {
				try {
					com.mopicmp.npcstudio.client.model.ModelStore.hold(payload.name(),
						com.mopicmp.npcstudio.model.ModelIO.readBlockbench(
							com.google.gson.JsonParser.parseString(payload.json())
								.getAsJsonObject()));
				} catch (RuntimeException unreadable) {
					com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
						"A model arrived that will not read: {}", unreadable.toString());
				}
			}));

		// A scene the world knows about. Held rather than written, as models are:
		// the world's copy is the record and this one is a draft of it.
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.ScenePayloads.Document.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.scene.Scenes.accept(payload.name(), payload.json())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.ScenePayloads.Gone.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.scene.Scenes.gone(payload.name())));

		// The scene's own clock, and the half-second that turns a drag into one
		// save instead of forty.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			com.mopicmp.npcstudio.client.scene.Scenes.tick();
			com.mopicmp.npcstudio.client.scene.Playing.tick();
			com.mopicmp.npcstudio.client.scene.SceneCameras.tick();
			// Noticed here rather than at the door: the workspace can be left by
			// closing the game's own screen, by a crash to the title, or by the
			// world going away, and only a tick sees all three.
			com.mopicmp.npcstudio.client.scene.Hush.tick();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			// Whatever was waiting is already lost — the connection is going — but
			// the draft must not follow us into the next world, where the ids in it
			// name different characters or nobody at all.
			com.mopicmp.npcstudio.client.scene.Playing.forget();
			com.mopicmp.npcstudio.client.scene.Scenes.forget();
			// The sky held for this world, and the receiver it was being held by.
			// Both are statements about an afternoon in one world; carried into the
			// next they would be a wrong sky with nothing on screen explaining it.
			com.mopicmp.npcstudio.client.scene.Weather.forget();
			com.mopicmp.npcstudio.client.workspace.Landing.pin(null);
		});

		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.Editing.TYPE,
			(payload, context) -> context.client().execute(() -> {
				EditorState state = EditorState.from(payload.json(), payload.names());
				if (com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()) {
					com.mopicmp.npcstudio.client.workspace.panel.GraphPanel.accept(state);
					return;
				}
				context.client().setScreenAndShow(new GraphEditorScreen(state));
			}));
		ClientPlayNetworking.registerGlobalReceiver(com.mopicmp.npcstudio.net.WardrobePayloads.Library.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.wardrobe.Costumes.accept(payload.costumes())));
		ClientPlayNetworking.registerGlobalReceiver(com.mopicmp.npcstudio.net.WardrobePayloads.Versions.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.wardrobe.Costumes.acceptVersions(payload.names())));
		ClientPlayNetworking.registerGlobalReceiver(com.mopicmp.npcstudio.net.WardrobePayloads.Marks.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.wardrobe.Costumes.acceptMarks(payload.marks())));
		ClientPlayNetworking.registerGlobalReceiver(com.mopicmp.npcstudio.net.WardrobePayloads.Picture.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.wardrobe.Costumes.acceptPicture(
					payload.fingerprint(), payload.index(), payload.count(), payload.part())));
		ClientPlayNetworking.registerGlobalReceiver(com.mopicmp.npcstudio.net.NpcPayloads.SkinFor.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.skin.CustomSkins.accept(payload.entityId(), payload.pixels())));
		ClientPlayNetworking.registerGlobalReceiver(com.mopicmp.npcstudio.net.NpcPayloads.Details.TYPE,
			(payload, context) -> context.client().execute(() -> {
				// The workspace is the one door now. Asking a character for its
				// details is asking to work on it, so this both opens the workspace
				// and says which character it is about — the two used to be one
				// screen and there is no reason for them to become two steps.
				com.mopicmp.npcstudio.client.workspace.Workspace.select(payload.entityId());
				if (!com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()) {
					com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.show(context.client());
				}
				com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.deliver(
					com.mopicmp.npcstudio.client.workspace.panel.CharacterPanel.class,
					panel -> panel.accept(payload));
			}));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.BenchPayloads.Told.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.deliver(
					com.mopicmp.npcstudio.client.workspace.panel.BenchPanel.class,
					panel -> panel.accept(payload))));
		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.Listing.TYPE,
			(payload, context) -> context.client().execute(() -> {
				// Always remembered, because two screens want the same answer for
				// different reasons: one to show a list to choose from, the other to
				// fill a drop-down. Asking twice would be one packet more and one
				// state more to keep in step.
				com.mopicmp.npcstudio.client.editor.DialogueNames.remember(payload.graphs());
				// A screen that asked only for the names keeps itself; anything else
				// meant "show me the list".
				// In the workspace the list belongs to the dialogue panel, which is
				// where the graph is going to appear anyway. Outside it, asking for
				// the list still means asking to see it on a screen of its own.
				if (com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()) {
					com.mopicmp.npcstudio.client.workspace.panel.GraphPanel.choose();
					return;
				}
				context.client().setScreenAndShow(new DialogueListScreen(payload.names()));
			}));
		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.Saved.TYPE,
			(payload, context) -> context.client().execute(() -> {
				// Only the editor cares, and only if it is still open — the player may
				// have closed it while the save was in flight.
				GraphEditorScreen editor = GraphEditorScreen.current();
				if (editor != null) editor.saveResult(payload.ok(), payload.message());
			}));

		// Entity ids are handed out per world, so a skin remembered against one
		// would be worn by whatever happens to hold that number in the next.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			com.mopicmp.npcstudio.client.skin.CustomSkins.forget();
			com.mopicmp.npcstudio.client.wardrobe.Costumes.forget();
			com.mopicmp.npcstudio.client.entity.ShapeEditing.end();
			com.mopicmp.npcstudio.client.wardrobe.TryingOn.forget();
		});

		// The state is static, so it outlives the world it belonged to. Without
		// this, a line from the last server would still be on screen when the next
		// one loads.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> DialogueClientState.close());

		// Right-clicking an NPC mid-line should finish the line rather than skip
		// past it. The click still travels to the server as usual; this only
		// decides what the player sees while it is in flight.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (entity instanceof NpcEntity) DialogueClientState.skipTyping();
			return InteractionResult.PASS;
		});

		// The turn towards the speaker is stepped on every client tick.
		ClientTickEvents.END_CLIENT_TICK.register(DialogueCamera::tick);

		// Placed objects are told what shape they are, on a slow beat and on every
		// client — not only while the editor is open. A client works out where the
		// player may walk before the server confirms it, so one that thinks an object
		// is a single box while the server knows it is three disagrees on every step
		// taken near it, and disagreeing about collision is what rubber-banding is.
		ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
			private int beat;

			@Override
			public void onEndTick(net.minecraft.client.Minecraft client) {
				if (client.level == null) return;
				// Every tick while the editor is open, because the outline is drawn from
				// these and an outline that catches up a second after the box it belongs
				// to has been dragged reads as an outline that does not follow at all.
				// A slow beat otherwise: nothing is changing shape then.
				boolean editing = com.mopicmp.npcstudio.client.model.ModellingScreen.showing();
				if (!editing && ++beat < 20) return;
				beat = 0;
				com.mopicmp.npcstudio.client.model.Modelling.refreshColliders();
			}
		});

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			NpcStudio.id("dialogue"), DialogueHud::render);

		// Last of everything, and that is the whole requirement: a scene fading out
		// has to take the hotbar and the crosshair with it, or what fades is the
		// world with the interface still floating over it. See {@code Titles} for why
		// this is here rather than in a panel — a panel draws nothing while filming,
		// which is precisely when a fade matters most.
		HudElementRegistry.addLast(NpcStudio.id("titles"),
			com.mopicmp.npcstudio.client.scene.Titles::render);

		// Chat and a conversation want the same corner of the screen, and chat
		// wins by being taller. While an NPC is speaking the messages step aside —
		// but not when the chat screen is open, because then the player is reading
		// or typing and hiding it would be taking something away.
		HudElementRegistry.replaceElement(VanillaHudElements.CHAT, chat -> (graphics, delta) -> {
			boolean talking = DialogueClientState.current() != null;
			if (!talking || DialogueClientState.isChatOpen()) chat.extractRenderState(graphics, delta);
		});

		// Answers can be clicked once the chat screen has given the player a
		// cursor. A stopgap until the full-screen mode has a pointer of its own,
		// but it beats typing numbers at a shopkeeper.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof ChatScreen)) return;
			DialogueClientState.chatOpened();
			ScreenEvents.remove(screen).register(closed -> DialogueClientState.chatClosed());

			ScreenMouseEvents.allowMouseClick(screen).register((clicked, mouse) -> {
				DialogueClientState state = DialogueClientState.current();
				if (state == null || state.options().isEmpty()) return true;
				int hovered = DialogueHud.hoveredOption(state);
				if (hovered < 0) return true;
				ClientPlayNetworking.send(
					new AnswerPayload(state.npcId(), state.options().get(hovered).index()));
				// Swallow the click so it does not also land on whatever is behind,
				// and close the chat, since answering was the reason it was open.
				client.setScreenAndShow(null);
				return false;
			});
		});
	}

	/**
	 * Puts the current moment where it belongs.
	 *
	 * A fullscreen line opens the screen; anything else takes it away again, so a
	 * scene that starts in a cutscene and ends with a passing remark does not
	 * leave the player staring at a panel.
	 */
	private static void present(net.minecraft.client.Minecraft client) {
		DialogueClientState state = DialogueClientState.current();
		if (state != null && state.isCutscene()) {
			// A new screen every line, and that is fine: the flight refuses to
			// restart while it is already aimed at the same NPC, so several lines
			// read as one continuous shot rather than as a camera that jumps back
			// to the player between them.
			client.setScreenAndShow(new CutsceneScreen(state));
		} else if (state != null && state.takesOverScreen()) {
			client.setScreenAndShow(new DialogueScreen(state));
		} else if (DialogueScreen.isOpen() || CutsceneScreen.isOpen()) {
			client.setScreenAndShow(null);
		}
	}
}
