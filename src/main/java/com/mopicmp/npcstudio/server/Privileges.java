package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * What a server can give somebody, and which thing on it decides that.
 *
 * <b>Two backends, not a framework.</b> A plain server has four operator levels
 * and nothing else — no names, no groups, nothing anybody can add to. A server
 * with a permission plugin has named ranks with weights, prefixes and
 * inheritance, and the plugin, not this window, is what enforces them.
 *
 * <p>Which plugin: measured rather than remembered. Among the plugins on Modrinth
 * LuckPerms has 2.6 million downloads and the next thing that actually manages
 * permissions has about eleven hundred — a factor of two thousand. So there is
 * one backend worth writing and an interface shaped so a second is a class rather
 * than a rewrite. What is deliberately <em>not</em> here is a permission system of
 * this window's own: names it invented would be names no server obeys.
 */
public final class Privileges {

	private Privileges() {
	}

	/** Where the answer about this server comes from. */
	public enum Kind {

		/** {@code ops.json} and the four levels, which every server has. */
		OPERATORS,

		/** Named groups, kept by LuckPerms. */
		LUCK_PERMS
	}

	/**
	 * The file {@code lp export} is asked to write.
	 *
	 * One fixed name rather than the dated one LuckPerms picks itself, because
	 * this file is read once and deleted: leaving a trail of dated backups in
	 * somebody's plugin folder every time a tab is opened is littering in a
	 * folder that is not ours. LuckPerms refuses to write over a file that is
	 * already there, so the old one goes before the new one is asked for.
	 */
	public static final String EXPORT = "npc-studio-view";

	/**
	 * Where LuckPerms keeps its things, which is not the same place on every core.
	 *
	 * Under {@code plugins} on Bukkit and its descendants, and beside the world on
	 * Fabric, Sponge and Velocity — the plugin is the same plugin and the folder
	 * moves with the platform. Both are looked for rather than one assumed,
	 * because looking in the wrong place gives an empty tab and no error at all.
	 */
	public static Path folder(ManagedServer server) {
		Path root = server.path();
		Path under = root.resolve("plugins").resolve("LuckPerms");
		if (Files.isDirectory(under)) return under;
		Path beside = root.resolve("LuckPerms");
		if (Files.isDirectory(beside)) return beside;
		return null;
	}

	/**
	 * Whether this server has the plugin at all.
	 *
	 * The folder is what is looked for rather than the jar, and on purpose: the
	 * folder only exists once the plugin has run, and a plugin that has been
	 * dropped in but never started has no groups to show and no command channel
	 * to ask. A jar in a folder is a plan; a data folder is a server that has
	 * actually loaded it.
	 */
	public static Kind kindOf(ManagedServer server) {
		return folder(server) == null ? Kind.OPERATORS : Kind.LUCK_PERMS;
	}

	/** The file the export lands in, or null when there is no plugin folder. */
	public static Path exportFile(ManagedServer server) {
		Path folder = folder(server);
		return folder == null ? null : folder.resolve(EXPORT + ".json.gz");
	}

	/**
	 * Take the export away once it has been read.
	 *
	 * Called before asking for a new one as well as after reading it: LuckPerms
	 * will not write over a file that exists, and an export left behind by a crash
	 * would otherwise make every later refresh fail with "file already exists".
	 */
	public static void forgetExport(ManagedServer server) {
		Path file = exportFile(server);
		if (file == null) return;
		try {
			Files.deleteIfExists(file);
		} catch (IOException stays) {
			// Left behind. The next export says so plainly, which is better than
			// this quietly deleting something it turns out it could not.
		}
	}

	public static LuckPermsExport.Data read(ManagedServer server) throws IOException {
		Path file = exportFile(server);
		if (file == null || !Files.isRegularFile(file)) return LuckPermsExport.Data.empty();
		return LuckPermsExport.read(file);
	}

	/**
	 * How long to wait for the file, and how often to look at it.
	 *
	 * The export runs on a thread of the plugin's own and finishes when it
	 * finishes; against a live server with four groups it took well under a
	 * second, and the time is proportional to how many people have ever been
	 * given anything. Ten seconds is long enough not to give up on a big server
	 * and short enough that a plugin which is not going to answer says so while
	 * somebody is still looking at the screen.
	 */
	public static final long WAIT_MS = 10_000;
	private static final long LOOK_MS = 150;

	/**
	 * Ask a running server for everything LuckPerms has, and read it.
	 *
	 * <b>Here rather than in the window</b>, because this is the whole mechanism
	 * the rights tab stands on and it can be run against a real server without a
	 * game: see {@code PermissionsRun}. A copy of this living in the client would
	 * be a copy nothing could check.
	 *
	 * <p>Delete, ask, wait, read. Delete first because LuckPerms refuses to write
	 * over a file that exists — so one left behind by a crash would otherwise
	 * break every later refresh with a complaint about a file nobody remembers.
	 * Wait, because the command returns before the export is written and
	 * <b>LuckPerms answers nothing at all over this channel</b>, on success or on
	 * failure. Read with a retry, because a size that has stopped changing is a
	 * good guess about a finished archive and not a promise.
	 */
	public static LuckPermsExport.Data pull(ManagedServer server) throws IOException {
		Path file = exportFile(server);
		if (file == null) return LuckPermsExport.Data.empty();
		forgetExport(server);
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			rcon.command("lp export " + EXPORT);
		}

		long until = System.currentTimeMillis() + WAIT_MS;
		long size = -1;
		while (System.currentTimeMillis() < until) {
			if (Files.isRegularFile(file)) {
				long now = Files.size(file);
				if (now > 0 && now == size) {
					try {
						LuckPermsExport.Data data = LuckPermsExport.read(file);
						forgetExport(server);
						return data;
					} catch (IOException halfWritten) {
						size = -1;
					}
				} else {
					size = now;
				}
			}
			try {
				Thread.sleep(LOOK_MS);
			} catch (InterruptedException stopped) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new IOException("LuckPerms did not write " + file.getFileName()
			+ " within " + WAIT_MS / 1000 + " seconds.");
	}

	/** Send changes and then read what the server exported afterwards. */
	public static LuckPermsExport.Data push(ManagedServer server, java.util.List<String> commands)
			throws IOException {
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			for (String command : commands) {
				rcon.command(command);
			}
		}
		return pull(server);
	}

	// ------------------------------------------------------------------ operators

	/**
	 * The four levels, which are the whole of permissions without a plugin.
	 *
	 * The numbers are the server's; the descriptions beside them in the window are
	 * this window's own reading of what each one lets somebody do, and are labelled
	 * as such. They are not in a file anywhere and nothing can be measured about
	 * them — they are what the game's own command tree says, written down so that
	 * "level 3" is not a number somebody has to go and look up.
	 */
	public static final List<Integer> LEVELS = List.of(1, 2, 3, 4);

	/**
	 * Whether a name may be given to a group.
	 *
	 * LuckPerms lower-cases group names and uses them in command arguments, so a
	 * name with a space in it is a command that means something else. Checked here
	 * rather than after the command has been sent, because a refusal that arrives
	 * as a line of somebody else's chat formatting is a refusal nobody reads.
	 */
	public static boolean validGroup(String name) {
		return name != null && name.matches("[A-Za-z0-9_.-]{1,36}");
	}

	/** The name as LuckPerms will store it, which is the one to send commands with. */
	public static String tidy(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Whether a permission node is one this window will send.
	 *
	 * Deliberately loose — a node is whatever plugin invented it, and the shapes
	 * range from {@code essentials.tp} to {@code worldguard.region.bypass.*} — but
	 * not so loose that a space gets through, because a space turns one command
	 * argument into two and the second one lands where the value goes.
	 */
	public static boolean validNode(String node) {
		return node != null && !node.isBlank() && node.length() <= 200
			&& !node.contains(" ") && !node.contains("\n");
	}
}
