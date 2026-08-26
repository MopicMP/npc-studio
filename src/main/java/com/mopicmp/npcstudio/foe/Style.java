package com.mopicmp.npcstudio.foe;

import java.util.List;

/**
 * How a character carries herself in a fight: a stance, a chain of blows, and
 * the timing of each.
 *
 * <h2>Why these three belong together</h2>
 *
 * Because they are one decision. A stance that does not match the swings looks
 * like two animations fighting over a body; a swing whose timing does not match
 * its picture lands before the blade moves. Naming them separately would invite
 * exactly those mismatches, one setting at a time.
 *
 * <h2>Where the numbers come from</h2>
 *
 * The animations. Every strike in the pack is between twenty-three and
 * twenty-eight ticks long, and each style's parts add up to no more than its
 * <em>shortest</em> swing — an animation that finishes while the character is
 * still committed leaves her frozen at the end of it, which looks worse than no
 * animation at all. A test reads the pack and holds this to it, because the
 * numbers below are exactly the sort of thing that drifts once somebody swaps an
 * animation.
 *
 * <h2>What makes a heavy weapon heavy</h2>
 *
 * Not a longer swing. The pack's animations are all about the same length, and
 * holding one past its end freezes it. What reads as weight is the <b>wind-up</b>
 * — twelve ticks of telegraph against a sword's eight — and the pause the graph
 * leaves between blows. Weight is how long you see it coming, not how long it
 * takes.
 *
 * The blade is dangerous for four ticks of it. That is a fifth of a second: long
 * enough for a sweep to pass through somebody who is moving, short enough that
 * walking into a swing that has already gone by does not count.
 *
 * <h2>Chosen from the weapon, and overridable</h2>
 *
 * The same bargain as recognising the weapon in the first place: guess from what
 * the item says about itself, and let the author say otherwise. A guess that
 * cannot be overruled is worse than no guess.
 */
public record Style(String stance, List<String> chain, Blow.Shape shape) {

	/**
	 * A sword, and the default for anything that swings and is not obviously
	 * heavy. Three blows, because the pack has three and a fight of one repeated
	 * blow is the thing being fixed.
	 */
	public static final Style SWORD = new Style(
		"spe_fighting_stance_with_swords",
		List.of("spe_strike_with_a_sword1", "spe_strike_with_a_sword2",
			"spe_strike_with_a_sword3"),
		new Blow.Shape(8, 4, 12));

	/** Slower everywhere, and it should look it. */
	public static final Style HEAVY = new Style(
		"spe_fighting_pose",
		List.of("spe_a_blow_with_a_heavy_sword1", "spe_a_blow_with_a_heavy_sword2",
			"spe_a_blow_with_a_heavy_sword3"),
		new Blow.Shape(12, 5, 6));

	/** A reach weapon: a long wind-up and a short, deep thrust. */
	public static final Style SPEAR = new Style(
		"spe_fighting_pose",
		List.of("spe_spear_strike1", "spe_spear_strike2", "spe_spear_strike3"),
		new Blow.Shape(10, 3, 10));

	/** Nothing in her hands. Quick, and it is a kick as often as a punch. */
	public static final Style FIST = new Style(
		"spe_fighting_stance",
		List.of("spe_kick", "spe_kick_out"),
		new Blow.Shape(5, 3, 8));

	/**
	 * Something is in her hands and she is behind it rather than swinging it.
	 *
	 * The swings are the sword's. {@code spe_swing_the_sword} looks like the
	 * obvious choice by its name and is the wrong one: it loops, and a blow whose
	 * animation never ends is a character stuck mid-swing for ever.
	 */
	public static final Style GUARDED = new Style(
		"spe_fighting_pose_with_shild",
		SWORD.chain(),
		new Blow.Shape(8, 4, 12));

	/** Holding something that shoots: a stance, and swings only if pressed. */
	public static final Style RANGED = new Style(
		"spe_fighting_pose_with_a_bow",
		SWORD.chain(),
		new Blow.Shape(8, 4, 12));

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

	/** Which animation the next blow of the chain uses. */
	public String swing(int link) {
		return chain.get(Math.floorMod(link, chain.size()));
	}
}
