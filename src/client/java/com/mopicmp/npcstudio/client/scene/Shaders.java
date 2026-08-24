package com.mopicmp.npcstudio.client.scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mopicmp.npcstudio.NpcStudio;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Which shader pack the scene is being looked at through.
 *
 * <h2>What is certain here, and what is not</h2>
 *
 * This mod writes no shaders. Everything below is about asking the mod that does
 * — Iris, or Oculus on the other loader — to put a different pack on, so that a
 * shot can be judged under the lighting it will be filmed under without leaving
 * the workspace.
 *
 * Two of the three parts are solid ground. Whether Iris is installed is a
 * question the loader answers. What packs there are is a question the
 * {@code shaderpacks} folder answers, and that folder is a convention Iris has
 * kept since it was OptiFine's.
 *
 * The third part — actually putting a pack on — is where this was wrong, and it
 * was wrong in the way an unchecked guess always is. It called
 * {@code setShaderPackName} on the object Iris hands out as its configuration,
 * on no evidence beyond the name being the obvious one. The panel listed the
 * packs, the click did nothing, and the log filled up with
 * {@code NoSuchMethodException: IrisApiV0ConfigImpl.setShaderPackName}.
 *
 * <h2>What the API actually is</h2>
 *
 * Read out of {@code iris-fabric-1.11.2+mc26.2.jar} rather than assumed. The
 * whole of the public {@code IrisApiConfig} is two methods:
 *
 * <pre>
 *   boolean areShadersEnabled();
 *   void    setShadersEnabledAndApply(boolean);
 * </pre>
 *
 * Neither of them chooses a pack, and there is no third one. Which pack is on
 * lives in {@code Iris.getIrisConfig()} — an internal class, not part of any
 * published contract — and Iris's own selection screen does exactly this:
 *
 * <pre>
 *   Iris.clearShaderPackOptionQueue();
 *   Iris.getIrisConfig().setShaderPackName(name);
 *   IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true);
 * </pre>
 *
 * That last call is what applies it: it saves the config and calls
 * {@code Iris.reload()} unconditionally, so it reloads even when shaders were
 * already on and only the name has changed.
 *
 * <h2>Reaching into an internal class, and saying so</h2>
 *
 * Two of the three calls above are internal. There is no version of this that is
 * not: the published API can turn shaders on and off and nothing else, so a mod
 * that wants to <em>choose</em> a pack either goes where Iris's own screen goes
 * or does not have the feature. What can be done is to fail like a probe rather
 * than like a guess — every step checked, and what was missing written on the
 * panel in words somebody can read off the screen instead of out of a log.
 *
 * <h2>Why reflection rather than a compile-time dependency</h2>
 *
 * Because Iris is optional and will be missing for most people, and a workspace
 * that will not start without a shader mod would be a poor trade for a tab.
 */
public final class Shaders {

	/** What was found when this was last asked. */
	public enum State {
		/** No shader mod is installed. Nothing to switch between. */
		ABSENT,
		/** Iris is here, but its API was not where it was expected. */
		UNREACHABLE,
		/** Iris is here and answering. */
		READY
	}

	/** The name Iris goes by on each loader. */
	private static final String[] MODS = { "iris", "oculus" };

	/** Where packs live. A convention rather than an API, and an old and stable one. */
	private static final String FOLDER = "shaderpacks";

	/** What the panel says it found. Never empty once anything has been asked. */
	private static String detail = "";

	private Shaders() { }

	public static boolean installed() {
		for (String mod : MODS) {
			if (FabricLoader.getInstance().isModLoaded(mod)) return true;
		}
		return false;
	}

	public static State state() {
		if (!installed()) {
			detail = "Iris не установлен";
			return State.ABSENT;
		}
		// Both halves, because the feature needs both: the published API to apply a
		// change and the internal config to say which pack. Ready on one of them is
		// the state this was in — a list you could click and nothing behind it.
		return api() == null || irisConfig() == null ? State.UNREACHABLE : State.READY;
	}

	public static String detail() {
		return detail;
	}

	/**
	 * The packs on disk, newest name order, with nothing at the front.
	 *
	 * A folder or a zip, which is what Iris accepts. Read here rather than asked of
	 * Iris because it is the one part of this that needs no API at all, and because
	 * a list that shows up before the API question is settled is a list that says
	 * something useful even when the answer is no.
	 */
	public static List<String> packs() {
		List<String> found = new ArrayList<>();
		found.add("");
		Path folder = folder();
		if (!Files.isDirectory(folder)) return found;

		try (var listing = Files.list(folder)) {
			listing.forEach(path -> {
				String name = path.getFileName().toString();
				if (Files.isDirectory(path) || name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
					found.add(name);
				}
			});
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.warn("Could not list the shader packs: {}", unreadable.toString());
		}
		found.subList(1, found.size()).sort(String::compareToIgnoreCase);
		return found;
	}

	// ------------------------------------------------------------- through Iris

	/**
	 * Iris's published configuration, or null with a reason written down.
	 *
	 * Two methods live on this and both are about the switch, not the pack:
	 * {@code areShadersEnabled} and {@code setShadersEnabledAndApply}. The second
	 * is what actually applies anything — it saves and reloads unconditionally —
	 * so every change made below ends with a call to it.
	 *
	 * Looked up afresh rather than held. It is asked once when a panel is drawn,
	 * which is not often enough for the lookup to cost anything, and holding it
	 * would mean holding a stale answer across a resource reload.
	 */
	private static Object api() {
		try {
			Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = irisApi.getMethod("getInstance").invoke(null);
			Object config = irisApi.getMethod("getConfig").invoke(instance);
			if (config == null) {
				detail = "Iris есть, но конфигурация не отдалась";
				return null;
			}
			detail = "Iris отвечает";
			return config;
		} catch (ClassNotFoundException missing) {
			detail = "Iris есть, но класс " + missing.getMessage() + " не найден";
		} catch (NoSuchMethodException missing) {
			detail = "Iris есть, но метод не найден: " + missing.getMessage();
		} catch (ReflectiveOperationException | RuntimeException failed) {
			detail = "Iris есть, но не отвечает: " + failed;
		}
		return null;
	}

	/**
	 * Iris's internal config, which is the only thing that knows about packs.
	 *
	 * Not part of any published contract, and reached anyway because the published
	 * one cannot name a pack at all. Held to the same rule as everything else here:
	 * checked at each step, and what was missing said in words.
	 */
	private static Object irisConfig() {
		try {
			return iris().getMethod("getIrisConfig").invoke(null);
		} catch (ClassNotFoundException missing) {
			detail = "Iris есть, но его внутренний класс не найден: " + missing.getMessage();
		} catch (NoSuchMethodException missing) {
			detail = "Iris есть, но getIrisConfig не найден: " + missing.getMessage();
		} catch (ReflectiveOperationException | RuntimeException failed) {
			detail = "Iris есть, но настройки не отдались: " + failed;
		}
		return null;
	}

	private static Class<?> iris() throws ClassNotFoundException {
		return Class.forName("net.irisshaders.iris.Iris");
	}

	/**
	 * Which pack is on, or empty for none.
	 *
	 * The name is asked of Iris and the switch is asked separately, because a pack
	 * stays named in the config after shaders are turned off. Reporting that name
	 * while the picture is plain would be the panel disagreeing with the screen,
	 * which is the fault this whole class exists to avoid.
	 */
	public static String current() {
		Object config = api();
		if (config == null) return "";
		try {
			if (!(Boolean) config.getClass().getMethod("areShadersEnabled").invoke(config)) {
				return "";
			}
			Object named = iris().getMethod("getCurrentPackName").invoke(null);
			return named == null ? "" : named.toString();
		} catch (ReflectiveOperationException | RuntimeException failed) {
			detail = "не удалось спросить текущий пак: " + failed;
			return "";
		}
	}

	/** Where Iris keeps its packs, asked of Iris and guessed only if it will not say. */
	private static Path folder() {
		try {
			Object where = iris().getMethod("getShaderpacksDirectory").invoke(null);
			if (where instanceof Path path) return path;
		} catch (ReflectiveOperationException | RuntimeException notThere) {
			// The convention below has outlived several versions of this method's name
			// and is right whenever it is asked; there is nothing to report.
		}
		return Minecraft.getInstance().gameDirectory.toPath().resolve(FOLDER);
	}

	/**
	 * Puts a pack on, or takes them all off when given nothing.
	 *
	 * The exact three steps Iris's own selection screen takes, in its order. The
	 * queue of pack options is cleared first because it belongs to whichever pack
	 * was on before and is meaningless to the next one; the name goes into the
	 * internal config; and the published call at the end is what saves and reloads.
	 *
	 * Turning them off is only that last call, with false. Leaving the name in
	 * place is deliberate and is Iris's own behaviour — it is what makes turning
	 * them back on return to the pack you had.
	 */
	public static boolean use(String pack) {
		Object config = api();
		if (config == null) return false;
		boolean wanted = pack != null && !pack.isEmpty();
		try {
			if (wanted) {
				Object internal = irisConfig();
				if (internal == null) return false;
				iris().getMethod("clearShaderPackOptionQueue").invoke(null);
				internal.getClass().getMethod("setShaderPackName", String.class)
					.invoke(internal, pack);
			}
			config.getClass().getMethod("setShadersEnabledAndApply", boolean.class)
				.invoke(config, wanted);
			detail = wanted ? said(pack) : "шейдеры выключены";
			return true;
		} catch (ReflectiveOperationException | RuntimeException failed) {
			detail = "не удалось переключить: " + failed;
			NpcStudio.LOGGER.warn("Could not switch the shader pack: {}", failed.toString());
			return false;
		}
	}

	/**
	 * What to say after applying, which is not always "done".
	 *
	 * Iris does not throw when a pack will not compile — it logs, falls back to no
	 * shaders and carries on, which from outside looks exactly like a click that
	 * did nothing. It keeps the reason, so the reason is what gets shown.
	 */
	private static String said(String pack) {
		try {
			Object stored = iris().getMethod("getStoredError").invoke(null);
			if (stored instanceof java.util.Optional<?> maybe && maybe.isPresent()) {
				return pack + " не загрузился: " + maybe.get();
			}
			Object fell = iris().getMethod("isFallback").invoke(null);
			if (Boolean.TRUE.equals(fell)) return pack + " не загрузился — смотри лог";
		} catch (ReflectiveOperationException | RuntimeException cannotTell) {
			// Then the plain answer below, which is still true: it was applied.
		}
		return "включён " + pack;
	}
}
