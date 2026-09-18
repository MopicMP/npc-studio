package com.mopicmp.npcstudio.server;

/**
 * One thing added to a server: a plugin, a mod, whatever the core calls it.
 *
 * The distinction between the two words belongs to the core and not to the
 * person — Paper says plugins, Fabric says mods, hybrids say both — so they are
 * one kind of thing here with a note about which shelf it sits on.
 *
 * @param file        the jar's own file name, which is what identifies it on disk
 * @param id          the identifier its own descriptor gives it, or the file name
 * @param name        what it calls itself
 * @param version     its version, or blank when it does not say
 * @param kind        which folder it lives in
 * @param side        where it is meant to run — the field that matters most
 * @param needs       what it says it cannot run without
 * @param described   whether a descriptor was found at all, or this is a guess
 */
public record Addon(String file, String id, String name, String version,
		Kind kind, Side side, java.util.List<String> needs, boolean described) {

	public enum Kind {
		/** In {@code plugins/}: Bukkit, Spigot, Paper. */
		PLUGIN,
		/** In {@code mods/}: Fabric, Quilt, Forge, NeoForge. */
		MOD,
		/**
		 * In a world's {@code datapacks/}, and therefore not really one of these.
		 *
		 * It belongs to a world rather than to the server, it is a folder or a zip
		 * rather than a jar, and whether it is switched on is kept in the world's
		 * own data. It is in this enum because it shares one list with the others
		 * and people think of it as a third thing you install.
		 */
		DATAPACK
	}

	/**
	 * Where a thing is meant to run.
	 *
	 * The whole reason this is read at all. A mod written for the client does
	 * nothing useful on a server and often stops it starting — and the mistake is
	 * easy to make, because the two are the same kind of file with the same name
	 * on the same download page.
	 */
	public enum Side {
		BOTH,
		SERVER_ONLY,
		CLIENT_ONLY,
		/** Nothing said. Plugins never say; they are always server-side. */
		UNSAID
	}

	public String label() {
		return name.isBlank() ? file : name;
	}

	/** Whether putting this on a server is a mistake we can see in advance. */
	public boolean wrongSide() {
		return side == Side.CLIENT_ONLY;
	}
}
