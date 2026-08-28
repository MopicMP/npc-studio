package com.mopicmp.npcstudio.foe;

import java.util.List;

/**
 * How a character carries herself in a fight: a stance and a chain of blows.
 *
 * <h2>What is no longer here, and why that matters</h2>
 *
 * The timing. This record used to hold a {@code Blow.Shape} — one set of numbers
 * for the whole style — and that was wrong twice.
 *
 * Wrong about animations: a blow lands when its own picture says so.
 * {@code spe_hand_strike1} connects on its fourth tick and
 * {@code spe_zweihander_strike} on its thirtieth, and nothing about a fist or a
 * greatsword predicts either. One number for a style was out by up to eleven
 * ticks — half a second between somebody being hurt and the arm that hurt them
 * moving.
 *
 * Wrong in kind: a number written into a class is a number nobody but us can
 * change. It is asked of {@link Swings} now, which has a measured default and
 * takes an answer from anybody who has a better one.
 *
 * <h2>Where the chains come from</h2>
 *
 * Looked at, one frame at a time, with {@code tools/shape-look}. Two automatic
 * measures of where a blade passes agreed on eleven strikes out of nineteen and
 * disagreed on eight, and on the disagreements each was right about half the
 * time — so the numbers behind these names were settled by looking rather than
 * by choosing a formula.
 *
 * <h2>What was dropped after looking, and why</h2>
 *
 * {@code spe_spear_strike3} turns the character through a whole circle. A blow
 * that spins cannot be aimed, and the graph is meanwhile holding her facing her
 * target — two authors of one body. It is a fine showpiece and a bad third blow.
 *
 * {@code spe_zweihander_strike} is not the heavy chain either, for a duller
 * reason: it is one animation where the heavy sword has three, and three
 * different blows are worth more than one heavier-looking one. It keeps its
 * numbers in the registry for whoever wants it.
 *
 * <h2>Chosen from the weapon, and overridable</h2>
 *
 * The same bargain as recognising the weapon in the first place: guess from what
 * the item says about itself, and let the author say otherwise. A guess that
 * cannot be overruled is worse than no guess.
 */
public record Style(String stance, String walk, String run, List<String> chain) {

	/**
	 * A style for somebody who moves the way she always does.
	 *
	 * Empty is a real answer rather than a missing one: it means "leave her walk
	 * alone", which is right for bare hands — the pack has no unarmed fighting
	 * walk, and putting a swordsman's gait on a boxer would be worse than nothing.
	 */
	public Style(String stance, List<String> chain) {
		this(stance, "", "", chain);
	}

	/**
	 * A sword, and the default for anything that swings and is not obviously
	 * heavy. Three blows, because the pack has three and a fight of one repeated
	 * blow is the thing being fixed.
	 */
	public static final Style SWORD = new Style(
		"spe_fighting_stance_with_swords",
		// Chosen because the walk barely touches the upper body — 0.34 radians in
		// the arm against 2.0 in the legs — so it reads as somebody carrying a
		// weapon rather than as a second animation arguing with the stance.
		"spe_gait_with_a_sword_on_belt", "spe_running_with_swords",
		List.of("spe_strike_with_a_sword1", "spe_strike_with_a_sword2",
			"spe_strike_with_a_sword3"));

	/** Slower everywhere, and it should look it. */
	public static final Style HEAVY = new Style(
		"spe_fighting_pose",
		"spe_gait_with_a_sword_on_belt", "spe_running_with_swords",
		List.of("spe_a_blow_with_a_heavy_sword1", "spe_a_blow_with_a_heavy_sword2",
			"spe_a_blow_with_a_heavy_sword3"));

	/**
	 * A reach weapon: a long draw back and a short, deep thrust.
	 *
	 * Two blows rather than three. The third spins, and a blow that spins cannot
	 * be aimed at anybody.
	 */
	public static final Style SPEAR = new Style(
		"spe_fighting_pose",
		"spe_gait_with_a_sword_on_belt", "spe_running_with_swords",
		List.of("spe_spear_strike1", "spe_spear_strike2"));

	/**
	 * Nothing in her hands.
	 *
	 * Punches, not kicks. The kicks were here first and were the wrong choice
	 * twice over: {@code spe_kick} barely moves a leg, and both are nearly twice
	 * as long as a punch — so bare hands fought slower than a greatsword. The pack
	 * has a real three-blow punching chain and nobody had looked for it.
	 */
	public static final Style FIST = new Style(
		"spe_fighting_stance",
		List.of("spe_hand_strike1", "spe_hand_strike2", "spe_hand_strike3"));

	/**
	 * Something is in her hands and she is behind it rather than swinging it.
	 *
	 * The swings are the sword's. {@code spe_swing_the_sword} looks like the
	 * obvious choice by its name and is the wrong one: it loops, and a blow whose
	 * animation never ends is a character stuck mid-swing for ever.
	 */
	public static final Style GUARDED = new Style(
		"spe_fighting_pose_with_shild", SWORD.walk(), SWORD.run(), SWORD.chain());

	/** Holding something that shoots: a stance, and swings only if pressed. */
	public static final Style RANGED = new Style(
		"spe_fighting_pose_with_a_bow",
		"spe_walking_with_a_gun", "spe_running_with_gun", SWORD.chain());

	/**
	 * The style a weapon suggests.
	 *
	 * Read off the classification rather than off a list of items, for the same
	 * reason the classification itself is: a modded greatsword lands in the right
	 * place because of what it says about itself, and nobody had to be told about
	 * it.
	 */
	public static Style forWeapon(Arms.Kind kind) {
		return switch (kind) {
			case NOTHING -> FIST;
			case SHIELD -> GUARDED;
			case DRAWN, LOADED -> RANGED;
			// A gun that fires by being swung is swung, so it swings like a weapon.
			case MELEE, SWUNG, THROWN, OTHER -> SWORD;
		};
	}

	/**
	 * What taking a blow looks like.
	 *
	 * <h2>Where this comes from, honestly</h2>
	 *
	 * The opening of a death. The pack has no animation called a flinch — the
	 * nearest things are held states like {@code spe_stomach_pain}, which is a
	 * condition rather than a moment — but {@code spe_to_die_from_a_severe_blow}
	 * begins with somebody being hit hard, and its first half-second is exactly
	 * that: the body folds forward over the blow.
	 *
	 * Looked at frame by frame before it was used. Ticks two to seven are the
	 * impact; everything after is the falling over, which is why it is played for a
	 * few ticks and then handed back rather than run to its end.
	 *
	 * <h2>How far in reads as how hard</h2>
	 *
	 * For nothing. A gesture given a length eases back out of itself over its last
	 * few ticks, so a short flinch only reaches a shallow fold and a long one
	 * reaches a deep one. Severity is the length, and needs no second number.
	 *
	 * On the style so that it can differ — a blow taken behind a shield is not the
	 * blow taken in the chest — even though every style names the same one today.
	 */
	public String flinch() {
		return "spe_to_die_from_a_severe_blow";
	}

	/**
	 * How she carries herself while doing this, given how she is moving.
	 *
	 * Empty means "the way she always does", which is what an unarmed style says
	 * and what any style says about a movement the pack has nothing for.
	 */
	public String carriage(boolean moving, boolean quickly) {
		if (!moving) return stance;
		String wanted = quickly ? run : walk;
		return wanted.isEmpty() ? "" : wanted;
	}

	/** Which animation the next blow of the chain uses. */
	public String swing(int link) {
		return chain.get(Math.floorMod(link, chain.size()));
	}

	/**
	 * When that blow lands, asked rather than known.
	 *
	 * Every caller goes through here rather than reaching for the registry itself,
	 * so that "which animation" and "when does it land" cannot be answered about
	 * two different blows — which is exactly how a picture and a piece of damage
	 * come to belong to different swings.
	 */
	public Swing timing(int link) {
		return Swings.of(swing(link));
	}

	public Blow.Shape shape(int link) {
		return timing(link).shape();
	}
}
