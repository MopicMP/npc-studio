package com.mopicmp.npcstudio.map;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * A client setting a map is allowed to have an opinion about.
 *
 * <h2>Why this is a short list rather than every option there is</h2>
 *
 * Because every entry here is a promise that the mod can set the value, put it
 * back afterwards and defend it while the player is on the map, and each of
 * those costs something to get right. The five below are the ones that change
 * what a map <em>looks like</em> — how far you see, how dark it is, how wide the
 * lens is, how big the writing is. Everything else is a preference the map has
 * no business holding.
 *
 * <h2>The limits are not the authority</h2>
 *
 * They are here so a panel can offer a sensible slider, and that is all they are
 * for. The authority is the game: {@code OptionInstance.set} runs its own
 * {@code validateValue} and, when it fails, quietly substitutes <em>the option's
 * default</em> rather than clamping — so a brightness of 1.5 becomes 0.5 and
 * nothing says so.
 *
 * That is why every value is read back after it is applied. A table of limits
 * checked against itself proves nothing; a value read out of the game after
 * setting it proves the thing that matters.
 */
public enum Setting implements StringRepresentable {

	/** How far chunks are drawn. Capped by what the server sends, never raised past it. */
	RENDER_DISTANCE("render_distance", 2, 32, 12, 0),

	/** How far things tick. Costs more than drawing distance and is noticed less. */
	SIMULATION_DISTANCE("simulation_distance", 5, 32, 12, 0),

	/**
	 * Gamma, which everybody calls brightness.
	 *
	 * Nought is the game's "Moody" and one is "Bright". Past one is not refused
	 * politely — see the class note — so the ceiling here is the game's own.
	 */
	BRIGHTNESS("brightness", 0.0, 1.0, 0.5, 2),

	/** The lens. Changes how tight a corridor feels more than any other number here. */
	FOV("fov", 30, 110, 70, 0),

	/** Nought is "auto", which is what most people are on and what most maps want left alone. */
	GUI_SCALE("gui_scale", 0, 4, 0, 0);

	public static final Codec<Setting> CODEC = StringRepresentable.fromEnum(Setting::values);

	private final String name;
	private final double least;
	private final double most;
	private final double usual;
	private final int decimals;

	Setting(String name, double least, double most, double usual, int decimals) {
		this.name = name;
		this.least = least;
		this.most = most;
		this.usual = usual;
		this.decimals = decimals;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	public double least() {
		return least;
	}

	public double most() {
		return most;
	}

	/** What the game ships with, used as the starting value for a new entry. */
	public double usual() {
		return usual;
	}

	public int decimals() {
		return decimals;
	}

	public double step() {
		return decimals == 0 ? 1 : 0.05;
	}

	/** Kept inside the game's own range, because outside it the value is thrown away. */
	public double sane(double wanted) {
		return Math.clamp(wanted, least, most);
	}

	public String key() {
		return "npc_studio.start.setting." + name;
	}

	public static Setting named(String name) {
		for (Setting setting : values()) {
			if (setting.name.equals(name)) return setting;
		}
		return null;
	}
}
