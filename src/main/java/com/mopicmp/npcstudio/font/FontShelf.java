package com.mopicmp.npcstudio.font;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.net.FontPayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * The typefaces this server hands out.
 *
 * <h2>Where they come from</h2>
 *
 * A folder. Whoever runs the server drops {@code .ttf} files in it, and that is
 * the whole of the administration — there is no command, no upload, no menu. In
 * single player it is the same folder the client reads, so a map author who put a
 * typeface in it to write with has already put it where a second player will get
 * it from, without being told there were two steps.
 *
 * <h2>Why nothing is sent until it is asked for</h2>
 *
 * Because a player who was here yesterday has the files already. Sending
 * everything on every join would cost a megabyte a door for a map with six
 * typefaces, every time, for ever — and the thing that makes it unnecessary is
 * cheap: a list of digests, and the client asks about the ones it is missing.
 *
 * <h2>What is not here</h2>
 *
 * A way for a player to upload one. It was considered and left out on purpose:
 * it is the only part of this that takes a binary file from somebody else's
 * machine and hands it to a C library on everybody's, and it is not needed to
 * make a map — the author has a folder. When it is wanted, it wants the same care
 * the dialogue editor gets: the permission check on this side, the size cap on
 * this side, and a note in the security document.
 */
public final class FontShelf {

	private FontShelf() { }

	/** Name to file, in the order they were found. */
	private static final Map<String, Kept> shelf = new LinkedHashMap<>();

	private record Kept(String name, String digest, byte[] file) { }

	/** Where a server keeps them. The same path a client reads, which is the point. */
	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("npc_studio").resolve("fonts");
	}

	/**
	 * Reads the folder. Called when the server starts.
	 *
	 * Held in memory rather than read per request. A typeface is a few hundred
	 * kilobytes and there are at most thirty-two of them, which is a rounding error
	 * beside a loaded chunk — and reading from disk inside a packet handler is how
	 * a server ends up stuttering when somebody walks through a door.
	 */
	public static void load() {
		shelf.clear();
		Path folder = folder();
		try {
			Files.createDirectories(folder);
			try (var files = Files.list(folder)) {
				for (Path file : files.sorted().toList()) {
					if (shelf.size() >= FontFiles.MOST) break;
					String name = file.getFileName().toString();
					if (!name.toLowerCase(Locale.ROOT).endsWith(".ttf")) continue;
					if (Files.size(file) > FontFiles.LARGEST) {
						NpcStudio.LOGGER.warn("{} is larger than {} bytes and is not offered",
							name, FontFiles.LARGEST);
						continue;
					}
					byte[] bytes = Files.readAllBytes(file);
					String plain = name.substring(0, name.length() - 4);
					shelf.put(FontFiles.digest(bytes),
						new Kept(plain, FontFiles.digest(bytes), bytes));
				}
			}
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read the fonts folder: {}", failed.toString());
		}
		// Said once, with the names, for the same reason the client says it: "the
		// fonts do not work" is one sentence for two faults, and which of them it is
		// should not need guessing at.
		NpcStudio.LOGGER.info("Fonts this server offers: {}",
			shelf.isEmpty() ? "none" : shelf.values().stream().map(Kept::name).toList());
	}

	public static void forget() {
		shelf.clear();
	}

	/** Tells a player who has just arrived what there is. */
	public static void greet(ServerPlayer player) {
		if (shelf.isEmpty()) return;
		List<FontPayloads.Catalogue.Entry> entries = new ArrayList<>();
		for (Kept kept : shelf.values()) {
			entries.add(new FontPayloads.Catalogue.Entry(
				kept.name(), kept.digest(), kept.file().length));
		}
		ServerPlayNetworking.send(player, new FontPayloads.Catalogue(entries));
	}

	/**
	 * Sends one typeface, in pieces.
	 *
	 * A request for something not on the shelf is answered with nothing rather than
	 * with a complaint. The client asks by digest, and the only way to ask for a
	 * digest that is not here is to have been told about it and then have it
	 * removed — which is ordinary, not an offence.
	 */
	public static void hand(ServerPlayer player, String digest) {
		Kept kept = shelf.get(digest);
		if (kept == null) return;

		List<byte[]> pieces = FontFiles.split(kept.file());
		for (int at = 0; at < pieces.size(); at++) {
			ServerPlayNetworking.send(player,
				new FontPayloads.Part(digest, at, pieces.size(), pieces.get(at)));
		}
	}
}
