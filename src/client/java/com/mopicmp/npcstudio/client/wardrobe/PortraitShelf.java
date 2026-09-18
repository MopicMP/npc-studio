package com.mopicmp.npcstudio.client.wardrobe;

import java.util.List;

import com.mopicmp.npcstudio.net.PortraitPayloads;
import com.mopicmp.npcstudio.net.WardrobePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * What the world's portrait shelf holds, as this client last heard it.
 *
 * A list and nothing else. The pictures themselves go through {@link Costumes},
 * which is where every picture fetched by fingerprint already lives — one cache, one
 * request guard, one place that turns bytes into a texture. Two caches would be two
 * places for a picture to be, and the second one would be the one nobody clears.
 */
public final class PortraitShelf {

	private PortraitShelf() { }

	private static List<WardrobePayloads.Costume> known = List.of();

	public static List<WardrobePayloads.Costume> all() {
		return known;
	}

	public static void accept(List<WardrobePayloads.Costume> pictures) {
		known = List.copyOf(pictures);
	}

	/** Asks the server for the list. Cheap, and a picker needs it before it opens. */
	public static void refresh() {
		ClientPlayNetworking.send(new PortraitPayloads.Please());
	}

	/**
	 * What a picture is called, or the fingerprint itself when the list has not come.
	 *
	 * The fingerprint is honest and useless in equal measure, and it is better than an
	 * empty button: a graph naming a picture this client has not heard of is a real
	 * state — the list arrives a moment later — and a blank reads as broken.
	 */
	public static String labelOf(String fingerprint) {
		if (fingerprint == null || fingerprint.isEmpty()) return "";
		for (WardrobePayloads.Costume each : known) {
			if (each.fingerprint().equals(fingerprint)) return each.label();
		}
		return fingerprint;
	}

	/** Dropped on leaving a world; the next one has its own shelf. */
	public static void forget() {
		known = List.of();
	}

	// ------------------------------------------------------------------ changing

	/**
	 * Puts pictures on the shelf, in pieces, through the wardrobe's own upload.
	 *
	 * One path for both shelves on purpose: the guards on the far side — the piece
	 * count, the index, the size, one transfer at a time — are what stop a stranger
	 * filling a server's memory, and a second copy of them is a second copy to get
	 * wrong. See {@code WardrobePayloads.Shelves}.
	 */
	public static void add(String label, String folder, byte[] png) {
		Costumes.add(label, folder, "", png, WardrobePayloads.Shelves.PORTRAITS);
	}

	public static void remove(List<String> ids) {
		change(WardrobePayloads.Edit.Verb.REMOVE, ids, "", "");
	}

	public static void rename(String id, String label) {
		change(WardrobePayloads.Edit.Verb.RENAME, List.of(id), label, "");
	}

	/** Moves pictures into a folder. An empty name is the shelf's own top level. */
	public static void refile(List<String> ids, String folder) {
		change(WardrobePayloads.Edit.Verb.REFILE, ids, "", folder);
	}

	/**
	 * Takes a folder away, and the records of what was in it.
	 *
	 * The pictures themselves stay on disk under their fingerprints — that is the
	 * shelf's oldest promise and the reason a folder is safe to delete at all. What
	 * is lost is a list, and lists are kept in versions.
	 */
	public static void dropFolder(String folder) {
		change(WardrobePayloads.Edit.Verb.DROP_CATEGORY, List.of(), "", folder);
	}

	private static void change(WardrobePayloads.Edit.Verb verb, List<String> ids,
			String label, String folder) {
		ClientPlayNetworking.send(new WardrobePayloads.Edit(verb, ids, label, folder, "",
			new byte[0], WardrobePayloads.Shelves.PORTRAITS));
	}

	/** Every folder that has something in it, in the order the shelf lists them. */
	public static List<String> folders() {
		var seen = new java.util.ArrayList<String>();
		for (WardrobePayloads.Costume each : known) {
			if (!each.category().isEmpty() && !seen.contains(each.category())) {
				seen.add(each.category());
			}
		}
		return seen;
	}
}
