package com.mopicmp.npcstudio.client.map;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The resource pack that travels inside the map.
 *
 * <h2>Why this is four lines rather than a feature</h2>
 *
 * Because the game already does it. A zip at
 * {@code <world>/resourcepacks/resources.zip} is applied when the world is
 * opened — {@code WorldOpenFlows.loadBundledResourcePack} — and it needs no
 * hosting, no checksum and no setting of ours. Adding a switch beside a thing
 * that already has one is how a map ends up with two answers about whether its
 * pack is on.
 *
 * So the only thing worth writing is the part the game does not do: saying
 * whether the file is there, and saying <em>where</em> exactly, because the path
 * is not the one people remember. It used to be {@code resources.zip} in the
 * world folder itself, and a pack left at the old path produces no error, no
 * message, and no textures — which reads as a broken pack rather than a
 * misplaced one.
 */
public final class MapPack {

	private MapPack() { }

	/**
	 * Where the pack goes for this world, or null when there is no local world.
	 *
	 * Null on a server, and that is the honest answer rather than a missing
	 * feature: a pack inside the world folder is something only the machine
	 * running the world can see, and a map played over a connection needs the
	 * server's own resource pack setting instead.
	 */
	public static Path where() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || !client.hasSingleplayerServer()) return null;
		var server = client.getSingleplayerServer();
		if (server == null) return null;
		return server.getWorldPath(LevelResource.MAP_RESOURCE_FILE);
	}

	public static boolean there() {
		Path at = where();
		return at != null && Files.isRegularFile(at);
	}

	/** The folder to put it in, made if it is not there yet, or null if it cannot be. */
	public static Path folder() {
		Path at = where();
		if (at == null) return null;
		Path folder = at.getParent();
		try {
			Files.createDirectories(folder);
		} catch (Exception failed) {
			// Opening a folder that does not exist does nothing on every platform, so
			// the button that would follow this is better disabled than mysterious.
			return null;
		}
		return folder;
	}
}
