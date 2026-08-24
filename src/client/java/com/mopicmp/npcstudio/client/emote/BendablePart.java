package com.mopicmp.npcstudio.client.emote;

/**
 * A model part that can be folded in the middle.
 *
 * Bolted onto the vanilla {@code ModelPart} rather than replacing it, because
 * everything else about those parts — the skin, the outer layer, the way a
 * renderer walks them — is wanted exactly as it is. The only thing missing is
 * that a limb is one rigid box, and an animator's limb has an elbow.
 */
public interface BendablePart {

	/**
	 * Folds this part at its own middle.
	 *
	 * @param radians how far to fold; zero puts it back to a plain rigid box and
	 *                costs nothing at all, which matters because every model part
	 *                in the game runs through this
	 */
	void npcStudio$setBend(float radians);

	float npcStudio$bend();

	/**
	 * Bends by somebody else's measurements.
	 *
	 * An outer skin layer is an inflated copy of the limb under it, so its box is
	 * a fraction taller — and a part that works out the curve from its own height
	 * gets a slightly different curve. The sleeve then bends by a slightly
	 * different amount than the arm at the same height, the two surfaces cross,
	 * and the overlap shimmers. Larger characters make it obvious, because the
	 * gap between the two grows with them.
	 *
	 * So a layer is told the limb's extent and bends along the same curve.
	 */
	void npcStudio$setBendAlong(float radians, float top, float bottom);

	float npcStudio$bendTop();

	float npcStudio$bendBottom();

	/** Every measurement a part takes of its own box, in blocks. */
	record Extent(float top, float bottom,
			float middleX, float halfX, float middleZ, float halfZ) { }

	Extent npcStudio$extent();

	/**
	 * Deform by somebody else's measurements rather than by your own.
	 *
	 * <h2>Why an outer layer must never measure itself</h2>
	 *
	 * A sleeve is the arm inflated by a quarter of a pixel, so every box it could
	 * measure is a fraction wider, deeper and taller. Everything we do to a vertex
	 * is per-vertex and none of it is linear: a bend turns a corner by an angle
	 * that depends on its own height, roundness moves it by a factor that depends
	 * on its own distance from the middle. A flat face therefore comes out
	 * slightly <em>not</em> flat, and its two triangles meet along a shallow
	 * crease down the quad's diagonal.
	 *
	 * Measured from a bigger box that crease lands somewhere slightly different.
	 * The sleeve's dips where the arm's does not, the arm shows through along it,
	 * and what you see is a thin hatched line across the middle of a sleeve —
	 * nowhere near the body, which is what made this look like limbs colliding for
	 * so long.
	 *
	 * Given the arm's numbers the sleeve becomes an exact offset copy of the same
	 * deformed surface: the creases run parallel and the quarter-pixel between
	 * them survives everywhere.
	 *
	 * This started life as {@link #npcStudio$setBendAlong}, which fixed exactly
	 * this for bending and left width and depth to be found out later. This is the
	 * rest of it.
	 */
	void npcStudio$followExtent(Extent extent);

	/**
	 * How shut to draw the eyes, and where on the skin they are.
	 *
	 * On the same interface as the bend because it is the same kind of thing:
	 * something handed to a vanilla model part for one frame, by way of a mixin,
	 * because the part has nowhere of its own to keep it.
	 *
	 * The place travels with the amount rather than being assumed, because the
	 * two are only meaningful together — a lid drawn where a face has no eyes is
	 * worse than no lid at all.
	 */
	void npcStudio$setEyes(com.mopicmp.npcstudio.client.skin.Eyes eyes);

	/**
	 * What to do with this part's own surface, for one frame.
	 *
	 * Scales rather than sizes, and multiplied rather than added, which is what
	 * keeps an outer layer outside the limb it covers. A jacket is an inflated
	 * copy — a quarter-pixel bigger all round — so multiplying both by the same
	 * factor keeps that quarter-pixel in proportion. Adding a width to both would
	 * close the gap at some scales and open it at others, and where it closes the
	 * two surfaces cross and shimmer.
	 *
	 * The push forwards is the exception and is added rather than multiplied,
	 * because a stomach is a distance and not a proportion. It is the same
	 * distance for the jacket as for the body, so the two move together and the
	 * gap between them survives untouched.
	 */
	void npcStudio$setBuild(com.mopicmp.npcstudio.client.entity.PartBuild build);

	/**
	 * Which part of a body this is, for one frame, or null for none of it.
	 *
	 * Set instead of a build wherever the body field has taken over — the torso and
	 * the legs so far. A part given a place stops being told how much wider to make
	 * itself and starts asking where its surface is, which is the only way its edge
	 * and its neighbour's can be the same edge.
	 *
	 * Written every frame, including the frames where the answer is null. A part
	 * that clears itself only when it draws never clears at all when it is hidden,
	 * and the model is shared: a jacket switched off would hand the last character's
	 * figure to the next one wearing it.
	 */
	void npcStudio$setPlace(com.mopicmp.npcstudio.client.entity.BodyPlace place);

	/** Whether this part has anything to say about its own shape this frame. */
	boolean npcStudio$built();
}
