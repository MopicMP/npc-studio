package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Making a server out of a name and a version.
 *
 * The order matters and is not the obvious one. The core is downloaded first,
 * because that is the step that fails — no network, a version with no server, a
 * checksum that does not match — and every later step is cheap and local. A
 * failure therefore leaves nothing behind: no half-made server in the list, no
 * folder that looks like one, nothing to explain.
 *
 * The agreement is checked here rather than only in the screen. A gate that
 * lives in the interface is a gate that the next caller walks around.
 */
public final class ServerCreation {

	private ServerCreation() {
	}

	/**
	 * What somebody asked for.
	 *
	 * @param onlineMode whether players are checked against Mojang's accounts.
	 *                   True, and it should stay true: with it off, anybody may
	 *                   join under any name, including the owner's — which is
	 *                   also how somebody with a stolen name ends up with the
	 *                   owner's permissions.
	 */
	public record Request(
		String name,
		String coreId,
		String gameVersion,
		int port,
		int memoryMb,
		boolean eulaAccepted,
		boolean onlineMode) {

		public static Request of(String name, String coreId, String gameVersion) {
			return new Request(name, coreId, gameVersion, 25565, 1024, false, true);
		}

		public Request agreed() {
			return new Request(name, coreId, gameVersion, port, memoryMb, true, onlineMode);
		}

		public Request on(int port) {
			return new Request(name, coreId, gameVersion, port, memoryMb, eulaAccepted, onlineMode);
		}

		public Request withMemory(int megabytes) {
			return new Request(name, coreId, gameVersion, port, megabytes, eulaAccepted, onlineMode);
		}
	}

	public static ManagedServer create(
		ServerStore store,
		Path baseDirectory,
		CoreCatalog catalog,
		Request request,
		Downloads.Progress progress) throws IOException {

		CoreCatalog.Core core = catalog.byId(request.coreId())
			.orElseThrow(() -> new IOException("No such core as '" + request.coreId() + "'"));
		CoreProvider provider = CoreProvider.of(core, catalog.downloads())
			.orElseThrow(() -> new IOException(
				"This version of the mod does not know how to install " + core.name()));
		return create(store, baseDirectory, core, provider, request, progress);
	}

	/**
	 * The same, with the core already chosen.
	 *
	 * Split out so that everything except the network can be exercised: what this
	 * has to get right — the order of the steps, the agreement, the clash on a
	 * port, and leaving nothing behind when a step fails — has nothing to do with
	 * where a jar came from.
	 */
	static ManagedServer create(
		ServerStore store,
		Path baseDirectory,
		CoreCatalog.Core core,
		CoreProvider provider,
		Request request,
		Downloads.Progress progress) throws IOException {

		if (!request.eulaAccepted()) {
			throw new IllegalStateException(
				"A server cannot be created before the Minecraft EULA has been accepted");
		}
		if (store.isUnreadable()) {
			throw new IOException("The server list could not be read,"
				+ " and a new server would be lost when it is saved");
		}
		if (!Ports.free(request.port())) {
			// Not worked around by moving to another port: the port is part of the
			// address the owner is about to give their friends.
			throw new IOException("Port " + request.port() + " is already in use on this machine");
		}

		String id = store.idFor(request.name());
		Path directory = baseDirectory.resolve(id);
		if (Files.exists(directory) && !isEmpty(directory)) {
			throw new IOException("There is already something at " + directory);
		}
		Files.createDirectories(directory);

		try {
			String jar = provider.install(directory, request.gameVersion(), progress);
			Eula.accept(directory);
			writeProperties(directory, request);

			ManagedServer server = new ManagedServer();
			server.id = id;
			server.name = request.name();
			server.directory = directory.toString();
			server.core = core.id();
			server.gameVersion = request.gameVersion();
			server.jar = jar;
			server.memoryMb = request.memoryMb();
			server.port = request.port();
			store.add(server);
			store.save();
			NpcStudio.LOGGER.info("Created server '{}' ({} {}) at {}",
				id, core.id(), request.gameVersion(), directory);
			return server;
		} catch (IOException | RuntimeException failed) {
			// Undone completely: a folder with a broken half-install in it is worse
			// than no folder, because next time the name is taken and the reason is
			// invisible.
			discard(baseDirectory, directory);
			throw failed;
		}
	}

	/**
	 * The settings we have an opinion about, and no others.
	 *
	 * The server writes the rest of the file itself on its first start, filling
	 * in every default. Writing a full file here would mean shipping a copy of
	 * somebody else's defaults and keeping it in step with their versions for
	 * ever.
	 */
	private static void writeProperties(Path directory, Request request) throws IOException {
		Path file = directory.resolve("server.properties");
		PropertiesFile properties = Files.exists(file)
			? PropertiesFile.read(file)
			: PropertiesFile.empty();
		properties.set("server-port", String.valueOf(request.port()));
		properties.set("motd", request.name());
		properties.set("online-mode", String.valueOf(request.onlineMode()));
		properties.set("level-name", "world");
		// Not vanilla's defaults, and deliberately. Ten chunks is chosen for a
		// server that has its machine to itself; this one is sharing a processor
		// with the game that made it, and the work grows with the square of this
		// number. Both are in the settings and can be put back.
		properties.set("view-distance", String.valueOf(Machine.viewDistance()));
		properties.set("simulation-distance", String.valueOf(Machine.simulationDistance()));
		// Vanilla waits for each chunk to reach the disk before carrying on, which
		// on a laptop drive is a pause inside a tick. Paper turns this off by
		// default on Windows for exactly that reason; the cost is the chunks
		// written in the last moments of a crash, and the gain is a server that
		// does not stutter every time somebody walks.
		properties.set("sync-chunk-writes", "false");
		properties.write(file);
	}

	private static boolean isEmpty(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) return false;
		try (var listing = Files.list(directory)) {
			return listing.findAny().isEmpty();
		}
	}

	/**
	 * Remove a folder we made, and only one we made.
	 *
	 * The guard is not ceremony. This deletes a directory tree, and the argument
	 * comes from a name somebody typed; the one thing that must be impossible is
	 * this pointing anywhere but inside the folder servers are kept in.
	 */
	private static void discard(Path baseDirectory, Path directory) {
		Path base = baseDirectory.toAbsolutePath().normalize();
		Path target = directory.toAbsolutePath().normalize();
		if (!target.startsWith(base) || target.equals(base)) {
			NpcStudio.LOGGER.error("Refusing to remove {}: it is not inside {}", target, base);
			return;
		}
		try (var walk = Files.walk(target)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException stubborn) {
					NpcStudio.LOGGER.warn("Could not remove {}: {}", path, stubborn.getMessage());
				}
			});
		} catch (IOException failed) {
			NpcStudio.LOGGER.warn("Could not clean up {}: {}", target, failed.getMessage());
		}
	}
}
