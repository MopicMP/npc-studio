package com.mopicmp.npcstudio.client.map;

import com.mopicmp.npcstudio.map.MapStart;
import com.mopicmp.npcstudio.net.MapPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * What this client knows about the map's start.
 *
 * Two things arrive from the server and both are kept here rather than in the
 * panel: the settings, because they are applied to a player who may never open a
 * panel at all, and the spawn report, because the panel is rebuilt whenever it is
 * resized and an answer that a round trip away would blink out every time.
 */
public final class Started {

	private Started() { }

	private static MapStart start = MapStart.NOTHING;
	private static MapPayloads.Spawn spawn;

	/**
	 * Set when the map has suggestions nobody has been asked about yet.
	 *
	 * Not asked the moment the payload lands: that is during the join, with the
	 * world still coming in, and a window over a loading screen is a window that
	 * gets dismissed without being read. It waits for a tick where the player
	 * exists and nothing else is open.
	 */
	private static boolean toAsk;

	public static MapStart start() {
		return start;
	}

	/** The last answer about the spawn, or null when none has come back yet. */
	public static MapPayloads.Spawn spawn() {
		return spawn;
	}

	public static void please() {
		if (Minecraft.getInstance().getConnection() == null) return;
		ClientPlayNetworking.send(new MapPayloads.Please());
	}

	/**
	 * Asked at most once for a world, however many times the settings arrive.
	 *
	 * They arrive more than once: the server sends the whole document to everybody
	 * on every edit, including back to the person who made it. Without this, the
	 * author changing a suggested setting would be handed the offer window on every
	 * keystroke — a window they are the author of.
	 */
	private static boolean asked;

	public static void took(MapStart next) {
		start = next;
		StartOptions.arrive(next);
		if (!asked && next.worthAsking()) {
			asked = true;
			toAsk = true;
		}
	}

	public static void told(MapPayloads.Spawn report) {
		spawn = report;
	}

	/**
	 * An edit from the panel: applied here at once, sent when it settles.
	 *
	 * Applied first because that is the whole point of editing brightness beside
	 * the viewport — a value that waits for a round trip before doing anything is a
	 * value nobody can judge a room by. The server still decides whether it is
	 * kept, and a refusal arrives as the next {@code Start} payload, which puts
	 * this back.
	 *
	 * Sent late because a number that is dragged produces one of these per step,
	 * and a packet per step is a packet per step for everybody else on the map too.
	 */
	public static void change(MapStart next) {
		start = next;
		StartOptions.arrive(next);
		unsent = next;
		changedAt = System.currentTimeMillis();
	}

	private static MapStart unsent;
	private static long changedAt;

	private static final long SETTLE = 400;

	public static void setSpawn(net.minecraft.core.BlockPos pos, float yaw, float pitch) {
		if (Minecraft.getInstance().getConnection() == null) return;
		ClientPlayNetworking.send(new MapPayloads.SetSpawn(pos, yaw, pitch));
	}

	public static void tick(Minecraft client) {
		StartOptions.tick();

		if (unsent != null && System.currentTimeMillis() - changedAt >= SETTLE) {
			MapStart sending = unsent;
			unsent = null;
			if (client.getConnection() != null) {
				ClientPlayNetworking.send(new MapPayloads.Start(sending));
			}
		}

		if (!toAsk || client.player == null || client.gui.screen() != null) return;
		toAsk = false;
		client.setScreenAndShow(new StartOfferScreen(start));
	}

	/** Everything undone and forgotten, on the way out of a world. */
	public static void gone() {
		start = MapStart.NOTHING;
		spawn = null;
		toAsk = false;
		asked = false;
		unsent = null;
		changedAt = 0;
		StartOptions.leave();
	}
}
