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

	/**
	 * Whether anything at all has an opinion about the sky: a scene, or the world.
	 *
	 * Every mixin that touches the sky asks this first and stands down when the
	 * answer is no, so it is the single switch between "the mod is colouring the
	 * sky" and "the game is". Both sources have to be in it — the hold was
	 * invisible for as long as this only knew about scenes.
	 */
	public static boolean showing() {
		return part() != null || !held.isEmpty();
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

	/**
	 * What the sky is set to outside any scene.
	 *
	 * <h2>Why the panel needed this to exist</h2>
	 *
	 * The lighting panel was unusable without a scene open — not awkward, unusable:
	 * every slider wrote a key, so with no timeline there was nowhere for a movement
	 * to go, and the panel gave up and drew six headings. Which is absurd for the
	 * one thing here that is not about a scene at all. The sky exists whether or not
	 * anybody is filming, and wanting it to be evening while building a set is the
	 * ordinary case rather than the exotic one.
	 *
	 * So there is a second place for these numbers to live, with the same reading
	 * path and the same held-not-commanded bargain as the scene's: no {@code /time
	 * set}, nothing counting down, the world underneath untouched. See {@link
	 * com.mopicmp.npcstudio.client.workspace.Landing} for which of the two an edit
	 * goes to and how that is decided.
	 *
	 * <h2>Kept for the session and not longer</h2>
	 *
	 * Dropped on leaving the world, like everything else here. Writing it to the
	 * config would mean somebody who made it midnight to light one shot finds the
	 * sky still wrong a week later with nothing on screen explaining why — and the
	 * way out of that would have to be found rather than remembered.
	 */
	private static final java.util.Map<String, Float> held = new java.util.HashMap<>();

	/** Dropped on leaving a world, along with the scenes. */
	public static void forget() {
		held.clear();
	}

	/**
	 * A scene's answer, then the world's, then the caller's.
	 *
	 * That order and not the other one: a scene is a statement about a shot and the
	 * world hold is a statement about the afternoon, so with a scene open its keys
	 * win. Where the scene says nothing the hold shows through, which is what makes
	 * it possible to set the sky up first and then film in it.
	 */
	private static float value(String channel, float unsaid) {
		Scene scene = Playing.scene();
		Role role = part();
		if (scene != null && role != null) {
			float keyed = scene.valueAt(role.name(), channel, Playing.head().at(), Float.NaN);
			if (!Float.isNaN(keyed)) return keyed;
		}
		Float world = held.get(channel);
		return world != null ? world : unsaid;
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
		if (scene != null && role != null) {
			float keyed = scene.valueAt(role.name(), Channels.FADE, when, Float.NaN);
			if (!Float.isNaN(keyed)) return clamp(keyed);
		}
		// The same fall-through as every other channel, written out because this one
		// is sampled between ticks and cannot go through value().
		Float world = held.get(Channels.FADE);
		return world == null ? 0 : clamp(world);
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

	/**
	 * Sets one of the sky's numbers, wherever edits are landing.
	 *
	 * A key on the cursor with a scene receiving, and the world's own hold
	 * otherwise. The panel does not choose between these and must not: which of
	 * them is in force is one answer for the whole workspace, so that every panel
	 * agrees about it and the indicator in the toolbar is telling the truth about
	 * all of them at once.
	 */
	public static void put(String channel, float value) {
		if (com.mopicmp.npcstudio.client.workspace.Landing.of(
				com.mopicmp.npcstudio.client.workspace.Property.WORLD)
					!= com.mopicmp.npcstudio.client.workspace.Landing.SCENE) {
			held.put(channel, value);
			return;
		}
		Scene scene = Playing.scene();
		if (scene == null) return;
		Scene changed = withWorld(scene);
		Scenes.keep(Playing.openName(), changed.keyed(Channels.WORLD, channel,
			Key.at(Playing.head().tick(), value)));
	}

	/**
	 * Takes one of them back out, so the answer underneath returns.
	 *
	 * Underneath, not "the world's own": clearing a scene's channel now uncovers
	 * the hold if there is one, and clearing the hold uncovers the game. Which is
	 * the same rule reading and writing, so the two cannot disagree.
	 */
	public static void clear(String channel) {
		if (com.mopicmp.npcstudio.client.workspace.Landing.of(
				com.mopicmp.npcstudio.client.workspace.Property.WORLD)
					!= com.mopicmp.npcstudio.client.workspace.Landing.SCENE) {
			held.remove(channel);
			return;
		}
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
