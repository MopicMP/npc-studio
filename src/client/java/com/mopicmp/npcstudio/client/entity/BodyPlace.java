package com.mopicmp.npcstudio.client.entity;

import com.mopicmp.npcstudio.entity.BodyShape;

/**
 * Which part of a body this model part is, and where it sits in it.
 *
 * This is what replaced the multipliers. A part used to be told how much wider to
 * make itself; now it is told only who it is, and it asks {@link
 * com.mopicmp.npcstudio.entity.BodyField} where its surface goes. That is the whole
 * difference between six boxes that each grew on their own and one body: a pelvis
 * and the thigh under it put the same question to the same function at the same
 * height, so they cannot come back with different answers.
 *
 * <h2>Why the pivot is carried here</h2>
 *
 * A part's vertices are in its own space, where the pivot is the origin, and the
 * field is described in the model's space, measured down from the neck. Somebody
 * has to know the distance between the two, and the part does not: it knows its own
 * offset from its parent and nothing about the chain above it. So the builder,
 * which is holding the whole model, writes it down here.
 *
 * <h2>The units, said out loud</h2>
 *
 * {@code pivotY} and {@code pivotX} are in model pixels, because that is what
 * {@code ModelPart.x} and {@code y} are. Vertices are in blocks, a sixteenth of
 * that. The two meeting unnoticed is a mistake this project has already made once,
 * in a tool that drew a character a sixteenth of its size.
 *
 * @param shape  the build being drawn, whole — a part may need to ask about a
 *               height that is not its own, which is how a thigh knows the pelvis
 * @param leg    a leg or a trouser leg, as against the torso or a jacket
 * @param side   minus one on the character's right, plus one on its left
 * @param pivotY where this part hangs, in model pixels down from the neck
 * @param pivotX the same across, which a limb needs and the torso does not
 * @param base   the limb's own half-width in pixels, measured rather than assumed:
 *               a slim model's arm is three across and a classic one's is four, and
 *               assuming would have gone unnoticed until somebody wore an Alex skin
 * @param layer  an outer skin layer, a quarter-pixel outside what it covers
 * @param turnX  the limb's own turn this frame, which the joint gives back
 * @param turnY  the same about the other two axes, for an emote that uses them
 * @param turnZ  the same again
 */
public record BodyPlace(
		BodyShape shape,
		Kind kind,
		float side,
		float pivotY,
		float pivotX,
		float base,
		boolean layer,
		float turnX,
		float turnY,
		float turnZ) {

	/**
	 * Which of the body this is.
	 *
	 * Three so far, and the head is not among them: a face is drawn on that cube and
	 * the field has no business reinterpreting where somebody put their eyes.
	 */
	public enum Kind { TORSO, ARM, LEG }

	public boolean leg() {
		return kind == Kind.LEG;
	}

	public boolean arm() {
		return kind == Kind.ARM;
	}

	/**
	 * The limb's turn, handed over rather than read off the part.
	 *
	 * A trouser leg has no rotation of its own — it is a child, and the leg's turn
	 * reaches it through the pose stack. Reading each part's own angles would
	 * therefore free the leg's top band from the swing and leave the trouser's band
	 * swinging over it. So the builder, which knows which limb a layer belongs to,
	 * tells both the same number. The same rule as the extent a sleeve borrows, and
	 * for the same reason.
	 */
	public boolean turns() {
		return turnX != 0 || turnY != 0 || turnZ != 0;
	}

	/** How far an outer layer sits outside the surface it covers, in pixels. */
	public static final float LIP = 0.25f;

	public float lip() {
		return layer ? LIP : 0f;
	}
}
