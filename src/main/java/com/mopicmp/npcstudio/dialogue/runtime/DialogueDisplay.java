package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.CloseDialoguePayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who currently has a line on screen, and when to take it away.
 *
 * This is not the same thing as the bookmark, and conflating them was the bug:
 * the bar was only ever closed when a conversation *ended*, so walking away,
 * logging out or simply losing interest left it sitting there forever.
 *
 * The bookmark is where the player got to and survives all of that on purpose.
 * The display is a much weaker thing — it lasts only while the player is stood
 * in front of the NPC — and the two need separate lifetimes.
 *
 * Kept in memory rather than saved, because it describes a screen rather than
 * a world: after a restart nobody is looking at anything.
 */
public final class DialogueDisplay {

	/**
	 * How far a player can wander before the line is taken down.
	 *
	 * A little further than the reach for talking to an NPC, so that shuffling
	 * about while reading does not make the text flicker away and back.
	 */
	private static final double RANGE = 12.0;
	private static final double RANGE_SQUARED = RANGE * RANGE;

	/** Checked four times a second: often enough to feel immediate, rarely enough to be free. */
	private static final int EVERY_TICKS = 5;

	/**
	 * How long a line stays up with nobody touching it.
	 *
	 * Ten seconds is long enough to read a sentence twice and short enough that a
	 * player who wandered off mentally is not left with text on their screen.
	 */
	private static final long IDLE_MILLIS = 10_000;

	private record Shown(int npcId, long since, boolean fullscreen) { }

	private static final Map<UUID, Shown> SHOWING = new HashMap<>();

	private DialogueDisplay() { }

	public static void showing(ServerPlayer player, NpcEntity npc, boolean fullscreen) {
		SHOWING.put(player.getUUID(), new Shown(npc.getId(), System.currentTimeMillis(), fullscreen));
	}

	/**
	 * Whether this player is looking at this NPC's line right now.
	 *
	 * This is what decides between carrying on and starting the line again. If
	 * the bar has gone — timed out, walked away from — the player has lost the
	 * thread, and skipping straight to the next line would answer a question they
	 * can no longer see. So the same line is spoken again, and only a click while
	 * it is still up moves the conversation on.
	 */
	public static boolean isShowing(ServerPlayer player, NpcEntity npc) {
		Shown shown = SHOWING.get(player.getUUID());
		return shown != null && shown.npcId() == npc.getId();
	}

	public static void hide(ServerPlayer player) {
		if (SHOWING.remove(player.getUUID()) != null) {
			ServerPlayNetworking.send(player, new CloseDialoguePayload());
		}
	}

	/** A player who left takes their conversation off screen with them. */
	public static void forget(ServerPlayer player) {
		SHOWING.remove(player.getUUID());
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY_TICKS != 0 || SHOWING.isEmpty()) return;

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, Shown>> entries = SHOWING.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<UUID, Shown> entry = entries.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				entries.remove();
				continue;
			}
			if (!stillWatching(player, entry.getValue(), now)) {
				entries.remove();
				ServerPlayNetworking.send(player, new CloseDialoguePayload());
			}
		}
	}

	/**
	 * Whether the line should still be up.
	 *
	 * Four ways it should not be: the NPC is gone, it is dead, the player has
	 * walked off, or nobody has touched it for a while. The bookmark is untouched
	 * in every case — coming back and clicking picks the conversation up where it
	 * was left, repeating the line rather than skipping it.
	 */
	private static boolean stillWatching(ServerPlayer player, Shown shown, long now) {
		// The idle timer is for a line the player can walk away from. A full-screen
		// conversation has their whole attention and no way to click the NPC, so
		// timing it out would tear the screen away from someone still reading.
		if (!shown.fullscreen() && now - shown.since() > IDLE_MILLIS) return false;
		var entity = player.level().getEntity(shown.npcId());
		if (!(entity instanceof NpcEntity npc) || !npc.isAlive()) return false;
		return npc.distanceToSqr(player) <= RANGE_SQUARED;
	}
}
