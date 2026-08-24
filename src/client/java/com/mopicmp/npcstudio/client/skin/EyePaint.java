package com.mopicmp.npcstudio.client.skin;

import java.util.ArrayList;
import java.util.List;

/**
 * What to draw over one eye, cut to the shape somebody marked.
 *
 * <h2>The whole idea, in one sentence</h2>
 *
 * For every cell of the marked opening, decide where its colour comes from —
 * and never write outside the opening.
 *
 * That is the rule the drawing used to break. It moved a rectangle, and a
 * rectangle round an eye contains cheek. Here the opening is a set of runs, and
 * every quad is clipped to them, so an unmarked cell inside the eye's own
 * bounding box is as safe as one on the chin.
 *
 * <h2>Three sources, in order of precedence</h2>
 *
 * <ol>
 * <li><b>The pupil</b> — where a widened or narrowed iris now reaches. Its
 *     colour is the iris's own cells, stretched or squeezed across it, so an
 *     iris with a lighter rim keeps its rim at every size.</li>
 * <li><b>The eye, moved</b> — everywhere the opening still overlaps itself once
 *     the glance has slid it. The colour is simply the cell a glance's distance
 *     away, so nothing is invented: a highlight, a lash, a lid shadow all travel
 *     along.</li>
 * <li><b>The sclera</b> — whatever is left. That is the strip a glance has
 *     vacated and the ring a narrowed pupil has given up, and it is filled with
 *     the white cell nearest the iris <em>in that row</em>.</li>
 * </ol>
 *
 * The three are disjoint by construction rather than by being drawn on top of
 * each other. That matters for more than tidiness: layering them meant four
 * quads at four depths fighting over the same pixels, which is what put the fine
 * hatching over an eye that was merely looking sideways.
 *
 * <h2>Why the sclera is taken per row</h2>
 *
 * Because a white is not white. On the face this was reported against it runs
 * from 162 at the outer corner through 195 and 211 to a clean 255 against the
 * iris, and it does that differently in each of the eye's three rows. One cell
 * stretched over the lot is a flat grey block in a drawing that has none — which
 * is precisely what "the texture is drawn wrongly when the pupil moves aside"
 * was describing.
 */
public final class EyePaint {

	/**
	 * One quad: where it goes on the face, and where its colour comes from.
	 *
	 * Both in sixty-fourths, because that is the space the marking, the skin and
	 * the head's front all agree in. A model column is its u less twelve and a
	 * model row is its v less sixteen; the two conversions live at the point the
	 * vertices are written and nowhere else.
	 */
	public record Patch(float u0, float u1, float v0, float v1,
			float su0, float su1, float sv0, float sv1) {

		/**
		 * Whether this quad would draw the skin exactly back over itself.
		 *
		 * Which most of them would, most of the time: an eye whose pupil is
		 * breathing but which is not looking anywhere has its whole sclera in the
		 * partition, sourced from where it already is. The list keeps them because a
		 * partition with holes in it is a thing that cannot be checked; the drawing
		 * throws them away, because four vertices that change nothing still cost
		 * four vertices and a depth test.
		 */
		public boolean redundant() {
			return Math.abs(u0 - su0) < 1e-4f && Math.abs(u1 - su1) < 1e-4f
				&& Math.abs(v0 - sv0) < 1e-4f && Math.abs(v1 - sv1) < 1e-4f;
		}
	}

	/** u 8..16 spans model x −4..4, so a column is its u less twelve. */
	public static final float MIDDLE = 12f;

	/** v 8..16 spans model y −8..0, so a row is its v less sixteen. */
	public static final float HEAD = 16f;

	/** Below this a quad is not worth the four vertices. */
	private static final float NOTHING = 0.001f;

	private EyePaint() { }

	/**
	 * The eye itself: moved, resized, and filled in behind.
	 *
	 * @param rows   the opening, on the side this eye is on
	 * @param glance what the eye is doing
	 */
	public static List<Patch> eye(EyeRows rows, Glance glance) {
		List<Patch> out = new ArrayList<>();
		if (rows == null || glance == null) return out;

		float shift = glance.shift();
		boolean resizes = glance.resizes();
		float pupilFrom = glance.pupil().from() + MIDDLE;
		float pupilTo = glance.pupil().to() + MIDDLE;
		float pupilTop = glance.pupil().top() + HEAD;
		float pupilBottom = glance.pupil().bottom() + HEAD;

		for (EyeRows.Row row : rows.rows()) {
			if (!row.any()) continue;
			for (float[] band : bands(row.top(), row.bottom(),
					resizes ? pupilTop : Float.NaN, resizes ? pupilBottom : Float.NaN)) {
				float v0 = band[0];
				float v1 = band[1];
				if (v1 - v0 <= NOTHING) continue;

				List<float[]> open = pairs(row.open());

				// The pupil, where it now reaches. Only in the band it occupies —
				// above and below that it has given its rows up, and those rows fall
				// through to the sclera rather than keeping an iris nobody asked for.
				List<float[]> pupil = List.of();
				if (resizes && band[2] > 0.5f && row.iris().length > 0
					&& pupilTo - pupilFrom > NOTHING) {
					pupil = intersect(List.of(new float[] { pupilFrom, pupilTo }), open);
				}

				// The eye, moved: everywhere the opening still lies over itself. The
				// iris is cut out of it whenever the pupil is being drawn separately,
				// or the old one would show through beside the new.
				List<float[]> moved = intersect(open, moved(open, shift));
				if (resizes) moved = subtract(moved, moved(pairs(row.iris()), shift));
				moved = subtract(moved, pupil);

				// And the sclera: whatever neither of them covers.
				List<float[]> filled = subtract(subtract(open, pupil), moved);

				float irisFrom = row.iris().length > 0 ? row.iris()[0] : 0;
				float irisTo = row.iris().length > 0 ? row.iris()[row.iris().length - 1] : 0;
				for (float[] piece : pupil) {
					out.add(new Patch(piece[0], piece[1], v0, v1,
						across(piece[0], pupilFrom, pupilTo, irisFrom, irisTo),
						across(piece[1], pupilFrom, pupilTo, irisFrom, irisTo), v0, v1));
				}
				for (float[] piece : moved) {
					out.add(new Patch(piece[0], piece[1], v0, v1,
						piece[0] - shift, piece[1] - shift, v0, v1));
				}
				if (Float.isNaN(row.white())) continue;
				for (float[] piece : filled) {
					out.add(new Patch(piece[0], piece[1], v0, v1,
						row.white(), row.white() + rows.texel(), v0, v1));
				}
			}
		}
		return out;
	}

	/**
	 * The eyelid: bare skin brought down over the opening, and nowhere else.
	 *
	 * Clipped to the marking like everything else here, so a closed eye is an
	 * eye-shaped patch of skin rather than a rectangle stamped across the corner
	 * of somebody's design. Each row takes its skin from beside itself, which is
	 * the shade the face actually is at that height — an eye is set in shadow on
	 * any drawing with modelling in it, and one colour for the whole lid loses
	 * that.
	 *
	 * @param shut how far down the lid is, nought to one
	 */
	public static List<Patch> lid(EyeRows rows, float top, float bottom, float shut) {
		List<Patch> out = new ArrayList<>();
		if (rows == null || shut <= 0 || bottom <= top) return out;

		float edge = top + (bottom - top) * Math.clamp(shut, 0f, 1f);
		for (EyeRows.Row row : rows.rows()) {
			if (!row.any() || Float.isNaN(row.bare())) continue;
			float v0 = Math.max(row.top(), top);
			float v1 = Math.min(row.bottom(), edge);
			if (v1 - v0 <= NOTHING) continue;
			for (float[] piece : pairs(row.open())) {
				out.add(new Patch(piece[0], piece[1], v0, v1,
					row.bare(), row.bare() + rows.texel(), v0, v1));
			}
		}
		return out;
	}

	/**
	 * The lash: the lid's own lower edge, in the colour of the eye it is shutting.
	 *
	 * <h2>Why this was the blink looking like a pupil</h2>
	 *
	 * It was a whole model pixel tall. On a face marked at eight cells across a
	 * pixel and a cell are the same thing and it is a line; on a face marked at
	 * thirty-two, a pixel is four cells and the eye itself is only three tall — so
	 * the lash covered the entire lid, in the colour of the iris, and a blink came
	 * down as a solid black rectangle.
	 *
	 * One cell was the first answer and it is not enough either — which is worth
	 * writing down, because it is the same mistake one step along. On a face marked
	 * at eight cells the eye is <b>one cell tall</b>, so "one cell" is once again
	 * the whole lid and once again the whole thing goes dark. What bounds a line at
	 * the edge of a lid is the eye it is closing, not the grid the marking happens
	 * to be on: never more than {@link #LASH} of the eye, and no more than a cell
	 * on top of that.
	 */
	public static List<Patch> lash(EyeRows rows, float top, float bottom, float shut) {
		List<Patch> out = new ArrayList<>();
		if (rows == null || shut <= 0 || bottom <= top) return out;

		float edge = top + (bottom - top) * Math.clamp(shut, 0f, 1f);
		float thick = Math.min(rows.texel(), (bottom - top) * LASH);
		for (EyeRows.Row row : rows.rows()) {
			if (!row.any() || row.iris().length == 0) continue;
			// The row the lid's edge has reached, and only that one.
			if (edge <= row.top() + NOTHING || edge > row.bottom() + NOTHING) continue;
			float v0 = Math.max(row.top(), edge - thick);
			if (edge - v0 <= NOTHING) continue;
			// The source stays a cell wide and a cell tall whatever the quad is: it
			// is one texel of iris stretched, and stretching is what makes a line at
			// a fraction of a cell possible at all.
			for (float[] piece : pairs(row.open())) {
				out.add(new Patch(piece[0], piece[1], v0, edge,
					row.iris()[0], row.iris()[0] + rows.texel(), v0, edge));
			}
		}
		return out;
	}

	/**
	 * How much of an eye a lash may be, at most.
	 *
	 * A quarter. Below that it stops reading as the edge of anything; above it the
	 * eye reads as having gone dark rather than as having closed.
	 */
	private static final float LASH = 0.25f;

	// ------------------------------------------------------------------ intervals

	/**
	 * A row cut into the bands above, inside and below the pupil's own reach.
	 *
	 * Three at most, each carrying whether the pupil belongs in it. Cutting rather
	 * than rounding to whole rows is what lets a pupil close on an eye three cells
	 * tall — rounded, it could only ever close by nothing or by a third.
	 */
	private static List<float[]> bands(float top, float bottom, float from, float to) {
		if (Float.isNaN(from) || Float.isNaN(to) || to <= from) {
			return List.of(new float[] { top, bottom, 0 });
		}
		List<float[]> out = new ArrayList<>(3);
		float inFrom = Math.max(top, from);
		float inTo = Math.min(bottom, to);
		if (inFrom > top) out.add(new float[] { top, Math.min(inFrom, bottom), 0 });
		if (inTo > inFrom) out.add(new float[] { inFrom, inTo, 1 });
		if (inTo < bottom) out.add(new float[] { Math.max(inTo, top), bottom, 0 });
		return out;
	}

	/** A flat array of bounds as a list of intervals. */
	private static List<float[]> pairs(float[] runs) {
		List<float[]> out = new ArrayList<>(runs.length / 2);
		for (int i = 0; i + 1 < runs.length; i += 2) out.add(new float[] { runs[i], runs[i + 1] });
		return out;
	}

	private static List<float[]> moved(List<float[]> runs, float by) {
		List<float[]> out = new ArrayList<>(runs.size());
		for (float[] run : runs) out.add(new float[] { run[0] + by, run[1] + by });
		return out;
	}

	private static List<float[]> intersect(List<float[]> left, List<float[]> right) {
		List<float[]> out = new ArrayList<>();
		for (float[] a : left) {
			for (float[] b : right) {
				float from = Math.max(a[0], b[0]);
				float to = Math.min(a[1], b[1]);
				if (to - from > NOTHING) out.add(new float[] { from, to });
			}
		}
		return out;
	}

	/**
	 * What is left of one set of intervals once another is taken out of it.
	 *
	 * Written the long way — a piece at a time, carrying the remainder forward —
	 * rather than by sorting and sweeping, because a row of an eye has one or two
	 * runs in it and the long way is the one that can be read and checked.
	 */
	private static List<float[]> subtract(List<float[]> from, List<float[]> take) {
		List<float[]> out = new ArrayList<>(from);
		for (float[] cut : take) {
			List<float[]> next = new ArrayList<>(out.size() + 1);
			for (float[] piece : out) {
				if (cut[1] <= piece[0] || cut[0] >= piece[1]) {
					next.add(piece);
					continue;
				}
				if (cut[0] - piece[0] > NOTHING) next.add(new float[] { piece[0], cut[0] });
				if (piece[1] - cut[1] > NOTHING) next.add(new float[] { cut[1], piece[1] });
			}
			out = next;
		}
		return out;
	}

	/** Where a point of the drawn pupil sits in the iris it was drawn from. */
	private static float across(float at, float from, float to, float sourceFrom, float sourceTo) {
		if (to - from <= NOTHING) return sourceFrom;
		return sourceFrom + (at - from) / (to - from) * (sourceTo - sourceFrom);
	}
}
