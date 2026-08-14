package com.mopicmp.npcstudio.net;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.entity.NpcEntity;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The server half of editing an NPC.
 *
 * Every check that matters lives here, because the client is the half that can
 * be replaced. It asks; this decides. The rules are the same two as everywhere
 * else in the mod: editing is a creative-mode activity, and you have to be
 * within reach of what you are editing.
 */
public final class NpcEditing {

	/** As far as an NPC can be and still be the one you are obviously pointing at. */
	private static final double REACH_SQUARED = 64.0;

	private NpcEditing() { }

	/**
	 * Finds the NPC a player is allowed to edit, or explains why not.
	 *
	 * Returning null after saying something, rather than throwing or going quiet:
	 * a refusal the player cannot see is indistinguishable from a mod that has
	 * stopped working.
	 */
	private static NpcEntity reachable(ServerPlayer player, int entityId) {
		if (!player.isCreative()) {
			player.sendOverlayMessage(Component.literal("Editing NPCs is a creative-mode thing."));
			return null;
		}
		Entity found = player.level().getEntity(entityId);
		if (!(found instanceof NpcEntity npc) || npc.distanceToSqr(player) > REACH_SQUARED) {
			player.sendOverlayMessage(Component.literal("That NPC is not here."));
			return null;
		}
		return npc;
	}

	/**
	 * Takes a skin from a player and gives it to the character.
	 *
	 * The picture is examined here before it goes anywhere, and that is a
	 * correction: this used to check only the length and leave the rest to
	 * whichever client drew it. But the server hands this file to everyone who
	 * can see the character, and each of them opens it with a native decoder — so
	 * "the clients will find out" meant one person choosing what a dozen other
	 * people's decoders would be fed.
	 */
	public static void uploadSkin(ServerPlayer player, int entityId, byte[] pixels) {
		NpcEntity npc = reachable(player, entityId);
		if (npc == null) return;
		// Looked at here, before it goes anywhere. This picture is about to be sent
		// to every player who can see the character, and each of them will hand it
		// to a native image decoder — so bytes chosen by one person must not reach
		// a dozen other people's decoders unexamined. Saying "the clients will
		// find out" was the earlier reasoning and it was the wrong way round.
		String refused = com.mopicmp.npcstudio.wardrobe.SkinBytes.refuse(pixels);
		if (refused != null) {
			player.sendOverlayMessage(Component.literal(refused));
			return;
		}
		npc.setCustomSkin(pixels);
		// Straight back to everyone who can see it, rather than waiting to be
		// asked: the people watching are exactly the people who need it, and they
		// are already listed.
		for (ServerPlayer nearby : player.level().getServer().getPlayerList().getPlayers()) {
			if (nearby.level() == npc.level() && nearby.distanceToSqr(npc) < 128 * 128) {
				ServerPlayNetworking.send(nearby, new NpcPayloads.SkinFor(entityId, pixels));
			}
		}
		player.sendOverlayMessage(Component.literal("Skin set."));
	}

	/** Answers a client that has seen a skin it does not have. */
	public static void sendSkin(ServerPlayer player, int entityId) {
		if (!(player.level().getEntity(entityId) instanceof NpcEntity npc)) return;
		byte[] pixels = npc.customSkin();
		if (pixels == null || pixels.length == 0) return;
		ServerPlayNetworking.send(player, new NpcPayloads.SkinFor(entityId, pixels));
	}

	public static void open(ServerPlayer player, int entityId) {
		NpcEntity npc = reachable(player, entityId);
		if (npc == null) return;
		ServerPlayNetworking.send(player, describe(npc));
	}

	/**
	 * Takes a new build for a character, and does whatever else was asked with it.
	 *
	 * The numbers are trusted only as far as {@code BodyShape} lets them be — it
	 * clamps every one of them on the way in, so a client sending nonsense gets a
	 * character at the end of a slider rather than one turned inside out.
	 */
	public static void reshape(ServerPlayer player, NpcPayloads.Shape order) {
		NpcEntity npc = reachable(player, order.entityId());
		if (npc == null) return;

		if (order.verb() == NpcPayloads.Shape.Verb.TAKE_FROM_COSTUME) {
			com.mopicmp.npcstudio.wardrobe.Wardrobes.restoreShape(player, npc);
			return;
		}
		npc.setBodyShape(com.mopicmp.npcstudio.entity.BodyShape.unpack(
			order.sizes(), order.posture()));
		if (order.verb() == NpcPayloads.Shape.Verb.KEEP_ON_COSTUME) {
			com.mopicmp.npcstudio.wardrobe.Wardrobes.keepShape(player, npc);
		}
	}

	public static NpcPayloads.Details describe(NpcEntity npc) {
		List<String> animations = new ArrayList<>();
		for (NpcEntity.Motion motion : NpcEntity.Motion.values()) {
			animations.add(npc.motionAnimation(motion));
		}
		// The skin travels as the name it was asked for rather than as a resolved
		// profile: a name is what somebody typed and what they will recognise, and
		// a resolved profile would show them a UUID.
		return new NpcPayloads.Details(npc.getId(), npc.getProfile().name().orElse(""),
			npc.dialogueId(), animations, List.of(held(npc, HANDS[0]), held(npc, HANDS[1])),
			npc.scale());
	}

	/** Main hand, then off hand, in the order the packet expects. */
	private static final EquipmentSlot[] HANDS = { EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND };

	private static String held(NpcEntity npc, EquipmentSlot slot) {
		ItemStack stack = npc.getItemBySlot(slot);
		return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/**
	 * Puts an item in a hand, by name.
	 *
	 * An unknown name empties the hand rather than being ignored. Silently
	 * keeping the old item would leave somebody staring at a sword they have
	 * just tried three times to replace.
	 */
	private static void hold(NpcEntity npc, EquipmentSlot slot, String id) {
		if (id == null || id.isBlank()) {
			npc.setItemSlot(slot, ItemStack.EMPTY);
			return;
		}
		Identifier name = Identifier.tryParse(id.trim());
		Item item = name == null ? null : BuiltInRegistries.ITEM.getValue(name);
		npc.setItemSlot(slot, item == null || item == Items.AIR
			? ItemStack.EMPTY : new ItemStack(item));
	}

	public static void apply(ServerPlayer player, NpcPayloads.Apply edit) {
		NpcEntity npc = reachable(player, edit.entityId());
		if (npc == null) return;

		if (!edit.skin().isBlank() && !edit.skin().equals(npc.getProfile().name().orElse(""))) {
			npc.setSkin(edit.skin().trim());
		}
		npc.setDialogueId(edit.dialogue().trim());

		// Taken by position, in the order the entity declares. A list shorter than
		// the enum is not an error — an older client simply has less to say — so it
		// leaves the rest alone rather than clearing them.
		NpcEntity.Motion[] motions = NpcEntity.Motion.values();
		for (int i = 0; i < motions.length && i < edit.animations().size(); i++) {
			npc.setMotionAnimation(motions[i], edit.animations().get(i));
		}
		for (int i = 0; i < HANDS.length && i < edit.held().size(); i++) {
			hold(npc, HANDS[i], edit.held().get(i));
		}
		npc.setScale(edit.scale());
		player.sendOverlayMessage(Component.literal("NPC updated."));
	}
}
