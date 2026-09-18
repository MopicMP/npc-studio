package com.mopicmp.npcstudio.puppet;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.net.PuppetPayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The world's layout sheets, and the few things a client may ask of them. */
public final class Puppets {

	private static PuppetShelf shelf;

	private Puppets() { }

	public static void open(MinecraftServer server) {
		shelf = PuppetShelf.of(server);
	}

	public static void close() {
		shelf = null;
	}

	public static PuppetShelf shelf() {
		return shelf;
	}

	/**
	 * The same gate the wardrobe has, for the same reason.
	 *
	 * A layout sheet is part of what was built, not part of playing — and a packet
	 * arrives from a client, which is to say from anybody. Said out loud rather than
	 * ignored: a button that quietly does nothing for ever is worse than a refusal.
	 */
	private static boolean allowed(ServerPlayer player) {
		if (shelf == null) {
			player.sendOverlayMessage(Component.literal("No layout sheets with this world."));
			return false;
		}
		if (player.isCreative()) return true;
		player.sendOverlayMessage(Component.literal("Layout sheets are a creative-mode thing."));
		return false;
	}

	/** Every sheet, as the same text the world's own file holds. */
	public static void send(ServerPlayer player) {
		if (shelf == null) return;
		List<String> written = new ArrayList<>();
		var compact = new GsonBuilder().create();
		for (Puppet sheet : shelf.all()) written.add(compact.toJson(PuppetShelf.toJson(sheet)));
		ServerPlayNetworking.send(player, new PuppetPayloads.Sheets(written));
	}

	/**
	 * One slot placed, on a sheet made if it was not there.
	 *
	 * <h2>Why a bad packet is dropped rather than argued with</h2>
	 *
	 * The text arrives from a client and is parsed. A parse that throws is a client
	 * sending nonsense — a broken one or a hostile one — and neither is worth a reply.
	 * What matters is that it cannot take the server with it, and that the sheet on disk
	 * is left exactly as it was.
	 */
	public static void put(ServerPlayer player, PuppetPayloads.PutSlot order) {
		if (!allowed(player)) return;
		Puppet.Slot slot;
		try {
			JsonObject read = JsonParser.parseString(order.slot()).getAsJsonObject();
			slot = PuppetShelf.slotFromJson(read);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("A slot that would not read, from {}: {}",
				player.getGameProfile().name(), failed.toString());
			return;
		}
		if (slot.name().isEmpty()) return;

		Puppet was = shelf.named(order.sheet());
		// The canvas is the first slot's, and later slots do not get to change it. A
		// figure whose canvas moved would put every already-placed slot somewhere else,
		// which is the one edit nobody would ever mean to make in passing.
		Puppet now = was == null
			? new Puppet(order.sheet(), order.wide(), order.high(), List.of(slot))
			: was.with(slot);
		if (shelf.put(now)) send(player);
	}

	/** A slot off a sheet, or the whole sheet when no slot is named. */
	public static void drop(ServerPlayer player, PuppetPayloads.Drop order) {
		if (!allowed(player)) return;
		if (order.slot().isEmpty()) {
			if (shelf.remove(order.sheet())) send(player);
			return;
		}
		Puppet was = shelf.named(order.sheet());
		if (was == null) return;
		List<Puppet.Slot> left = new ArrayList<>(was.slots());
		if (!left.removeIf(each -> each.name().equals(order.slot()))) return;
		if (shelf.put(new Puppet(was.name(), was.wide(), was.high(), left))) send(player);
	}
}
