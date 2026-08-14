package com.mopicmp.npcstudio.client.skin;

/**
 * What a character's eyes are doing this frame.
 *
 * <h2>Why the three travel together</h2>
 *
 * Where the eyes are, how shut they are and which way they are turned are all
 * answers to one question, and they are all wanted at the same moment by the
 * same code. They used to be carried separately — a face here, a blink there —
 * and every new thing an eye can do meant another field on the render state,
 * another setter on the model, another argument to the part. Bundled, the next
 * one is a field on this record and nothing else moves.
 *
 * The brows arrived exactly that way, and are the proof the shape was right: a
 * whole new part of the face moving, and the only things that changed outside
 * this record were the thing that decides where a brow goes and the thing that
 * draws it.
 *
 * @param face  where the eyes are on this skin, or null on a face with none
 * @param shutRight how shut the character's own right eye is, nought to one
 * @param shutLeft  and its left. Two rather than one because of the wink: every
 *                  other movement is the same on both sides, and the one that is
 *                  not would otherwise need a field of its own and a rule about
 *                  which field wins
 * @param aside which way they are turned, in the character's own terms: −1 its
 *              left through 1 its right, and any fraction between — the eye
 *              slides rather than jumping, so there is no smallest step
 * @param up    which way they are turned upright, −1 down to 1 up
 * @param pupil how much wider than drawn the pupils are, −1 to 1, which the
 *              light and whoever is standing nearby between them decide
 * @param brows where the brows sit against where they were drawn — the one part
 *              of a face this size that moves a whole pixel, and therefore the
 *              one an expression is actually read from
 */
public record Eyes(FaceReading face, float shutRight, float shutLeft,
		float aside, float up, float pupil, Brows brows) {

	/** A face with nothing asked of its brows keeps the ones it was drawn with. */
	public Eyes {
		if (brows == null) brows = Brows.NONE;
	}

	/** How shut one of the two is. */
	public float shut(boolean rightSide) {
		return rightSide ? shutRight : shutLeft;
	}

	/** How shut the more closed of the two is. */
	public float mostShut() {
		return Math.max(shutRight, shutLeft);
	}

	/** A character whose face nobody could read: no lid, no glance. */
	public static final Eyes NONE = new Eyes(null, 0f, 0f, 0f, 0f, 0f, Brows.NONE);

	/** Whether there is anything to draw over the face at all. */
	public boolean anything() {
		return face != null && face.hasEyes() && (mostShut() > 0f
			|| Math.abs(aside) > 0.01f || Math.abs(up) > 0.01f || Math.abs(pupil) > 0.01f
			|| brows.any());
	}
}
