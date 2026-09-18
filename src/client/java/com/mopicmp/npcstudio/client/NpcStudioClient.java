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

		// The way into the server manager, from the screen where somebody is
		// already looking at servers.
		com.mopicmp.npcstudio.client.server.ServerEntry.register();

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

		// The map's start. The settings arrive on the way in and are applied at once;
		// leaving puts every one of them back, and that is not optional — see
		// StartOptions for what these numbers are and where they live afterwards.
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.MapPayloads.Start.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.map.Started.took(payload.start())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.MapPayloads.Spawn.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.map.Started.told(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(
			com.mopicmp.npcstudio.client.map.Started::tick);

		// A second jump, watched for on the side where a jump happens. Free when nobody
		// has the ability: it is a key read and a list lookup, and the list is empty for
		// every player on a map that grants none.
		ClientTickEvents.END_CLIENT_TICK.register(
			com.mopicmp.npcstudio.client.knack.Knacks::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			com.mopicmp.npcstudio.client.map.Started.gone());

		// Typefaces this map is written in. The server sends a list on the way in and
		// this asks only for the files it has not got, so a second visit costs nothing.
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.FontPayloads.Catalogue.TYPE, (payload, context) ->
				com.mopicmp.npcstudio.client.text.FontDelivery.offered(payload));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.FontPayloads.Part.TYPE, (payload, context) ->
				com.mopicmp.npcstudio.client.text.FontDelivery.piece(payload));
		// Half of a typeface that was still arriving goes with the world it was
		// arriving from. Held statically, it would otherwise sit here on the next
		// server waiting for pieces that are never coming.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			com.mopicmp.npcstudio.client.text.FontDelivery.forget());

		// The map's named places. Asked for on the way in rather than when a panel
		// opens, because they are drawn in the world and a graph acts on them whether
		// or not anybody is editing.
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.SpotPayloads.Spots.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.map.Spots.took(payload.spots())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			com.mopicmp.npcstudio.client.map.Spots.gone());

		// A route being drawn is a mode, and a mode left on across a disconnect is
		// one that borrows the mouse buttons in whatever world comes next — with the
		// document it was placing into belonging to a server nobody is talking to.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			com.mopicmp.npcstudio.client.map.Routing.forget());

		// Entity ids belong to the world that handed them out and are handed out
		// again in the next one. An entry kept across a disconnect would eventually
		// name a different character and teleport it somewhere it has never been.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			com.mopicmp.npcstudio.client.edit.History.forget();
			// A hold is about a world. Held statically, it would otherwise still be in
			// force on the next world somebody joined, with nothing there to release it.
			com.mopicmp.npcstudio.client.dialogue.Holding.forget();
		});

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
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.HoldPlayerPayload.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.dialogue.Holding.told(payload.held())));
		ClientPlayNetworking.registerGlobalReceiver(CloseDialoguePayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				// Only the document's blanket rule. A hold the graph put on spans nodes
				// on purpose — see Holding.barGone — and the bar going away is not the
				// conversation ending. The end of the conversation sends its own release.
				com.mopicmp.npcstudio.client.dialogue.Holding.barGone();
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
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.PortraitPayloads.Shelf.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.wardrobe.PortraitShelf.accept(payload.pictures())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.GaugePayloads.Showing.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.dialogue.Gauges.accept(payload.gauges())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.KnackPayloads.Have.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.knack.Knacks.accept(payload.knacks())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.VariablePayloads.Names.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.editor.KnownVariables.accept(payload.known())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.WatchPayloads.Told.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.dialogue.Watching.accept(payload.lines())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.PuppetPayloads.Sheets.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.puppet.PuppetSheets.accept(payload.written())));
		ClientPlayNetworking.registerGlobalReceiver(
			com.mopicmp.npcstudio.net.ShowPortraitPayload.TYPE,
			(payload, context) -> context.client().execute(() ->
				com.mopicmp.npcstudio.client.dialogue.Portraits.show(payload)));
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
			(payload, context) -> context.client().execute(() -> {
				com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.deliver(
					com.mopicmp.npcstudio.client.workspace.panel.BenchPanel.class,
					panel -> panel.accept(payload));
				// And out loud when nothing is showing it. These answers used to be read
				// only in the bench panel, which was right while the bench was the only
				// thing that asked. The ring asks now, from the world, with no panel open
				// — and an act with no visible answer is an act that looks like nothing
				// happened.
				var client = context.client();
				if (client.player == null || payload.lines().isEmpty()) return;
				if (com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.watching("bench")) return;
				client.player.sendOverlayMessage(
					net.minecraft.network.chat.Component.literal(payload.lines().get(0)));
			}));
		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.Listing.TYPE,
			(payload, context) -> context.client().execute(() -> {
				// Always remembered, because two screens want the same answer for
				// different reasons: one to show a list to choose from, the other to
				// fill a drop-down. Asking twice would be one packet more and one
				// state more to keep in step.
				com.mopicmp.npcstudio.client.editor.DialogueNames.remember(payload.graphs());
				// Whoever asked only for the names keeps their window, and the packet
				// says which that was. Without it every answer opened the list — and the
				// character panel asks for the names as it fills itself in, which is
				// every time a character is opened. So the dialogue window arrived over
				// the world each time the menu did, having been asked for by a dropdown.
				if (!payload.toShow()) return;
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
			// The skin the last map dressed everybody in. It holds a texture registered
			// against that world's library, so carrying it over would put the previous
			// map's costume on the next map's players until somebody looked closely.
			com.mopicmp.npcstudio.client.map.WornHere.forget();
			// The picture standing beside the last scene, and the list it came from.
			com.mopicmp.npcstudio.client.dialogue.Portraits.forget();
			com.mopicmp.npcstudio.client.wardrobe.PortraitShelf.forget();
			// The layout sheets and the alpha masks decoded for placing them. Both are
			// this world's; the next one has its own figures and its own pictures.
			com.mopicmp.npcstudio.client.puppet.PuppetSheets.forget();
			com.mopicmp.npcstudio.client.puppet.Masks.forget();
			com.mopicmp.npcstudio.client.dialogue.Watching.forget();
			// The names the last map's documents used. Offering them in the next map's
			// editor would be offering variables that do not exist there.
			com.mopicmp.npcstudio.client.editor.KnownVariables.forget();
			// And what this map let anybody do. Carried over, the next map would hand
			// somebody an ability none of its documents ever granted.
			com.mopicmp.npcstudio.client.knack.Knacks.forget();
			// And what it was showing of it. Carried over, the next map would draw a
			// stamina bar none of its documents ever asked for.
			com.mopicmp.npcstudio.client.dialogue.Gauges.forget();
			com.mopicmp.npcstudio.client.entity.ShapeEditing.end();
			com.mopicmp.npcstudio.client.wardrobe.TryingOn.forget();

			// And four more of exactly the same kind, which had been missed while the
			// reason for the others was being written out three separate times.
			//
			// The selection is an entity id, so in the next world it names whoever holds
			// that number there — and every panel in the workspace takes its subject
			// from it, so the costume panel would be dressing a stranger.
			com.mopicmp.npcstudio.client.workspace.Workspace.forget();
			// The graphs are documents in a world save. Offered in the next world they
			// name nothing, and a character given one stands still with a graph on her
			// panel saying she has one.
			com.mopicmp.npcstudio.client.editor.DialogueNames.forget();
			// The model being built is a document of the world it was opened from.
			// Carried over, saving it would write one world's ship into another's save.
			com.mopicmp.npcstudio.client.model.Modelling.close();
			// The scene camera is an entity of a level that is going away, and the flag
			// beside it says the view is looking through one. Kept, the next world would
			// start seeing through a camera that no longer exists.
			com.mopicmp.npcstudio.client.scene.SceneCameras.forget();
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
		// The way out of standing still, watched every tick. It is here rather than in
		// the input mixin because it has to keep running on the tick that lets go, and
		// the mixin returns before then.
		ClientTickEvents.END_CLIENT_TICK.register(client ->
			com.mopicmp.npcstudio.client.dialogue.Holding.tick());

		// Typefaces dropped in the folder, put where the game looks them up — and put
		// back when a resource reload throws them out again.
		//
		// A tick rather than a reload listener, and the reason is worth keeping: the
		// order reload listeners run in is not ours to choose, so one registered
		// beside the font manager's own may run *before* it and carefully fill a map
		// that is about to be replaced. Asking "are they still there" cannot be wrong
		// about the order, and it also catches a font going missing without a reload.
		ClientTickEvents.END_CLIENT_TICK.register(client ->
			com.mopicmp.npcstudio.client.text.Fonts.ensure());

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

		// The map's marks over the ordinary game. They are put down by pointing at the
		// world, from a ring that opens without the workspace — so drawing them only
		// inside the workspace meant placing one while walking about produced nothing
		// you could see, and a mark you cannot see is a mark you place twice.
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			NpcStudio.id("map_marks"),
			com.mopicmp.npcstudio.client.map.SpotMarkers::overWorld);

		// A route being drawn, for the same reason and in the same place. It is put
		// down by pointing at the ground with no window open at all, so it has nowhere
		// else it could be drawn — and a path you cannot see while walking it is a
		// path laid twice.
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			NpcStudio.id("route"),
			com.mopicmp.npcstudio.client.map.Routing::overWorld);

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
		// A question staged as a cutscene is shown as a question instead.
		//
		// Not a preference overruled: a cutscene has no answers on it and no way to
		// pick one — clicking it means "go on", and going on from a question is
		// refused, so the scene stopped dead with the camera out and nothing to do.
		// The mode is about how a *line* is staged, and a node that can be either was
		// allowed to choose a staging that cannot hold it.
		if (state != null && state.isCutscene() && !state.options().isEmpty()) {
			client.setScreenAndShow(new DialogueScreen(state));
		} else if (state != null && state.isCutscene()) {
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
