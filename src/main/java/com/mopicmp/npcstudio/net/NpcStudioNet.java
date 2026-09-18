package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.dialogue.runtime.DialogueEditing;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime;
import com.mopicmp.npcstudio.entity.NpcEntity;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Everything this mod sends over the wire. */
public final class NpcStudioNet {

	private NpcStudioNet() { }

	/**
	 * Both sides have to agree on the payload types before anyone connects, so
	 * this runs during mod initialisation rather than when a world loads.
	 */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ShowLinePayload.TYPE, ShowLinePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShowChoicePayload.TYPE, ShowChoicePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CloseDialoguePayload.TYPE, CloseDialoguePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			HoldPlayerPayload.TYPE, HoldPlayerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AnswerPayload.TYPE, AnswerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AdvancePayload.TYPE, AdvancePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SpeakOnPayload.TYPE, SpeakOnPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PutDownPayload.TYPE, PutDownPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			PortraitPayloads.Please.TYPE, PortraitPayloads.Please.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			PortraitPayloads.Shelf.TYPE, PortraitPayloads.Shelf.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			ShowPortraitPayload.TYPE, ShowPortraitPayload.CODEC);

		// Layout sheets. Slots go up one at a time and the whole shelf comes back down;
		// see PuppetPayloads for why the two directions are shaped differently.
		PayloadTypeRegistry.serverboundPlay().register(
			PuppetPayloads.Please.TYPE, PuppetPayloads.Please.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			PuppetPayloads.PutSlot.TYPE, PuppetPayloads.PutSlot.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			PuppetPayloads.Drop.TYPE, PuppetPayloads.Drop.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			PuppetPayloads.Sheets.TYPE, PuppetPayloads.Sheets.CODEC);

		// Watching a conversation decide things. Off unless somebody asks, and then
		// only for them — see WatchPayloads.
		PayloadTypeRegistry.serverboundPlay().register(
			WatchPayloads.Watch.TYPE, WatchPayloads.Watch.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			WatchPayloads.Told.TYPE, WatchPayloads.Told.CODEC);

		// Which variable names exist anywhere, so the editor can offer them rather than
		// leave them to be remembered and retyped — see VariablePayloads.
		PayloadTypeRegistry.serverboundPlay().register(
			VariablePayloads.Please.TYPE, VariablePayloads.Please.CODEC);

		// The abilities this mod writes itself: what a player may do, and when they did
		// it. The client is told ahead of time because a jump happens there — see
		// KnackPayloads.
		PayloadTypeRegistry.clientboundPlay().register(
			KnackPayloads.Have.TYPE, KnackPayloads.Have.CODEC);
		// What the player is shown of all this. Worked out on the server for the reason
		// written at GaugePayloads: a gauge that computed itself could disagree with the
		// graph, and would disagree silently.
		PayloadTypeRegistry.clientboundPlay().register(
			GaugePayloads.Showing.TYPE, GaugePayloads.Showing.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			KnackPayloads.Used.TYPE, KnackPayloads.Used.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			EditorPayloads.Replay.TYPE, EditorPayloads.Replay.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			VariablePayloads.Names.TYPE, VariablePayloads.Names.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.Editing.TYPE, EditorPayloads.Editing.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.Saved.TYPE, EditorPayloads.Saved.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.Listing.TYPE, EditorPayloads.Listing.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.Browse.TYPE, EditorPayloads.Browse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.Rename.TYPE, EditorPayloads.Rename.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.Open.TYPE, EditorPayloads.Open.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.Save.TYPE, EditorPayloads.Save.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(NpcPayloads.Details.TYPE, NpcPayloads.Details.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Open.TYPE, NpcPayloads.Open.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			NpcPayloads.PlaceModel.TYPE, NpcPayloads.PlaceModel.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			NpcPayloads.RemoveModel.TYPE, NpcPayloads.RemoveModel.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			NpcPayloads.ModelDocument.TYPE, NpcPayloads.ModelDocument.CODEC);
		// The same payload travels both ways: a client sends the model it drew, and
		// the server hands it on to everybody else unchanged.
		PayloadTypeRegistry.clientboundPlay().register(
			NpcPayloads.ModelDocument.TYPE, NpcPayloads.ModelDocument.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			NpcPayloads.SolidModel.TYPE, NpcPayloads.SolidModel.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Apply.TYPE, NpcPayloads.Apply.CODEC);

		// The bench. Everything about it is scaffolding and all of it — these two
		// lines, the two below, one payload file, one handler and one panel — is
		// meant to come out together when block programming can say the same things.
		PayloadTypeRegistry.serverboundPlay().register(BenchPayloads.Ask.TYPE, BenchPayloads.Ask.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BenchPayloads.Told.TYPE, BenchPayloads.Told.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.SkinUpload.TYPE, NpcPayloads.SkinUpload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.SkinPlease.TYPE, NpcPayloads.SkinPlease.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Shape.TYPE, NpcPayloads.Shape.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NpcPayloads.SkinFor.TYPE, NpcPayloads.SkinFor.CODEC);

		PayloadTypeRegistry.serverboundPlay().register(WardrobePayloads.Please.TYPE, WardrobePayloads.Please.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Place.TYPE, NpcPayloads.Place.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Post.TYPE, NpcPayloads.Post.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(WardrobePayloads.PicturePlease.TYPE, WardrobePayloads.PicturePlease.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(WardrobePayloads.Edit.TYPE, WardrobePayloads.Edit.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(WardrobePayloads.Wear.TYPE, WardrobePayloads.Wear.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			WardrobePayloads.MarkEyes.TYPE, WardrobePayloads.MarkEyes.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			WardrobePayloads.AddPart.TYPE, WardrobePayloads.AddPart.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WardrobePayloads.Library.TYPE, WardrobePayloads.Library.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WardrobePayloads.Picture.TYPE, WardrobePayloads.Picture.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WardrobePayloads.Versions.TYPE, WardrobePayloads.Versions.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WardrobePayloads.Marks.TYPE, WardrobePayloads.Marks.CODEC);

		PayloadTypeRegistry.serverboundPlay().register(
			ScenePayloads.Please.TYPE, ScenePayloads.Please.CODEC);
		// Out in one piece and back in several. The two directions have different
		// ceilings — the wire allows a server far more room than a client — and a
		// scene with a minute of recording in it is past what a client may send at
		// once, so every scene goes up in pieces and comes down whole.
		PayloadTypeRegistry.serverboundPlay().register(
			ScenePayloads.Part.TYPE, ScenePayloads.Part.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			ScenePayloads.Document.TYPE, ScenePayloads.Document.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			ScenePayloads.Gone.TYPE, ScenePayloads.Gone.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			ScenePayloads.Gone.TYPE, ScenePayloads.Gone.CODEC);

		// The map's start. The settings travel both ways under one type, as a scene's
		// document does: it is one small whole document, and there is no half of it
		// worth sending on its own.
		PayloadTypeRegistry.serverboundPlay().register(
			MapPayloads.Please.TYPE, MapPayloads.Please.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			MapPayloads.Start.TYPE, MapPayloads.Start.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			MapPayloads.Start.TYPE, MapPayloads.Start.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			MapPayloads.SetSpawn.TYPE, MapPayloads.SetSpawn.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			MapPayloads.Spawn.TYPE, MapPayloads.Spawn.CODEC);

		// The map's named places. The whole list goes out on any change, because it is
		// short by construction and everybody in the world draws all of it.
		PayloadTypeRegistry.serverboundPlay().register(
			SpotPayloads.Please.TYPE, SpotPayloads.Please.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			SpotPayloads.Spots.TYPE, SpotPayloads.Spots.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			SpotPayloads.Put.TYPE, SpotPayloads.Put.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			SpotPayloads.Drop.TYPE, SpotPayloads.Drop.CODEC);

		// Typefaces: a list down, a request up, the file down in pieces.
		PayloadTypeRegistry.clientboundPlay().register(
			FontPayloads.Catalogue.TYPE, FontPayloads.Catalogue.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
			FontPayloads.Part.TYPE, FontPayloads.Part.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
			FontPayloads.Want.TYPE, FontPayloads.Want.CODEC);

		// The engine already refuses an option the player was not offered, so a
		// client that lies gets an error rather than a branch it should not reach.
		ServerPlayNetworking.registerGlobalReceiver(AnswerPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// A question a place asked has nobody to travel with, and minus one is how
			// "nobody is speaking" has always been sent. Without this arm the answer was
			// looked up as an entity, found none, and was dropped — which from the
			// player's side is a question that cannot be answered at all.
			if (payload.npc() < 0) {
				DialogueRuntime.answerHere(player, payload.option());
				return;
			}
			if (player.level().getEntity(payload.npc()) instanceof NpcEntity npc
					&& npc.distanceToSqr(player) <= 144.0) {
				DialogueRuntime.choose(player, npc, payload.option());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(AdvancePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.level().getEntity(payload.npc()) instanceof NpcEntity npc
					&& npc.distanceToSqr(player) <= 400.0) {
				DialogueRuntime.talkTo(player, npc);
			}
		});

		// Nothing is checked about who is near: this is a conversation a place is
		// having, so there is nobody to be near. What may be advanced is whatever the
		// server already has on this player's screen, and that is decided there.
		ServerPlayNetworking.registerGlobalReceiver(SpeakOnPayload.TYPE, (payload, context) ->
			DialogueRuntime.speakOn(context.player()));

		// Somebody pressed escape. The line comes off the screen and the bookmark stays
		// exactly where it is — see PutDownPayload, which is about a screen rather than
		// about a scene.
		ServerPlayNetworking.registerGlobalReceiver(PutDownPayload.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.dialogue.runtime.DialogueDisplay.hide(context.player()));

		// The list of portraits, for the picker on an act node. Read-only and cheap;
		// the pictures themselves come one at a time through the wardrobe's own pair.
		ServerPlayNetworking.registerGlobalReceiver(PortraitPayloads.Please.TYPE,
			(payload, context) ->
				com.mopicmp.npcstudio.wardrobe.Portraits.send(context.player()));

		ServerPlayNetworking.registerGlobalReceiver(VariablePayloads.Please.TYPE,
			(payload, context) -> DialogueEditing.sendVariables(context.player()));

		ServerPlayNetworking.registerGlobalReceiver(KnackPayloads.Used.TYPE,
			(payload, context) -> DialogueRuntime.usedKnack(context.player(), payload.knack()));

		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Replay.TYPE,
			(payload, context) -> DialogueEditing.replay(context.player(), payload.id()));

		ServerPlayNetworking.registerGlobalReceiver(WatchPayloads.Watch.TYPE,
			(payload, context) ->
				com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime
					.watch(context.player(), payload.on()));

		ServerPlayNetworking.registerGlobalReceiver(PuppetPayloads.Please.TYPE,
			(payload, context) -> com.mopicmp.npcstudio.puppet.Puppets.send(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(PuppetPayloads.PutSlot.TYPE,
			(payload, context) ->
				com.mopicmp.npcstudio.puppet.Puppets.put(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(PuppetPayloads.Drop.TYPE,
			(payload, context) ->
				com.mopicmp.npcstudio.puppet.Puppets.drop(context.player(), payload));

		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Browse.TYPE, (payload, context) ->
			DialogueEditing.browse(context.player(), payload.toShow()));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Open.TYPE, (payload, context) ->
			DialogueEditing.open(context.player(), payload.id(), payload.kind()));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Save.TYPE, (payload, context) ->
			DialogueEditing.save(context.player(), payload.json()));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Rename.TYPE, (payload, context) ->
			DialogueEditing.rename(context.player(), payload.from(), payload.to()));

		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Open.TYPE, (payload, context) ->
			NpcEditing.open(context.player(), payload.entityId()));
		ServerPlayNetworking.registerGlobalReceiver(BenchPayloads.Ask.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.bench.Bench.asked(
				context.player(), payload.entityId(), payload.action()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.PlaceModel.TYPE, (payload, context) ->
			NpcEditing.placeModel(context.player(), payload.name()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.RemoveModel.TYPE, (payload, context) ->
			NpcEditing.removeModel(context.player(), payload.entityId()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.ModelDocument.TYPE,
			(payload, context) -> NpcEditing.takeModel(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.SolidModel.TYPE,
			(payload, context) -> NpcEditing.solidModel(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Apply.TYPE, (payload, context) ->
			NpcEditing.apply(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.SkinUpload.TYPE, (payload, context) ->
			NpcEditing.uploadSkin(context.player(), payload.entityId(), payload.pixels()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.SkinPlease.TYPE, (payload, context) ->
			NpcEditing.sendSkin(context.player(), payload.entityId()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Shape.TYPE, (payload, context) ->
			NpcEditing.reshape(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Place.TYPE, (payload, context) ->
			NpcEditing.place(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Post.TYPE, (payload, context) ->
			NpcEditing.post(context.player(), payload));

		ServerPlayNetworking.registerGlobalReceiver(WardrobePayloads.Please.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.send(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(WardrobePayloads.PicturePlease.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.sendPicture(context.player(), payload.fingerprint()));
		ServerPlayNetworking.registerGlobalReceiver(WardrobePayloads.Edit.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.edit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(WardrobePayloads.AddPart.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.addPart(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(WardrobePayloads.Wear.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.wear(context.player(), payload.entityId(), payload.costumeId()));
		ServerPlayNetworking.registerGlobalReceiver(WardrobePayloads.MarkEyes.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.wardrobe.Wardrobes.markEyes(context.player(), payload));

		ServerPlayNetworking.registerGlobalReceiver(ScenePayloads.Please.TYPE, (payload, context) ->
			SceneEditing.sendAll(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(ScenePayloads.Part.TYPE, (payload, context) ->
			SceneEditing.part(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ScenePayloads.Gone.TYPE, (payload, context) ->
			SceneEditing.remove(context.player(), payload.name()));

		ServerPlayNetworking.registerGlobalReceiver(MapPayloads.Please.TYPE, (payload, context) ->
			MapEditing.send(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MapPayloads.Start.TYPE, (payload, context) ->
			MapEditing.change(context.player(), payload.start()));
		ServerPlayNetworking.registerGlobalReceiver(MapPayloads.SetSpawn.TYPE, (payload, context) ->
			MapEditing.setSpawn(context.player(), payload));

		ServerPlayNetworking.registerGlobalReceiver(SpotPayloads.Please.TYPE, (payload, context) ->
			SpotEditing.send(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(SpotPayloads.Put.TYPE, (payload, context) ->
			SpotEditing.put(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(SpotPayloads.Drop.TYPE, (payload, context) ->
			SpotEditing.drop(context.player(), payload));

		// A typeface is asked for by its contents, so nothing is sent to a player who
		// has it already — which is what keeps a second visit free.
		ServerPlayNetworking.registerGlobalReceiver(FontPayloads.Want.TYPE, (payload, context) ->
			com.mopicmp.npcstudio.font.FontShelf.hand(context.player(), payload.digest()));
	}
}
