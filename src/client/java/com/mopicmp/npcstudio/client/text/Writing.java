package com.mopicmp.npcstudio.client.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * Text being edited, with a caret in it.
 *
 * <h2>Why this is ours and not the game's</h2>
 *
 * Because the game has no editor for text that is drawn in more than one way.
 * {@code EditBox} is one row of one colour; {@code MultiLineEditBox} is many rows
 * of one colour. Neither can be persuaded otherwise — the colour is not a thing
 * they store per character, it is a thing they are handed when they draw — so
 * either was going to be replaced the moment a second colour existed.
 *
 * <h2>Flat, one look per code point</h2>
 *
 * Not runs. Runs are the right way to <em>store</em> text and the wrong way to
 * change it: inserting a letter in the middle of a run splits it, deleting one
 * welds two back, and every operation has to remember both cases and get the
 * caret right across the seam. Flat, an insertion is an insertion in two lists
 * that cannot fall out of step, and the runs are rebuilt on the way out.
 *
 * Code points rather than chars for the reason they are used everywhere else in
 * this line of work: a character outside the basic plane is one thing to a
 * person, and a caret that can be put in the middle of one is a caret that can
 * cut a character in half.
 *
 * <h2>Why it knows nothing about the screen</h2>
 *
 * So it can be tested. Where the caret goes when a key is pressed is a rule;
 * where the caret is on the screen is a fact about a font. Only the first is
 * here, and it is the one with all the edge cases in it.
 */
public final class Writing {

	/** One code point and how it is drawn. */
	private record Cell(int point, Look look) { }

	private final List<Cell> cells = new ArrayList<>();

	private int caret;

	/**
	 * The other end of the selection.
	 *
	 * Equal to the caret when nothing is selected. Kept as a position rather than
	 * as a start and a length because a selection has a direction: shift-left from
	 * the middle and then shift-right again has to shrink back to nothing rather
	 * than turn inside out.
	 */
	private int anchor;

	/**
	 * What the next letter typed will look like, when nothing is selected.
	 *
	 * Pressing bold with an empty selection has to mean something, and what it
	 * means is "from here on". Without this it would mean nothing at all, and a
	 * person would press it, type, see plain text, and conclude the button is
	 * broken. Cleared by moving the caret, because "from here on" stops being
	 * true the moment "here" moves.
	 */
	private Look pending;

	// -------------------------------------------------------------- undoing

	private record Step(List<Cell> cells, int caret, int anchor) { }

	/**
	 * How far back it is worth being able to go.
	 *
	 * A line of dialogue, not a chapter. Deep enough that nobody reaches the end of
	 * it by accident, shallow enough that the copies cost nothing worth measuring.
	 */
	private static final int REMEMBERED = 64;

	private final java.util.Deque<Step> before = new java.util.ArrayDeque<>();
	private final java.util.Deque<Step> after = new java.util.ArrayDeque<>();

	/**
	 * What kind of change the last one was, so a run of them counts as one.
	 *
	 * Undo that takes back one letter is undo nobody uses: typing a sentence and
	 * changing your mind about it is one decision, and it should cost one press.
	 * So a step is kept only when the kind of editing changes, or when the caret is
	 * somewhere other than where the last edit left it — which is exactly the two
	 * moments a person would call "a separate thing I did".
	 */
	private enum Kind { NONE, TYPE, REMOVE, DRESS }

	private Kind lastKind = Kind.NONE;
	private int lastAt = -1;

	private void remember(Kind kind) {
		if (kind == lastKind && caret == lastAt) return;
		before.push(new Step(List.copyOf(cells), caret, anchor));
		while (before.size() > REMEMBERED) before.removeLast();
		after.clear();
		lastKind = kind;
	}

	/** @return whether there was anything to take back */
	public boolean undo() {
		if (before.isEmpty()) return false;
		after.push(new Step(List.copyOf(cells), caret, anchor));
		restore(before.pop());
		return true;
	}

	public boolean redo() {
		if (after.isEmpty()) return false;
		before.push(new Step(List.copyOf(cells), caret, anchor));
		restore(after.pop());
		return true;
	}

	private void restore(Step step) {
		cells.clear();
		cells.addAll(step.cells());
		caret = step.caret();
		anchor = step.anchor();
		pending = null;
		// So the next letter typed starts a step of its own rather than being welded
		// onto whatever was undone.
		lastKind = Kind.NONE;
		lastAt = -1;
	}

	public Writing(Words words) {
		set(words);
	}

	public void set(Words words) {
		cells.clear();
		if (words != null) {
			for (Words.Run run : words.runs()) {
				String text = run.text();
				for (int at = 0; at < text.length(); at = text.offsetByCodePoints(at, 1)) {
					cells.add(new Cell(text.codePointAt(at), run.look()));
				}
			}
		}
		caret = cells.size();
		anchor = caret;
		pending = null;
	}

	// ------------------------------------------------------------- reading

	public int length() {
		return cells.size();
	}

	public int caret() {
		return caret;
	}

	public boolean hasSelection() {
		return caret != anchor;
	}

	public int selectionStart() {
		return Math.min(caret, anchor);
	}

	public int selectionEnd() {
		return Math.max(caret, anchor);
	}

	public String plain() {
		StringBuilder out = new StringBuilder(cells.size());
		for (Cell cell : cells) out.appendCodePoint(cell.point());
		return out.toString();
	}

	public Words words() {
		List<Look> looks = new ArrayList<>(cells.size());
		for (Cell cell : cells) looks.add(cell.look());
		return Words.of(plain(), looks);
	}

	/**
	 * What the buttons should show as being on.
	 *
	 * The look of the selection when it is all one, of the letter before the caret
	 * when there is no selection, and whatever was pressed while nothing was
	 * selected if that is still standing. Null when a selection is drawn several
	 * ways at once, which is a real answer: no button should claim to be on for a
	 * stretch that is half bold.
	 */
	public Look lookHere() {
		if (hasSelection()) {
			Look first = cells.get(selectionStart()).look();
			for (int at = selectionStart(); at < selectionEnd(); at++) {
				if (!cells.get(at).look().equals(first)) return null;
			}
			return first;
		}
		if (pending != null) return pending;
		if (caret > 0) return cells.get(caret - 1).look();
		return cells.isEmpty() ? Look.PLAIN : cells.get(0).look();
	}

	// ------------------------------------------------------------- moving

	public void moveTo(int at, boolean select) {
		caret = Math.clamp(at, 0, cells.size());
		if (!select) anchor = caret;
		pending = null;
	}

	public void move(int by, boolean select) {
		moveTo(caret + by, select);
	}

	public void toStart(boolean select) {
		moveTo(0, select);
	}

	public void toEnd(boolean select) {
		moveTo(cells.size(), select);
	}

	public void selectAll() {
		anchor = 0;
		caret = cells.size();
		pending = null;
	}

	/**
	 * The start of the word before the caret.
	 *
	 * Spaces first, then letters — so control-left from the end of "  слово" lands
	 * before the word rather than in the middle of the spaces, which is what makes
	 * repeated presses walk backwards a word at a time instead of alternating.
	 */
	public int wordLeft() {
		int at = caret;
		while (at > 0 && isSpace(cells.get(at - 1).point())) at--;
		while (at > 0 && !isSpace(cells.get(at - 1).point())) at--;
		return at;
	}

	public int wordRight() {
		int at = caret;
		while (at < cells.size() && !isSpace(cells.get(at).point())) at++;
		while (at < cells.size() && isSpace(cells.get(at).point())) at++;
		return at;
	}

	private static boolean isSpace(int point) {
		return point == ' ' || point == '\n' || point == '\t';
	}

	// ------------------------------------------------------------ changing

	/**
	 * Puts one code point in, replacing whatever is selected.
	 *
	 * @return whether anything changed
	 */
	public boolean insert(int point) {
		remember(Kind.TYPE);
		Look wearing = lookForNew();
		deleteSelection();
		cells.add(caret, new Cell(point, wearing));
		caret++;
		anchor = caret;
		lastAt = caret;
		// Kept rather than cleared: typing a second letter after pressing bold must
		// not quietly stop being bold halfway through the word.
		pending = wearing;
		return true;
	}

	/** Puts a string in, one code point at a time, so pasted text behaves as typed text. */
	public boolean insert(String text) {
		if (text == null || text.isEmpty()) return false;
		// Pasted text is one step however long it is: it arrived in one press and it
		// has to leave in one. The caret is written down as well as the step, so the
		// first letter of the paste welds onto this rather than starting a second.
		remember(Kind.TYPE);
		lastAt = caret;
		boolean any = false;
		for (int at = 0; at < text.length(); at = text.offsetByCodePoints(at, 1)) {
			int point = text.codePointAt(at);
			// Everything below a space except the newline is a control character that
			// has no business in a line of dialogue and would draw as a hole.
			if (point < ' ' && point != '\n') continue;
			any |= insert(point);
		}
		return any;
	}

	private Look lookForNew() {
		if (pending != null) return pending;
		if (hasSelection()) return cells.get(selectionStart()).look();
		return caret > 0 ? cells.get(caret - 1).look() : Look.PLAIN;
	}

	public boolean backspace() {
		remember(Kind.REMOVE);
		if (hasSelection()) return deleteSelection();
		if (caret == 0) return false;
		cells.remove(caret - 1);
		caret--;
		anchor = caret;
		lastAt = caret;
		pending = null;
		return true;
	}

	public boolean delete() {
		remember(Kind.REMOVE);
		if (hasSelection()) return deleteSelection();
		if (caret >= cells.size()) return false;
		cells.remove(caret);
		lastAt = caret;
		pending = null;
		return true;
	}

	private boolean deleteSelection() {
		if (!hasSelection()) return false;
		int from = selectionStart();
		int to = selectionEnd();
		cells.subList(from, to).clear();
		caret = from;
		anchor = from;
		return true;
	}

	/** What is selected, as plain text, for the clipboard. */
	public String selected() {
		if (!hasSelection()) return "";
		StringBuilder out = new StringBuilder();
		for (Cell cell : cells.subList(selectionStart(), selectionEnd())) {
			out.appendCodePoint(cell.point());
		}
		return out.toString();
	}

	// ------------------------------------------------------------ dressing

	/**
	 * Changes how the selection is drawn, or what will be typed next.
	 *
	 * One function rather than a method per property, because the properties are
	 * seven and growing and every one of them would otherwise need the same three
	 * lines of "selection, or pending, or nothing".
	 */
	public void dress(UnaryOperator<Look> change) {
		dress(change, false);
	}

	/**
	 * @param continuing whether this is more of the change already in progress
	 *
	 * True while a colour is being dragged along a bar. Without it every frame of
	 * the drag would be its own step to undo — a second of dragging is sixty of
	 * them, which fills the whole history and leaves ctrl+Z unable to reach past
	 * the colour to the words.
	 */
	public void dress(UnaryOperator<Look> change, boolean continuing) {
		if (continuing) {
			remember(Kind.DRESS);
			lastAt = caret;
		} else {
			// Otherwise always its own step, never welded to the one before. Pressing
			// bold is a decision by itself, and taking it back must not also take back
			// the sentence that was typed before it.
			lastKind = Kind.NONE;
			remember(Kind.DRESS);
			lastKind = Kind.NONE;
		}
		if (!hasSelection()) {
			pending = change.apply(lookHere() == null ? Look.PLAIN : lookHere());
			return;
		}
		for (int at = selectionStart(); at < selectionEnd(); at++) {
			Cell cell = cells.get(at);
			cells.set(at, new Cell(cell.point(), change.apply(cell.look())));
		}
	}

	/**
	 * Whether a face is on for the whole of what a press would affect.
	 *
	 * Asked so that pressing bold on a stretch that is already bold turns it off,
	 * which is what a person expects and what a naive "set it to true" gets wrong
	 * in exactly the case they will try first.
	 */
	public boolean allAre(java.util.function.Predicate<Look> test) {
		if (!hasSelection()) {
			Look here = lookHere();
			return here != null && test.test(here);
		}
		for (int at = selectionStart(); at < selectionEnd(); at++) {
			if (!test.test(cells.get(at).look())) return false;
		}
		return true;
	}
}
