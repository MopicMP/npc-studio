package com.mopicmp.npcstudio.client.entity;

/**
 * What one model part is asked to do with its own surface this frame.
 *
 * <h2>Why this replaced a pair of scales</h2>
 *
 * The first version described a build as a scale of the cross-section: multiply
 * across and multiply front-to-back. That is the right primitive for a box and
 * the wrong one for a body, and the difference showed up the moment anybody
 * looked at it.
 *
 * A torso is eight pixels across and four deep. Asking for "belly 1.45" scaled
 * both by nearly a half, which is three and a half pixels sideways and under two
 * forwards — so a stomach came out as a character who had got wider. A stomach
 * does not make somebody wider. It sticks out in front.
 *
 * The same mistake made the chest a matter of width, when the whole point of a
 * chest is that it protrudes, and made hips into nothing but the gap between the
 * legs, when hips are the width of a pelvis that the legs then hang under.
 *
 * So the primitive here is a <b>directed displacement</b>: a width that varies
 * down the part, and a push forwards that has a place, a spread and a falloff.
 * Width and protrusion are different things and are now said differently.
 */
public record PartBuild(
		float wideTop,
		float wideBottom,
		float deep,
		float chest,
		float belly,
		float roundness) {

	/** Leave it exactly as Minecraft built it. */
	public static final PartBuild NONE = new PartBuild(1, 1, 1, 0, 0, 0);

	/**
	 * How far a full slider pushes a surface forwards, in blocks.
	 *
	 * Three pixels. The torso is four deep, so the far end of the slider is a
	 * stomach that nearly doubles the depth of a character — plenty, and short of
	 * the point where the arms would have to be moved out of its way.
	 */
	public static final float PUSH = 3f / 16f;

	public boolean isNone() {
		return equals(NONE);
	}

	/** Whether this part changes width anywhere along itself. */
	public boolean tapers() {
		return wideTop != wideBottom;
	}
}
