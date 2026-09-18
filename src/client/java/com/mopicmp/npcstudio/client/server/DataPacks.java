package com.mopicmp.npcstudio.client.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.server.ManagedServer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

/**
 * The datapacks of a world, and which of them are switched on.
 *
 * Two different questions, kept apart because they have two different answers on
 * disk. <b>Which packs exist</b> is a folder listing: {@code <world>/datapacks}
 * holds a folder or a zip per pack. <b>Which are on</b> is not there at all — it
 * is in {@code level.dat}, under {@code Data.DataPacks}, as two lists of names.
 *
 * <p>So this reads and writes {@code level.dat}. That is the game's own file in
 * the game's own format, and this is a mod running inside the game, which means
 * the game's own reader and writer are right here — no guessing at a binary
 * format, no second implementation to keep in step. It is also the reason this
 * class is on the client side of the mod rather than with the rest of the server
 * code: {@code NbtIo} comes with Minecraft.
 *
 * <p>Never while the server is running. A running server holds the level data in
 * memory and writes it out on its own schedule; anything written underneath it is
 * overwritten without a word. When it is up, the change goes over the command
 * channel instead, which is what {@code /datapack} is for.
 */
public final class DataPacks {

	private DataPacks() {
	}

	/**
	 * One pack in a world.
	 *
	 * @param name the name the game knows it by, which is the file's own name
	 * @param id   what a command calls it: {@code file/<name>}
	 */
	public record Pack(String name, Path path, boolean folder, boolean on, long size) {

		public String id() {
			return "file/" + name;
		}

		/** Ours, and therefore not somebody's work to be careful about. */
		public boolean mine() {
			return name.equals(com.mopicmp.npcstudio.server.Dimensions.SPACE);
		}
	}

	public static Path folder(ManagedServer server, String world) {
		return server.path().resolve(world).resolve("datapacks");
	}

	private static Path levelDat(ManagedServer server, String world) {
		return server.path().resolve(world).resolve("level.dat");
	}

	/**
	 * Every pack in the world, with whether the world has it switched on.
	 *
	 * A pack that is on disk and in neither list of {@code level.dat} is on: that
	 * is what the game does with one that has just been dropped into the folder,
	 * and saying otherwise would show a new pack as disabled until somebody had
	 * started the server once.
	 */
	public static List<Pack> of(ManagedServer server, String world) {
		Path where = folder(server, world);
		if (!Files.isDirectory(where)) return List.of();

		Set<String> off = disabled(server, world);
		List<Pack> packs = new ArrayList<>();
		try (var files = Files.list(where)) {
			for (Path each : files.sorted().toList()) {
				String name = each.getFileName().toString();
				boolean folder = Files.isDirectory(each);
				if (!folder && !name.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
				// A folder with no pack.mcmeta is not a pack; the server says so in
				// its log every start, and showing it here would be showing a fault
				// as a feature.
				if (folder && !Files.isRegularFile(each.resolve("pack.mcmeta"))) continue;
				packs.add(new Pack(name, each, folder, !off.contains(name),
					com.mopicmp.npcstudio.server.Worlds.size(each)));
			}
		} catch (IOException unreadable) {
			return List.of();
		}
		return List.copyOf(packs);
	}

	private static Set<String> disabled(ManagedServer server, String world) {
		Set<String> off = new LinkedHashSet<>();
		CompoundTag level = read(server, world);
		if (level == null) return off;
		CompoundTag data = level.getCompound("Data").orElse(null);
		if (data == null) return off;
		CompoundTag packs = data.getCompound("DataPacks").orElse(null);
		if (packs == null) return off;
		ListTag list = packs.getList("Disabled").orElse(null);
		if (list == null) return off;
		for (int at = 0; at < list.size(); at++) {
			list.getString(at).ifPresent(off::add);
		}
		return off;
	}

	private static CompoundTag read(ManagedServer server, String world) {
		Path file = levelDat(server, world);
		if (!Files.isRegularFile(file)) return null;
		try {
			return NbtIo.readCompressed(file, NbtAccounter.create(MOST_LEVEL_DAT));
		} catch (IOException | RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("Could not read {}: {}", file, unreadable.toString());
			return null;
		}
	}

	/** A level.dat is tens of kilobytes; this is room for a very strange one. */
	private static final long MOST_LEVEL_DAT = 8L * 1024 * 1024;

	/**
	 * Switch a pack on or off in the world's own record of it.
	 *
	 * Both lists are written, not one. The game reads {@code Enabled} as the order
	 * packs are applied in and {@code Disabled} as the ones to leave out, and a
	 * name that is in neither is treated as new — so taking a name out of
	 * {@code Disabled} without putting it into {@code Enabled} would work by
	 * accident, and stop working the day that changes.
	 */
	public static void set(ManagedServer server, String world, String name, boolean on)
			throws IOException {
		Path file = levelDat(server, world);
		CompoundTag level = read(server, world);
		if (level == null) throw new IOException("This world has no level.dat to write to.");
		CompoundTag data = level.getCompound("Data")
			.orElseThrow(() -> new IOException("This world's level.dat has no Data in it."));
		CompoundTag packs = data.getCompound("DataPacks").orElseGet(CompoundTag::new);

		List<String> enabled = strings(packs.getList("Enabled").orElse(null));
		List<String> off = strings(packs.getList("Disabled").orElse(null));
		enabled.remove(name);
		off.remove(name);
		if (on) enabled.add(name);
		else off.add(name);

		packs.put("Enabled", list(enabled));
		packs.put("Disabled", list(off));
		data.put("DataPacks", packs);
		level.put("Data", data);

		// Written beside and moved into place, because a half-written level.dat is
		// a world that will not open at all.
		Path part = file.resolveSibling("level.dat.npc_part");
		NbtIo.writeCompressed(level, part);
		Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private static List<String> strings(ListTag list) {
		List<String> out = new ArrayList<>();
		if (list == null) return out;
		for (int at = 0; at < list.size(); at++) {
			list.getString(at).ifPresent(out::add);
		}
		return out;
	}

	private static ListTag list(List<String> names) {
		ListTag out = new ListTag();
		for (String each : names) out.add(StringTag.valueOf(each));
		return out;
	}

	/**
	 * The version of the game that last wrote this world.
	 *
	 * {@code Data.Version.Name} — the game writes it every time it saves, so it is
	 * the version the world was last opened by rather than the one that made it.
	 * That is the more useful of the two anyway: it is the answer to "can I still
	 * open this", and a world written by a newer version is the one thing an older
	 * server will refuse outright.
	 */
	public static String versionOf(ManagedServer server, String world) {
		CompoundTag level = read(server, world);
		if (level == null) return "";
		return level.getCompound("Data")
			.flatMap(data -> data.getCompound("Version"))
			.flatMap(version -> version.getString("Name"))
			.orElse("");
	}

	/** Delete a pack from the world, which is a folder or one file. */
	public static void drop(Pack pack) throws IOException {
		if (pack.folder()) {
			try (var walk = Files.walk(pack.path())) {
				for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(each);
				}
			}
		} else {
			Files.deleteIfExists(pack.path());
		}
	}
}
