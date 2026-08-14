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
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Apply.TYPE, NpcPayloads.Apply.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.SkinUpload.TYPE, NpcPayloads.SkinUpload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.SkinPlease.TYPE, NpcPayloads.SkinPlease.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcPayloads.Shape.TYPE, NpcPayloads.Shape.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NpcPayloads.SkinFor.TYPE, NpcPayloads.SkinFor.CODEC);

		PayloadTypeRegistry.serverboundPlay().register(WardrobePayloads.Please.TYPE, WardrobePayloads.Please.CODEC);
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
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Apply.TYPE, (payload, context) ->
			NpcEditing.apply(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.SkinUpload.TYPE, (payload, context) ->
			NpcEditing.uploadSkin(context.player(), payload.entityId(), payload.pixels()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.SkinPlease.TYPE, (payload, context) ->
			NpcEditing.sendSkin(context.player(), payload.entityId()));
		ServerPlayNetworking.registerGlobalReceiver(NpcPayloads.Shape.TYPE, (payload, context) ->
			NpcEditing.reshape(context.player(), payload));

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
	}
}
