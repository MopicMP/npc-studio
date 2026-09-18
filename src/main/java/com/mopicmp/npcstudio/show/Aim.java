package com.mopicmp.npcstudio.show;

import java.util.List;

/**
 * Which shown thing somebody is pointing at.
 *
 * <h2>Why a press is worked out here instead of by the game</h2>
 *
 * Because a display entity cannot be clicked. It has no collision and no hit box at all,
 * and that is deliberate in the game rather than an oversight — the supported way to make
 * one answer a click is to stand an {@code interaction} entity in the same place.
 *
 * That is what this replaces, and the reasons are worth writing down, because the
 * interaction route is what every guide describes:
 *
 * <ul>
 * <li>An invisible box that catches clicks catches <em>every</em> click, including the ones
 *     aimed at the block behind it. A label that is only a label would swallow them for no
 *     reason anybody can see.</li>
 * <li>It is a second entity per thing shown, made, moved and taken away in step with the
 *     first — and the world is where somebody deletes one of the two with a command.</li>
 * <li>Its size has to be decided when it is made, so a thing that grows is a box that
 *     does not.</li>
 * </ul>
 *
 * A ray has none of those. Nothing is standing in the world that was not asked for, the
 * test is made against the size the thing has right now, and a click that hits nothing of
 * ours is a click the game handles exactly as it always did.
 *
 * <h2>What is approximate about it, said out loud</h2>
 *
 * The size of the thing. A display's own bounds are not something the server can ask for —
 * the words are laid out by the client, in whatever font it is running — so the test is
 * against a ball around the point the thing stands at, of a radius that follows its scale.
 * A long label is therefore pressed near its words rather than at either end of them.
 *
 * That is a real limit and it is the right one to take: the alternative is a server that
 * guesses at font metrics, which would be wrong in a way nobody could see and would change
 * under people with a resource pack on.
 *
 * @param name what this thing is called
 * @param size how large it is drawn, as a multiple
 */
public record Aim(String name, double x, double y, double z, double size) {

	/**
	 * How much room a thing of scale one takes, for pressing.
	 *
	 * Half a block, which is a little more than a line of text is tall and a good deal less
	 * than a block display is wide. Erring small on purpose: a ball that is too big makes
	 * the row above the one somebody meant answer instead, and a menu that acts on the
	 * wrong row is worse than one that needs aiming at.
	 */
	public static final double ROOM = 0.25;

	/** A little extra, so that a very small label is not a pixel to hit. */
	public static final double LEAST = 0.1;

	private double radius() {
		return ROOM * size + LEAST;
	}

	/**
	 * The name of the thing the eye is pointing at, or empty when it is pointing at none.
	 *
	 * The ray has to pass through the ball; among the ones it passes through, the nearest
	 * wins. Nearest rather than best-centred, so that a label standing in front of another
	 * takes the press — which is what a person looking at them expects, since the near one
	 * is what they can see.
	 *
	 * @param look must be a unit vector, which is what a look angle already is
	 * @param reach how far a press carries, in blocks
	 */
	public static String at(List<Aim> things, double eyeX, double eyeY, double eyeZ,
			double lookX, double lookY, double lookZ, double reach) {
		String found = "";
		double nearest = Double.MAX_VALUE;
		for (Aim thing : things) {
			double toX = thing.x() - eyeX;
			double toY = thing.y() - eyeY;
			double toZ = thing.z() - eyeZ;
			double along = toX * lookX + toY * lookY + toZ * lookZ;
			// Behind the eye, or past the end of the reach. The radius is allowed at both
			// ends, so a thing one is standing inside of, or one just beyond arm's length,
			// is still pressable rather than falling off a cliff edge.
			double radius = thing.radius();
			if (along < -radius || along > reach + radius) continue;
			double square = toX * toX + toY * toY + toZ * toZ;
			double perpendicular = square - along * along;
			if (perpendicular > radius * radius) continue;
			if (along < nearest) {
				nearest = along;
				found = thing.name();
			}
		}
		return found;
	}
}
