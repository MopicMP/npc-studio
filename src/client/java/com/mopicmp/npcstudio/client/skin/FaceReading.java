package com.mopicmp.npcstudio.client.skin;

/**
 * What a skin's face actually has on it.
 *
 * Everything about the eyes was guessed at until now — the blink assumed eyes
 * two rows below the middle and a forehead above them, because that is where a
 * skin drawn to the usual shape puts them. Most skins are, and for those the
 * guess is right. For the rest it is a rectangle of forehead stamped over
 * somebody's visor every few seconds.
 *
 * So the skin is read instead. The rules are deliberately blunt, because a
 * clever reading that is wrong is worse than a plain one that knows it: find
 * the face's own colour, call anything far from it a feature, and see whether
 * the features sit where eyes and brows sit. Nothing here needs the skin to
 * have been prepared by anybody.
 */
public record FaceReading(
		boolean hasEyes,
		int eyeTop,
		int eyeBottom,
		int eyeInner,
		int eyeOuter,
		boolean hasBrows,
		int browTop,
		int browBottom,
		int browColour,
		boolean prepared,
		int irisInner,
		int irisOuter,
		int whiteAt,
		int irisTop,
		int irisBottom,
		int browInner,
		int browOuter) {

	/** Nothing recognisable: no blinking, no brows, leave the face alone. */
	public static final FaceReading BLANK =
		new FaceReading(false, 0, 0, 0, 0, false, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0);

	/**
	 * Whether the eye is drawn in two parts, so a glance has somewhere to go.
	 *
	 * A gaze is not painted, it is <b>moved</b>: the iris is covered over with the
	 * white beside it and drawn again a column across. That needs the two to be
	 * known apart, and on a face where they are not — a solid dot of an eye, a
	 * visor, anything the reading was unsure of — the character simply looks
	 * straight ahead, which is what it does now and nobody has complained.
	 */
	public boolean canGlance() {
		return hasEyes && whiteAt > 0 && irisInner > irisOuter && eyeInner > eyeOuter;
	}

	/**
	 * Whether the brows are known well enough to be moved.
	 *
	 * A brow is moved the same way a gaze is: its own pixels, drawn a little higher
	 * or lower, with the place they came from painted out. That needs the brow's
	 * <em>columns</em> as well as its rows — the rows alone are enough to know a
	 * brow is there and not enough to pick it up. On a face where the reading found
	 * only a band, the brows stay where the artist drew them, which is what they
	 * have always done.
	 */
	public boolean canMoveBrows() {
		return hasBrows && browInner > browOuter && browBottom > browTop;
	}

	/**
	 * The face on a skin, in sixty-fourths.
	 *
	 * A skin at 128 or 256 across is the same layout drawn larger, so everything
	 * is measured in the proportions of a 64-wide skin and scaled on the way in.
	 * That is why this returns rows and columns in those units rather than in
	 * whatever the picture happens to be.
	 */
	public static final int FACE_LEFT = 8;
	public static final int FACE_TOP = 8;
	public static final int FACE_SIZE = 8;

	/**
	 * Where a prepared skin keeps its extra eyes.
	 *
	 * Fresh Moves paints eyes into a corner of the skin that nothing else uses,
	 * and its resource pack draws them as separate geometry. We do not need those
	 * pixels ourselves, but finding them tells us the skin was made for that pack
	 * — and somebody who went to the trouble of preparing a skin should not have
	 * our guesswork applied on top of their work.
	 */
	public static final int PREPARED_LEFT = 4;
	public static final int PREPARED_TOP = 4;
	public static final int PREPARED_SIZE = 4;

	/** A pixel is a feature if it is this far from the face's own colour. */
	private static final int FEATURE = 60;

	/**
	 * The part of the face that is reliably face.
	 *
	 * Not the whole square and not the corners, and both of those were tried on
	 * real skins before this was. The top rows are hair on anybody with a fringe.
	 * The outer columns are an outline on any skin drawn with one — and a dark
	 * outline is common enough that it won the count on the very first skin this
	 * was tested against, which made the actual cheek colour read as a marking and
	 * threw the whole face away.
	 *
	 * What is left is the middle: cheeks, nose and the space between the eyes.
	 * Twenty-four pixels, of which the eyes can only ever be a few, so the count
	 * comes out as skin on both of the skins that were failing.
	 */
	private static final int CHEEK_TOP = 2;
	private static final int CHEEK_BOTTOM = 7;
	private static final int CHEEK_LEFT = 2;
	private static final int CHEEK_RIGHT = 5;

	/** How much of the face to search: the middle rows, where eyes live on any face. */
	private static final int SEARCH_TOP = 2;
	private static final int SEARCH_BOTTOM = 7;

	/**
	 * The same thing, said by a map of pixels rather than by a band of rows.
	 *
	 * <h2>Why the reading below is no longer what the game blinks with</h2>
	 *
	 * Because it was measured. Against thirty-eight faces marked by hand in the
	 * eye grid, the reading below puts the lid on the right rows <b>four times</b>;
	 * {@link com.mopicmp.npcstudio.skin.FaceLook} does it twenty-nine times. It
	 * says something about thirty-six of them either way, so the difference is not
	 * caution — it is being wrong confidently, several times a second, on most
	 * faces.
	 *
	 * The record itself is kept because everything downstream speaks it: a lid is
	 * still a rectangle over a pair of columns for a pair of rows, and that has not
	 * changed. What changed is who works out which rows.
	 *
	 * The lid comes down over the eye <em>and</em> its white, because that is what
	 * a lid does — the two are marked apart for a glance, which moves what is
	 * inside the white, and together for a blink.
	 */
	public static FaceReading of(com.mopicmp.npcstudio.entity.EyeMap map) {
		if (map == null || map.isNone()) return BLANK;

		long lids = map.eyes() | map.whites();
		int[] rows = com.mopicmp.npcstudio.entity.EyeMap.rows(lids);
		if (rows == null) return BLANK;

		// Taken from the left half and mirrored, the way the lid is drawn. A face
		// with an eye on one side only — a wink, a patch — therefore blinks with
		// both, which is the same compromise the drawing itself makes.
		int outer = FACE_SIZE / 2;
		int inner = 0;
		for (int x = 0; x < FACE_SIZE / 2; x++) {
			for (int y = rows[0]; y <= rows[1]; y++) {
				if (!com.mopicmp.npcstudio.entity.EyeMap.at(lids, x, y)) continue;
				outer = Math.min(outer, x);
				inner = Math.max(inner, x + 1);
			}
		}
		if (inner == 0) {
			// Nothing on the left: mirror whatever the right half has.
			for (int x = FACE_SIZE / 2; x < FACE_SIZE; x++) {
				for (int y = rows[0]; y <= rows[1]; y++) {
					if (!com.mopicmp.npcstudio.entity.EyeMap.at(lids, x, y)) continue;
					outer = Math.min(outer, FACE_SIZE - 1 - x);
					inner = Math.max(inner, FACE_SIZE - x);
				}
			}
		}
		if (inner == 0) return BLANK;

		// The iris on its own, and one column that is certainly white. A glance
		// needs both: what to move, and what to cover its old place with.
		int irisOuter = FACE_SIZE / 2;
		int irisInner = 0;
		for (int x = 0; x < FACE_SIZE / 2; x++) {
			for (int y = rows[0]; y <= rows[1]; y++) {
				if (!com.mopicmp.npcstudio.entity.EyeMap.at(map.eyes(), x, y)) continue;
				irisOuter = Math.min(irisOuter, x);
				irisInner = Math.max(irisInner, x + 1);
			}
		}
		int white = -1;
		for (int x = 0; x < FACE_SIZE / 2 && white < 0; x++) {
			for (int y = rows[0]; y <= rows[1]; y++) {
				if (com.mopicmp.npcstudio.entity.EyeMap.at(map.whites(), x, y)
					&& !com.mopicmp.npcstudio.entity.EyeMap.at(map.eyes(), x, y)) {
					white = x;
					break;
				}
			}
		}

		// Which rows the iris itself occupies, which is not always the whole eye —
		// and is what a pupil closing down has to shrink within.
		int[] irisRows = com.mopicmp.npcstudio.entity.EyeMap.rows(map.eyes());

		int[] brows = com.mopicmp.npcstudio.entity.EyeMap.rows(map.brows());

		// The brow's own columns, the same way and for the same reason the eye's
		// were: a brow that is only a band of rows can be recognised and cannot be
		// picked up and put down a pixel higher.
		int browOuter = FACE_SIZE / 2;
		int browInner = 0;
		if (brows != null) {
			for (int x = 0; x < FACE_SIZE / 2; x++) {
				for (int y = brows[0]; y <= brows[1]; y++) {
					if (!com.mopicmp.npcstudio.entity.EyeMap.at(map.brows(), x, y)) continue;
					browOuter = Math.min(browOuter, x);
					browInner = Math.max(browInner, x + 1);
				}
			}
			if (browInner == 0) {
				for (int x = FACE_SIZE / 2; x < FACE_SIZE; x++) {
					for (int y = brows[0]; y <= brows[1]; y++) {
						if (!com.mopicmp.npcstudio.entity.EyeMap.at(map.brows(), x, y)) continue;
						browOuter = Math.min(browOuter, FACE_SIZE - 1 - x);
						browInner = Math.max(browInner, FACE_SIZE - x);
					}
				}
			}
		}

		return new FaceReading(true,
			FACE_TOP + rows[0], FACE_TOP + rows[1] + 1,
			FACE_LEFT + inner, FACE_LEFT + outer,
			brows != null, FACE_TOP + (brows == null ? 0 : brows[0]),
			FACE_TOP + (brows == null ? 0 : brows[1]) + 1, 0, false,
			irisInner == 0 ? 0 : FACE_LEFT + irisInner,
			irisInner == 0 ? 0 : FACE_LEFT + irisOuter,
			white < 0 ? 0 : FACE_LEFT + white,
			irisRows == null ? FACE_TOP + rows[0] : FACE_TOP + irisRows[0],
			irisRows == null ? FACE_TOP + rows[1] + 1 : FACE_TOP + irisRows[1] + 1,
			browInner == 0 ? 0 : FACE_LEFT + browInner,
			browInner == 0 ? 0 : FACE_LEFT + browOuter);
	}

	/**
	 * Reads a face out of a skin.
	 *
	 * Kept for the tests that describe what it does, and no longer on the path
	 * anything is drawn from — see {@link #of(com.mopicmp.npcstudio.entity.EyeMap)}
	 * for why.
	 *
	 * @param pixels  a function from (x, y) in sixty-fourths to a packed colour,
	 *                so the caller deals with scaling and this deals with faces
	 */
	public static FaceReading of(Pixels pixels) {
		int skin = complexion(pixels);
		if (skin == 0) return BLANK;

		boolean prepared = anythingAt(pixels, PREPARED_LEFT, PREPARED_TOP, PREPARED_SIZE);

		// Rows where both halves of the face have something on them. One eye alone
		// is a scar or a patch; two, level with each other, are eyes.
		//
		// Every such band is collected and the likeliest kept, rather than stopping
		// at the first. Stopping at the first meant a fringe won simply by being
		// higher up the face than the eyes were, and the lid then covered the hair
		// while the eyes carried on staring.
		int top = -1;
		int bottom = -1;
		int best = Integer.MAX_VALUE;
		int runStart = -1;
		for (int row = SEARCH_TOP; row <= SEARCH_BOTTOM + 1; row++) {
			boolean pair = row <= SEARCH_BOTTOM && bothSides(pixels, skin, row);
			if (pair) {
				if (runStart < 0) runStart = row;
				continue;
			}
			if (runStart < 0) continue;

			int runEnd = row - 1;
			int score = likeness(runStart, runEnd);
			if (score < best) {
				best = score;
				top = runStart;
				bottom = runEnd;
			}
			runStart = -1;
		}
		if (top < 0) {
			return new FaceReading(false, 0, 0, 0, 0, false, 0, 0, 0, prepared, 0, 0, 0, 0, 0, 0, 0);
		}

		// Brows and eyes both look like a pair, so a brow sitting on top of an eye
		// comes back as one tall band, and a fringe on top of the brow makes it
		// taller still. They are told apart by position, which is blunt and always
		// true: <b>the eyes are the bottom of the band</b>. Nothing on a face sits
		// under the eyes and looks like a pair — brows are above them, hair is above
		// that, and the mouth has no gap in the middle so it never joins the band.
		//
		// Counting down from the top was the first attempt and it was wrong by
		// however tall the hair was: on a skin with a fringe the lid came down over
		// the eyebrows, covering something that was not an eye and leaving the eye
		// staring out from underneath.
		int browTop = top;
		boolean hasBrows = bottom - top + 1 >= 3;

		// An eye is one row or two. Taken from the bottom, so whatever else the band
		// picked up stays above and is treated as brow.
		int eyeTop = Math.max(top, bottom - 1);
		int browBottom = Math.max(top, eyeTop - 1);
		top = eyeTop;

		// Where across the face they sit, taken from the left half and mirrored —
		// faces are symmetrical, and reading one side twice is one fewer thing to
		// disagree with itself.
		int inner = 0;
		int outer = FACE_SIZE / 2;
		for (int column = 0; column < FACE_SIZE / 2; column++) {
			boolean feature = false;
			for (int row = top; row <= bottom; row++) {
				if (differs(pixels.unlike(FACE_LEFT + column, FACE_TOP + row, skin), skin)) {
					feature = true;
				}
			}
			if (feature) {
				outer = Math.min(outer, column);
				inner = Math.max(inner, column + 1);
			}
		}

		int browColour = hasBrows ? darkestIn(pixels, skin, browTop) : 0;

		return new FaceReading(true,
			FACE_TOP + top, FACE_TOP + bottom + 1,
			FACE_LEFT + inner, FACE_LEFT + outer,
			hasBrows, FACE_TOP + browTop, FACE_TOP + browBottom + 1,
			// No glance and no brow to lift: this reading never told the iris from
			// its white nor the brow from the band it sat in, which is most of why it
			// was replaced.
			browColour, prepared, 0, 0, 0, 0, 0, 0, 0);
	}

	/**
	 * The face's own colour: whichever one most of the face is.
	 *
	 * <h2>Why not the corners, which is what this used to do</h2>
	 *
	 * Because the top two corners are hair on a great many skins. A fringe made
	 * the "complexion" a blend of hair and jaw, which is a colour the face is
	 * nowhere — so every skin pixel counted as a feature, every row looked like
	 * marks all the way across, and the face was rejected. That is most of why
	 * this worked on roughly half of skins and not the other half.
	 *
	 * An average is no better: it includes the eyes and the mouth and lands
	 * somewhere nobody's skin is. What is wanted is the most common colour of the
	 * part of the face that is reliably face — see {@link #CHEEK_TOP}. Counting
	 * the whole square instead let a fringe or an outline win, which is the same
	 * failure by a different route.
	 *
	 * Shades are grouped a little before counting, because a skin drawn with
	 * shading has a dozen near-identical browns and no single one of them would
	 * win on its own.
	 */
	private static int complexion(Pixels pixels) {
		java.util.Map<Integer, int[]> tally = new java.util.HashMap<>();
		for (int y = CHEEK_TOP; y <= CHEEK_BOTTOM; y++) {
			for (int x = CHEEK_LEFT; x <= CHEEK_RIGHT; x++) {
				int colour = pixels.at(FACE_LEFT + x, FACE_TOP + y);
				if ((colour >>> 24) < 128) continue;
				// Five bits a channel: close enough that shading of one skin lands in
				// one bucket, far enough apart that skin and hair do not.
				int bucket = ((colour >> 19) & 0x1F) << 10
					| ((colour >> 11) & 0x1F) << 5 | ((colour >> 3) & 0x1F);
				int[] sum = tally.computeIfAbsent(bucket, key -> new int[4]);
				sum[0] += (colour >> 16) & 0xFF;
				sum[1] += (colour >> 8) & 0xFF;
				sum[2] += colour & 0xFF;
				sum[3]++;
			}
		}

		int[] winner = null;
		for (int[] sum : tally.values()) {
			if (winner == null || sum[3] > winner[3]) winner = sum;
		}
		if (winner == null) return 0;
		return 0xFF000000 | (winner[0] / winner[3]) << 16
			| (winner[1] / winner[3]) << 8 | (winner[2] / winner[3]);
	}

	/**
	 * How unlike a pair of eyes a band of rows is. Smaller is likelier.
	 *
	 * Two things say it, and both are blunt on purpose. Eyes are short — one row
	 * or two, with a third above them being a brow — so a tall band is a fringe or
	 * a beard rather than a face's eyes. And they sit around the middle of the
	 * face, so distance from there breaks the tie between two short bands.
	 */
	private static int likeness(int top, int bottom) {
		int rows = bottom - top + 1;
		int height = rows > 3 ? (rows - 3) * 4 : 0;
		int middle = (SEARCH_TOP + SEARCH_BOTTOM) / 2;
		return height + Math.abs(top - middle);
	}

	/**
	 * Whether a row looks like a pair of something.
	 *
	 * Markings on both sides **and a gap between them**. The gap is the whole
	 * rule, and leaving it out was the bug: a mouth is also marks on both sides,
	 * and so is the shading down a nose, so a face came back as one band running
	 * from the eyes to the chin — and the eyelid covered all of it. A pair of
	 * eyes always has a nose between them; a mouth never has anything.
	 */
	private static boolean bothSides(Pixels pixels, int skin, int row) {
		boolean left = false;
		boolean right = false;
		int middle = 0;
		for (int column = 0; column < FACE_SIZE; column++) {
			if (!differs(pixels.unlike(FACE_LEFT + column, FACE_TOP + row, skin), skin)) continue;
			if (column < FACE_SIZE / 2) left = true;
			else right = true;
			if (column == FACE_SIZE / 2 - 1 || column == FACE_SIZE / 2) middle++;
		}
		return left && right && middle < 2;
	}

	private static int darkestIn(Pixels pixels, int skin, int row) {
		int darkest = skin;
		int lowest = Integer.MAX_VALUE;
		for (int column = 0; column < FACE_SIZE; column++) {
			int colour = pixels.unlike(FACE_LEFT + column, FACE_TOP + row, skin);
			if (!differs(colour, skin)) continue;
			int brightness = ((colour >> 16) & 0xFF) + ((colour >> 8) & 0xFF) + (colour & 0xFF);
			if (brightness < lowest) {
				lowest = brightness;
				darkest = colour;
			}
		}
		return darkest;
	}

	private static boolean anythingAt(Pixels pixels, int left, int top, int size) {
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				if ((pixels.at(left + x, top + y) >>> 24) >= 128) return true;
			}
		}
		return false;
	}

	private static boolean differs(int colour, int skin) {
		if ((colour >>> 24) < 128) return false;
		int red = Math.abs(((colour >> 16) & 0xFF) - ((skin >> 16) & 0xFF));
		int green = Math.abs(((colour >> 8) & 0xFF) - ((skin >> 8) & 0xFF));
		int blue = Math.abs((colour & 0xFF) - (skin & 0xFF));
		return red + green + blue > FEATURE;
	}

	/** A skin's pixels, addressed in sixty-fourths whatever size it really is. */
	public interface Pixels {

		int at(int x, int y);

		/**
		 * The least ordinary colour in one sixty-fourth of the skin.
		 *
		 * On a skin drawn at 64 across there is one pixel there and this is that
		 * pixel. On one drawn at 128 or 256 there are four or sixteen, and taking
		 * whichever of them is furthest from the face's own colour is the whole
		 * difference between finding an eye and missing it: an eye on a detailed
		 * skin can be a line a single pixel wide, and the one pixel this used to
		 * sample was usually the cheek beside it.
		 *
		 * Erring towards finding features rather than missing them is the right way
		 * round. A feature found where there is none makes the search a little
		 * generous; a feature missed makes a character that never blinks.
		 */
		default int unlike(int x, int y, int reference) {
			return at(x, y);
		}
	}
}
