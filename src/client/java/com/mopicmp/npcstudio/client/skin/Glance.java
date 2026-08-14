package com.mopicmp.npcstudio.client.skin;

/**
 * Where one eye's drawing goes when a character looks aside, and how much of it
 * is pupil.
 *
 * <h2>What the first version got wrong</h2>
 *
 * It painted the whole eye flat with one texel of sclera and drew the iris back
 * a column across. On the face that idea was written for — a white pixel and a
 * dark one — that is exactly right, because those two pixels <em>are</em> the
 * whole eye. On anything drawn with care it is vandalism: a highlight, a lash,
 * an iris with a lighter rim, all replaced by one flat colour with a rectangle
 * in it. The eyes came out as dead blocks, which is worse than not moving them.
 *
 * <h2>What it does instead: the eye slides, and the socket clips it</h2>
 *
 * The eye's own pixels are drawn again, offset. Nothing is invented and nothing
 * is flattened — it is the same drawing, moved. The socket clips it, so nothing
 * spills onto the cheek, and the strip left bare at the trailing edge is filled
 * with the sclera, because that is what is behind an eye that has looked away.
 *
 * <h2>And it moves by fractions of a pixel</h2>
 *
 * A glance that jumps a whole pixel is not a glance, it is the picture being
 * swapped for a different picture. What makes the movement smooth is that this
 * is <b>geometry, not texture</b>: the quad is moved a third of a pixel and the
 * drawing on it goes with it. Shifting texture coordinates instead could only
 * ever snap, since a texel is either sampled or it is not.
 *
 * <h2>The pupil is a rectangle inside the eye</h2>
 *
 * Resizing a pupil needs no new drawing either. It is the eye's own iris, drawn
 * over a rectangle that grows or shrinks about the middle of where the artist
 * put it; the sclera is painted over the old one first, so what shrinks away
 * leaves white behind rather than a hole.
 *
 * <b>Both ways, not only sideways.</b> The first version moved one edge only, so
 * a pupil closing in bright light became a thin column running the full height
 * of the eye — a cat's slit, not a small pupil. Sideways-only is also
 * geometrically odd: nothing about a pupil is wider than it is tall.
 *
 * Which way it can actually go is decided by the drawing rather than assumed.
 * Measured over the faces marked by hand, the iris fills the whole height of its
 * eye, so upwards there is nowhere to grow — an eye two rows tall has two rows
 * of iris. Downwards there is: a closing pupil pulls in from top and bottom
 * alike, and that is what makes a bright day read as bright rather than as a
 * character squinting.
 *
 * Everything is in model x, where one unit is one pixel of the skin and
 * <b>lower is the character's own right</b> — vanilla hangs the right arm at
 * x = −5.
 *
 * <h2>The thing that surprises: at rest the eyes are converged</h2>
 *
 * A face drawn to the usual shape puts each iris on the <em>inner</em> column of
 * its own eye, next to the nose, with the white outside it. So at rest the two
 * eyes point slightly towards each other, and a glance is not "move both eyes":
 * one of them is already as far that way as its own white allows, and it stays
 * where it is while the other comes to meet it. Both then sit on the same side
 * of their own socket, which is what "both looking right" means.
 */
public record Glance(
		float showFrom, float showTo, float showAt,
		float fillFrom, float fillTo, int white,
		Strip erase, Strip pupil) {

	/**
	 * A rectangle of one column of the skin, in model coordinates.
	 *
	 * One column rather than one texel, because a column keeps whatever the artist
	 * drew down it — an iris shaded lighter at the top, a white with a lid shadow
	 * in it. Stretching a single texel over two rows is what once turned the strip
	 * behind a glancing eye into a flat block.
	 */
	public record Strip(float from, float to, float top, float bottom, int column) {

		/** Whether there is anything of it to draw. */
		public boolean any() {
			return to - from > 0.001f && bottom - top > 0.001f;
		}
	}

	/** The face's own middle, about which the far eye is mirrored. */
	private static final float MIRROR = 24f;

	/** u 8..16 spans model x −4..4, so a column is its u less twelve. */
	private static final float MIDDLE = 12f;

	/** How far an eye travels, at most: one pixel and not a fraction more. */
	private static final float REACH = 1f;

	/** Below this there is nothing to see and a quad to draw. */
	private static final float NOTHING = 0.02f;

	/** v 8..16 spans model y −8..0, so a row is its v less sixteen. */
	private static final float HEAD = 16f;

	/**
	 * How much of itself each side of a closing pupil may take.
	 *
	 * A quarter each, so the pupil keeps half — which is not the same number, and
	 * the difference is the whole of it: taking half from each side of a
	 * one-pixel iris leaves nothing at all. A test caught that, having been
	 * written to.
	 */
	private static final float EACH_SIDE = 0.25f;

	/** Nothing to draw. */
	private static final Strip NONE = new Strip(0, 0, 0, 0, 0);

	/**
	 * What to draw for one eye, or null if this eye has nothing to do.
	 *
	 * @param face      where the eyes are, which must be able to glance
	 * @param aside     which way the character is looking, −1 its left to 1 its
	 *                  right, and any fraction between
	 * @param upDown    which way the eyes are turned upright, −1 down to 1 up
	 * @param pupil     how much wider than drawn the pupil is, −1 to 1
	 * @param rightSide whether this is the eye whose columns the reading holds,
	 *                  the other being those columns mirrored about the face
	 */
	public static Glance of(FaceReading face, float aside, float upDown, float pupil,
			boolean rightSide) {
		if (face == null || !face.canGlance()) return null;

		float from = rightSide ? face.eyeOuter() : MIRROR - face.eyeInner();
		float to = rightSide ? face.eyeInner() : MIRROR - face.eyeOuter();
		float irisFrom = rightSide ? face.irisOuter() : MIRROR - face.irisInner();
		float irisTo = rightSide ? face.irisInner() : MIRROR - face.irisOuter();
		int whiteAt = rightSide ? face.whiteAt() : (int) MIRROR - 1 - face.whiteAt();
		int irisAt = rightSide ? face.irisOuter() : (int) MIRROR - 1 - (face.irisInner() - 1);

		// A character's right is model −x, so looking right is u decreasing. Both
		// eyes move the same way in the world; how far each may go is its own
		// business, since the iris sits differently in each socket.
		float shift = Math.clamp(-aside * REACH, from - irisFrom, to - irisTo);
		boolean slides = Math.abs(shift) >= NOTHING;
		if (!slides) shift = 0;

		// The drawing, clipped by the socket it lives in.
		float showFrom = slides ? Math.max(from, from + shift) : 0;
		float showTo = slides ? Math.min(to, to + shift) : 0;
		// And the sclera behind it, at whichever edge the drawing has left.
		float fillFrom = slides ? (shift > 0 ? from : showTo) : 0;
		float fillTo = slides ? (shift > 0 ? showFrom : to) : 0;

		// The pupil, measured from where the iris now lies rather than from where it
		// was drawn — because a glance has already moved it, and half way through
		// one the white is on <em>both</em> sides of the iris. Growing outwards from
		// "the side the white started on" was the first attempt and it is wrong in
		// exactly that moment: at a full glance the two have swapped over, so the
		// pupil grew off the edge of the socket and was clipped to nothing.
		//
		// Growing outwards in both directions and letting the socket do the
		// clipping needs no such assumption. On the ordinary two-pixel eye it also
		// gives the right extremes for free: at nothing the pupil is the column the
		// artist drew, and at full it is the whole eye.
		float irisLow = Math.clamp(irisFrom + shift, from, to);
		float irisHigh = Math.clamp(irisTo + shift, from, to);
		float reach = Math.clamp(pupil, -1f, 1f);

		// A pupil exactly the size the artist drew it needs no quad at all: the
		// face already has it. Only a change is worth drawing.
		float look = Math.clamp(upDown, -1f, 1f);
		boolean resizes = Math.abs(reach) >= NOTHING || Math.abs(look) >= NOTHING;
		if (!slides && !resizes) return null;

		float irisUp = face.irisTop() - HEAD;
		float irisDown = face.irisBottom() - HEAD;
		float pupilFrom = irisLow;
		float pupilTo = irisHigh;
		float pupilUp = irisUp;
		float pupilDown = irisDown;
		if (resizes) {
			if (reach >= 0) {
				pupilFrom = Math.max(from, irisLow - reach);
				pupilTo = Math.min(to, irisHigh + reach);
				// Upwards there is usually nowhere to go, the iris being as tall as
				// its own eye, and the socket says so rather than a guess.
				pupilUp = Math.max(face.eyeTop() - HEAD, irisUp - reach);
				pupilDown = Math.min(face.eyeBottom() - HEAD, irisDown + reach);
			} else {
				// Closing pulls in from every side at once, and never past halfway:
				// a pupil closed to nothing is not a bright day, it is a character
				// with its eyes taken out.
				float sideways = Math.min(-reach, (irisHigh - irisLow) * EACH_SIDE);
				float upright = Math.min(-reach, (irisDown - irisUp) * EACH_SIDE);
				pupilFrom = irisLow + sideways;
				pupilTo = irisHigh - sideways;
				pupilUp = irisUp + upright;
				pupilDown = irisDown - upright;
			}

			// And looking up or down, which on an eye whose iris fills its own height
			// can only be the pupil giving up the far half rather than moving into
			// somewhere it does not have.
			float give = Math.abs(look) * (pupilDown - pupilUp) * EACH_SIDE * 2f;
			if (look > 0) pupilDown -= give;
			else pupilUp += give;
		}

		// The old pupil, painted out in sclera, then the new one drawn over it. Both
		// are the eye's own columns; only the rectangle changes.
		Strip erase = resizes
			? new Strip(irisLow - MIDDLE, irisHigh - MIDDLE, irisUp, irisDown, whiteAt)
			: NONE;
		Strip drawn = resizes
			? new Strip(pupilFrom - MIDDLE, pupilTo - MIDDLE, pupilUp, pupilDown, irisAt)
			: NONE;

		return new Glance(showFrom - MIDDLE, showTo - MIDDLE, showFrom - shift,
			fillFrom - MIDDLE, fillTo - MIDDLE, whiteAt, erase, drawn);
	}

	/** Whether the eye has moved at all, and so has anything to redraw. */
	public boolean slides() {
		return showTo - showFrom > 0.001f;
	}

	/** Whether any sclera shows at all: at a fraction of a pixel, barely. */
	public boolean fills() {
		return fillTo - fillFrom > 0.001f;
	}

	/** Whether the pupil is any size other than the one it was drawn. */
	public boolean resizes() {
		return pupil.any();
	}
}
