package com.mopicmp.npcstudio.dialogue.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the stretches of a decorated line end up.
 *
 * <h2>Why a ruler that lies</h2>
 *
 * Every letter here is six wide, whatever it is. That is not a shortcut around
 * the real font — it is the point. How wide a letter is, is a fact about a font
 * and a window and cannot be checked without both; where a paragraph breaks is
 * arithmetic and can be checked in a millisecond. Mixing the two would mean
 * neither was checked: the sums would be hidden behind measurements nobody can
 * predict, and every change to the font would look like a bug in the wrapping.
 *
 * So the ruler is exact and boring, and every number below can be worked out by
 * hand — which is the only way a test of arithmetic is worth anything.
 */
class LayoutTest {

	/** Six a letter, and bold costs one more, as it does in the game. */
	private static final Layout.Ruler SIX = (text, font, bold) ->
		text.codePointCount(0, text.length()) * (bold ? 7 : 6);

	private static final Look BIG = Look.PLAIN.withSize(2f);
	private static final Look RED = Look.PLAIN.withColour(0xE57373);

	private static List<String> textOf(Layout.Placed placed) {
		return placed.rows().stream()
			.map(row -> row.pieces().stream().map(Layout.Piece::text)
				.reduce("", String::concat))
			.toList();
	}

	@Test
	@DisplayName("words are kept whole and the row breaks between them")
	void wrapsBetweenWords() {
		// Four letters a word, six a letter: a word is 24 wide, a space 6. Two words
		// and the space between them are 54; room for 60 takes two words and not three.
		Layout.Placed placed = Layout.of(Words.of("абвг абвг абвг"), 60, SIX);

		assertEquals(List.of("абвг абвг", "абвг"), textOf(placed));
	}

	@Test
	@DisplayName("the space that would hang off the edge is not counted or drawn")
	void trailingSpacesGo() {
		// Two words and the space between them are 54, so 53 is the widest room that
		// still forces a break. Without trimming, the first row would then measure 30
		// and look 24, and anything centred on it sits half a space out of place.
		Layout.Placed placed = Layout.of(Words.of("абвг абвг"), 53, SIX);

		assertEquals(List.of("абвг", "абвг"), textOf(placed));
		assertEquals(24, placed.width(), "the row is as wide as it looks");
	}

	@Test
	@DisplayName("a newline breaks the row wherever it falls")
	void hardBreaks() {
		assertEquals(List.of("аб", "вг"), textOf(Layout.of(Words.of("аб\nвг"), 600, SIX)));

		// And a blank row is a row: two newlines together mean an empty line, which
		// still takes up height, because that is what somebody typing two of them
		// meant by it.
		Layout.Placed twice = Layout.of(Words.of("аб\n\nвг"), 600, SIX);
		assertEquals(List.of("аб", "", "вг"), textOf(twice));
		assertEquals(Layout.LINE * 3, twice.height());
	}

	@Test
	@DisplayName("a word too long for the room is cut rather than run off the edge")
	void breaksAWordThatCannotFit() {
		assertEquals(List.of("абв", "где", "жз"),
			textOf(Layout.of(Words.of("абвгдежз"), 18, SIX)));

		// Room narrower than a single letter still has to end. Keeping at least one
		// letter a row is what stops it producing empty rows for ever.
		assertEquals(List.of("а", "б", "в"), textOf(Layout.of(Words.of("абв"), 1, SIX)));
	}

	@Test
	@DisplayName("a row is left empty at the end only when somebody asked for one")
	void noPhantomLastRow() {
		// A word cut in half leaves exactly what a newline leaves — an empty row in
		// hand and no text left — and the two must not be treated alike. Cutting a
		// word that happens to end at the cut used to add a blank row after it, which
		// is a gap under the text that nothing on the screen accounts for.
		assertEquals(3, Layout.of(Words.of("абвгдеж"), 18, SIX).rows().size());
		assertEquals(List.of("абв", "где", "ж"),
			textOf(Layout.of(Words.of("абвгдеж"), 18, SIX)));

		// Whereas a newline at the end does mean a row, because that is what typing
		// one at the end means.
		assertEquals(List.of("аб", ""), textOf(Layout.of(Words.of("аб\n"), 600, SIX)));
	}

	@Test
	@DisplayName("nought room means do not wrap at all")
	void noRoomMeansNoWrapping() {
		// For the places that measure first and decide the room afterwards.
		assertEquals(List.of("абвг абвг абвг"),
			textOf(Layout.of(Words.of("абвг абвг абвг"), 0, SIX)));
	}

	@Test
	@DisplayName("large and small text on one row sit on the same line")
	void baselinesAgree() {
		// The thing that would be got wrong by not thinking about it: lined up by
		// their tops, small text beside large text appears to float.
		Words mixed = new Words(List.of(
			new Words.Run("аб", Look.PLAIN),
			new Words.Run("вг", BIG)));
		Layout.Row row = Layout.of(mixed, 600, SIX).rows().get(0);

		assertEquals(2, row.pieces().size());
		Layout.Piece small = row.pieces().get(0);
		Layout.Piece large = row.pieces().get(1);

		assertEquals(Layout.ASCENT * 2, small.y() + Layout.ASCENT,
			"the small stretch sits on the deeper baseline");
		assertEquals(Layout.ASCENT * 2, large.y() + Layout.ASCENT * 2,
			"and so does the large one");
		assertEquals(7, small.y(), "which puts the small text seven down, not at the top");
		assertEquals(0, large.y());
	}

	@Test
	@DisplayName("a row is as tall as the largest thing on it, descenders included")
	void heightFollowsTheLargest() {
		assertEquals(Layout.LINE, Layout.of(Words.of("аб"), 600, SIX).height());
		assertEquals(Layout.LINE * 2, Layout.of(Words.of("аб").allOf(BIG), 600, SIX).height());
	}

	@Test
	@DisplayName("size widens the text, and the wrapping knows it")
	void sizeCountsTowardsTheWidth() {
		// Two letters at twice the size are 24 wide, not 12. Measured wrong, large
		// text runs off the edge — and it is the text somebody made large to be read.
		Words big = Words.of("абвг").allOf(BIG);
		assertEquals(48, Layout.of(big, 600, SIX).width());
		assertEquals(List.of("аб", "вг"), textOf(Layout.of(big, 24, SIX)));
	}

	@Test
	@DisplayName("the row is cut where the drawing has to change and nowhere else")
	void piecesFollowTheLook() {
		Words words = new Words(List.of(
			new Words.Run("аб", Look.PLAIN),
			new Words.Run("вг", RED),
			new Words.Run("де", Look.PLAIN)));
		Layout.Row row = Layout.of(words, 600, SIX).rows().get(0);

		assertEquals(3, row.pieces().size());
		assertEquals(0, row.pieces().get(0).x());
		assertEquals(12, row.pieces().get(1).x());
		assertEquals(24, row.pieces().get(2).x());
		assertEquals(RED, row.pieces().get(1).look());
	}

	@Test
	@DisplayName("a word split by a colour is still one word")
	void aWordIsAWordAcrossLooks() {
		// The trap in wrapping runs instead of words: colouring one letter would turn
		// one word into three and let the row break inside it.
		Words words = new Words(List.of(
			new Words.Run("аб", Look.PLAIN),
			new Words.Run("в", RED),
			new Words.Run("гд", Look.PLAIN)));

		assertEquals(List.of("абвгд"), textOf(Layout.of(words, 30, SIX)));
	}

	@Test
	@DisplayName("bold is wider, and that reaches the wrapping too")
	void boldCountsAsWell() {
		Words bold = Words.of("абвг").allOf(Look.PLAIN.withBold(true));
		assertEquals(28, Layout.of(bold, 600, SIX).width());
	}

	@Test
	@DisplayName("nothing lays out as nothing")
	void emptiness() {
		assertEquals(Layout.Placed.NOTHING, Layout.of(Words.EMPTY, 100, SIX));
		assertEquals(Layout.Placed.NOTHING, Layout.of(null, 100, SIX));
		assertTrue(Layout.of(Words.of(""), 100, SIX).rows().isEmpty());
	}
}
