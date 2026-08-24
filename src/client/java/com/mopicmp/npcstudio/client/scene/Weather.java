package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.scene.Channels;
import com.mopicmp.npcstudio.scene.Key;
import com.mopicmp.npcstudio.scene.Role;
import com.mopicmp.npcstudio.scene.Scene;

/**
 * What the sky is doing, when a scene has an opinion about it.
 *
 * <h2>Why the scene owns the weather</h2>
 *
 * Because vanilla weather is a countdown and a scene is a repeat. Rain begins,
 * runs for some minutes and stops; the sun crosses the sky at its own pace. Both
 * are exactly right for a world being lived in and exactly wrong for a shot
 * being got right on the third take — the light was different each time and the
 * rain ended in the middle of one of them.
 *
 * So a scene says what the sky <em>is</em> rather than starting anything. Every
 * frame, for as long as the scene is open, the sun is put where the scene says
 * and the rain is set to what the scene says. There is no countdown left to run
 * out and nothing to drift.
 *
 * <h2>Held, not commanded</h2>
 *
 * None of this touches the world. No {@code /time set}, no {@code /weather} — the
 * numbers are pushed onto the render state on their way to the screen, and the
 * world underneath goes on with its own day. Which is the behaviour worth having:
 * close the scene and the sky is whatever it was, with nothing to put back, and
 * two people editing two scenes on one server do not fight over the sun.
 *
 * The cost is honest and worth writing down: it is a picture, not the weather.
 * Crops do not grow, mobs do not burn, and a second player standing next to you
 * sees their own sky unless they have the same scene open.
 */
public final class Weather {

	/** Nought is where the game puts the sun at time nought, which is noon. */
	private static final float NOON = 0f;

	private Weather() { }

	/** Whether the open scene says anything about the sky at all. */
	public static boolean showing() {
		return part() != null;
	}

	/**
	 * The part the sky is played by, or null.
	 *
	 * By kind rather than by name, so that a scene written before this existed and
	 * one written after are told apart by what is in them rather than by a version
	 * number.
	 */
	private static Role part() {
		Scene scene = Playing.scene();
		if (scene == null) return null;
		for (Role role : scene.cast()) {
			if (role.kind() == Role.Kind.WORLD) return role;
		}
		return null;
	}

	private static float value(String channel, float unsaid) {
		Scene scene = Playing.scene();
		Role role = part();
		if (scene == null || role == null) return unsaid;
		return scene.valueAt(role.name(), channel, Playing.head().at(), unsaid);
	}

	/** Whether the scene has anything to say about this number. */
	public static boolean says(String channel) {
		return !Float.isNaN(value(channel, Float.NaN));
	}

	/** Where the sun is along its arc, in degrees, or the number handed in. */
	public static float sun(float unsaid) {
		return value(Channels.SUN, unsaid);
	}

	/** Which way round the horizon that arc runs, in degrees. Nought is the east. */
	public static float sunTurn(float unsaid) {
		return value(Channels.SUN_TURN, unsaid);
	}

	/** How hard it is raining, nought to one. */
	public static float rain(float unsaid) {
		return clamp(value(Channels.RAIN, unsaid));
	}

	/** How much of a storm it is, nought to one. */
	public static float storm(float unsaid) {
		return clamp(value(Channels.STORM, unsaid));
	}

	/**
	 * How black the picture is drawn over, nought to one, at a moment.
	 *
	 * Asked with a time rather than reading the cursor, because this one is drawn
	 * every frame and the others are read once when a panel is painted. A fade
	 * stepping twenty times a second is not a fade — see {@code Titles}.
	 */
	public static float fadeAt(double when) {
		Scene scene = Playing.scene();
		Role role = part();
		if (scene == null || role == null) return 0;
		return clamp(scene.valueAt(role.name(), Channels.FADE, when, 0));
	}

	/**
	 * A colour the scene has set, or the one already there.
	 *
	 * Three channels in and one packed number out, because that is the shape the
	 * render state wants and the shape a colour picker gives. The fading happens
	 * in the three, where it works.
	 */
	public static int colour(String red, String green, String blue, int already) {
		if (!says(red) && !says(green) && !says(blue)) return already;
		int r = byteOf(value(red, (already >> 16) & 0xFF));
		int g = byteOf(value(green, (already >> 8) & 0xFF));
		int b = byteOf(value(blue, already & 0xFF));
		return (already & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	public static int sky(int already) {
		return colour(Channels.SKY_R, Channels.SKY_G, Channels.SKY_B, already);
	}

	public static int sunColour(int already) {
		return colour(Channels.SUN_R, Channels.SUN_G, Channels.SUN_B, already);
	}

	public static int rainColour(int already) {
		return colour(Channels.RAIN_R, Channels.RAIN_G, Channels.RAIN_B, already);
	}

	/**
	 * The sky's colour as a filter over the rest of the atmosphere.
	 *
	 * The dome overhead takes the colour outright; the fog and the haze take it as
	 * a shape only, so that they keep their own brightness and go on carrying the
	 * time of day. See {@link Filter} for why those two cannot be the same
	 * operation, and what it looked like when only one of them happened.
	 *
	 * White when the scene has said nothing, which multiplies to nothing at all.
	 */
	public static float[] skyFilter() {
		if (!showing()) return Filter.NONE;
		return Filter.of(colour(Channels.SKY_R, Channels.SKY_G, Channels.SKY_B, 0xFFFFFF));
	}

	/** The same for the sun, which the glow at the horizon belongs to. */
	public static float[] sunFilter() {
		if (!showing()) return Filter.NONE;
		return Filter.of(colour(Channels.SUN_R, Channels.SUN_G, Channels.SUN_B, 0xFFFFFF));
	}

	// ------------------------------------------------------------------- editing

	/** Puts the sky's part into a scene that has none, and hands back the scene. */
	public static Scene withWorld(Scene scene) {
		if (scene == null) return null;
		for (Role role : scene.cast()) {
			if (role.kind() == Role.Kind.WORLD) return scene;
		}
		return scene.with(Role.of(Channels.WORLD, Role.Kind.WORLD));
	}

	/** Keys one of the sky's numbers at the cursor. */
	public static void put(String channel, float value) {
		Scene scene = Playing.scene();
		if (scene == null) return;
		Scene changed = withWorld(scene);
		Scenes.keep(Playing.openName(), changed.keyed(Channels.WORLD, channel,
			Key.at(Playing.head().tick(), value)));
	}

	/** Takes one of them back out, so the world's own answer returns. */
	public static void clear(String channel) {
		Scene scene = Playing.scene();
		if (scene == null) return;
		Scenes.keep(Playing.openName(), scene.without(Channels.WORLD, channel));
	}

	/** What a channel reads as for the panel: the scene's answer, or the fallback. */
	public static float read(String channel, float unsaid) {
		return value(channel, unsaid);
	}

	private static float clamp(float value) {
		return Float.isNaN(value) ? 0 : Math.clamp(value, 0f, 1f);
	}

	private static int byteOf(float value) {
		return (int) Math.clamp(Math.round(value), 0, 255);
	}
}
