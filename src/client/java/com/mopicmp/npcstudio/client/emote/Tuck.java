package com.mopicmp.npcstudio.client.emote;

/**
 * Pulling a part a hair inside its own surface, without splitting it open.
 *
 * <h2>Why a part is tucked at all</h2>
 *
 * A limb and the body it hangs from share planes. An arm's inner face and the
 * side of a torso are in exactly the same place, and two surfaces at the same
 * depth are a coin toss the depth buffer takes again every pixel. Drawing ours a
 * hair further back settles every one of those ties the same way, and in the
 * right direction: pressed against, never in front.
 *
 * A sixteenth of a pixel is far below what an eye can see and far above what the
 * depth buffer needs to make up its mind.
 *
 * <h2>Why it must not be done along the face's normal</h2>
 *
 * The first version moved each vertex along the normal of the face it was drawn
 * for. That looks like the same thing and is not, because <b>a corner of a box
 * belongs to three faces with three different normals</b> — so one point of the
 * model was given three answers and came apart into three. Every edge of every
 * part we drew opened by a fraction of a pixel, and through the crack you could
 * see the inside of the box: a strip of somebody else's skin standing proud of
 * the model, all the way round, on every part.
 *
 * It survived a long time because it was judged by its size. A sixteenth of a
 * pixel sounds too small to matter, and at arm's length it is; up close it is a
 * great many screen pixels of daylight.
 *
 * The fix is not a smaller number. It is that the tuck has to be a <b>function
 * of the point alone</b>. Shrinking the box towards its own centre is: each face
 * moves inward by exactly as much as before, and a corner shared by three faces
 * is one point going to one place, because nothing about the face it was reached
 * through is consulted.
 *
 * Distances are blocks, and every method is a function of its arguments alone.
 */
public final class Tuck {

	/**
	 * How far inside its own surface a face we draw ourselves is pulled.
	 *
	 * A sixteenth of a pixel, which is a thousandth of a block.
	 */
	public static final float DEPTH = 1f / 16f / 16f;

	private Tuck() { }

	/**
	 * Where a coordinate ends up once its part has been shrunk towards its middle.
	 *
	 * @param at     the coordinate, in blocks
	 * @param middle the middle of the part along this axis
	 * @param half   half the part's size along this axis
	 * @param by     how far the surface should move inward
	 */
	public static float inset(float at, float middle, float half, float by) {
		// Nothing thinner than the tuck has any room to give, and collapsing it to
		// a plane is better than turning it inside out.
		if (half <= by) return middle;
		return middle + (at - middle) * (1f - by / half);
	}
}
