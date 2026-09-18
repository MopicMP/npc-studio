package com.mopicmp.npcstudio.client.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.font.FontFiles;
import com.mopicmp.npcstudio.net.FontPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Typefaces arriving from the server.
 *
 * <h2>Why anything arrives at all</h2>
 *
 * A line written in a typeface the reader has not got draws in the ordinary one,
 * and the person who wrote it cannot see that happening — they have the file. It
 * is the shape of fault this mod keeps meeting: something that works perfectly
 * for the only person able to check it.
 *
 * <h2>Only what is missing</h2>
 *
 * The server sends a list; this asks for the ones not already on disk. A player
 * who was here yesterday asks for nothing and receives nothing. Files are known
 * by their contents, so a typeface renamed on the server is not downloaded again
 * and two different typefaces called the same thing cannot replace each other.
 */
public final class FontDelivery {

	private FontDelivery() { }

	/** Pieces of the files still coming, by digest. */
	private static final Map<String, byte[][]> arriving = new HashMap<>();

	/** What each digest is to be saved as, learned from the list. */
	private static final Map<String, String> names = new HashMap<>();

	/**
	 * Everything is dropped when the world goes.
	 *
	 * Held statically, so without this the half of a typeface that was arriving as
	 * somebody disconnected would still be sitting here on the next server, waiting
	 * for pieces that will never come and taking up the memory in the meantime.
	 */
	public static void forget() {
		arriving.clear();
		names.clear();
	}

	/** What the server has: ask for the ones not on disk. */
	public static void offered(FontPayloads.Catalogue catalogue) {
		List<String> asking = new ArrayList<>();
		for (FontPayloads.Catalogue.Entry entry : catalogue.fonts()) {
			if (entry.bytes() <= 0 || entry.bytes() > FontFiles.LARGEST) continue;
			names.put(entry.digest(), entry.name());
			if (have(entry.digest())) continue;
			asking.add(entry.digest());
		}
		for (String digest : asking) {
			ClientPlayNetworking.send(new FontPayloads.Want(digest));
		}
		if (!asking.isEmpty()) {
			NpcStudio.LOGGER.info("Asking the server for {} typeface(s)", asking.size());
		}
	}

	/**
	 * Whether this exact file is already here.
	 *
	 * By contents rather than by name, and it costs a read of every file in the
	 * folder — which is a handful of files at the moment of joining a world, once.
	 * The alternative is a second file listing what we have, and a second file is a
	 * second thing that can disagree with the first.
	 */
	private static boolean have(String digest) {
		Path folder = Fonts.folder();
		if (!Files.isDirectory(folder)) return false;
		try (var files = Files.list(folder)) {
			for (Path file : files.toList()) {
				if (!file.getFileName().toString().endsWith(".ttf")) continue;
				if (Files.size(file) > FontFiles.LARGEST) continue;
				if (FontFiles.digest(Files.readAllBytes(file)).equals(digest)) return true;
			}
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not look through the fonts folder: {}", failed.toString());
		}
		return false;
	}

	/** One piece. When the last one lands, the file is checked, saved and installed. */
	public static void piece(FontPayloads.Part part) {
		if (part.parts() <= 0 || part.parts() > FontFiles.pieces(FontFiles.LARGEST)) return;
		if (part.index() < 0 || part.index() >= part.parts()) return;

		byte[][] pieces = arriving.computeIfAbsent(part.digest(), any -> new byte[part.parts()][]);
		if (pieces.length != part.parts()) return;
		pieces[part.index()] = part.bytes();

		for (byte[] piece : pieces) {
			if (piece == null) return;
		}
		arriving.remove(part.digest());
		keep(part.digest(), pieces);
	}

	private static void keep(String digest, byte[][] pieces) {
		byte[] file = FontFiles.join(java.util.Arrays.asList(pieces), digest);
		if (file == null) {
			// Not saved, and said out loud. A font assembled wrong is not a letter
			// missing — it is a byte stream handed to a C library that parses it — so
			// the digest is the whole reason this transfer is allowed to be simple.
			NpcStudio.LOGGER.warn("A typeface arrived damaged and was thrown away");
			return;
		}

		String name = names.getOrDefault(digest, digest.substring(0, 8));
		try {
			Path folder = Fonts.folder();
			Files.createDirectories(folder);
			Path path = folder.resolve(safe(name) + ".ttf");
			// Written beside the ones put here by hand rather than in a cache of its
			// own, because a typeface that arrived and a typeface that was dropped in
			// are the same thing to everything downstream — including the next server,
			// which will then not have to send it again.
			Files.write(path, file);
			Fonts.install(path);
			NpcStudio.LOGGER.info("Typeface {} arrived and was installed", name);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not keep the typeface {}: {}", name, failed.toString());
		}
	}

	private static String safe(String name) {
		String tidy = name.replaceAll("[^A-Za-z0-9_.-]+", "_");
		return tidy.isEmpty() ? "font" : tidy;
	}
}
