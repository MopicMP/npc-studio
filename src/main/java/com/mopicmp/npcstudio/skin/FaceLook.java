package com.mopicmp.npcstudio.skin;

import com.mopicmp.npcstudio.entity.EyeMap;

/**
 * Working out where a face keeps its eyes, from the face alone.
 *
 * <h2>Why this is a function of sixty-four numbers and nothing else</h2>
 *
 * It takes a face as plain colours and returns a map. No texture, no image
 * class, no game — so it can be run against every skin anybody has, in a test,
 * and asked how often it is right. The reading it replaces lived where nothing
 * could call it, and was therefore never measured; it was tuned by looking at
 * one character in one world.
 *
 * <h2>The rule everything turns on: the white is lighter than the eye</h2>
 *
 * The version before this one asked one question of each pixel — is it far from
 * the face's own colour — and then sorted what it found by position. That is why
 * it swept the white of the eye, the lash and the brow into one blob and called
 * all of it an eye: from the face's colour, all of them are simply "not skin".
 * Measured against faces marked by hand it agreed about the eyes twice in
 * thirty-eight.
 *
 * The thing it was missing is not a position, it is a <b>contrast</b>. On every
 * one of the hundred and eighteen eyes a person marked, the white was lighter
 * than the iris beside it. Not once the other way round, not once equal. So an
 * eye is not a lone pixel to be classified — it is a <b>pair</b>, a lighter
 * pixel outside a darker one, and finding the pair settles which is which
 * without any appeal to where it sits.
 *
 * Lightness against the <em>face</em> settles nothing, and this is why the old
 * reading could not have got there by tuning: the white is lighter than the skin
 * only fifty-seven times in a hundred and twenty-six. It is the two pixels
 * against each other that carries the answer, not either against the complexion.
 *
 * <h2>What is then built on the pair</h2>
 *
 * A row is believed when both sides show such a pair, mirrored, with something
 * between them that is neither — which is what a mouth fails, a mouth being one
 * colour that crosses the middle. The band is one row or two, taken by looking
 * at the row above and the row below for the same pair in the same columns. The
 * brow is the stroke above it, and it is found by <em>its</em> colour: whatever
 * lies directly above the eye, followed outwards and upwards for as long as the
 * colour holds. That is what a brow is — one stroke of one colour — and it is
 * why the brow no longer has to look like an eye to be found.
 *
 * <h2>Saying "I do not know" is a real answer</h2>
 *
 * A blink in the wrong place is worse than no blink: one is a character who does
 * not blink, the other is a rectangle of forehead stamped over somebody's visor
 * every few seconds. So a face with no such pair on it returns
 * {@link EyeMap#NONE} and is left alone until somebody marks it by hand.
 *
 * <h2>Measured, not asserted</h2>
 *
 * Against thirty-eight faces marked by hand in game, this agrees exactly about
 * the eyes on twenty-nine, the whites on twenty-eight and the brows on
 * twenty-four,
 * and finds the eye's rows — which is all a blink needs — on twenty-nine. Those
 * numbers are checked on every build by {@code FaceLookAccuracyTest}, which
 * reads the marked faces out of {@code test/Eyes/faces.txt}.
 */
public final class FaceLook {

	/**
	 * How much lighter the white has to be than the iris beside it.
	 *
	 * Low on purpose. A pair drawn in two shades of one colour is common — on a
	 * pale mask the whole eye is light and the difference is a dozen levels — and
	 * what keeps this from finding pairs everywhere is not the size of the step but
	 * everything else a row has to satisfy. Raising it to twenty loses four faces
	 * outright.
	 */
	private static final int CONTRAST = 4;

	/**
	 * How different from either eye the pixels between them must be.
	 *
	 * This is the test a mouth fails: a mouth is one run of one colour lying across
	 * the middle of the face, so its "pair" has itself in between. A nose bridge
	 * that is merely shaded does not fail it, which is the point — the middle has
	 * to be a different thing, not a lighter one.
	 */
	private static final int MIDDLE = 15;

	/** What a row is worth for being drawn the same on both sides. */
	private static final int MIRROR = 120;

	/** And for not running off the side of the head. */
	private static final int EDGE = 40;

	/**
	 * How much weight the usual place for an eye carries, at most.
	 *
	 * Counted over the faces marked by hand: the top row of the eye was row five
	 * twenty-four times, row six five times, row four four times, row three once
	 * and row one twice. So this is worth having and worth keeping small — it
	 * breaks a tie between two believable rows, and it is exactly what stops a
	 * decorative pair up by the hairline from winning over the eyes, but it must
	 * never be able to invent a pair where there is none.
	 */
	private static final int PLACE = 60;

	private static final int[] LIKELY_ROW = { 0, 1, 1, 3, 6, 8, 6, 2 };

	/** How close to the colour above the eye a pixel must be to be that brow. */
	private static final int BROW = 20;

	/** How far up a brow is followed. Two rows is a thick brow and a fringe. */
	private static final int BROW_ROWS = 2;

	private FaceLook() { }

	/**
	 * One reading of one row: where the pair is, and how much it is believed.
	 *
	 * The columns are the four that matter — the white and the iris of each eye —
	 * and everything else is derived from them, which is why they travel together.
	 */
	private record Pair(int score, int whiteLeft, int eyeLeft, int whiteRight, int eyeRight) {

		boolean sameColumns(Pair other) {
			return other != null && whiteLeft == other.whiteLeft && eyeLeft == other.eyeLeft
				&& whiteRight == other.whiteRight && eyeRight == other.eyeRight;
		}
	}

	/**
	 * Reads a face.
	 *
	 * @param face the eight-by-eight face as ARGB, row by row
	 * @return where its eyes, whites and brows are, or {@link EyeMap#NONE} when the
	 *         face does not read as one — which is not a failure, it is the honest
	 *         answer for a visor
	 */
	public static EyeMap read(int[] face) {
		if (face == null || face.length < EyeMap.SIZE * EyeMap.SIZE) return EyeMap.NONE;

		Pair best = null;
		int bestRow = -1;
		int bestScore = Integer.MIN_VALUE;
		for (int y = 0; y < EyeMap.SIZE; y++) {
			Pair pair = readRow(face, y);
			if (pair == null) continue;
			int score = pair.score() + LIKELY_ROW[y] * PLACE / 8;
			if (score > bestScore) {
				bestScore = score;
				best = pair;
				bestRow = y;
			}
		}
		if (best == null) return EyeMap.NONE;

		// An eye is one row or two. The second is taken when the row above or below
		// shows the same pair in the same columns — which is also what tells a
		// two-row eye from a one-row eye with a brow over it, since a brow is one
		// colour across and has no lighter half.
		int top = bestRow;
		int bottom = bestRow;
		if (bestRow + 1 < EyeMap.SIZE && best.sameColumns(readRow(face, bestRow + 1))) bottom++;
		if (bestRow - 1 >= 0 && best.sameColumns(readRow(face, bestRow - 1))) top--;

		long eyes = 0;
		long whites = 0;
		for (int y = top; y <= bottom; y++) {
			eyes = EyeMap.with(EyeMap.with(eyes, best.eyeLeft(), y, true), best.eyeRight(), y, true);
			whites = EyeMap.with(EyeMap.with(whites, best.whiteLeft(), y, true),
				best.whiteRight(), y, true);
		}

		return new EyeMap(eyes, whites, brows(face, best, top, bottom), false);
	}

	/**
	 * The brow, found by its own colour rather than by its shape.
	 *
	 * Whatever sits directly above the eye is taken as the brow's colour, and the
	 * brow is then everything of that colour running outwards from there and
	 * upwards from there. It is the same idea as the pair and it works for the same
	 * reason: a brow is one stroke of one colour, and asking "what colour is this
	 * stroke" is a question a drawing can answer, where "is this shaped like a pair
	 * of eyes" is not — brows are wider than eyes, they run out to the side of the
	 * head, and they touch the hair. Requiring them to look like eyes is why the
	 * reading before this found brows on one face in thirty-eight.
	 */
	private static long brows(int[] face, Pair eyes, int top, int bottom) {
		if (top == 0) return 0;

		int seed = face[(top - 1) * EyeMap.SIZE + eyes.eyeLeft()];
		// Skin above the eye is a forehead, not a brow. This is the one place the
		// face's own colour is still asked about, and it is asked about one pixel.
		if (!far(seed, complexion(face))) return 0;

		long brows = 0;
		for (int y = top - 1; y >= 0 && y > top - 1 - BROW_ROWS; y--) {
			if (!like(face, eyes.eyeLeft(), y, seed) || !like(face, eyes.eyeRight(), y, seed)) break;
			// A row of one colour all the way across is a fringe. Taking it would put
			// a brow over somebody's whole forehead.
			boolean solid = true;
			for (int x = eyes.eyeLeft() + 1; x < eyes.eyeRight(); x++) {
				if (!like(face, x, y, seed)) solid = false;
			}
			if (solid) break;

			brows = run(face, brows, eyes.eyeLeft(), y, -1, seed);
			brows = run(face, brows, eyes.eyeRight(), y, 1, seed);
		}

		// And the end of the brow, which lies beside the eye rather than above it —
		// beside its <em>top</em> row and no further down.
		//
		// This one line was worth eleven faces. Taking the flank on every row of a
		// two-row eye put a brow pixel down the side of the lower row as well, and a
		// person marking the same face never does: the brow slopes down past the
		// corner of the eye and stops. Measured, it took the brows from thirteen
		// faces in thirty-eight to twenty-four, and the pixels it guesses from
		// three-quarters right to nine-tenths.
		brows = run(face, brows, eyes.whiteLeft() - 1, top, -1, seed);
		brows = run(face, brows, eyes.whiteRight() + 1, top, 1, seed);
		return brows;
	}

	/** Marks pixels of the brow's colour, outwards from a starting column. */
	private static long run(int[] face, long brows, int from, int y, int step, int seed) {
		for (int x = from; x >= 0 && x < EyeMap.SIZE && like(face, x, y, seed); x += step) {
			brows = EyeMap.with(brows, x, y, true);
		}
		return brows;
	}

	private static boolean like(int[] face, int x, int y, int colour) {
		return apart(face[y * EyeMap.SIZE + x], colour) <= BROW;
	}

	/**
	 * The likeliest pair of eyes on one row, or null if the row shows none.
	 *
	 * Every lighter-then-darker step on the left is tried against every one on the
	 * right, because the best-contrasting step is not always the eye: on a face
	 * with a dark outline the step from outline to cheek is the sharpest thing in
	 * the row. What settles it is the pair the two sides agree on.
	 */
	private static Pair readRow(int[] face, int y) {
		Pair best = null;
		for (int whiteLeft = 0; whiteLeft < EyeMap.SIZE / 2; whiteLeft++) {
			int eyeLeft = whiteLeft + 1;
			if (!steps(face, whiteLeft, eyeLeft, y)) continue;

			for (int whiteRight = EyeMap.SIZE - 1; whiteRight >= EyeMap.SIZE / 2; whiteRight--) {
				int eyeRight = whiteRight - 1;
				if (!steps(face, whiteRight, eyeRight, y)) continue;
				if (eyeRight - eyeLeft < 2) continue;
				if (!clearBetween(face, y, eyeLeft, eyeRight)) continue;

				int score = Math.min(step(face, whiteLeft, eyeLeft, y),
					step(face, whiteRight, eyeRight, y));
				if (whiteLeft == EyeMap.SIZE - 1 - whiteRight
					&& eyeLeft == EyeMap.SIZE - 1 - eyeRight) score += MIRROR;
				if (eyeLeft >= 1 && eyeRight <= EyeMap.SIZE - 2) score += EDGE;

				if (best == null || score > best.score()) {
					best = new Pair(score, whiteLeft, eyeLeft, whiteRight, eyeRight);
				}
			}
		}
		return best;
	}

	/** How much lighter the white is than the iris, or nought if it is not. */
	private static int step(int[] face, int white, int eye, int y) {
		int lighter = face[y * EyeMap.SIZE + white];
		int darker = face[y * EyeMap.SIZE + eye];
		if ((lighter >>> 24) < 128 || (darker >>> 24) < 128) return 0;
		return light(lighter) - light(darker);
	}

	private static boolean steps(int[] face, int white, int eye, int y) {
		return step(face, white, eye, y) >= CONTRAST;
	}

	/** Whether what lies between the eyes is neither of them. */
	private static boolean clearBetween(int[] face, int y, int eyeLeft, int eyeRight) {
		int left = face[y * EyeMap.SIZE + eyeLeft];
		int right = face[y * EyeMap.SIZE + eyeRight];
		for (int x = eyeLeft + 1; x < eyeRight; x++) {
			int colour = face[y * EyeMap.SIZE + x];
			if (apart(colour, left) < MIDDLE || apart(colour, right) < MIDDLE) return false;
		}
		return true;
	}

	/** How far apart two colours are, by their furthest channel. */
	public static int apart(int a, int b) {
		return Math.max(Math.max(
			Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)),
			Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))),
			Math.abs((a & 0xFF) - (b & 0xFF)));
	}

	/** How light a colour is, weighted the way an eye sees it. */
	static int light(int colour) {
		return (((colour >> 16) & 0xFF) * 30 + ((colour >> 8) & 0xFF) * 59 + (colour & 0xFF) * 11)
			/ 100;
	}

	/** How far from the face's own colour a pixel must be to be a feature. */
	private static final int FEATURE = 60;

	/**
	 * The face's own colour.
	 *
	 * Counted over the cheeks and chin, not over the middle and not over all of
	 * it. The top rows are hair on anybody with a fringe and the outer columns are
	 * an outline on any skin drawn with one.
	 *
	 * The middle was tried and is not enough. On a face framed closely by hair
	 * there is more hair than skin inside it, so the hair won the count outright,
	 * the actual face read as a marking, and the whole reading inverted — measured
	 * on a real skin, not imagined. The lower middle is the one patch that is
	 * cheek on nearly every drawing.
	 *
	 * Only the brow still asks about this. Everything to do with the eye is settled
	 * between neighbouring pixels, which is what made the reading work on faces
	 * whose "skin" this gets wrong — a mask, a visor, a face of fur.
	 */
	static final int CHEEK_TOP = 4;
	static final int CHEEK_BOTTOM = 7;
	static final int CHEEK_LEFT = 2;
	static final int CHEEK_RIGHT = 5;

	public static int complexion(int[] face) {
		int best = face[CHEEK_TOP * EyeMap.SIZE + CHEEK_LEFT];
		int bestCount = 0;
		for (int y = CHEEK_TOP; y <= CHEEK_BOTTOM; y++) {
			for (int x = CHEEK_LEFT; x <= CHEEK_RIGHT; x++) {
				int colour = face[y * EyeMap.SIZE + x];
				if ((colour >>> 24) < 128) continue;
				int count = 0;
				for (int j = CHEEK_TOP; j <= CHEEK_BOTTOM; j++) {
					for (int i = CHEEK_LEFT; i <= CHEEK_RIGHT; i++) {
						if (face[j * EyeMap.SIZE + i] == colour) count++;
					}
				}
				if (count > bestCount) {
					bestCount = count;
					best = colour;
				}
			}
		}
		return best;
	}

	/** Whether a colour is far enough from the face's own to be a feature. */
	static boolean far(int colour, int skin) {
		if ((colour >>> 24) < 128) return false;
		return apart(colour, skin) > FEATURE;
	}
}
