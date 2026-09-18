package com.mopicmp.npcstudio.map;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * What a map's number means: the value, a floor, or a ceiling.
 *
 * <h2>The case this exists for</h2>
 *
 * Drawing distance is two things at once — how much of the map is visible, and
 * how hard the machine is working. A map that pins it to thirty-two is a map
 * that is unplayable on a laptop, and the author finds out from a complaint
 * rather than from the panel. "Not below twelve" gets the view the map was built
 * for and leaves everyone above it alone.
 *
 * Brightness is the opposite and it is worth saying why, because the difference
 * is the whole reason there are three of these rather than one: darkness is not
 * a resource anybody is short of. A player who raises it is not coping with a
 * weak machine, they are switching off the thing the room was built out of. So
 * that one wants {@link #EXACT}, and a map that cannot tell these two apart gets
 * one of them wrong.
 */
public enum Bound implements StringRepresentable {

	/** The value, and no other. */
	EXACT("exact"),

	/** A floor: anything higher is the player's business. */
	AT_LEAST("at_least"),

	/** A ceiling: anything lower is the player's business. */
	AT_MOST("at_most");

	public static final Codec<Bound> CODEC = StringRepresentable.fromEnum(Bound::values);

	private final String name;

	Bound(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	/**
	 * What the value has to become, given what it is now.
	 *
	 * Returns {@code now} when nothing needs doing, which is how a bound stays out
	 * of the way of a player who was already inside it.
	 */
	public double applied(double now, double wanted) {
		return switch (this) {
			case EXACT -> wanted;
			case AT_LEAST -> Math.max(now, wanted);
			case AT_MOST -> Math.min(now, wanted);
		};
	}

	/** Whether a value the player is trying to move to is still allowed. */
	public boolean allows(double moving, double wanted) {
		return switch (this) {
			case EXACT -> moving == wanted;
			case AT_LEAST -> moving >= wanted;
			case AT_MOST -> moving <= wanted;
		};
	}

	public String key() {
		return "npc_studio.start.bound." + name;
	}
}
