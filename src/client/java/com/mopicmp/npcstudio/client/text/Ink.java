package com.mopicmp.npcstudio.client.text;

import com.mopicmp.npcstudio.dialogue.text.Layout;
import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Drawing a decorated line, on the side of the fence where the game lives.
 *
 * <h2>What is here and what is not</h2>
 *
 * {@link Layout} works out where every stretch goes and cannot see a font or a
 * window; this measures and draws and takes no view about where anything belongs.
 * The split is what lets the wrapping be checked by a test in a millisecond, and
 * it only holds if nothing here starts making decisions.
 *
 * <h2>Why the colour is not put in the style</h2>
 *
 * Because a style's colour has no alpha and takes precedence over the colour
 * handed to the drawing call — so a caption that was meant to fade in arrives
 * fully opaque and throws the fade away. That was found once already, in the
 * scene captions, and the note is repeated here rather than left to be found a
 * second time. The colour travels in the one argument that can carry both.
 */
public final class Ink {

	private Ink() { }

	/**
	 * How the game measures a stretch, at ordinary size.
	 *
	 * Size is not passed and must not be: {@link Layout} multiplies by it itself,
	 * and a ruler that did as well would double it — which looks like "large text
	 * is a bit too large", the sort of wrongness nobody reports.
	 */
	public static Layout.Ruler ruler(Font font) {
		return (text, named, bold) ->
			font.width(Component.literal(text).withStyle(faces(named, bold, false, false, false)));
	}

	public static Layout.Placed lay(Font font, Words words, int room) {
		return Layout.of(words, room, ruler(font));
	}

	public static Layout.Placed lay(Font font, Words words, int room, int leading) {
		return Layout.of(words, room, ruler(font), leading);
	}

	/** How wide the line would be if nothing wrapped it. */
	public static int width(Font font, Words words) {
		return Layout.of(words, 0, ruler(font)).width();
	}

	/**
	 * Draws a laid-out line with its top left corner at {@code x, y}.
	 *
	 * @param colour what a stretch that chose no colour is drawn in; its alpha is
	 *               kept for the ones that did choose, so a line can still fade
	 */
	public static void draw(GuiGraphicsExtractor graphics, Font font, Layout.Placed placed,
			int x, int y, int colour) {
		for (Layout.Row row : placed.rows()) {
			for (Layout.Piece piece : row.pieces()) {
				Look look = piece.look();
				Component said = Component.literal(piece.text()).withStyle(styleOf(look));
				int shade = look.colour() == null
					? colour
					: (colour & 0xFF000000) | (look.colour() & 0xFFFFFF);

				graphics.pose().pushMatrix();
				graphics.pose().translate(x + piece.x(), y + piece.y());
				// After the move rather than before it, so the size grows the letters
				// and does not also carry them across the screen.
				graphics.pose().scale(look.size(), look.size());
				graphics.text(font, said, 0, 0, shade);
				graphics.pose().popMatrix();
			}
		}
	}

	/** Lays out and draws in one go, for the callers that need neither number. */
	public static int draw(GuiGraphicsExtractor graphics, Font font, Words words,
			int x, int y, int room, int leading, int colour) {
		Layout.Placed placed = lay(font, words, room, leading);
		draw(graphics, font, placed, x, y, colour);
		return placed.height();
	}

	/**
	 * Which code point a place on the screen falls on.
	 *
	 * Coordinates are relative to the top left of the laid-out block. Used to put
	 * the caret where somebody clicked, so it answers with a place <em>between</em>
	 * letters: past the middle of a letter means after it, which is what makes
	 * clicking near the end of a word land at the end of it rather than one back.
	 */
	public static int pointAt(Font font, Layout.Placed placed, int x, int y) {
		if (placed.rows().isEmpty()) return 0;

		Layout.Row row = placed.rows().get(0);
		for (Layout.Row candidate : placed.rows()) {
			row = candidate;
			if (y < candidate.top() + candidate.height()) break;
		}
		if (row.pieces().isEmpty()) return endOfRowBefore(placed, row);

		Layout.Piece piece = row.pieces().get(0);
		for (Layout.Piece candidate : row.pieces()) {
			piece = candidate;
			if (x < candidate.x() + widthOf(font, candidate)) break;
		}

		String text = piece.text();
		float scale = piece.look().size();
		int at = 0;
		while (at < text.length()) {
			int next = text.offsetByCodePoints(at, 1);
			float before = measure(font, text.substring(0, at), piece.look()) * scale;
			float after = measure(font, text.substring(0, next), piece.look()) * scale;
			// The middle of the letter, not its end: clicking on the right half of a
			// letter means "after this one", which is what makes clicking near the end
			// of a word put the caret at the end rather than one letter short.
			if (x < piece.x() + (before + after) / 2f) break;
			at = next;
		}
		return piece.from() + text.codePointCount(0, at);
	}

	/** Where the row above this one ended, for a click on a blank row. */
	private static int endOfRowBefore(Layout.Placed placed, Layout.Row row) {
		int end = 0;
		for (Layout.Row candidate : placed.rows()) {
			if (candidate == row) break;
			for (Layout.Piece piece : candidate.pieces()) {
				end = piece.from() + piece.text().codePointCount(0, piece.text().length());
			}
		}
		return end;
	}

	/**
	 * Where the caret sits for a place in the text: {@code {x, y, height}}.
	 *
	 * A place that is not drawn anywhere — a space trimmed off the end of a wrapped
	 * row — answers with the end of the row it belonged to, which is where a person
	 * would expect the caret to be after typing it.
	 */
	public static int[] caretAt(Font font, Layout.Placed placed, int point) {
		int[] answer = { 0, 0, Layout.LINE };
		for (Layout.Row row : placed.rows()) {
			for (Layout.Piece piece : row.pieces()) {
				String text = piece.text();
				int length = text.codePointCount(0, text.length());
				float scale = piece.look().size();
				int tall = Math.round(Layout.LINE * scale);

				if (point >= piece.from() + length) {
					answer = new int[] {
						piece.x() + Math.round(measure(font, text, piece.look()) * scale),
						piece.y(), tall };
					continue;
				}
				int keep = text.offsetByCodePoints(0, Math.max(0, point - piece.from()));
				return new int[] {
					piece.x() + Math.round(measure(font, text.substring(0, keep), piece.look()) * scale),
					piece.y(), tall };
			}
		}
		return answer;
	}

	private static int widthOf(Font font, Layout.Piece piece) {
		return Math.round(measure(font, piece.text(), piece.look()) * piece.look().size());
	}

	private static int measure(Font font, String text, Look look) {
		return font.width(Component.literal(text)
			.withStyle(faces(look.font(), look.bold(), false, false, false)));
	}

	public static Style styleOf(Look look) {
		return faces(look.font(), look.bold(), look.italic(), look.underlined(), look.struck());
	}

	private static Style faces(String named, boolean bold, boolean italic,
			boolean underlined, boolean struck) {
		Style style = Style.EMPTY
			.withBold(bold)
			.withItalic(italic)
			.withUnderlined(underlined)
			.withStrikethrough(struck);

		if (named == null || named.isEmpty()) return style;
		Identifier which = Identifier.tryParse(named);
		// A name that is not a name is drawn in the ordinary font rather than
		// refused. A typeface is worth less than the line it is carrying, and this
		// is the same call the scene captions make for the same reason.
		return which == null ? style : style.withFont(new FontDescription.Resource(which));
	}
}
