package com.mopicmp.npcstudio.map;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * How hard a map insists on one of its settings.
 *
 * <h2>Why this belongs to the setting and not to the map</h2>
 *
 * It was going to be one choice for the whole map, and that was wrong for a
 * reason the author gave straight away: the same map wants to <em>hold</em> the
 * darkness — a horror corridor that can be gamma'd away is not a corridor — and
 * merely <em>suggest</em> the drawing distance, because that one is somebody
 * else's machine and somebody else's problem. One answer for both settings is
 * the wrong answer for one of them, whichever answer it is.
 */
public enum Firmness implements StringRepresentable {

	/**
	 * Offered, and nothing happens without a yes.
	 *
	 * Nothing to put back afterwards, because nothing was taken: the player
	 * changed their own settings, in the ordinary way, having been asked.
	 */
	SUGGESTED("suggested"),

	/**
	 * Set on arrival, put back on the way out.
	 *
	 * The player may still change it while they are here, and that is the point:
	 * the map decides what you start with, not what you are stuck with.
	 */
	WHILE_HERE("while_here"),

	/**
	 * Set on arrival, put back on the way out, and refused in between.
	 *
	 * The strongest thing here and the one that has to explain itself: a value
	 * that will not move without saying why reads as a broken game.
	 */
	HELD("held");

	public static final Codec<Firmness> CODEC = StringRepresentable.fromEnum(Firmness::values);

	private final String name;

	Firmness(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	/** Whether arriving on the map changes the value at all. */
	public boolean applies() {
		return this != SUGGESTED;
	}

	public String key() {
		return "npc_studio.start.firmness." + name;
	}
}
