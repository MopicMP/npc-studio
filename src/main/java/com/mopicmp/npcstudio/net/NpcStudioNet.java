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
		PayloadTypeRegistry.serverboundPlay().register(AnswerPayload.TYPE, AnswerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AdvancePayload.TYPE, AdvancePayload.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.Editing.TYPE, EditorPayloads.Editing.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.Saved.TYPE, EditorPayloads.Saved.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.Listing.TYPE, EditorPayloads.Listing.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.Browse.TYPE, EditorPayloads.Browse.CODEC);
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

		// The engine already refuses an option the player was not offered, so a
		// client that lies gets an error rather than a branch it should not reach.
		ServerPlayNetworking.registerGlobalReceiver(AnswerPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
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

		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Browse.TYPE, (payload, context) ->
			DialogueEditing.browse(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Open.TYPE, (payload, context) ->
			DialogueEditing.open(context.player(), payload.id()));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.Save.TYPE, (payload, context) ->
			DialogueEditing.save(context.player(), payload.json()));

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
	}
}
