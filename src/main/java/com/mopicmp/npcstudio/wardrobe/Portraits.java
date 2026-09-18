package com.mopicmp.npcstudio.wardrobe;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.net.PortraitPayloads;
import com.mopicmp.npcstudio.net.WardrobePayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The world's portraits: the second shelf of the same cupboard.
 *
 * <h2>Why this is a shelf of the world and not a folder of the game</h2>
 *
 * The first version of the plan said {@code config/npc_studio/portraits}, and gave a
 * good reason: somebody drops pictures in with a file browser and expects them to
 * turn up. The reason is right and the conclusion was wrong — <b>config does not
 * travel with the map</b>. An author hands the world to somebody as a folder, and
 * every portrait would stay behind on their own machine.
 *
 * The world's own shelf was already built, for skins, with every property this needs:
 * pictures named by their own fingerprint, never rewritten and never deleted, a small
 * versioned list beside them, and delivery by fingerprint to any player who asks. So
 * portraits live there, and the whole distribution question — which the plan had down
 * as a separate later job — does not arise.
 *
 * What it costs is folders you can open in a file browser. What it buys is pictures
 * that arrive with the map. See {@link PictureLibrary}, where the shelves are.
 */
public final class Portraits {

	private static PictureLibrary shelf;

	private Portraits() { }

	public static void open(MinecraftServer server) {
		shelf = PictureLibrary.portraitsOf(server);
	}

	public static void close() {
		shelf = null;
	}

	public static PictureLibrary shelf() {
		return shelf;
	}

	/** Everything on the shelf, for the picker in the editor. */
	public static void send(ServerPlayer player) {
		if (shelf == null) return;
		List<WardrobePayloads.Costume> rows = new ArrayList<>();
		for (PictureLibrary.Entry entry : shelf.entries()) {
			// The two fields at the end belong to a costume and mean nothing here: a
			// portrait has no model width and no eyes to blink. Sent as their harmless
			// values rather than by inventing a second row type for six shared fields.
			rows.add(new WardrobePayloads.Costume(entry.id(), entry.label(),
				entry.category(), entry.group(), entry.fingerprint(), 0, false));
		}
		ServerPlayNetworking.send(player, new PortraitPayloads.Shelf(rows));
	}
}
