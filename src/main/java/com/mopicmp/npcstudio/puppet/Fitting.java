package com.mopicmp.npcstudio.puppet;

/**
 * Where a second variant of one slot has to sit so that it lands on the first.
 *
 * <h2>What this is for, and what it is deliberately not for</h2>
 *
 * A person places {@code eyes01} by hand. {@code eyes02} is nearly the same picture and
 * belongs in the same place — but the two were trimmed to their own edges when they were
 * exported, so their pictures are different sizes and starting them both at the same
 * corner puts the second one a few pixels out. Measured on a real set: in one pose, 30
 * slots out of 88 have variants whose canvases disagree.
 *
 * That is a well-posed question. Two variants of one thing share most of their shape, so
 * there is exactly one offset where they lie on top of each other, and the arithmetic
 * finds it exactly.
 *
 * <b>It is not for placing different parts.</b> Hair against a head, a hat against hair —
 * there is no signal there at all: a hair layer sits mostly <em>outside</em> the head's
 * silhouette, so an offset chosen to maximise overlap drags it down into the body, which
 * is worse than leaving it where the person dropped it. Two things worn together are not
 * evidence about each other. That placement is a human's, and this class must not be
 * asked to guess at it.
 *
 * <h2>Why the shape and not the colours</h2>
 *
 * Because the shape is the part the variants share. {@code eyes02} may be a different
 * colour from {@code eyes01} in every pixel and still be the same eyes in the same place;
 * matching on colour would find nothing at all. What they share is where the drawing is
 * and where it is not — so the comparison is against the alpha channel, and nothing is
 * ever read from the picture itself.
 */
public final class Fitting {

	private Fitting() { }

	/**
	 * As far as one variant may be moved to meet another.
	 *
	 * A bound, because the search costs the window squared. It is generous against what
	 * it is for: two variants of one slot differ by the few pixels their trims differ by,
	 * so anything past a couple of dozen is not a variant that landed badly, it is a
	 * variant this has no business moving. Being unable to find a far-off answer is the
	 * right failure — the near-off ones are all that were ever claimed.
	 */
	public static final int REACH = 24;

	/**
	 * Where {@code moving} sits, relative to {@code fixed}'s own corner.
	 *
	 * Both are alpha masks read row by row: {@code mask[y * wide + x]} is true where
	 * there is drawing. The answer is the offset to add to {@code moving}'s corner.
	 *
	 * <h2>What is being counted, after getting it wrong once</h2>
	 *
	 * How much of the drawing the two share, against how much drawing there is between
	 * them: the shared pixels divided by the pixels either of them has. Nothing else
	 * works, and the failure of the obvious alternative is worth writing down.
	 *
	 * Counting agreeing pixels over the ground the two happen to share sounds right and
	 * is not, because shifting a picture <em>reduces</em> that ground — part of it hangs
	 * off the edge. So the un-shifted offset compares over the widest area and wins on
	 * volume, whatever it actually lines up with. Written that way it never moved
	 * anything, and the test that said so was the first one run.
	 *
	 * Dividing by the union fixes it for the reason that matters rather than by
	 * accident: a pixel drawn by one and hanging off the far edge of the other is not
	 * agreement and not absence — it is a place where they differ, and it belongs in the
	 * denominator. Nothing is then gained by pushing a picture off the edge.
	 *
	 * <h2>Ties</h2>
	 *
	 * Broken towards not moving. Two blank masks agree everywhere and two drawings with
	 * nothing in common agree nowhere; in both cases every offset scores the same, and
	 * the answer has to be the person's own placement rather than whichever corner the
	 * search happened to start at.
	 */
	public static int[] meet(boolean[] fixed, int fixedWide, int fixedHigh,
			boolean[] moving, int movingWide, int movingHigh) {
		long fixedInk = count(fixed);
		long movingInk = count(moving);

		int bestX = 0;
		int bestY = 0;
		// The score of standing still, which is what has to be beaten rather than assumed
		// worst. A picture is left where it was put unless something is actually better.
		long bestShared = shared(fixed, fixedWide, fixedHigh, moving, movingWide, movingHigh, 0, 0);
		long bestEither = fixedInk + movingInk - bestShared;

		for (int byY = -REACH; byY <= REACH; byY++) {
			for (int byX = -REACH; byX <= REACH; byX++) {
				long share = shared(fixed, fixedWide, fixedHigh,
					moving, movingWide, movingHigh, byX, byY);
				long either = fixedInk + movingInk - share;
				// share/either against bestShared/bestEither, multiplied out: a ratio of
				// two counts compared without ever becoming a fraction, so there is no
				// rounding to be wrong about at the third decimal place.
				if (either > 0 && share * bestEither > bestShared * either) {
					bestShared = share;
					bestEither = either;
					bestX = byX;
					bestY = byY;
				}
			}
		}
		return new int[] { bestX, bestY };
	}

	private static long count(boolean[] mask) {
		long ink = 0;
		for (boolean on : mask) {
			if (on) ink++;
		}
		return ink;
	}

	/** How many pixels both have drawn, with {@code moving} shifted by this much. */
	private static long shared(boolean[] fixed, int fixedWide, int fixedHigh,
			boolean[] moving, int movingWide, int movingHigh, int byX, int byY) {
		long both = 0;
		for (int y = 0; y < movingHigh; y++) {
			int onto = y + byY;
			if (onto < 0 || onto >= fixedHigh) continue;
			for (int x = 0; x < movingWide; x++) {
				int across = x + byX;
				if (across < 0 || across >= fixedWide) continue;
				if (moving[y * movingWide + x] && fixed[onto * fixedWide + across]) both++;
			}
		}
		return both;
	}
}
