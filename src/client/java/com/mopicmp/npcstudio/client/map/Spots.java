package com.mopicmp.npcstudio.client.map;

import java.util.List;

import com.mopicmp.npcstudio.map.Spot;
import com.mopicmp.npcstudio.net.SpotPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * What this client knows about the map's named places.
 *
 * Kept here rather than in a panel for the same reason the start settings are:
 * they are drawn in the world whether or not any panel is open, and the panel
 * that lists them is rebuilt on every resize.
 */
public final class Spots {

	private Spots() { }

	private static List<Spot> spots = List.of();

	/**
	 * Which world the list in hand describes.
	 *
	 * <h2>The gap this closes</h2>
	 *
	 * Places belong to a level rather than to the map — two dimensions may each
	 * reasonably have a doorway called "the gate" — so walking through a portal makes
	 * the list in hand the wrong list. The server sends the new one, but not
	 * instantly: for the few ticks in between, the old level's points would be drawn
	 * at those coordinates in the new one. Markers standing where nobody put them are
	 * worse than no markers, and they are worse in the way that costs an afternoon,
	 * because they look exactly like real ones.
	 *
	 * Written when a list arrives rather than sent with it. By then the client is
	 * already in the new level — the game's own dimension packet goes first, and a
	 * connection delivers in order — so the answer is had for nothing.
	 */
	private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> of;

	/** The places of the world being stood in, and no others. */
	public static List<Spot> all() {
		var level = Minecraft.getInstance().level;
		if (level == null || of == null || !level.dimension().equals(of)) return List.of();
		return spots;
	}

	/** Where a place is, or null when this world has no such name. */
	public static BlockPos at(String name) {
		for (Spot spot : all()) {
			if (spot.name().equals(name)) return spot.at();
		}
		return null;
	}

	public static boolean has(String name) {
		return at(name) != null;
	}

	public static void took(List<Spot> arrived) {
		spots = List.copyOf(arrived);
		var level = Minecraft.getInstance().level;
		of = level == null ? null : level.dimension();
	}

	public static void please() {
		if (Minecraft.getInstance().getConnection() == null) return;
		ClientPlayNetworking.send(new SpotPayloads.Please());
	}

	/**
	 * Puts one down, or moves the one already called this.
	 *
	 * Nothing is written here — the list is replaced when the server says so. A
	 * client that drew its own guess first would show a place that exists nowhere
	 * else the moment the server refused, which is exactly the sort of thing nobody
	 * notices until a graph does not work.
	 */
	public static void put(String name, BlockPos at) {
		if (Minecraft.getInstance().getConnection() == null) return;
		ClientPlayNetworking.send(new SpotPayloads.Put(name, at));
	}

	public static void drop(String name) {
		if (Minecraft.getInstance().getConnection() == null) return;
		ClientPlayNetworking.send(new SpotPayloads.Drop(name));
	}

	/**
	 * A name nobody has used yet, for the case where somebody wants a point more
	 * than they want to name it.
	 *
	 * Numbered rather than clever. It is meant to be replaced by a real name, and a
	 * generated name that looks like a real one is a name that survives into a
	 * finished map.
	 */
	public static String spare(String stem) {
		if (!has(stem)) return stem;
		for (int n = 2; n < 1000; n++) {
			String tried = stem + " " + n;
			if (!has(tried)) return tried;
		}
		return stem;
	}

	public static void gone() {
		spots = List.of();
		of = null;
	}
}
