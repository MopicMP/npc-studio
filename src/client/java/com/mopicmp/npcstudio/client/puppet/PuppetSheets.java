package com.mopicmp.npcstudio.client.puppet;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.net.PuppetPayloads;
import com.mopicmp.npcstudio.puppet.Puppet;
import com.mopicmp.npcstudio.puppet.PuppetShelf;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The layout sheets this client last heard about, and how it asks for changes.
 *
 * A list of names and numbers; the pictures they refer to go through {@code Costumes},
 * which is where every picture fetched by fingerprint already lives. Nothing here holds
 * a pixel.
 */
public final class PuppetSheets {

	private PuppetSheets() { }

	private static List<Puppet> known = List.of();

	public static List<Puppet> all() {
		return known;
	}

	public static Puppet named(String name) {
		for (Puppet each : known) {
			if (each.name().equals(name)) return each;
		}
		return null;
	}

	/**
	 * The whole shelf as it now stands, read out of the text the server sent.
	 *
	 * A sheet that will not parse is dropped and the rest are kept. One unreadable sheet
	 * is a sheet somebody has to look at; the rest of the world's figures working is not
	 * something to give up over it.
	 */
	public static void accept(List<String> written) {
		List<Puppet> read = new ArrayList<>();
		for (String each : written) {
			try {
				read.add(PuppetShelf.fromJson(JsonParser.parseString(each).getAsJsonObject()));
			} catch (Exception ignored) {
				// Named nowhere on purpose: the server logs what it sent, and a message
				// on the screen about a sheet nobody asked to open is noise.
			}
		}
		known = List.copyOf(read);
	}

	public static void refresh() {
		ClientPlayNetworking.send(new PuppetPayloads.Please());
	}

	/** Dropped on leaving a world; the next one has its own figures. */
	public static void forget() {
		known = List.of();
	}

	// ------------------------------------------------------------------ changing

	/**
	 * One slot written down, on a sheet made if it is not there yet.
	 *
	 * Sent as it is placed rather than gathered up for a save button. A slot is what
	 * somebody is working on, it is small enough to fit in one packet whatever it holds,
	 * and closing the window has then never lost anything.
	 */
	public static void put(String sheet, int wide, int high, Puppet.Slot slot) {
		String written = new GsonBuilder().create().toJson(PuppetShelf.slotToJson(slot));
		ClientPlayNetworking.send(new PuppetPayloads.PutSlot(sheet, wide, high, written));
	}

	public static void drop(String sheet, String slot) {
		ClientPlayNetworking.send(new PuppetPayloads.Drop(sheet, slot));
	}

	/** The whole figure. Named separately from dropping a slot so it reads at the call site. */
	public static void dropSheet(String sheet) {
		ClientPlayNetworking.send(new PuppetPayloads.Drop(sheet, ""));
	}
}
