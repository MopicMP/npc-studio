package com.mopicmp.npcstudio.foe;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * One thing worth attending to, whatever sense brought it in.
 *
 * <h2>Why this exists, told as the bug it is the fix for</h2>
 *
 * Watching grew a branch per sense — one for seeing somebody, one for hearing
 * them, one for a noise the world made — and each wrote its findings into a
 * different set of fields. What she was actually looking at was then reassembled
 * afterwards from those fields by a second set of rules, and the two sets
 * disagreed.
 *
 * They disagreed in exactly the way that is hardest to spot from inside the
 * game. An explosion would correctly become the most important thing in the
 * world and set the place to look at; then the reassembly, seeing that there
 * was also a person on file, would quietly return the person instead. Reported
 * as: after the dynamite she goes on staring at the floor, and the moment I
 * move behind the wall she forgets the explosion entirely and locks onto me.
 * Both halves of that, from one disagreement.
 *
 * So there are no longer several kinds of finding. There is one kind, and every
 * sense produces it, and what she is looking at is the last one chosen — not a
 * conclusion drawn a second time from its leftovers.
 *
 * @param at      where to look
 * @param who     whose it is, when that is known; a noise has no author
 * @param seen    whether this is somebody actually in view, which is the only
 *                evidence that makes a character certain and the only kind whose
 *                position is worth following live
 * @param strength how strongly it registers, nought to one
 * @param urgency  how much it matters, which is a different question — see
 *                 {@link Din#urgencyFor}
 */
public record Lead(Vec3 at, LivingEntity who, boolean seen, float strength, float urgency) {

	/**
	 * Seeing somebody, which outranks everything.
	 *
	 * Nothing is more informative than looking at the thing itself, so a sighting
	 * is not merely urgent, it is as urgent as anything gets. That is what stops a
	 * door slamming behind a character from turning her away from the person she is
	 * looking straight at.
	 */
	public static Lead sighting(LivingEntity who, float strength) {
		return new Lead(who.getEyePosition(), who, true, strength, 1f);
	}

	/**
	 * A noise, from wherever it seemed to come.
	 *
	 * The place is a guess and stays a guess: a noise never becomes a live track,
	 * however long it goes on. Hearing somebody walk about behind a wall used to
	 * hand back their exact position every tick, which is watching them through the
	 * wall by another name — and when they stopped, the head snapped away as though
	 * nothing had ever happened. Both were one mistake.
	 */
	public static Lead noise(Vec3 at, LivingEntity who, float strength, float urgency) {
		return new Lead(at, who, false, strength, urgency);
	}

	/** Whether this beats another as the thing to attend to. */
	public boolean beats(Lead other) {
		if (other == null) return true;
		// Importance first and loudness only to settle a tie, which is the whole
		// lesson of the crater: a nearby lump of falling rubble is louder than a
		// distant explosion and is not what anybody would look at.
		if (urgency != other.urgency()) return urgency > other.urgency();
		return strength > other.strength();
	}

	/**
	 * How fast to turn towards this, in degrees a tick.
	 *
	 * <h2>Speed says what kind of thing it was</h2>
	 *
	 * A character who whips round at a dropped stick looks alarmed by nothing; one
	 * who takes four seconds to face an explosion looks asleep. Both were happening,
	 * because the speed was chosen by her mood and not by what she was reacting to —
	 * and mood changes slowly by design, so it cannot say "that one was loud".
	 *
	 * Urgency can, and it is already the number that means it. So the turn is quick
	 * for a bang and unhurried for a footstep, and the player learns to read which
	 * happened from across a room without being told.
	 */
	public float turningAt() {
		return SLOWEST + (QUICKEST - SLOWEST) * Math.clamp(urgency, 0f, 1f);
	}

	/** An idle glance, and a head whipping round. */
	private static final float SLOWEST = 5f;
	private static final float QUICKEST = 22f;

	/** And the shoulders, which always follow rather than lead. */
	public float squaringAt() {
		return turningAt() * 0.55f;
	}
}
