package com.mopicmp.npcstudio.puppet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.wardrobe.Kept;

import net.minecraft.server.MinecraftServer;

/**
 * The layout sheets a world knows: which figures are assembled out of which pictures.
 *
 * <h2>Why beside the pictures rather than inside a dialogue</h2>
 *
 * Because a sheet is shared. One figure appears in every scene she is in, and a sheet
 * kept inside a conversation would be copied into the next conversation and then drift
 * from it — two Lonas with the eyes in different places, and no way to tell which one a
 * scene is using.
 *
 * It lives in the world's own folder for the reason the portraits do: <b>config does not
 * travel with the map</b>. An author hands the world over as a folder, and a sheet left
 * in a game directory stays behind on their machine while every picture it names arrives.
 *
 * <h2>What is in a sheet and what is not</h2>
 *
 * Positions and names. The pictures themselves are on the portrait shelf, referred to by
 * fingerprint — so a sheet is a few kilobytes of text however many thousand pictures the
 * world holds, and throwing a sheet away has never lost a picture.
 *
 * Kept in versions by {@link Kept}, like the picture list, and for the same reason: a
 * sheet is an afternoon of somebody placing things by hand.
 */
public final class PuppetShelf {

	/** As many figures as one world may have sheets for. */
	public static final int MOST = 256;

	private final Path root;
	private final List<Puppet> sheets = new ArrayList<>();

	public PuppetShelf(Path root) {
		this.root = root;
		read();
	}

	public static PuppetShelf of(MinecraftServer server) {
		return new PuppetShelf(server
			.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
			.resolve("npcstudio"));
	}

	private Path list() {
		return root.resolve("puppets.json");
	}

	private Path versions() {
		return root.resolve("puppets-past");
	}

	public List<Puppet> all() {
		return List.copyOf(sheets);
	}

	public Puppet named(String name) {
		for (Puppet each : sheets) {
			if (each.name().equals(name)) return each;
		}
		return null;
	}

	/**
	 * Writes a sheet down, replacing the one of that name.
	 *
	 * Named rather than numbered, and that is what makes the whole thing safe to edit:
	 * a scene says "Lona", so renaming a sheet is the one edit that can lose a scene its
	 * figure — and it is an edit somebody makes deliberately, looking at the name.
	 * Everything else about a sheet can be moved around freely.
	 */
	public boolean put(Puppet sheet) {
		if (sheet == null || sheet.name().isEmpty()) return false;
		if (sheet.slots().size() > Puppet.MOST_SLOTS) return false;
		for (Puppet.Slot slot : sheet.slots()) {
			if (slot.parts().size() > Puppet.MOST_PARTS) return false;
		}
		for (int i = 0; i < sheets.size(); i++) {
			if (sheets.get(i).name().equals(sheet.name())) {
				sheets.set(i, sheet);
				write();
				return true;
			}
		}
		if (sheets.size() >= MOST) return false;
		sheets.add(sheet);
		write();
		return true;
	}

	public boolean remove(String name) {
		if (!sheets.removeIf(each -> each.name().equals(name))) return false;
		write();
		return true;
	}

	public List<String> history() {
		return Kept.history(versions());
	}

	/** Puts a past set of sheets back. The pictures they name are untouched on disk. */
	public boolean restore(String version) {
		Path file = Kept.find(versions(), version);
		if (file == null) return false;
		try {
			// Read before anything is written, for the reason the picture list learned it:
			// keeping the current state aside first can land the copy on the very file
			// being restored, and then the safety net cuts its own rope.
			List<Puppet> wanted = parse(Files.readString(file, StandardCharsets.UTF_8));
			Kept.aside(list(), versions());
			sheets.clear();
			sheets.addAll(wanted);
			writeList();
			return true;
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not restore puppets {}: {}", version, failed.toString());
			return false;
		}
	}

	// -------------------------------------------------------------- on disk

	private void write() {
		Kept.aside(list(), versions());
		writeList();
	}

	private void writeList() {
		try {
			Files.createDirectories(root);
			JsonArray array = new JsonArray();
			for (Puppet sheet : sheets) array.add(toJson(sheet));
			Files.writeString(list(),
				new GsonBuilder().setPrettyPrinting().create().toJson(array),
				StandardCharsets.UTF_8);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not write the puppet sheets: {}", failed.toString());
		}
	}

	private void read() {
		sheets.clear();
		try {
			if (!Files.isRegularFile(list())) return;
			sheets.addAll(parse(Files.readString(list(), StandardCharsets.UTF_8)));
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read the puppet sheets: {}", failed.toString());
		}
	}

	// -------------------------------------------------------------- the file

	/**
	 * A sheet as JSON, and back.
	 *
	 * Written by hand rather than through a codec because this file is read by people:
	 * somebody looking at why an eye is two pixels out opens it, and a codec's output is
	 * shaped by the codec. The fields are the ones a person would name.
	 *
	 * A nudge of nothing is left out entirely, which is nearly every part — so the file
	 * says what somebody actually did and not what the record happens to hold.
	 */
	public static JsonObject toJson(Puppet sheet) {
		JsonObject object = new JsonObject();
		object.addProperty("name", sheet.name());
		object.addProperty("wide", sheet.wide());
		object.addProperty("high", sheet.high());
		JsonArray slots = new JsonArray();
		for (Puppet.Slot slot : sheet.slots()) slots.add(slotToJson(slot));
		object.add("slots", slots);
		return object;
	}

	public static Puppet fromJson(JsonObject object) {
		List<Puppet.Slot> slots = new ArrayList<>();
		if (object.has("slots")) {
			for (var element : object.getAsJsonArray("slots")) {
				slots.add(slotFromJson(element.getAsJsonObject()));
			}
		}
		return new Puppet(object.get("name").getAsString(),
			object.get("wide").getAsInt(), object.get("high").getAsInt(), slots);
	}

	/**
	 * One slot on its own, which is the unit a client sends.
	 *
	 * The same reading as inside a whole sheet, because it is the same thing written the
	 * same way. Two readers for one shape is how a field ends up understood two ways.
	 */
	public static Puppet.Slot slotFromJson(JsonObject read) {
		List<Puppet.Part> parts = new ArrayList<>();
		if (read.has("parts")) {
			for (var each : read.getAsJsonArray("parts")) {
				JsonObject one = each.getAsJsonObject();
				parts.add(new Puppet.Part(
					one.get("label").getAsString(),
					one.get("picture").getAsString(),
					one.has("nudge_x") ? one.get("nudge_x").getAsInt() : 0,
					one.has("nudge_y") ? one.get("nudge_y").getAsInt() : 0));
			}
		}
		return new Puppet.Slot(read.get("name").getAsString(),
			read.get("x").getAsInt(), read.get("y").getAsInt(), parts);
	}

	/** One slot as JSON, for the same reason: the client sends slots, not sheets. */
	public static JsonObject slotToJson(Puppet.Slot slot) {
		JsonObject written = new JsonObject();
		written.addProperty("name", slot.name());
		written.addProperty("x", slot.x());
		written.addProperty("y", slot.y());
		JsonArray parts = new JsonArray();
		for (Puppet.Part part : slot.parts()) {
			JsonObject one = new JsonObject();
			one.addProperty("label", part.label());
			one.addProperty("picture", part.picture());
			if (part.nudgeX() != 0) one.addProperty("nudge_x", part.nudgeX());
			if (part.nudgeY() != 0) one.addProperty("nudge_y", part.nudgeY());
			parts.add(one);
		}
		written.add("parts", parts);
		return written;
	}

	private static List<Puppet> parse(String text) {
		List<Puppet> found = new ArrayList<>();
		for (var element : JsonParser.parseString(text).getAsJsonArray()) {
			found.add(fromJson(element.getAsJsonObject()));
		}
		return found;
	}
}
