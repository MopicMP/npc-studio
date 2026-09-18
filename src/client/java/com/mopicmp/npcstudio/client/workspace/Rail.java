package com.mopicmp.npcstudio.client.workspace;

import java.util.List;

/**
 * The strip of icons down an edge, and what belongs on which one.
 *
 * <h2>Where the two lists come from</h2>
 *
 * Not from taste. They are what is left over after the ladder has been walked:
 * anything that can be done in the world is done in the world, anything that is
 * an instruction goes to blocks, and only what fits neither ends up here. The
 * left edge takes what moves <em>partly</em> — a list that exists for the case
 * pointing cannot reach — and the right takes what cannot move at all, which in
 * every case so far turned out to be a catalogue.
 *
 * <h2>Why these are ids and not panels</h2>
 *
 * Because a rail button is a request, not a place. Pressing one may make the
 * panel, or may take away the one already out; neither needs the panel to exist
 * while the workspace is merely drawn.
 *
 * <h2>What this list is meant to become</h2>
 *
 * Shorter. Five of the six entries below are waiting on things that have not
 * been built: the four on the right are one catalogue with a source chosen at
 * the top, and the two on the left are one panel of numbers for whatever is
 * held. When those exist this file loses most of its lines, and the edges end up
 * with one button each — which is the point, and the reason the rails are not
 * being designed to be comfortable with nine.
 */
public final class Rail {

	private Rail() { }

	/**
	 * How wide a strip is, and therefore how big its targets are.
	 *
	 * Sized off the icon rather than chosen: the whole complaint that started this
	 * was targets of ten pixels in a bar of eighteen. An icon is sixteen, so a
	 * strip is twenty and every button in it is twenty by twenty — the same size
	 * as a direction on the ring, and about four times the area of what it
	 * replaces.
	 */
	public static final int WIDTH = 20;

	/** How wide a summoned panel is. */
	public static final int PANEL = 150;

	/**
	 * The left edge: what can be pointed at, mostly, but not always.
	 *
	 * Both of these exist for one case — the thing you want is behind something
	 * else. Clicking a character in the world is the ordinary way and it fails
	 * when the character is behind the ship, which is what the objects list is
	 * for and the only reason it survives the ladder at all.
	 */
	public static List<String> left() {
		return List.of("scene", "start");
	}

	/** The right edge: catalogues, which have no place in the world and never will. */
	public static List<String> right() {
		return List.of("assets", "wardrobe", "animation", "shaders");
	}

	public static boolean holds(String id) {
		return left().contains(id) || right().contains(id);
	}
}
