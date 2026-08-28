package com.mopicmp.npcstudio.foe;

/**
 * What one animation's blow does, in time and in space.
 *
 * <h2>Why this is a property of the animation and not of the weapon</h2>
 *
 * Because it is a fact about a picture. The blade of
 * {@code spe_katana_strike3} passes at its second tick and the blade of
 * {@code spe_zweihander_strike} at its thirtieth, and no fact about a katana or a
 * greatsword predicts either. Held on the style instead — which is where it was —
 * one number stood for eleven animations and was wrong by up to eleven ticks
 * against most of them: half a second between somebody being hurt and the arm
 * that hurt them moving.
 *
 * This is the same thing Epic Fight says with {@code AttackAnimation.Phase}: six
 * numbers, and they live in the animation.
 *
 * <h2>The three numbers</h2>
 *
 * @param contact  the first tick the edge is dangerous
 * @param through  how many ticks it stays dangerous. More than one because a
 *                 sweep passes through a space rather than arriving at a point
 * @param cancel   the earliest tick another blow may begin. Not the end of the
 *                 animation, and the difference is the whole of why a fight is
 *                 not a slideshow: {@code spe_zweihander_strike} runs for
 *                 sixty-one ticks and has finished swinging by its thirty-fifth
 * @param reach    how far in front of herself the edge gets when it lands, in
 *                 blocks, measured off the animation along the arm's own axis.
 *                 <p>
 *                 This used to be {@code ENTITY_INTERACTION_RANGE}, which is
 *                 three blocks and is the game's number for clicking on a mob
 *                 with a mouse. Nothing about it is a fact about an arm, and it
 *                 was three times what an arm is: two characters stood three and
 *                 a half blocks apart and punched the air between them.
 *                 <p>
 *                 In blocks rather than model units because everything that asks
 *                 — a distance to a target, a hitbox width — is in blocks, and a
 *                 unit that has to be converted at every use is a unit that will
 *                 be converted wrongly once
 * @param checked  whether somebody has looked at this rather than only measured
 *                 it. Two automatic measures agreed on eleven strikes out of
 *                 nineteen and disagreed on eight, and where they disagreed each
 *                 was right about half the time — so an unlooked-at number is a
 *                 draft, and saying which is which is worth a field
 */
public record Swing(int contact, int through, int cancel, double reach, boolean checked) {

	public Swing {
		contact = Math.max(0, contact);
		through = Math.max(1, through);
		// A blow that could be cancelled before it had landed would be a blow that
		// never happened, and the graph asking again every tick would make it the
		// ordinary case rather than an edge one.
		cancel = Math.max(contact + through, cancel);
		// A blow that reaches nowhere is a character who has decided not to fight,
		// and that is never what somebody meant to write.
		reach = Math.max(0.25, reach);
	}

	/**
	 * The same thing as an interval a body is committed to.
	 *
	 * The recovery reaches to the cancel point rather than to the end of the
	 * animation, which is what lets a second blow start while the first is still
	 * being drawn. The picture carries on; she is simply allowed to move again.
	 */
	public Blow.Shape shape() {
		return new Blow.Shape(contact, through, cancel - contact - through);
	}
}
