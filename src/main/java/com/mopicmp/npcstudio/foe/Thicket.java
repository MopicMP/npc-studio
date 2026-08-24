package com.mopicmp.npcstudio.foe;

/**
 * Standing in the crops: harder to see, easier to hear.
 *
 * <h2>The trade this exists to make</h2>
 *
 * A field of wheat is the clearest case in the game of cover that costs
 * something. It genuinely hides you — that is what a field of anything does —
 * and you cannot move a step through it quietly, because the whole field moves
 * when you do. Anybody who has crept along a hedge knows both halves.
 *
 * Which makes it the one piece of terrain that is worth modelling for its own
 * sake, because it is the only one where the right answer is not "better" or
 * "worse" but "different". Stone hides you and silences you; open ground does
 * neither. Wheat does one and undoes the other, and a player who works that out
 * has learnt something the game never told them.
 *
 * <h2>Crouching is the whole mechanic</h2>
 *
 * Wheat comes up to your waist, so standing in it hides your legs and nothing
 * that matters. Crouch and your head goes into it too. That is the difference
 * between being seen and not, it costs a keypress, and it is exactly the sort of
 * thing worth discovering rather than being told.
 */
public final class Thicket {

	private Thicket() { }

	/**
	 * How much cover somebody has, given what is at their feet and at their head.
	 *
	 * <h2>Both, and not either</h2>
	 *
	 * Legs in the wheat is nearly nothing: a head and shoulders above a field is a
	 * head and shoulders, and it is the part anybody looks at. Head in it as well is
	 * most of the way to gone. So the two halves are worth very different amounts,
	 * and adding them up equally would have made standing in a flower bed as good as
	 * lying in a hedge.
	 *
	 * @param feet whether there is growth at the feet
	 * @param head whether there is growth at head height, which crouching arranges
	 */
	public static float concealment(boolean feet, boolean head) {
		if (head && feet) return DEEP;
		if (head) return HALF;
		return feet ? SHALLOW : 0;
	}

	/** Legs in it only: your outline is broken up and you are still standing there. */
	public static final float SHALLOW = 0.2f;

	/** Head in it and feet not — a hedge you are standing behind rather than in. */
	public static final float HALF = 0.5f;

	/** In it up to the eyes, which is what crouching in a crop gets you. */
	public static final float DEEP = 0.8f;

	/**
	 * How much noise moving through growth makes at the least.
	 *
	 * <h2>A floor rather than an addition, and that is the point</h2>
	 *
	 * Added on, it would make a running man in wheat slightly louder than a running
	 * man on stone, which is true and dull. As a floor it does something far more
	 * interesting: it takes away the benefit of creeping. You can crouch through a
	 * field and be almost invisible, and you will be heard doing it, and there is
	 * nothing you can do about that except go round.
	 *
	 * So the field gives and takes with the same hand, and choosing to cross it is a
	 * decision rather than an obvious yes.
	 */
	public static final float RUSTLE = 0.22f;

	/**
	 * How loud somebody moving through growth is, at least.
	 *
	 * @param loudness what they would be making on bare ground
	 * @param moving   whether they are actually moving; standing still in a field
	 *                 makes no more noise than standing still anywhere else, and a
	 *                 field that hissed at a motionless man would be a strange field
	 */
	public static float rustling(float loudness, boolean moving) {
		if (!moving) return loudness;
		return Math.max(loudness, RUSTLE);
	}
}
