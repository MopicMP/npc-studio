package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.scene.Scene;
import com.mopicmp.npcstudio.scene.ServerScenes;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server's side of scene editing, and the rules for touching one.
 *
 * A scene is shared property in the way a costume is: it belongs to the world,
 * several people can have it open, and one person's mistake is everybody's
 * afternoon. So every check is here rather than in the panel — the panel is the
 * half that can be replaced.
 */
public final class SceneEditing {

	private SceneEditing() { }

	private static boolean allowed(ServerPlayer player) {
		if (player.isCreative()) return true;
		player.sendOverlayMessage(Component.literal("Scenes are a creative-mode thing."));
		return false;
	}

	/** Everything this world knows how to play, handed over at once. */
	public static void sendAll(ServerPlayer player) {
		for (String name : ServerScenes.names()) {
			String json = ServerScenes.written(name);
			if (json != null) {
				ServerPlayNetworking.send(player, new ScenePayloads.Document(name, json));
			}
		}
	}

	/**
	 * A piece of a scene somebody is sending.
	 *
	 * <h2>What is guarded here and why</h2>
	 *
	 * Everything about this arrives from a client, so every number in it is a
	 * stranger's choice. The piece count decides how much is kept on the heap, the
	 * index decides where it is written, and the transfer's name decides which heap
	 * it goes on — so all three are checked before any of them is used, and the
	 * whole is refused if it would come to more than a scene ever is. The wardrobe
	 * settled these rules for skins; a scene is the same shape of problem and gets
	 * the same answers rather than new ones.
	 *
	 * One transfer at a time per player. Nobody will notice — the client sends the
	 * pieces of one scene before starting another — and it means a client cannot
	 * fill a server's memory by starting a thousand transfers and finishing none.
	 */
	public static void part(ServerPlayer player, ScenePayloads.Part sent) {
		if (!allowed(player)) return;

		int count = sent.count();
		if (count < 1 || (long) count * ScenePayloads.PART > ScenePayloads.MOST + ScenePayloads.PART) {
			player.sendOverlayMessage(Component.literal("That scene is too large to send."));
			return;
		}
		if (sent.index() < 0 || sent.index() >= count) return;
		if (!ServerScenes.nameable(sent.name())) {
			player.sendOverlayMessage(Component.literal("That is not a name a scene can have."));
			return;
		}

		Sending sending = sendings.get(player.getUUID());
		if (sending == null || !sending.name.equals(sent.upload()) || sending.pieces.length != count) {
			// A new transfer name means a new scene: whatever was half sent before it
			// is dropped rather than merged with it.
			sending = new Sending(sent.upload(), count);
			sendings.put(player.getUUID(), sending);
		}
		sending.pieces[sent.index()] = sent.part();

		int have = 0;
		int size = 0;
		for (byte[] piece : sending.pieces) {
			if (piece == null) continue;
			have++;
			size += piece.length;
		}
		if (have < count) return;

		sendings.remove(player.getUUID());
		byte[] whole = new byte[size];
		int at = 0;
		for (byte[] piece : sending.pieces) {
			System.arraycopy(piece, 0, whole, at, piece.length);
			at += piece.length;
		}
		keep(player, sent.name(), new String(whole, java.nio.charset.StandardCharsets.UTF_8));
	}

	/** A scene arriving a piece at a time, and what it is called on the way. */
	private static final class Sending {
		private final String name;
		private final byte[][] pieces;

		private Sending(String name, int count) {
			this.name = name;
			this.pieces = new byte[count][];
		}
	}

	private static final java.util.Map<java.util.UUID, Sending> sendings =
		new java.util.concurrent.ConcurrentHashMap<>();

	/** Dropped when a player leaves, so a transfer nobody finished is not kept. */
	public static void forget(ServerPlayer player) {
		sendings.remove(player.getUUID());
	}

	/**
	 * Writes a finished scene down, and tells everybody else about it.
	 *
	 * Sent on to the other players rather than only written down, because a scene
	 * is watched as well as edited: somebody standing on the deck while it is being
	 * built should see the last version, not the one they logged in with.
	 */
	private static void keep(ServerPlayer player, String name, String json) {
		Scene kept = ServerScenes.take(name, json);
		if (kept == null) {
			player.sendOverlayMessage(Component.literal("That scene would not read."));
			return;
		}
		String written = ServerScenes.written(name);
		if (written == null) return;
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			if (other != player) {
				ServerPlayNetworking.send(other, new ScenePayloads.Document(name, written));
			}
		}
	}

	public static void remove(ServerPlayer player, String name) {
		if (!allowed(player)) return;
		if (!ServerScenes.remove(name)) return;
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(other, new ScenePayloads.Gone(name));
		}
	}
}
