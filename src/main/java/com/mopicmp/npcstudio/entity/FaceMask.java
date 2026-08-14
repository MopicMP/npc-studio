package com.mopicmp.npcstudio.entity;

/**
 * Which pixels of a face are eye, white, brow and lash — at the resolution the
 * face was drawn, and on the layer it was drawn on.
 *
 * <h2>Why {@link EyeMap} was not enough</h2>
 *
 * Because it is eight by eight, and that was a decision about ordinary skins
 * dressed up as a fact about faces. Measured over fifty HD skins — 128 across up
 * to 2048 — <b>not one is a plain upscale of a 64-wide skin</b>. Every single one
 * draws detail inside those eight-by-eight blocks, and on a face whose eye is
 * sixteen native pixels wide, saying "this block is eye" is throwing away the
 * difference between the iris, the sclera, the lash and the highlight.
 *
 * Reducing to a finer fixed grid was the obvious answer and it is the wrong one.
 * The same measurement says why: at sixteen cells across the eye is described
 * exactly on fifteen faces of fifty, at thirty-two on eighteen, at sixty-four on
 * twenty-six. There is no grid that is right — there is only the grid the artist
 * used, which is the skin's own.
 *
 * <h2>Why a lash is its own thing</h2>
 *
 * Because it moves with the lid and a brow does not. A brow sits on the forehead
 * and is lifted by an expression; a lash is drawn on the edge of the eyelid and
 * travels down with it as the eye closes. Marked as a brow it would be lifted in
 * surprise while the eye it belongs to stayed put, which is a face nobody has.
 * Ordinary skins rarely draw one and a great many HD skins do, which is why it
 * arrives now rather than earlier.
 *
 * <h2>Why every mark carries a layer</h2>
 *
 * Because a head is drawn twice and HD skins use both halves of that. Measured
 * over the same fifty: forty-nine put something on the outer layer over the face
 * and thirty-three cover most of the face with it. What they put there is not
 * decoration — it is commonly a <em>finer version of the same feature</em>: a
 * detailed lash over a plain one, or an iris on the outer layer above a sclera on
 * the inner.
 *
 * A mask that could not say which layer a pixel is on could not be drawn from.
 * The two layers are different geometry at different depths, so moving an iris
 * that lives on the outer layer means moving it on the outer layer; doing it on
 * the face would slide the eye along underneath its own outline.
 *
 * <h2>So the mask is as big as the picture, eight times over</h2>
 *
 * {@link #size} is how many cells the face is across: eight for an ordinary skin,
 * up to two hundred and fifty-six for a 2048-wide one — times four kinds times
 * two layers. That sounds alarming and is not; see {@link #encode}. A hand-marked
 * face is a few solid blobs, and a few solid blobs run-length encode to a couple
 * of hundred bytes however large the grid they sit on. Seven of the eight masks
 * are usually empty, and an empty mask is one number.
 *
 * <h2>And it still answers the old question</h2>
 *
 * {@link #reduce()} gives back an ordinary {@link EyeMap}, so everything written
 * against eight-by-eight keeps working: the blink, the glance, the brows, the
 * detector's accuracy test. The fine truth is stored and exported from the first
 * mark; the drawing catches up to it afterwards.
 */
public final class FaceMask {

	/** What a marked pixel is. */
	public enum Kind {
		/** The iris and pupil: the part a glance moves. */
		EYE,
		/** The sclera: what shows behind the iris when it moves. */
		WHITE,
		/** The brow, which an expression lifts. */
		BROW,
		/** The lash, which travels with the lid rather than with the forehead. */
		LASH
	}

	/** Which half of the head a mark is on. */
	public enum Layer {
		/** The face itself, at u 8..16. */
		FACE,
		/** The outer layer over it, at u 40..48. */
		OVER
	}

	/** How many masks a face carries: every kind on every layer. */
	public static final int PARTS = Kind.values().length * Layer.values().length;

	/** A face nobody has marked. */
	public static final FaceMask NONE = new FaceMask(EyeMap.SIZE, null, false);

	/** The coarsest a face can be: the eight-by-eight of an ordinary skin. */
	public static final int SMALLEST = EyeMap.SIZE;

	/**
	 * The finest a face may be marked at.
	 *
	 * A 2048-wide skin, which is the largest anybody draws and the largest in the
	 * measured set. Beyond this the grid is finer than the eye can aim at and the
	 * mask stops being something a person made.
	 */
	public static final int LARGEST = 256;

	private final int size;
	private final long[][] masks;
	private final boolean authored;

	/**
	 * @param masks eight masks, indexed by {@link #part}; nulls and a null array
	 *              both mean nothing marked
	 */
	public FaceMask(int size, long[][] masks, boolean authored) {
		this.size = clampSize(size);
		this.masks = new long[PARTS][];
		for (int i = 0; i < PARTS; i++) {
			this.masks[i] = copy(masks == null || i >= masks.length ? null : masks[i], this.size);
		}
		this.authored = authored;
	}

	/** Where one kind on one layer sits in the array. */
	public static int part(Kind kind, Layer layer) {
		return kind.ordinal() * Layer.values().length + layer.ordinal();
	}

	/** How many cells the face is across. Eight is an ordinary skin. */
	public int size() {
		return size;
	}

	public boolean authored() {
		return authored;
	}

	public boolean isNone() {
		for (long[] mask : masks) {
			if (!empty(mask)) return false;
		}
		return true;
	}

	/** How many words of a {@code long[]} a face of this size needs. */
	public static int words(int size) {
		return (size * size + 63) / 64;
	}

	public boolean is(Kind kind, Layer layer, int x, int y) {
		return at(masks[part(kind, layer)], x, y);
	}

	/** Whether a pixel is this kind on either layer. */
	public boolean is(Kind kind, int x, int y) {
		return is(kind, Layer.FACE, x, y) || is(kind, Layer.OVER, x, y);
	}

	/** One mask, copied so a caller cannot reach into the record. */
	public long[] mask(Kind kind, Layer layer) {
		return copy(masks[part(kind, layer)], size);
	}

	public long[][] masks() {
		long[][] out = new long[PARTS][];
		for (int i = 0; i < PARTS; i++) out[i] = copy(masks[i], size);
		return out;
	}

	private boolean at(long[] mask, int x, int y) {
		if (mask == null || x < 0 || y < 0 || x >= size || y >= size) return false;
		int bit = y * size + x;
		return (mask[bit >>> 6] & (1L << (bit & 63))) != 0;
	}

	/**
	 * The same face said in eighths, which is what everything else still speaks.
	 *
	 * A block counts as marked if <b>any</b> of the cells inside it is, rather than
	 * most of them. Erring towards finding a feature is the right way round here for
	 * the same reason it is when sampling pixels: a lid that covers a row too many
	 * is a small ugliness, and a lid that misses the eye is a character that never
	 * blinks.
	 *
	 * Both layers fold together and the lash folds in with the brow, because the
	 * coarse map has no room for either distinction. That is a loss and it is
	 * confined to the coarse map: the mask itself keeps both, and the drawing will
	 * read the mask directly once it speaks fractions.
	 */
	public EyeMap reduce() {
		return new EyeMap(reduce(Kind.EYE), reduce(Kind.WHITE),
			reduce(Kind.BROW) | reduce(Kind.LASH), authored);
	}

	private long reduce(Kind kind) {
		long out = reduce(masks[part(kind, Layer.FACE)]);
		return out | reduce(masks[part(kind, Layer.OVER)]);
	}

	private long reduce(long[] mask) {
		if (empty(mask)) return 0L;
		if (size == EyeMap.SIZE) return mask[0];

		int step = size / EyeMap.SIZE;
		long out = 0L;
		for (int y = 0; y < EyeMap.SIZE; y++) {
			for (int x = 0; x < EyeMap.SIZE; x++) {
				boolean any = false;
				for (int dy = 0; dy < step && !any; dy++) {
					for (int dx = 0; dx < step && !any; dx++) {
						any = at(mask, x * step + dx, y * step + dy);
					}
				}
				if (any) out = EyeMap.with(out, x, y, true);
			}
		}
		return out;
	}

	/** An ordinary eight-by-eight map, said in the fine form. */
	public static FaceMask of(EyeMap map) {
		if (map == null || map.isNone()) return NONE;
		long[][] masks = new long[PARTS][];
		masks[part(Kind.EYE, Layer.FACE)] = new long[] { map.eyes() };
		masks[part(Kind.WHITE, Layer.FACE)] = new long[] { map.whites() };
		masks[part(Kind.BROW, Layer.FACE)] = new long[] { map.brows() };
		return new FaceMask(EyeMap.SIZE, masks, map.authored());
	}

	// ------------------------------------------------------------------ storage

	/**
	 * The whole mask as one short line of text.
	 *
	 * <h2>Why run lengths rather than hex</h2>
	 *
	 * Because a mask is enormous and almost empty. Written out plainly, the eyes of
	 * a 2048-wide skin are eight kilobytes of mostly zeroes, eight times over, per
	 * costume — and that has to sit in a save file and travel to every client.
	 * Written as the lengths of its runs, the very same mask is a few hundred bytes,
	 * because what a person marks is a handful of solid blobs and a solid blob is
	 * two numbers however large the grid under it. A mask nobody touched — and seven
	 * of the eight usually are — is a single number.
	 *
	 * The form is deliberately plain text: {@code size:runs/runs/…}, each run a hex
	 * count, alternating clear and marked and starting clear. It goes into JSON
	 * without escaping, it survives being looked at, and a corrupt one is obvious
	 * rather than subtly wrong.
	 */
	public String encode() {
		if (isNone()) return "";
		StringBuilder text = new StringBuilder(Integer.toHexString(size)).append(':');
		for (int i = 0; i < PARTS; i++) {
			if (i > 0) text.append('/');
			text.append(runs(masks[i]));
		}
		return text.toString();
	}

	private String runs(long[] mask) {
		if (empty(mask)) return "";
		StringBuilder text = new StringBuilder();
		int bits = size * size;
		int at = 0;
		boolean on = false;
		while (at < bits) {
			int run = 0;
			while (at + run < bits && bit(mask, at + run) == on) run++;
			if (text.length() > 0) text.append('.');
			text.append(Integer.toHexString(run));
			at += run;
			on = !on;
		}
		return text.toString();
	}

	private static boolean bit(long[] mask, int index) {
		return mask != null && (mask[index >>> 6] & (1L << (index & 63))) != 0;
	}

	/**
	 * A mask read back from {@link #encode}.
	 *
	 * Three groups rather than eight is a mask from before lashes and layers
	 * existed, and it means what it used to mean: eye, white and brow, all on the
	 * face itself. Somebody who marked a wardrobe under the old build keeps every
	 * costume of it.
	 *
	 * Never throws. A line that does not parse is a face nobody marked, which is
	 * exactly what an unmarked face is — a save written by a later version, or one
	 * somebody edited by hand, must not stop a world loading over a pair of eyes.
	 */
	public static FaceMask decode(String text, boolean authored) {
		if (text == null || text.isBlank()) return NONE;
		try {
			int colon = text.indexOf(':');
			if (colon <= 0) return NONE;
			int size = clampSize(Integer.parseInt(text.substring(0, colon).trim(), 16));
			String[] parts = text.substring(colon + 1).split("/", -1);
			if (parts.length != PARTS && parts.length != 3) return NONE;

			long[][] masks = new long[PARTS][];
			if (parts.length == 3) {
				masks[part(Kind.EYE, Layer.FACE)] = unruns(parts[0], size);
				masks[part(Kind.WHITE, Layer.FACE)] = unruns(parts[1], size);
				masks[part(Kind.BROW, Layer.FACE)] = unruns(parts[2], size);
			} else {
				for (int i = 0; i < PARTS; i++) masks[i] = unruns(parts[i], size);
			}
			return new FaceMask(size, masks, authored);
		} catch (RuntimeException unreadable) {
			return NONE;
		}
	}

	private static long[] unruns(String text, int size) {
		long[] mask = new long[words(size)];
		if (text.isBlank()) return mask;
		int bits = size * size;
		int at = 0;
		boolean on = false;
		for (String run : text.split("\\.")) {
			int length = Integer.parseInt(run.trim(), 16);
			if (on) {
				for (int i = at; i < Math.min(at + length, bits); i++) {
					mask[i >>> 6] |= 1L << (i & 63);
				}
			}
			at += length;
			on = !on;
			if (at >= bits) break;
		}
		return mask;
	}

	// ------------------------------------------------------------------ the rest

	/**
	 * Sizes are powers of two from eight up, because skins are.
	 *
	 * A skin is 64 wide times a power of two — that is the game's own rule, not
	 * ours — so a face is eight times the same power. Anything else came from a
	 * corrupt file and is rounded down to something that at least cannot index off
	 * the end of an array.
	 */
	private static int clampSize(int size) {
		int wanted = Math.clamp(size, SMALLEST, LARGEST);
		int power = SMALLEST;
		while (power * 2 <= wanted) power *= 2;
		return power;
	}

	private static long[] copy(long[] mask, int size) {
		int words = words(size);
		long[] out = new long[words];
		if (mask != null) System.arraycopy(mask, 0, out, 0, Math.min(words, mask.length));
		return out;
	}

	private static boolean empty(long[] mask) {
		if (mask == null) return true;
		for (long word : mask) {
			if (word != 0) return false;
		}
		return true;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof FaceMask that)) return false;
		if (size != that.size || authored != that.authored) return false;
		for (int i = 0; i < PARTS; i++) {
			if (!java.util.Arrays.equals(masks[i], that.masks[i])) return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = java.util.Objects.hash(size, authored);
		for (long[] mask : masks) hash = hash * 31 + java.util.Arrays.hashCode(mask);
		return hash;
	}

	@Override
	public String toString() {
		return "FaceMask[" + size + "x" + size + (authored ? " by hand]" : " read]");
	}
}
