package com.mopicmp.npcstudio.dialogue.text;

import java.util.ArrayList;
import java.util.List;

/**
 * A line of dialogue, in stretches that are drawn differently.
 *
 * <h2>Canonical by construction</h2>
 *
 * Empty runs are dropped and neighbours that look the same are welded together,
 * in the constructor, always. So two of these that would draw identically
 * <em>are</em> equal, and writing one to a file and reading it back gives the
 * same object rather than one that merely looks the same.
 *
 * That is not tidiness. Without it, typing a word and deleting it again would
 * leave a seam in the file — two runs where there was one — and the next edit
 * would leave another. A document that grows scar tissue every time it is opened
 * is a document that eventually differs from itself for no reason anybody can
 * point at.
 *
 * <h2>Why editing does not happen here</h2>
 *
 * Runs are a good way to store text and a bad way to change it: inserting a
 * letter in the middle of a run splits it, deleting one welds two back, and
 * every operation has to remember both cases. So the editor unpacks all of this
 * into a flat list — one look per code point — changes that, and packs it back.
 * See {@link #looks()} and {@link #of(String, List)}.
 *
 * <h2>Code points, not chars</h2>
 *
 * The flat form counts code points, so a character outside the basic plane —
 * an emoji — is one thing with one look rather than two halves of a surrogate
 * pair that can be styled apart and then not be a character at all.
 */
public record Words(List<Run> runs) {

	/** One stretch of text drawn one way. */
	public record Run(String text, Look look) {
		public Run {
			if (text == null) text = "";
			if (look == null) look = Look.PLAIN;
		}
	}

	public static final Words EMPTY = new Words(List.of());

	public Words {
		runs = tidy(runs);
	}

	/** Drops what draws nothing and welds what draws the same. */
	private static List<Run> tidy(List<Run> given) {
		if (given == null || given.isEmpty()) return List.of();

		List<Run> out = new ArrayList<>(given.size());
		for (Run run : given) {
			if (run == null || run.text().isEmpty()) continue;
			int last = out.size() - 1;
			if (last >= 0 && out.get(last).look().equals(run.look())) {
				out.set(last, new Run(out.get(last).text() + run.text(), run.look()));
			} else {
				out.add(run);
			}
		}
		return List.copyOf(out);
	}

	/** A line nobody has decorated. */
	public static Words of(String plain) {
		if (plain == null || plain.isEmpty()) return EMPTY;
		return new Words(List.of(new Run(plain, Look.PLAIN)));
	}

	/**
	 * Whether this is a line nobody has decorated.
	 *
	 * Asked by the codec, and it is the whole reason existing dialogues survive
	 * this change untouched: a plain line is written back to the file as a bare
	 * string, exactly as it was written before any of this existed.
	 */
	public boolean isPlain() {
		for (Run run : runs) {
			if (!run.look().isPlain()) return false;
		}
		return true;
	}

	public boolean isEmpty() {
		return runs.isEmpty();
	}

	/** The words themselves, with nothing said about how they are drawn. */
	public String plain() {
		if (runs.size() == 1) return runs.get(0).text();
		StringBuilder out = new StringBuilder();
		for (Run run : runs) out.append(run.text());
		return out.toString();
	}

	/** How many code points there are, which is how many looks {@link #looks()} gives. */
	public int length() {
		int total = 0;
		for (Run run : runs) total += run.text().codePointCount(0, run.text().length());
		return total;
	}

	/**
	 * One look per code point, in order — the form the editor works in.
	 *
	 * Flat because editing is: inserting a letter means inserting a letter and a
	 * look at the same position, and the two lists cannot fall out of step with
	 * each other the way a caret and a run boundary can.
	 */
	public List<Look> looks() {
		List<Look> out = new ArrayList<>(length());
		for (Run run : runs) {
			String text = run.text();
			for (int at = 0; at < text.length(); at = text.offsetByCodePoints(at, 1)) {
				out.add(run.look());
			}
		}
		return out;
	}

	/**
	 * Packs the editor's flat form back into runs.
	 *
	 * @param text  the words
	 * @param looks one look per code point of {@code text}
	 * @throws IllegalArgumentException when the two do not describe the same text
	 *
	 * The count is checked rather than trusted, and the check is not defensive
	 * habit. The two lists are kept in step by every edit in the editor, so a
	 * mismatch means an edit that updated one and forgot the other — which
	 * otherwise shows up as text whose colours are shifted by one character,
	 * hours later, in a line nobody was editing at the time.
	 */
	public static Words of(String text, List<Look> looks) {
		if (text == null || text.isEmpty()) return EMPTY;
		int points = text.codePointCount(0, text.length());
		if (looks == null || looks.size() != points) {
			throw new IllegalArgumentException(
				"the text has " + points + " code points and " + (looks == null ? 0 : looks.size())
					+ " looks were given for it");
		}

		List<Run> out = new ArrayList<>();
		int point = 0;
		int start = 0;
		Look running = looks.get(0);
		for (int at = 0; at < text.length(); at = text.offsetByCodePoints(at, 1), point++) {
			Look here = looks.get(point);
			if (here.equals(running)) continue;
			out.add(new Run(text.substring(start, at), running));
			start = at;
			running = here;
		}
		out.add(new Run(text.substring(start), running));
		return new Words(out);
	}

	/** The same words with one look throughout, for "select all and make it red". */
	public Words allOf(Look look) {
		return of(plain(), java.util.Collections.nCopies(length(), look));
	}

	/**
	 * The first so many code points, keeping how each is drawn.
	 *
	 * For the line that types itself out on screen. Cutting the plain text and
	 * drawing that would type the whole line in one colour and then repaint it at
	 * the end, which reads as a fault rather than as an effect.
	 */
	public Words first(int points) {
		if (points <= 0) return EMPTY;
		if (points >= length()) return this;

		List<Run> out = new ArrayList<>();
		int left = points;
		for (Run run : runs) {
			String text = run.text();
			int have = text.codePointCount(0, text.length());
			if (have <= left) {
				out.add(run);
				left -= have;
				if (left == 0) break;
				continue;
			}
			out.add(new Run(text.substring(0, text.offsetByCodePoints(0, left)), run.look()));
			break;
		}
		return new Words(out);
	}

	/**
	 * The same line with different words, keeping the drawing where the words did
	 * not change.
	 *
	 * <h2>Why this is needed at all</h2>
	 *
	 * Because a decorated line still has to survive an ordinary text field. The
	 * panel beside the graph edits a line as a plain string, and it will go on
	 * doing that whatever the editor grows into — so replacing the words has to
	 * mean replacing the words, not throwing away the colours somebody chose.
	 *
	 * <h2>How much is kept, and why not more</h2>
	 *
	 * Whatever is unchanged at each end. Typing into the middle of a red word
	 * keeps the red on both sides and gives the new letters the drawing of what
	 * they were typed after — which is what every text editor does and what
	 * anybody would expect without being told.
	 *
	 * It is not a proper difference: a word moved from the beginning of a line to
	 * the end loses its colour. Working that out properly means matching text
	 * against text, and being clever about it would be wrong more surprisingly
	 * than being simple about it is. The real editor holds the looks as it edits
	 * and does not come through here at all.
	 */
	public Words reworded(String plain) {
		String words = plain == null ? "" : plain;
		String was = plain();
		if (was.equals(words)) return this;
		if (isPlain()) return of(words);

		int[] before = was.codePoints().toArray();
		int[] after = words.codePoints().toArray();
		List<Look> old = looks();

		int head = 0;
		while (head < before.length && head < after.length && before[head] == after[head]) head++;

		int tail = 0;
		while (tail < before.length - head && tail < after.length - head
				&& before[before.length - 1 - tail] == after[after.length - 1 - tail]) {
			tail++;
		}

		// What the new letters are drawn as: whatever they were typed after, or —
		// when they were typed at the very start — whatever came first.
		Look joining = head > 0 ? old.get(head - 1) : old.isEmpty() ? Look.PLAIN : old.get(0);

		List<Look> now = new ArrayList<>(after.length);
		for (int at = 0; at < head; at++) now.add(old.get(at));
		for (int at = head; at < after.length - tail; at++) now.add(joining);
		for (int at = after.length - tail; at < after.length; at++) {
			now.add(old.get(before.length - (after.length - at)));
		}
		return of(words, now);
	}
}
