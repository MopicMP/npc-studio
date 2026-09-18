package com.mopicmp.npcstudio.dialogue.text;

import java.util.List;

/**
 * The colours a writer may name, and what they come out as.
 *
 * <h2>Why this is one list and not three</h2>
 *
 * It was three. The bar knew five names, the answer screen knew the same five in
 * its own copy, and the editor knew none at all — which is why a colour set in the
 * text window changed nothing anywhere the author could see, and why a name that
 * was slightly wrong looked exactly like a name that was right.
 *
 * A picker can only be built from a list, and it must be the same list the drawing
 * reads. Two copies of a vocabulary are two vocabularies that will differ, and the
 * way they differ is the one thing nobody tests: a colour that is offered and does
 * nothing.
 *
 * <h2>Why so few</h2>
 *
 * Because they have to be told apart at eleven pixels, over a bar, by somebody
 * reading rather than comparing. A palette of sixteen contains several pairs that
 * are the same colour under those conditions, and offering them is offering a
 * choice that does not exist. These are the ones that read.
 *
 * The default is not in the list. "Say nothing" is not a colour and must not be
 * one, or the difference between a document's answer and a line overruling it
 * stops being expressible — see {@code Node.Line.nameColour}.
 */
public final class Tint {

	private Tint() { }

	/** The accent every undecorated line has always been drawn in. */
	public static final int DEFAULT = 0xFF4FC3F7;

	/**
	 * The names, in the order a picker should show them.
	 *
	 * Ordered by how loud they are rather than alphabetically, so the list reads as a
	 * scale from "she is speaking" to "something is wrong".
	 */
	public static final List<String> NAMES =
		List.of("blue", "green", "yellow", "orange", "red", "pink", "purple", "grey", "white");

	/**
	 * What a name comes out as, or the accent for a name nobody knows.
	 *
	 * An unknown name falls back rather than failing. A mistyped colour should leave
	 * a line readable — the alternative is a scene that cannot be played because of a
	 * spelling mistake in a word nobody sees.
	 */
	public static int of(String name) {
		if (name == null) return DEFAULT;
		return switch (name) {
			case "blue" -> DEFAULT;
			case "green" -> 0xFF66BB6A;
			case "yellow" -> 0xFFFFCA28;
			case "orange" -> 0xFFFF8A65;
			case "red" -> 0xFFEF5350;
			// Both spellings of each, because they arrive from a file somebody may have
			// typed by hand and neither spelling is wrong.
			case "pink", "magenta" -> 0xFFEC407A;
			case "purple", "violet" -> 0xFFBA68C8;
			case "grey", "gray" -> 0xFF90A4AE;
			case "white" -> 0xFFECEFF1;
			default -> DEFAULT;
		};
	}
}
