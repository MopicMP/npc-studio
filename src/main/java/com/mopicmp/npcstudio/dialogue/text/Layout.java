package com.mopicmp.npcstudio.dialogue.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each stretch of a decorated line is drawn.
 *
 * <h2>Why this had to be written at all</h2>
 *
 * Because size lives on a stretch of text rather than on a line. As long as a
 * whole line is one size, the game wraps it: one call, and the line breaks
 * itself. The moment two sizes can sit side by side, that call cannot be made —
 * it measures in one size and would break in the wrong place — and the wrapping
 * becomes ours.
 *
 * This is the price of the answer to "размер на кусок", and it is worth naming
 * plainly rather than discovering halfway through.
 *
 * <h2>Baselines, not tops</h2>
 *
 * The part that would be got wrong by not thinking about it. Large text beside
 * small text, lined up by their top edges, looks broken — the small text appears
 * to float. Writing sits on a line, and that line is what has to agree; so a row
 * finds the deepest ascent on it and every stretch is dropped to sit on that.
 *
 * <h2>Why the measuring is handed in</h2>
 *
 * So this can be tested. How wide a letter is, is a fact about a font and a
 * window; how a paragraph breaks is arithmetic. Kept apart, the arithmetic is
 * checked in milliseconds with a ruler that says every letter is six wide, and
 * the part that needs a game is one call that cannot get the wrapping wrong
 * because it does not do any.
 */
public final class Layout {

	private Layout() { }

	/** How tall one row of the game's font is, at ordinary size. */
	public static final int LINE = 9;

	/**
	 * How far the baseline sits below the top, at ordinary size.
	 *
	 * Seven of the nine; the remaining two are what descenders hang into.
	 */
	public static final int ASCENT = 7;

	/**
	 * How wide a stretch of text is at ordinary size.
	 *
	 * <p>Size is deliberately not passed. It is a multiplier this class applies
	 * itself, and a ruler that also applied it would double it — a mistake that
	 * looks like "large text is a bit too large", which is exactly the sort of
	 * wrongness nobody reports and nobody finds.
	 *
	 * <p>What the ruler does need is the font and whether it is bold, because both
	 * change how wide a letter is.
	 */
	@FunctionalInterface
	public interface Ruler {
		int width(String text, String font, boolean bold);
	}

	/**
	 * One stretch, drawn one way, at one place.
	 *
	 * Both numbers are measured from the top left of the whole block, so drawing
	 * is a loop over every piece of every row and nothing has to be added up on
	 * the way. {@code y} is the top of this stretch, not its baseline — that is
	 * what a drawing call wants, and the baseline is what {@link #place} used to
	 * work it out.
	 *
	 * <p>{@code from} is where this stretch begins in the line, counted in code
	 * points. It is carried rather than worked out afterwards because it cannot be
	 * worked out afterwards: a row that wraps drops the spaces that would have hung
	 * off its end, so the pieces laid out do not add up to the text they came from.
	 * Anything mapping a place on the screen back to a place in the text — putting
	 * the caret where somebody clicked, most of all — needs this and would be
	 * quietly wrong by a space per wrapped row without it.
	 */
	public record Piece(String text, Look look, int x, int y, int from) { }

	/** One row of the wrapped text. {@code top} is measured from the top of the whole block. */
	public record Row(List<Piece> pieces, int top, int height) { }

	/** The whole thing, and how much room it turned out to need. */
	public record Placed(List<Row> rows, int width, int height) {
		public static final Placed NOTHING = new Placed(List.of(), 0, 0);
	}

	private record Cell(int point, Look look, int at) { }

	private static final int SPACE = ' ';
	private static final int NEWLINE = '\n';

	/**
	 * Lays the words out in the room given.
	 *
	 * @param room how wide the text may be; nought or less means do not wrap
	 */
	public static Placed of(Words words, int room, Ruler ruler) {
		return of(words, room, ruler, 0);
	}

	/**
	 * Lays the words out with air between the rows.
	 *
	 * @param leading extra height under every row, the last one included
	 *
	 * Under the last as well, and that is deliberate rather than an oversight. The
	 * number is used to size a panel around the text, and a panel that hugs the
	 * bottom line while leaving room above it looks like a mistake at the bottom.
	 */
	public static Placed of(Words words, int room, Ruler ruler, int leading) {
		if (words == null || words.isEmpty()) return Placed.NOTHING;

		List<List<Cell>> rows = wrap(cellsOf(words), room, ruler);
		List<Row> out = new ArrayList<>(rows.size());
		int top = 0;
		int widest = 0;

		for (List<Cell> row : rows) {
			Row placed = place(row, top, ruler, leading);
			out.add(placed);
			top += placed.height();
			for (Piece piece : placed.pieces()) {
				widest = Math.max(widest, piece.x()
					+ Math.round(width(piece.text(), piece.look(), ruler)));
			}
		}
		return new Placed(List.copyOf(out), widest, top);
	}

	// ------------------------------------------------------------- breaking

	private static List<Cell> cellsOf(Words words) {
		List<Cell> cells = new ArrayList<>(words.length());
		int point = 0;
		for (Words.Run run : words.runs()) {
			String text = run.text();
			for (int at = 0; at < text.length(); at = text.offsetByCodePoints(at, 1)) {
				cells.add(new Cell(text.codePointAt(at), run.look(), point++));
			}
		}
		return cells;
	}

	/**
	 * Splits the text into rows.
	 *
	 * Words are kept whole and spaces go with the row they end; a row that has to
	 * break is trimmed of the spaces that were about to hang off its end, because
	 * a space drawn past the right-hand edge is a row that measures wider than it
	 * looks and pushes everything else about.
	 */
	private static List<List<Cell>> wrap(List<Cell> cells, int room, Ruler ruler) {
		List<List<Cell>> rows = new ArrayList<>();
		List<Cell> row = new ArrayList<>();
		float taken = 0;

		// Whether the row being built is owed to somebody, and it is the whole reason
		// this is not simply "emit whatever is left at the end". A row that ended
		// because a word had to be cut in half owes nothing if the word ended with it
		// — the text is finished — while a row that ended on a newline owes an empty
		// row, because that is what typing a newline at the end means. Both leave
		// exactly the same thing behind: an empty row and no more cells.
		boolean owed = true;

		int at = 0;
		while (at < cells.size()) {
			if (cells.get(at).point() == NEWLINE) {
				rows.add(trimmed(row));
				row = new ArrayList<>();
				taken = 0;
				owed = true;
				at++;
				continue;
			}

			int end = tokenEnd(cells, at);
			List<Cell> token = cells.subList(at, end);
			float wide = width(token, ruler);

			boolean spaces = cells.get(at).point() == SPACE;
			if (!spaces && room > 0 && !row.isEmpty() && taken + wide > room) {
				rows.add(trimmed(row));
				row = new ArrayList<>();
				taken = 0;
				owed = true;
			}

			// A word too long for an empty row cannot be kept whole by anybody, so it
			// is cut where it stops fitting rather than allowed to run off the edge.
			if (!spaces && room > 0 && row.isEmpty() && wide > room) {
				int cut = fits(token, room, ruler);
				row.addAll(token.subList(0, cut));
				rows.add(List.copyOf(row));
				row = new ArrayList<>();
				taken = 0;
				owed = false;
				at += cut;
				continue;
			}

			row.addAll(token);
			taken += wide;
			owed = true;
			at = end;
		}

		if (owed || !row.isEmpty() || rows.isEmpty()) rows.add(trimmed(row));
		return rows;
	}

	/** One word, or one stretch of spaces: whichever starts here. */
	private static int tokenEnd(List<Cell> cells, int from) {
		boolean spaces = cells.get(from).point() == SPACE;
		int at = from;
		while (at < cells.size()) {
			int point = cells.get(at).point();
			if (point == NEWLINE || (point == SPACE) != spaces) break;
			at++;
		}
		return at;
	}

	/**
	 * How many cells of this word fit, at least one.
	 *
	 * At least one because otherwise a room narrower than a single letter would
	 * produce a row with nothing on it, then another, for ever.
	 */
	private static int fits(List<Cell> word, int room, Ruler ruler) {
		int kept = 1;
		while (kept < word.size() && width(word.subList(0, kept + 1), ruler) <= room) kept++;
		return kept;
	}

	private static List<Cell> trimmed(List<Cell> row) {
		int end = row.size();
		while (end > 0 && row.get(end - 1).point() == SPACE) end--;
		return List.copyOf(row.subList(0, end));
	}

	// ------------------------------------------------------------- placing

	/**
	 * Turns one row of cells into stretches with places.
	 *
	 * The row is cut wherever the look changes, because that is where the drawing
	 * has to change; within a stretch everything is one colour, one face, one
	 * size, and can be handed to the game as it stands.
	 */
	private static Row place(List<Cell> cells, int top, Ruler ruler, int leading) {
		if (cells.isEmpty()) return new Row(List.of(), top, LINE + leading);

		float baseline = 0;
		float below = 0;
		for (Cell cell : cells) {
			baseline = Math.max(baseline, ASCENT * cell.look().size());
			below = Math.max(below, (LINE - ASCENT) * cell.look().size());
		}

		List<Piece> pieces = new ArrayList<>();
		float x = 0;
		int from = 0;
		while (from < cells.size()) {
			Look look = cells.get(from).look();
			int to = from;
			while (to < cells.size() && cells.get(to).look().equals(look)) to++;

			String text = textOf(cells.subList(from, to));
			pieces.add(new Piece(text, look, Math.round(x),
				top + Math.round(baseline - ASCENT * look.size()), cells.get(from).at()));
			x += width(text, look, ruler);
			from = to;
		}
		return new Row(List.copyOf(pieces), top, Math.round(baseline + below) + leading);
	}

	private static String textOf(List<Cell> cells) {
		StringBuilder out = new StringBuilder(cells.size());
		for (Cell cell : cells) out.appendCodePoint(cell.point());
		return out.toString();
	}

	// ------------------------------------------------------------ measuring

	private static float width(List<Cell> cells, Ruler ruler) {
		float total = 0;
		int from = 0;
		while (from < cells.size()) {
			Look look = cells.get(from).look();
			int to = from;
			while (to < cells.size() && cells.get(to).look().equals(look)) to++;
			total += width(textOf(cells.subList(from, to)), look, ruler);
			from = to;
		}
		return total;
	}

	private static float width(String text, Look look, Ruler ruler) {
		return ruler.width(text, look.font(), look.bold()) * look.size();
	}
}
