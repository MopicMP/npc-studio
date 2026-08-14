package com.mopicmp.npcstudio.entity;

/**
 * Which pixels of a face are eyes, and which are brows.
 *
 * <h2>Why a mask rather than a rectangle</h2>
 *
 * The reading this replaces described eyes as a box: a top row, a bottom row and
 * a pair of columns. That is enough to blink a face drawn to the usual shape and
 * nothing else. It cannot say that one eye is a pixel lower than the other, that
 * a lash runs out past the corner, or that an eye is three pixels of one shape
 * and two of another — and those are exactly the skins where a blink landed on
 * somebody's forehead.
 *
 * A face is eight pixels by eight. Sixty-four bits is a {@code long}, so the
 * whole truth about it fits in one, and both maps together are sixteen bytes.
 * That is small enough to keep with the costume and send over the network
 * without thinking about it, which is what lets everybody see the same face
 * rather than each client guessing separately.
 *
 * <h2>Everything is in sixty-fourths</h2>
 *
 * A skin at 128 or 256 across is the same layout drawn larger, so a face is
 * always eight by eight here and the picture is sampled into it. A mask made on
 * one resolution therefore still fits when the same character is redrawn at
 * another.
 *
 * <h2>Authored or guessed</h2>
 *
 * {@link #authored} is the difference between "a person said this is where the
 * eyes are" and "we worked it out". It is not decoration: a guess may be
 * replaced by a better guess when the detector improves, and an answer somebody
 * gave by hand may not. It is also what the tests measure against.
 */
public record EyeMap(long eyes, long whites, long brows, boolean authored) {

	/**
	 * The reading knows nothing about whites, so it says so rather than guessing.
	 *
	 * Kept because measuring for a sclera from the outside turned out to be much
	 * harder than it looks: the first attempt looked at one row of the eye and
	 * demanded something near white, and on that basis reported that four skins in
	 * thirty-three had one. Both of those were choices, not findings — a sclera
	 * drawn a row higher, or in light grey rather than white, is still a sclera.
	 * What a person marks settles it; a guess dressed as a measurement does not.
	 */
	public EyeMap(long eyes, long brows, boolean authored) {
		this(eyes, 0L, brows, authored);
	}

	/** A face nobody has read and nobody has marked. */
	public static final EyeMap NONE = new EyeMap(0L, 0L, 0L, false);

	/** A face is eight pixels square, wherever it was sampled from. */
	public static final int SIZE = 8;

	/** Where the face sits on a skin, in sixty-fourths. */
	public static final int FACE_LEFT = 8;
	public static final int FACE_TOP = 8;

	public boolean isNone() {
		return eyes == 0 && whites == 0 && brows == 0;
	}

	/** Whether the pixel at this spot of the face is marked in the given map. */
	public static boolean at(long map, int x, int y) {
		return inside(x, y) && (map & bit(x, y)) != 0;
	}

	public static long with(long map, int x, int y, boolean marked) {
		if (!inside(x, y)) return map;
		return marked ? map | bit(x, y) : map & ~bit(x, y);
	}

	public boolean isEye(int x, int y) {
		return at(eyes, x, y);
	}

	public boolean isBrow(int x, int y) {
		return at(brows, x, y);
	}

	/**
	 * Whether this pixel is the white of an eye.
	 *
	 * Marked apart from the eye rather than instead of it: the white is part of
	 * the eye for a blink — a lid comes down over all of it — and separate from it
	 * for a glance, which moves what is inside the white and leaves the white
	 * where it is.
	 */
	public boolean isWhite(int x, int y) {
		return at(whites, x, y);
	}

	private static boolean inside(int x, int y) {
		return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
	}

	private static long bit(int x, int y) {
		return 1L << (y * SIZE + x);
	}

	/** How many pixels a map marks. */
	public static int count(long map) {
		return Long.bitCount(map);
	}

	/**
	 * The rows a map touches, as first and last, or {@code null} for an empty map.
	 *
	 * The blink needs this and nothing finer: a lid comes down over whatever the
	 * eye occupies, so what matters is where the eye starts and where it ends.
	 */
	public static int[] rows(long map) {
		if (map == 0) return null;
		int first = SIZE;
		int last = -1;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (at(map, x, y)) {
					first = Math.min(first, y);
					last = Math.max(last, y);
				}
			}
		}
		return new int[] { first, last };
	}

	/**
	 * The same map mirrored left to right.
	 *
	 * Faces are drawn symmetrically far more often than not — measured at thirty-two
	 * of thirty-six real skins — so this is both what the detector leans on and
	 * what the editor offers, since marking one eye and having the other follow is
	 * most of the work saved.
	 */
	public static long mirrored(long map) {
		long flipped = 0;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (at(map, x, y)) flipped |= bit(SIZE - 1 - x, y);
			}
		}
		return flipped;
	}

	/** How much of a map survives being mirrored, from nought to one. */
	public static float symmetry(long map) {
		if (map == 0) return 1f;
		long flipped = mirrored(map);
		return (float) Long.bitCount(map & flipped) / Long.bitCount(map | flipped);
	}
}
