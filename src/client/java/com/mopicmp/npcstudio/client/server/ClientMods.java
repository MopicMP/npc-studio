package com.mopicmp.npcstudio.client.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * The mods in this game, offered to the server beside it.
 *
 * Somebody who has a modded client and makes a server almost always wants the
 * same mods on it: that is what "let us play with these" means. Doing it by hand
 * is opening two folders and remembering which of a dozen jars is which — and
 * getting it wrong in the direction that stops the server starting, because the
 * two folders look identical and half of what is in the client's cannot run on a
 * server at all.
 *
 * So the list is filtered before it is shown, and everything left is a mod that
 * will work there:
 *
 * <ul>
 * <li>anything the loader provides — the game, the loader, Java itself — is not
 * a file anybody installs;</li>
 * <li>anything marked client-only is left out, for the reason above;</li>
 * <li>anything not sitting in a jar of its own cannot be copied;</li>
 * <li>anything that came inside another mod is that mod, and anything that says
 * it is a module of an API bundle is that bundle — Fabric API is one file holding
 * ninety modules in a real game and ninety files on a development classpath, and
 * either way listing the modules is listing one thing ninety times;</li>
 * <li>in a real game, anything outside the mods folder was not installed by
 * anybody and cannot be copied anywhere;</li>
 * <li>and this mod is left in, deliberately: a server running it is the point of
 * the rest of this window.</li>
 * </ul>
 */
public final class ClientMods {

	private ClientMods() {
	}

	/** One mod of this client that could be copied to a server. */
	public record Mod(String id, String name, String version, Path jar) {
		public String file() {
			return jar.getFileName().toString();
		}
	}

	/** Ids that belong to the loader rather than to a file somebody added. */
	private static boolean provided(String id) {
		return id.equals("minecraft") || id.equals("java") || id.equals("fabricloader")
			|| id.equals("fabric-loader") || id.startsWith("fabric-api-base");
	}

	/**
	 * Worked out once and kept.
	 *
	 * The list of mods a running game has cannot change while it runs, and this
	 * asks the disk about every one of them — which the first version did from
	 * the drawing code, sixty times a second.
	 */
	private static List<Mod> found;

	private static boolean development() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	private static boolean inModsFolder(Path jar) {
		try {
			Path mods = FabricLoader.getInstance().getGameDir().resolve("mods")
				.toAbsolutePath().normalize();
			return jar.toAbsolutePath().normalize().startsWith(mods);
		} catch (RuntimeException unreadable) {
			// A path that will not normalise is not a reason to show nothing.
			return true;
		}
	}

	public static List<Mod> all() {
		if (found != null) return found;
		// The mods folder is the answer wherever there is one. Falling back to the
		// classpath happens only in a development run <em>with an empty folder</em>
		// — which is what listing MixinExtras was: a library the build put on the
		// classpath, never installed by anybody, sitting next to a mod that really
		// was in run/mods. Now that folder is asked first and answers.
		List<Mod> installed = gather(true);
		if (!installed.isEmpty() || !development()) {
			found = installed;
			return found;
		}
		found = gather(false);
		return found;
	}

	private static List<Mod> gather(boolean onlyInstalled) {
		List<Mod> mods = new ArrayList<>();
		java.util.Set<Path> seen = new java.util.HashSet<>();
		for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
			var meta = container.getMetadata();
			if (provided(meta.getId())) continue;
			if (meta.getEnvironment() == ModEnvironment.CLIENT) continue;

			// Anything that arrived inside another mod is that mod, not a file of
			// its own. True of every jar-in-jar library in an installed game.
			if (container.getContainingMod().isPresent()) continue;

			// And the same thing again, because in a development run it is not the
			// same thing at all. This was fixed once by the line above and came
			// back unchanged, which is worth writing down: Fabric API is one jar of
			// some ninety modules <em>in an installed game</em>, and the loader
			// reports them as nested. Under Gradle every module is a separate
			// dependency on the classpath — a real file, no parent, nothing to
			// distinguish it by origin. So the list of ninety was correct and
			// useless.
			//
			// What does tell them apart is that they say so themselves: every
			// module of the API carries this key in its metadata. It names one
			// library rather than a category, which is a thing to be uneasy about
			// — but the alternative is guessing from names beginning with "fabric-"
			// and that is worse, because it would also hide somebody's own mod.
			if (meta.containsCustomValue("fabric-api:module-lifecycle")) continue;

			// And in a development run the thing they add up to is not a file
			// either: the "fabric-api" jar there holds no code, only a list of the
			// ninety it depends on. Copying it to a server would install a jar that
			// asks for ninety mods which are not there, and the server would not
			// start. In an installed game the same name is one real fat jar, so
			// this is a rule about the run and not about the mod.
			if (development() && meta.getId().equals("fabric-api")) continue;

			// The kind is asked next, and this is not caution: a nested mod throws
			// when asked where its files are, because it has none of its own. That
			// threw while a screen was being drawn, which takes the game down.
			if (container.getOrigin().getKind() != ModOrigin.Kind.PATH) continue;
			List<Path> paths = container.getOrigin().getPaths();
			if (paths.size() != 1) continue;
			Path jar = paths.getFirst();
			if (!Files.isRegularFile(jar)
				|| !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
				continue;
			}
			// Only what is in the mods folder. That is what "the mods of this
			// client" means to the person reading it — a file they put there, and
			// also the only kind that can be copied anywhere.
			if (onlyInstalled && !inModsFolder(jar)) continue;

			// And one row per file whatever else happens: two mods that share a jar
			// are one thing to copy across, and offering it twice would copy it
			// twice.
			if (!seen.add(jar.toAbsolutePath().normalize())) continue;
			mods.add(new Mod(meta.getId(), meta.getName(), meta.getVersion().getFriendlyString(),
				jar));
		}
		mods.sort(java.util.Comparator.comparing(mod -> mod.name().toLowerCase(Locale.ROOT)));
		found = List.copyOf(mods);
		return found;
	}
}
