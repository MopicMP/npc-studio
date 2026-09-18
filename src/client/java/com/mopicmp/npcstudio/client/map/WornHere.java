package com.mopicmp.npcstudio.client.map;

import com.mopicmp.npcstudio.client.skin.CustomSkins;
import com.mopicmp.npcstudio.client.wardrobe.Costumes;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The skin this map puts on the people playing it.
 *
 * <h2>Why a map is allowed to do this at all</h2>
 *
 * Because on a built map the player is a character in somebody's story, not a
 * visitor wearing their own clothes. Asked for in those words: make the player use
 * this skin no matter what. It is the same thing a map already does to brightness
 * and to the resource pack, one step further in.
 *
 * It is also the reason this is deliberately not subtle. There is one skin, set in
 * one place, on all the time — no per-scene changes, no undressing halfway. A map
 * that could swap the player's face during a conversation would be a map where
 * nobody can tell whether what they are looking at is the story or a bug.
 *
 * <h2>Why nothing here is fetched eagerly</h2>
 *
 * The picture is asked for the first time somebody is drawn wearing it, by the same
 * machinery a costume in the wardrobe screen uses — request once, arrive in pieces,
 * cache by fingerprint. Until it lands this answers null and the player is drawn as
 * themselves, which is a face rather than a gap.
 */
public final class WornHere {

	private WornHere() { }

	/**
	 * What everybody on this map should be drawn in, or null for their own skin.
	 *
	 * Called from rendering, so both halves have to be cheap. The fingerprint is a
	 * field read; the texture is a map lookup that starts a fetch the first time and
	 * is silent afterwards — see {@link Costumes#texture}, which carries that guard
	 * because it is asked the same way, from drawing, sixty times a second.
	 */
	public static PlayerSkin skin() {
		String mark = Started.start().playerSkin();
		if (mark.isEmpty()) return null;

		PlayerSkin known = ready;
		if (known != null && mark.equals(readyFor)) return known;

		Identifier texture = Costumes.texture(mark);
		if (texture == null) return null;

		readyFor = mark;
		ready = CustomSkins.uploaded(texture);
		return ready;
	}

	/**
	 * The last answer, so that a skin is dressed up once rather than every frame.
	 *
	 * {@code CustomSkins.uploaded} builds a small record each time it is called, and
	 * this is called for every player on screen on every frame. Keeping the answer
	 * beside the fingerprint it was worked out for is what makes changing the map's
	 * skin still take effect: the pair disagreeing is the signal to build a new one.
	 */
	private static PlayerSkin ready;
	private static String readyFor = "";

	/** Dropped with the world, since the next one has its own map and its own skin. */
	public static void forget() {
		ready = null;
		readyFor = "";
	}
}
