package com.mopicmp.npcstudio.client.skin;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.entity.FaceMask;

/**
 * One eye as the person who marked it actually drew it: row by row, run by run.
 *
 * <h2>Why the box had to go</h2>
 *
 * Everything the eyes were ever accused of came from the same shortcut — the
 * marking was read as four numbers, a top, a bottom and two sides, and the
 * drawing then moved that rectangle about. On the skin these complaints were
 * made against, the marking is this:
 *
 * <pre>
 * row 16   W W W W E E E E .
 * row 17   W W W W E E E E .
 * row 18   . . W W E E E E .
 * </pre>
 *
 * The rectangle round it includes the two cells at the start of row 18, and
 * those are cheek. Nobody marked them; the box swept them up anyway; and from
 * then on they travelled with the iris, were painted over when the pupil
 * narrowed, and were dragged back into the eye by the strip that filled in
 * behind a glance. Four complaints, one rectangle.
 *
 * So the shape is kept as it was drawn. Each row of the eye is a few runs of
 * marked cells, and every quad the face draws is clipped to them. An unmarked
 * cell inside the eye's own bounding box is now exactly as untouchable as one
 * on the far side of the head, which is what "I marked this much" ought to have
 * meant from the start.
 *
 * <h2>Everything is in sixty-fourths</h2>
 *
 * Because that is what a skin's texture coordinates are, whatever size the
 * picture is: the face is the square from 8 to 16 either way, and a face marked
 * at thirty-two cells across simply has cells a quarter of a unit wide. Working
 * in cells would mean converting at every use; working in sixty-fourths means
 * the numbers here go straight onto a quad.
 */
public final class EyeRows {

	/**
	 * One row of the eye.
	 *
	 * @param top    the row's upper edge, in sixty-fourths
	 * @param bottom its lower edge
	 * @param open   the marked opening — iris and sclera together — as pairs of
	 *               bounds. What a glance may move and a lid may cover, and
	 *               nothing else on the face
	 * @param iris   the part of it that is iris, the same way. What a widening
	 *               pupil grows and a narrowing one gives back
	 * @param white  where this row's sclera is borrowed from: the marked white
	 *               cell nearest the iris, or {@code NaN} on a row with none.
	 *               Nearest rather than first, because the cell beside the iris
	 *               is the one an artist would have drawn behind it — on this
	 *               face the sclera runs from 162 at the outside to 255 against
	 *               the iris, and taking the far end of that made every eye that
	 *               looked aside leave a grey smear where the white should be
	 * @param bare   where this row's bare skin is: the first unmarked cell past
	 *               the opening's inner edge. What an eyelid is painted with
	 */
	public record Row(float top, float bottom, float[] open, float[] iris,
			float white, float bare) {

		/** Whether this row has any opening at all. */
		public boolean any() {
			return open.length > 0;
		}
	}

	/** The face's own middle, about which the far eye is mirrored. */
	public static final float MIRROR = 24f;

	private final float texel;
	private final Row[] rows;

	private EyeRows(float texel, Row[] rows) {
		this.texel = texel;
		this.rows = rows;
	}

	/** How wide one cell of this face is, in sixty-fourths. */
	public float texel() {
		return texel;
	}

	public Row[] rows() {
		return rows;
	}

	public boolean any() {
		return rows.length > 0;
	}

	/**
	 * The same eye on the other side of the face.
	 *
	 * A reflection about the middle of the face, which is where the other eye
	 * always is: the head's front spans u 8 to 16 and a model column is its u
	 * less twelve, so reflecting u about 24 puts a column at the mirror of its own
	 * position with the mirror of its own drawing under it. Both ends of every run
	 * swap over, which is why the pairs are rebuilt rather than adjusted.
	 */
	public EyeRows mirrored() {
		Row[] out = new Row[rows.length];
		for (int i = 0; i < rows.length; i++) {
			Row row = rows[i];
			out[i] = new Row(row.top(), row.bottom(),
				flip(row.open()), flip(row.iris()),
				Float.isNaN(row.white()) ? Float.NaN : MIRROR - texel - row.white(),
				Float.isNaN(row.bare()) ? Float.NaN : MIRROR - texel - row.bare());
		}
		return new EyeRows(texel, out);
	}

	private float[] flip(float[] runs) {
		float[] out = new float[runs.length];
		for (int i = 0; i < runs.length; i += 2) {
			// Reversed as well as reflected, so the runs stay in ascending order.
			int at = runs.length - 2 - i;
			out[at] = MIRROR - runs[i + 1];
			out[at + 1] = MIRROR - runs[i];
		}
		return out;
	}

	/**
	 * The eye somebody marked, read at the size they marked it.
	 *
	 * Only one half of the face is read, and mirrored to the other — the same
	 * compromise the lid has always made, and for the same reason: one drawing has
	 * to serve both eyes, so one side is the record. Which half is decided by
	 * which one has anything on it, so a face marked only on the far side is not
	 * lost.
	 *
	 * @return the rows, or null on a face nobody marked
	 */
	public static EyeRows of(FaceMask mask) {
		if (mask == null || mask.isNone()) return null;
		int size = mask.size();
		int half = Math.max(1, size / 2);
		float texel = (float) FaceReading.FACE_SIZE / size;

		boolean far = !marked(mask, size, false);
		if (far && !marked(mask, size, true)) return null;

		List<Row> rows = new ArrayList<>();
		for (int y = 0; y < size; y++) {
			float[] open = runs(mask, size, half, y, far, true);
			if (open.length == 0) continue;
			float[] iris = runs(mask, size, half, y, far, false);

			float top = FaceReading.FACE_TOP + y * texel;
			rows.add(new Row(top, top + texel, open, iris,
				white(mask, size, half, y, far, texel),
				bare(mask, size, half, y, far, texel)));
		}
		if (rows.isEmpty()) return null;
		return new EyeRows(texel, rows.toArray(new Row[0]));
	}

	/**
	 * An eye read out of a picture rather than marked, as a plain box.
	 *
	 * The reading tells an eye from a cheek and does not tell an iris from its
	 * white, so what comes back here is a row of open cells with no iris in them.
	 * That is enough for a blink and honestly not enough for a glance, which is
	 * exactly the state such a face has always been in.
	 */
	static EyeRows box(float top, float bottom, float outer, float inner) {
		if (bottom <= top || inner <= outer) return null;
		float texel = 1f;
		int count = Math.max(1, Math.round((bottom - top) / texel));
		Row[] rows = new Row[count];
		for (int i = 0; i < count; i++) {
			float rowTop = top + i * texel;
			rows[i] = new Row(rowTop, rowTop + texel,
				new float[] { outer, inner }, new float[0], Float.NaN, inner);
		}
		return new EyeRows(texel, rows);
	}

	// ------------------------------------------------------------------ reading

	/** Whether either half has anything of an eye on it. */
	private static boolean marked(FaceMask mask, int size, boolean far) {
		int half = Math.max(1, size / 2);
		for (int x = 0; x < half; x++) {
			for (int y = 0; y < size; y++) {
				if (open(mask, at(size, x, far), y)) return true;
			}
		}
		return false;
	}

	/**
	 * Which cell of the picture a position in the read half stands for.
	 *
	 * Counted outwards from the face's own edge either way, so that a face read
	 * from its far half comes back in the same order as one read from the near
	 * half: cell nought is the outermost and the last is against the nose.
	 * Everything downstream can then speak of "inner" and "outer" without asking
	 * which eye it is looking at.
	 */
	private static int at(int size, int x, boolean far) {
		return far ? size - 1 - x : x;
	}

	private static boolean open(FaceMask mask, int x, int y) {
		return mask.is(FaceMask.Kind.EYE, x, y) || mask.is(FaceMask.Kind.WHITE, x, y);
	}

	/** The runs of one row, as bounds in sixty-fourths. */
	private static float[] runs(FaceMask mask, int size, int half, int y,
			boolean far, boolean whole) {
		float texel = (float) FaceReading.FACE_SIZE / size;
		List<Float> bounds = new ArrayList<>();
		boolean on = false;
		for (int x = 0; x < half; x++) {
			int cell = at(size, x, far);
			boolean here = whole ? open(mask, cell, y) : mask.is(FaceMask.Kind.EYE, cell, y);
			if (here == on) continue;
			bounds.add(FaceReading.FACE_LEFT + x * texel);
			on = here;
		}
		if (on) bounds.add(FaceReading.FACE_LEFT + half * texel);

		float[] out = new float[bounds.size()];
		for (int i = 0; i < out.length; i++) out[i] = bounds.get(i);
		return out;
	}

	/**
	 * Where this row's sclera comes from: the white cell nearest its iris.
	 *
	 * Nearest, not first. The sclera of a face drawn with any care is shaded
	 * across its width — on the face this was reported against it runs from 162 at
	 * the outer corner to a clean 255 against the iris — so the cell an artist
	 * would have drawn behind a moved iris is the one that was touching it. Taking
	 * the first one found meant every glance left a dull grey block where the
	 * white of an eye should be.
	 */
	private static float white(FaceMask mask, int size, int half, int y,
			boolean far, float texel) {
		int nearest = -1;
		int best = Integer.MAX_VALUE;
		int irisFrom = -1;
		int irisTo = -1;
		for (int x = 0; x < half; x++) {
			if (!mask.is(FaceMask.Kind.EYE, at(size, x, far), y)) continue;
			if (irisFrom < 0) irisFrom = x;
			irisTo = x;
		}
		for (int x = 0; x < half; x++) {
			int cell = at(size, x, far);
			if (!mask.is(FaceMask.Kind.WHITE, cell, y)) continue;
			if (mask.is(FaceMask.Kind.EYE, cell, y)) continue;
			int away = irisFrom < 0 ? half - x
				: Math.min(Math.abs(x - irisFrom), Math.abs(x - irisTo));
			if (away >= best) continue;
			best = away;
			nearest = x;
		}
		return nearest < 0 ? Float.NaN : FaceReading.FACE_LEFT + nearest * texel;
	}

	/**
	 * Where this row's bare skin comes from: the first unmarked cell past the eye.
	 *
	 * Past its inner edge, which is the side the nose is on. That is bare by
	 * construction on any face with two eyes on it — a pair of eyes has a nose
	 * between them — and it is the nearest skin to the eye, so a lid painted with
	 * it is the shade the skin actually is at that height rather than a colour
	 * borrowed from the forehead.
	 */
	private static float bare(FaceMask mask, int size, int half, int y,
			boolean far, float texel) {
		int last = -1;
		for (int x = 0; x < half; x++) {
			if (open(mask, at(size, x, far), y)) last = x;
		}
		if (last < 0) return Float.NaN;
		for (int x = last + 1; x < half; x++) {
			int cell = at(size, x, far);
			boolean anything = open(mask, cell, y)
				|| mask.is(FaceMask.Kind.LASH, cell, y)
				|| mask.is(FaceMask.Kind.BROW, cell, y);
			if (!anything) return FaceReading.FACE_LEFT + x * texel;
		}
		return FaceReading.FACE_LEFT + Math.min(half - 1, last + 1) * texel;
	}
}
