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

	/**
	 * How far away an NPC may be and still be edited.
	 *
	 * Eight blocks once, which is arm's length, and that was written when the only
	 * way to edit a character was to walk up to it and press a button. The workspace
	 * changed the question: it has a camera that flies, a list of everyone in the
	 * scene and panels that follow whoever is chosen — and then the server refused,
	 * so choosing somebody across the deck meant walking over to them first.
	 *
	 * A hundred and twenty-eight, which is the far end of a scene rather than the
	 * far end of a world. Not unlimited, because the id comes from a client; but the
	 * thing it is protecting against is a creative-mode player who can already
	 * teleport, so the bound is about keeping the rule honest rather than about
	 * stopping anybody.
	 */
	public static final double REACH_SQUARED = 128.0 * 128.0;

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
	 * Puts a character somewhere, facing somewhere.
	 *
	 * The head is turned with the body rather than left where it was. A character
	 * whose body has been turned round while its head stays put is not looking
	 * over its shoulder — it is broken, because the head will snap back the moment
	 * anything else touches it.
	 *
	 * Nothing is clamped and nothing is validated beyond the reach the rest of the
	 * editing uses. The numbers come from a creative-mode player who can already
	 * teleport whatever they like wherever they like.
	 */
	public static void place(ServerPlayer player, NpcPayloads.Place order) {
		NpcEntity npc = reachable(player, order.entityId());
		if (npc == null) return;
		if (!Double.isFinite(order.x()) || !Double.isFinite(order.y())
			|| !Double.isFinite(order.z()) || !Float.isFinite(order.yaw())) {
			return;
		}
		npc.snapTo(order.x(), order.y(), order.z(), order.yaw(), npc.getXRot());
		npc.setYBodyRot(order.yaw());
		npc.setYHeadRot(order.yaw());
		npc.yRotO = order.yaw();
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
			npc.scale(), npc.watchful(), npc.endless());
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
		npc.setWatchful(edit.watchful());
		npc.setEndless(edit.endless());
		player.sendOverlayMessage(Component.literal("NPC updated."));
	}

	/**
	 * Puts a model where the caller stands, a step in front of them.
	 *
	 * In front rather than underfoot, because an object placed inside somebody is
	 * an object they are standing in the middle of and cannot see. A step away is
	 * where you would put a thing down.
	 */
	public static void placeModel(net.minecraft.server.level.ServerPlayer player, String name) {
		// The same gate the rest of this class uses: editing is a creative-mode
		// thing, and the server is the one that says so.
		if (!player.isCreative()) {
			player.sendOverlayMessage(Component.literal("Placing models is a creative-mode thing."));
			return;
		}

		// Where the player is, exactly. A step in front was meant to keep an object
		// out of the middle of somebody, and it costs more than it saves: a thing
		// that appears where you are is a thing you know the coordinates of, and a
		// thing a step away in a direction you were looking at the time is a thing
		// to go and find. The editor's camera is not in the body anyway.
		net.minecraft.world.phys.Vec3 ahead = player.position();
		com.mopicmp.npcstudio.entity.ModelObject object =
			com.mopicmp.npcstudio.entity.ModelObject.TYPE.create(
				player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		if (object == null) return;

		// Square to the world, not to whoever put it down. Facing the player was
		// meant to be helpful and is not: nobody stands on an axis, so a new box
		// arrived at whatever angle the person happened to be looking — about
		// forty-five degrees, most of the time — and a model is drawn in its own
		// axes. Turning it is a thing that exists and can be asked for.
		object.snapTo(ahead.x, ahead.y, ahead.z, 0, 0);
		object.setModel(name);
		// Measured now if the model is already here, and by the object itself on its
		// first tick if it arrives later. Either order ends in the same place.
		object.shapeFrom(com.mopicmp.npcstudio.model.ServerModels.get(name));
		player.level().addFreshEntity(object);

		// Said out loud, with the place. An object put down somewhere you were not
		// looking is an object lost, and "it did nothing" is what that feels like.
		player.sendOverlayMessage(Component.literal("%s → %.0f %.0f %.0f"
			.formatted(name, ahead.x, ahead.y, ahead.z)));
	}

	/**
	 * Takes a placed object back out of the world.
	 *
	 * The document on disk is untouched, and that is the whole shape of it: what is
	 * deleted is one copy standing in a world, not the model somebody spent an
	 * evening on. Putting it down again is the same action as putting it down the
	 * first time.
	 */
	/**
	 * Takes a model somebody drew, and re-measures everything standing that wears it.
	 *
	 * The whole of what the server needs in order to stop being told what things
	 * are shaped like. Everything downstream — collision, the size of the box the
	 * game looks for entities in, what another player sees — falls out of the
	 * document rather than out of a second opinion about it.
	 */
	public static void takeModel(net.minecraft.server.level.ServerPlayer player,
			NpcPayloads.ModelDocument sent) {
		if (!player.isCreative()) return;

		com.mopicmp.npcstudio.model.Model model =
			com.mopicmp.npcstudio.model.ServerModels.take(sent.name(), sent.json());
		if (model == null) return;

		for (net.minecraft.server.level.ServerLevel level : player.level().getServer().getAllLevels()) {
			for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
				if (entity instanceof com.mopicmp.npcstudio.entity.ModelObject object
					&& object.model().equals(sent.name())) {
					object.shapeFrom(model);
				}
			}
		}

		// On to everybody, so a model somebody else drew is a thing you can see
		// rather than an empty patch of air they keep talking about.
		for (net.minecraft.server.level.ServerPlayer other : player.level().getServer().getPlayerList()
				.getPlayers()) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(other, sent);
		}
	}

	/** Whether one placed object is something to walk into. */
	public static void solidModel(net.minecraft.server.level.ServerPlayer player,
			NpcPayloads.SolidModel wanted) {
		if (!player.isCreative()) return;
		net.minecraft.world.entity.Entity found = player.level().getEntity(wanted.entityId());
		if (found instanceof com.mopicmp.npcstudio.entity.ModelObject object) {
			object.setSolid(wanted.solid());
		}
	}

	public static void removeModel(net.minecraft.server.level.ServerPlayer player, int entityId) {
		if (!player.isCreative()) {
			player.sendOverlayMessage(Component.literal("Removing models is a creative-mode thing."));
			return;
		}

		net.minecraft.world.entity.Entity found = player.level().getEntity(entityId);
		// Only ours, and only by this route. The id comes from a client, so it could
		// name anything in the world; a packet that says "delete a model" must not be
		// a packet that deletes somebody's horse.
		if (!(found instanceof com.mopicmp.npcstudio.entity.ModelObject object)) return;

		String name = object.model();
		object.discard();
		player.sendOverlayMessage(Component.literal(name + " — removed"));
	}

}
