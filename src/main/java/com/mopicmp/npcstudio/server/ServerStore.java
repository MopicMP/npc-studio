package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mopicmp.npcstudio.NpcStudio;

/**
 * The list of servers, in a file.
 *
 * Kept apart from {@code NpcStudioConfig} on purpose. That file is preferences —
 * losing it costs somebody their panel arrangement. This one is the only record
 * of where a server's files are and what its command password is: lose it and a
 * running server becomes unreachable from here, still running.
 *
 * The path is asked for rather than looked up in a static field, so that this
 * class can be exercised by tests on a temporary folder without a game.
 */
public final class ServerStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Names Windows will not let a folder have, whatever the folder is for. */
	private static final java.util.Set<String> RESERVED = java.util.Set.of(
		"con", "prn", "aux", "nul",
		"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
		"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	/** What is actually written: a wrapper, so the file can grow fields later. */
	private static final class Saved {
		List<ManagedServer> servers = new ArrayList<>();
	}

	private final Path file;
	private final List<ManagedServer> servers = new ArrayList<>();
	private boolean unreadable;

	public ServerStore(Path file) {
		this.file = file;
		load();
	}

	/** The file the running game uses. */
	public static ServerStore forGame() {
		Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
		return new ServerStore(config.resolve("npc_studio_servers.json"));
	}

	/**
	 * Where a server created by us puts its files.
	 *
	 * Beside the game rather than inside it: a world folder is something people
	 * copy, and a server folder inside {@code saves} would be copied with it.
	 */
	public static Path defaultDirectory() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
			.resolve("npc-studio-servers");
	}

	private void load() {
		servers.clear();
		if (!Files.exists(file)) return;
		try {
			Saved read = GSON.fromJson(Files.readString(file), Saved.class);
			if (read != null && read.servers != null) {
				for (ManagedServer server : read.servers) {
					if (server != null && server.id != null && !server.id.isBlank()) {
						servers.add(server);
					}
				}
			}
		} catch (Exception broken) {
			// A parse error must not turn into a deletion. Preferences can be
			// rebuilt from defaults; this file cannot — it is the only record of a
			// running server's folder and password. So the list stays empty, saving
			// is refused, and somebody gets to look at their file with it still
			// intact.
			unreadable = true;
			NpcStudio.LOGGER.error("Could not read the server list at {}: {}",
				file, broken.toString());
		}
	}

	/** Whether the file on disk could not be understood, and so must not be written over. */
	public boolean isUnreadable() {
		return unreadable;
	}

	public List<ManagedServer> all() {
		return List.copyOf(servers);
	}

	public Optional<ManagedServer> byId(String id) {
		return servers.stream().filter(server -> server.id.equals(id)).findFirst();
	}

	public void add(ManagedServer server) {
		if (byId(server.id).isPresent()) {
			throw new IllegalArgumentException("A server called '" + server.id + "' is already listed");
		}
		servers.add(server);
	}

	public void remove(String id) {
		servers.removeIf(server -> server.id.equals(id));
	}

	/**
	 * A folder name from what somebody typed.
	 *
	 * The name is a person's, the id is a path component, and the gap between
	 * those two is where directory traversal lives — see the rule in
	 * {@code docs/security.md}. Nothing here is trusted to be safe: what comes
	 * out is built from an allowed set of characters rather than filtered for
	 * disallowed ones.
	 */
	public String idFor(String name) {
		StringBuilder id = new StringBuilder();
		for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
				id.append(c);
			} else if (!id.isEmpty() && id.charAt(id.length() - 1) != '-') {
				id.append('-');
			}
		}
		while (!id.isEmpty() && id.charAt(id.length() - 1) == '-') {
			id.deleteCharAt(id.length() - 1);
		}
		String base = id.isEmpty() ? "server" : id.toString();
		// Windows keeps a handful of names for devices, and a folder cannot have
		// one. A server called "Con" or "Aux" would otherwise fail to be created
		// with an error about the path being invalid, which says nothing about the
		// name being the cause.
		if (RESERVED.contains(base)) base = base + "-server";
		if (byId(base).isEmpty()) return base;
		for (int suffix = 2; ; suffix++) {
			String tried = base + "-" + suffix;
			if (byId(tried).isEmpty()) return tried;
		}
	}

	public void save() {
		if (unreadable) {
			NpcStudio.LOGGER.error("Refusing to overwrite the unreadable server list at {}", file);
			return;
		}
		Saved out = new Saved();
		out.servers = new ArrayList<>(servers);
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			// Written beside and moved into place: a crash halfway through a write
			// would otherwise leave a truncated list, and this file is the only
			// record of where a running server lives.
			Path temporary = file.resolveSibling(file.getFileName() + ".new");
			Files.writeString(temporary, GSON.toJson(out));
			try {
				Files.move(temporary, file,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
				Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException failed) {
			NpcStudio.LOGGER.error("Could not write the server list to {}: {}",
				file, failed.getMessage());
		}
	}
}
