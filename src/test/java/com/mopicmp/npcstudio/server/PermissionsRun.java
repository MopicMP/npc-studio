package com.mopicmp.npcstudio.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the rights tab's mechanism against a server that is actually up.
 *
 * The one thing {@link LuckPermsExportTest} cannot do. That test proves the
 * reader agrees with a file LuckPerms wrote; this proves the sequence around it —
 * delete the last export, send {@code lp export}, wait for a file that is written
 * by somebody else's thread, unpack it — works against the real plugin rather
 * than against our idea of it.
 *
 * <p>Two things this found that reading LuckPerms' source did not:
 *
 * <ul>
 * <li>a track in the export is a name against an <em>object</em> holding a
 * {@code groups} array, not a name against an array;
 * <li>LuckPerms replies <b>nothing</b> over the command channel — not on success,
 * not on failure, not for a command naming a group that does not exist. Every
 * design decision on that tab follows from this.
 * </ul>
 *
 * <pre>
 * gradlew checkPermissions -Pat=&lt;server folder&gt; -Pport=&lt;rcon port&gt; -Ppass=&lt;rcon password&gt;
 * </pre>
 *
 * <p>Points at a server that is already running; starting one is
 * {@link ServerRun}'s job. It writes into that server's LuckPerms folder — an
 * export file, which it deletes — and it makes and removes one group called
 * {@code npc-studio-check}. Nothing else on the server is touched.
 */
public final class PermissionsRun {

	private static final String PROBE = "npc-studio-check";

	public static void main(String[] arguments) throws Exception {
		String at = System.getProperty("at", "");
		if (at.isBlank()) {
			System.out.println("Point it at a running server:");
			System.out.println("  gradlew checkPermissions -Pat=<folder> -Pport=<rcon port>"
				+ " -Ppass=<rcon password>");
			System.exit(1);
		}

		ManagedServer server = new ManagedServer();
		server.id = "permissions-check";
		server.core = "paper";
		server.directory = at;
		server.rconPort = Integer.parseInt(System.getProperty("port", "25575"));
		server.rconPassword = System.getProperty("pass", "");

		System.out.println("Server folder: " + server.path());
		System.out.println("Backend:       " + Privileges.kindOf(server));
		Path folder = Privileges.folder(server);
		if (folder == null) {
			System.out.println("No LuckPerms folder here, so there is nothing to ask.");
			System.exit(1);
		}
		System.out.println("Plugin folder: " + folder);

		System.out.println();
		System.out.println("Asking for an export");
		long began = System.currentTimeMillis();
		LuckPermsExport.Data data = Privileges.pull(server);
		System.out.println("  came back in " + (System.currentTimeMillis() - began) + " ms");
		show(data);

		// The export must be gone again: this window is a guest in that folder.
		Path left = Privileges.exportFile(server);
		System.out.println();
		System.out.println("Export cleaned up: " + !Files.exists(left));

		System.out.println();
		System.out.println("Making a group called " + PROBE + " and giving it a weight");
		data = Privileges.push(server, List.of(
			"lp creategroup " + PROBE,
			"lp group " + PROBE + " setweight 3",
			"lp group " + PROBE + " permission set npcstudio.check true"));
		LuckPermsExport.Group made = data.group(PROBE);
		System.out.println("  in the export afterwards: " + (made != null));
		if (made != null) {
			System.out.println("  weight " + made.weight() + ", permissions "
				+ made.permissions());
		}

		System.out.println();
		System.out.println("Taking it away again");
		data = Privileges.push(server, List.of("lp deletegroup " + PROBE));
		System.out.println("  gone: " + (data.group(PROBE) == null));

		System.out.println();
		System.out.println("Asking for a group that does not exist, to see what comes back");
		data = Privileges.push(server, List.of("lp group nothing-of-the-sort setweight 5"));
		System.out.println("  the command channel said nothing, as always;"
			+ " groups are still " + data.groups().size());
	}

	private static void show(LuckPermsExport.Data data) {
		System.out.println("  groups (" + data.groups().size() + "):");
		for (LuckPermsExport.Group group : data.groups()) {
			System.out.println("    " + group.name() + "  weight " + group.weight()
				+ (group.display().isBlank() ? "" : "  shown as " + group.display())
				+ (group.prefix().isBlank() ? "" : "  prefix " + group.prefix())
				+ (group.parents().isEmpty() ? "" : "  inherits " + group.parents()));
			for (LuckPermsExport.Permission permission : group.permissions()) {
				System.out.println("        " + (permission.granted() ? "+ " : "- ")
					+ permission.node()
					+ (permission.temporary() ? "  until " + permission.until() : ""));
			}
		}
		System.out.println("  ladders (" + data.tracks().size() + "):");
		for (LuckPermsExport.Track track : data.tracks()) {
			System.out.println("    " + track.name() + ": " + String.join(" -> ", track.groups()));
		}
		System.out.println("  people (" + data.members().size() + "):");
		for (LuckPermsExport.Member member : data.members()) {
			System.out.println("    " + member.name() + "  " + member.groups()
				+ "  primary " + member.primary());
		}
	}
}
