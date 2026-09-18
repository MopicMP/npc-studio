package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.map.Spot;
import com.mopicmp.npcstudio.map.WorldSpots;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server's side of the map's named places.
 *
 * Same rule as the scenes and the start: the checks are here rather than in the
 * panel, because the panel is the half that can be replaced by anything at all.
 */
public final class SpotEditing {

	private SpotEditing() { }

	private static boolean allowed(ServerPlayer player) {
		if (player.isCreative()) return true;
		player.sendOverlayMessage(Component.translatable("npc_studio.start.creative_only"));
		return false;
	}

	/** What this player's world has, to whoever asked. */
	public static void send(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) return;
		ServerPlayNetworking.send(player, new SpotPayloads.Spots(WorldSpots.of(level).all()));
	}

	/**
	 * Sent to everybody in the same world rather than back to the author.
	 *
	 * Two people building a map are looking at the same places, and a point that
	 * exists for one of them and not the other is worse than no point: a graph
	 * written against it works for one and does nothing for the other, and nothing
	 * on either screen says why.
	 */
	private static void tellEveryone(ServerLevel level) {
		WorldSpots spots = WorldSpots.of(level);
		for (ServerPlayer other : level.players()) {
			ServerPlayNetworking.send(other, new SpotPayloads.Spots(spots.all()));
		}
	}

	public static void put(ServerPlayer player, SpotPayloads.Put sent) {
		if (!allowed(player)) return;
		if (!(player.level() instanceof ServerLevel level)) return;

		String name = Spot.tidy(sent.name());
		if (name == null) {
			player.sendOverlayMessage(Component.translatable("npc_studio.spot.bad_name"));
			return;
		}
		if (!level.isInWorldBounds(sent.at())) {
			player.sendOverlayMessage(Component.translatable("npc_studio.spot.outside"));
			return;
		}
		if (!WorldSpots.of(level).put(name, sent.at())) {
			player.sendOverlayMessage(
				Component.translatable("npc_studio.spot.too_many", WorldSpots.MOST));
			return;
		}
		tellEveryone(level);
	}

	/**
	 * Takes a place away, and names the graphs that were pointing at it.
	 *
	 * <h2>Why it removes and warns rather than refusing</h2>
	 *
	 * Refusing would make a place undeletable the moment anything used it, and
	 * retiring a place on purpose is an ordinary thing to want — the doorway moved,
	 * the scene was rewritten. So the removal happens and the consequence is spoken.
	 *
	 * <h2>Why it is spoken at all, when nothing breaks</h2>
	 *
	 * Because nothing breaking is exactly the problem. A mark naming nothing is not
	 * an error at run time and must not be: a lead that is not there behaves the same
	 * way, and every graph already has to survive that. So a character told to walk
	 * to a gate that has been taken away simply does not walk, which from outside is
	 * a character standing still — the failure this whole mod has spent the most time
	 * telling apart from a dozen others.
	 *
	 * The one person who can fix it is the one pressing the button, and the one
	 * moment they can is before they have forgotten which graphs it was.
	 */
	public static void drop(ServerPlayer player, SpotPayloads.Drop sent) {
		if (!allowed(player)) return;
		if (!(player.level() instanceof ServerLevel level)) return;
		if (!WorldSpots.of(level).drop(sent.name())) return;

		tellEveryone(level);

		// Asked after the removal rather than before it, because the answer is a
		// warning rather than a condition — and asking first would invite somebody to
		// turn it into a refusal, which is the behaviour rejected above.
		var pointing = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry
			.pointedAt(com.mopicmp.npcstudio.dialogue.Mark.at(sent.name()));
		if (pointing.isEmpty()) return;

		player.sendOverlayMessage(Component.translatable("npc_studio.spot.was_used",
			sent.name(), String.join(", ", pointing)));
	}
}
