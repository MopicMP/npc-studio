package com.mopicmp.npcstudio.client.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * The game's own noise, turned off while a scene is being worked on.
 *
 * <h2>Why this is needed at all</h2>
 *
 * Because a scene is scored against what you can hear, and what you can hear in
 * an ordinary world is water, wind, mobs, a jukebox somewhere and the game's own
 * background music starting on its own schedule. Setting a piece of music to a
 * shot under all of that is not difficult, it is guesswork.
 *
 * <h2>Why the volume options are not touched</h2>
 *
 * They belong to the person, not to us, and a mod that writes into somebody's
 * settings to get a quiet moment is a mod that leaves them silent after a crash.
 * The volume is intercepted on its way to the sound engine instead — see
 * {@code SoundHushMixin} — so nothing is saved and nothing has to be put back:
 * close the workspace and the world is exactly as loud as it was.
 *
 * <h2>Why the scene's own music is unaffected</h2>
 *
 * Because it never goes through the sound engine. A file in a folder is opened,
 * decoded and played on a channel taken straight from the device — see
 * {@link Music}, where that is done to avoid needing a resource pack — so the one
 * thing being silenced here is everything <em>except</em> the scene.
 */
public final class Hush {

	/**
	 * On by default, and that is a choice rather than an oversight.
	 *
	 * Somebody opening the workspace has opened a place for making a scene, and the
	 * first thing they do in it is listen to their own music against their own
	 * animation. The switch is in the music panel for the times it is wrong.
	 */
	private static boolean wanted = true;

	private static boolean applied;

	private Hush() { }

	public static boolean wanted() {
		return wanted;
	}

	public static void wanted(boolean on) {
		if (wanted == on) return;
		wanted = on;
		freshen();
	}

	/** Whether the game's own sounds are being held quiet at this moment. */
	public static boolean hushing() {
		return wanted && com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded();
	}

	/**
	 * Notices the workspace opening or closing, and tells the sounds already going.
	 *
	 * The interception below only decides the volume of a sound at the moment it is
	 * worked out, so a river that was already running keeps running at the volume it
	 * started with. Asking each category to refresh makes every sound in flight
	 * recompute — which is the same call the volume sliders in the options make, and
	 * for the same reason.
	 */
	public static void tick() {
		if (applied == hushing()) return;
		applied = hushing();
		freshen();
	}

	private static void freshen() {
		Minecraft client = Minecraft.getInstance();
		if (client.getSoundManager() == null) return;
		for (SoundSource source : SoundSource.values()) {
			client.getSoundManager().refreshCategoryVolume(source);
		}
	}
}
