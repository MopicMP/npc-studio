package com.mopicmp.npcstudio.client.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mopicmp.npcstudio.dialogue.text.Layout;
import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The window a line of dialogue is written in.
 *
 * <h2>Why it is not a screen and not a panel</h2>
 *
 * Because it has to work in both places, and there is exactly one mechanism that
 * does. A screen would throw the workspace away when the editor is a panel in it;
 * a panel cannot exist when the editor is running on its own. What works in both
 * is what the graph editor already does twice — for the list of node kinds and
 * for the list of skills — namely something the editor draws over itself and
 * offers the mouse to first.
 *
 * So this owns no widgets and registers nothing. It is drawn, and it is asked.
 * The widgets underneath are not merely covered while it is open; they are not
 * built, because a screen draws its widgets last and a window that is only drawn
 * over them is a window they can still be pressed through.
 *
 * <h2>One answer for both drawing and clicking</h2>
 *
 * Every control comes from {@link #controls}, which is called by the drawing and
 * by the clicking, and neither has its own idea of where anything is. Two
 * descriptions of one rectangle is how a button ends up drawn where it cannot be
 * pressed — a fault already met in this workspace and not worth meeting twice.
 *
 * <h2>Nothing is confirmed</h2>
 *
 * The line goes back to the node as it is typed, the way every other field in
 * this editor works and the way the graph itself is saved. That is why there is
 * no "cancel": with the change already made, a button promising to undo it would
 * have to remember the whole session to keep the promise. What takes a change
 * back is ctrl+Z, which takes back exactly what it says.
 */
public final class WordsEditor {

	private static final int SHADE = 0xC0000000;
	private static final int BODY = 0xF01A1F26;
	private static final int EDGE = 0xFF2C333D;
	private static final int FIELD = 0xFF12161C;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int SELECTED = 0x804FC3F7;

	private static final int PADDING = 10;
	private static final int BUTTON = 18;
	private static final int GAP = 4;

	private static final int TITLE = 18;
	private static final int TOOLBAR = 26;
	private static final int FOOTER = 26;
	private static final int TRAY_CELL = 13;

	/** How tall one channel's bar is in the colour tray, and how wide. */
	private static final int BAR = 11;
	private static final int BAR_WIDE = 220;

	private static final int FONT_ROW = 13;
	private static final int FONT_WIDE = 120;

	private static final String HINTS =
		"ctrl+Z вернуть  ·  ctrl+A выделить  ·  ctrl+C/V  ·  esc закрыть";

	/**
	 * The colours offered without asking for a number.
	 *
	 * A map is mostly written by choosing between a handful of voices, and ten
	 * squares is faster than any picker for that. The picker is beside them for the
	 * times it is not — see {@link Tray#COLOUR} — so neither has to serve the
	 * other's case badly.
	 */
	private static final int[] COLOURS = {
		0xFFECEFF1, 0xFFEF5350, 0xFFFF8A65, 0xFFFFCA28, 0xFF66BB6A,
		0xFF4FC3F7, 0xFF9575CD, 0xFFEC407A, 0xFF8A99A6, 0xFF37474F,
	};

	/** What the symbol tray offers. Arrows, marks, and the punctuation nobody's keyboard has. */
	private static final String SYMBOLS =
		"←↑→↓↔⇒⇐★☆♥♦♠♣✔✖†‡§¶«»„“”‘’–—…·•°±×÷≈≠≤≥∞µ¤€£¥№™©®";

	/**
	 * What is open under the toolbar, and only ever one thing.
	 *
	 * Two trays open at once would push the writing area down twice and leave it a
	 * strip; and having pressed one, nobody expects the other to still be there.
	 */
	private enum Tray { NONE, SYMBOLS, COLOUR }

	private record Control(int x, int y, int wide, int tall, Runnable press,
		String label, int colour, boolean on, Look dressed) { }

	private final Writing writing;
	private final Consumer<Words> done;
	private final String title;

	private int left;
	private int top;
	private int wide;
	private int tall;
	private int across;
	private int down;

	private Tray tray = Tray.NONE;
	private boolean showingFonts;
	private boolean dragging;
	private int draggingChannel = -1;
	private boolean finished;

	/**
	 * What the node has been told so far, so it is told again only when it changes.
	 *
	 * Read from the document rather than set by a flag at each place that edits it.
	 * There are a dozen such places and the thirteenth would be written without the
	 * flag, and the symptom would be a line that silently stops being saved.
	 */
	private Words pushed;

	public WordsEditor(String title, Words start, Consumer<Words> done) {
		this.title = title;
		this.writing = new Writing(start);
		this.done = done;
		this.pushed = writing.words();
	}

	public boolean finished() {
		return finished;
	}

	/**
	 * Hands the line back if it has changed since the last time.
	 *
	 * Driven from the editor's tick rather than from the drawing, because drawing
	 * something must not change it: a frame that is skipped or drawn twice would
	 * otherwise mean a different number of edits.
	 */
	public void tick() {
		Words now = writing.words();
		if (now.equals(pushed)) return;
		pushed = now;
		done.accept(now);
	}

	/**
	 * Takes most of the width and a fifth of the height.
	 *
	 * Wide because the toolbar is: eleven colours, four faces, a size and a
	 * typeface do not fold onto a second row without becoming a puzzle. Not tall,
	 * because what the height has to hold is a paragraph and the two strips around
	 * it — it was seven eighths of the window once, which for one line of dialogue
	 * is a sentence at the top and five hundred pixels of nothing under it.
	 */
	public void fit(int width, int height) {
		across = width;
		down = height;
		wide = Math.max(240, width - width / 8);
		tall = Math.clamp(300, 170, Math.max(170, height - height / 6));
		left = (width - wide) / 2;
		// A little above the middle: dead centre puts the writing low, and the eye
		// starts at the top of a thing it is about to read.
		top = Math.max(0, (height - tall) * 2 / 5);
	}

	// ------------------------------------------------------------- geometry

	private int toolbarTop() {
		return top + TITLE;
	}

	private int trayTop() {
		return toolbarTop() + TOOLBAR;
	}

	private int trayTall() {
		return switch (tray) {
			case NONE -> 0;
			case SYMBOLS -> symbolRows() * TRAY_CELL + GAP * 2;
			case COLOUR -> BAR * 3 + GAP * 4;
		};
	}

	private int textLeft() {
		return left + PADDING;
	}

	private int textTop() {
		return trayTop() + trayTall() + GAP;
	}

	private int textWide() {
		return wide - PADDING * 2;
	}

	private int textTall() {
		return Math.max(Layout.LINE, footerTop() - GAP - textTop());
	}

	private int footerTop() {
		return top + tall - FOOTER;
	}

	private int symbolsPerRow() {
		return Math.max(1, textWide() / TRAY_CELL);
	}

	private int symbolRows() {
		return (SYMBOLS.codePointCount(0, SYMBOLS.length()) + symbolsPerRow() - 1) / symbolsPerRow();
	}

	/** Where the typeface button is, which is also where its list drops from. */
	private int fontLeft;

	// ------------------------------------------------------------- controls

	private List<Control> controls() {
		List<Control> out = new ArrayList<>();
		int y = toolbarTop() + (TOOLBAR - BUTTON) / 2;
		int x = left + PADDING;

		// None first, then the colours. "None" is the way back and has to be first,
		// where a person looks for it.
		out.add(plain(x, y, BUTTON, BUTTON,
			() -> writing.dress(look -> look.withColour(null)), "—", TEXT_DIM,
			writing.allAre(look -> look.colour() == null)));
		x += BUTTON + 2;
		for (int colour : COLOURS) {
			int chosen = colour;
			out.add(new Control(x, y, BUTTON, BUTTON,
				() -> writing.dress(look -> look.withColour(chosen & 0xFFFFFF)), null, colour,
				writing.allAre(look -> look.colour() != null
					&& look.colour() == (chosen & 0xFFFFFF)), null));
			x += BUTTON + 2;
		}
		// And the way to any other colour, at the end of the ones already offered
		// rather than hidden behind them.
		out.add(plain(x, y, BUTTON, BUTTON, () -> open(Tray.COLOUR), "+", ACCENT,
			tray == Tray.COLOUR));
		x += BUTTON;

		x = rule(out, x);
		out.add(face(x, y, "B", Look::bold, Look::withBold));
		x += BUTTON + 2;
		out.add(face(x, y, "I", Look::italic, Look::withItalic));
		x += BUTTON + 2;
		out.add(face(x, y, "U", Look::underlined, Look::withUnderlined));
		x += BUTTON + 2;
		out.add(face(x, y, "S", Look::struck, Look::withStruck));
		x += BUTTON;

		x = rule(out, x);
		out.add(plain(x, y, BUTTON, BUTTON, () -> resize(-0.25f), "−", TEXT, false));
		x += BUTTON + 2;
		Look here = writing.lookHere();
		// The number in the middle is a button as well as a reading: pressing it puts
		// the size back to one. Somebody who has made something enormous by accident
		// should not have to press minus eight times to undo it.
		out.add(plain(x, y, 32, BUTTON, () -> writing.dress(look -> look.withSize(1f)),
			here == null ? "—" : trim(here.size()) + "x", TEXT_DIM, false));
		x += 32 + 2;
		out.add(plain(x, y, BUTTON, BUTTON, () -> resize(0.25f), "+", TEXT, false));
		x += BUTTON;

		x = rule(out, x);
		// Not the section sign, which is what this was. The game's font treats it as
		// the mark that begins a formatting code, so the button came out blank — and a
		// button with nothing on it is indistinguishable from a broken one.
		out.add(plain(x, y, BUTTON, BUTTON, () -> open(Tray.SYMBOLS), "Ω", ACCENT,
			tray == Tray.SYMBOLS));
		x += BUTTON;

		// The typeface, as a list that drops rather than a button that steps round.
		// Stepping was fine for three and useless for a dozen: to see what there is
		// you had to visit every one of them in turn and remember.
		x = rule(out, x);
		fontLeft = x;
		Look wearing = writing.lookHere();
		String named = wearing == null || wearing.font() == null ? "" : wearing.font();
		out.add(new Control(x, y, FONT_WIDE, BUTTON, () -> showingFonts = !showingFonts,
			wearing == null ? "—" : Fonts.shown(named),
			named.isEmpty() ? TEXT_DIM : ACCENT, showingFonts,
			// Drawn in the typeface it names. A list of names in one face tells you
			// their spelling and nothing about the thing being chosen — but only when
			// the typeface is actually here, or the name would come out as a row of
			// boxes and the button would look broken rather than absent.
			named.isEmpty() || !Fonts.loaded(named) ? null : Look.PLAIN.withFont(named)));

		// The two ways out say the same thing now that nothing is confirmed: the line
		// is already in the node. One in the corner where a window's cross lives, one
		// in the footer for the hand that looks for a word.
		out.add(plain(left + wide - TITLE, top, TITLE, TITLE, () -> finished = true,
			"✕", 0xFFEF5350, false));
		out.add(plain(left + wide - PADDING - 70, footerTop() + (FOOTER - BUTTON) / 2, 70, BUTTON,
			() -> finished = true, "готово", 0xFF66BB6A, false));
		return out;
	}

	private static Control plain(int x, int y, int wide, int tall, Runnable press,
			String label, int colour, boolean on) {
		return new Control(x, y, wide, tall, press, label, colour, on, null);
	}

	/**
	 * One of the four faces, as a button wearing it.
	 *
	 * The letter is drawn bold on the bold button, italic on the italic one, and so
	 * on. A row of four identical letters is a row that has to be learned; a row
	 * where each one looks like what it does is a row that explains itself.
	 *
	 * The new value is worked out <em>once</em>, before the stretch is walked, and
	 * that is not tidiness. Asking "is it all bold" inside the change would ask it
	 * again after each letter had been made bold — so the answer would flip at the
	 * first letter and the rest would be turned back off.
	 */
	private Control face(int x, int y, String label, java.util.function.Predicate<Look> reads,
			java.util.function.BiFunction<Look, Boolean, Look> set) {
		boolean already = writing.allAre(reads);
		return new Control(x, y, BUTTON, BUTTON,
			() -> writing.dress(look -> set.apply(look, !already)), label, ACCENT, already,
			set.apply(Look.PLAIN, true));
	}

	/**
	 * A hairline between two groups of buttons, and the gap around it.
	 *
	 * Added to the list as a control that does nothing, so the drawing does not need
	 * a second description of where the groups begin. A rule worked out twice is a
	 * rule that ends up in two places.
	 */
	private int rule(List<Control> out, int x) {
		out.add(plain(x + GAP * 2, toolbarTop() + 5, 1, TOOLBAR - 10, () -> { },
			null, EDGE, false));
		return x + GAP * 4 + 1;
	}

	private void open(Tray which) {
		tray = tray == which ? Tray.NONE : which;
		showingFonts = false;
	}

	private void resize(float by) {
		writing.dress(look -> look.withSize(Math.clamp(look.size() + by, 0.5f, 4f)));
	}

	private static String trim(float size) {
		return size == Math.rint(size) ? String.valueOf((int) size) : String.valueOf(size);
	}

	/** The colour being worked on, or what the text would be drawn in without one. */
	private int channelled() {
		Look here = writing.lookHere();
		return here == null || here.colour() == null ? 0xECEFF1 : here.colour();
	}

	// -------------------------------------------------------------- drawing

	public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		graphics.fill(0, 0, across, down, SHADE);
		graphics.fill(left - 1, top - 1, left + wide + 1, top + tall + 1, EDGE);
		graphics.fill(left, top, left + wide, top + tall, BODY);

		// A title bar with a stripe down its left edge, as every other panel in this
		// workspace has: the same window in the same clothes, so it reads as part of
		// the same programme rather than as something that has landed on top of it.
		graphics.fill(left, top, left + wide, top + TITLE, FIELD);
		graphics.fill(left, top, left + 2, top + TITLE, ACCENT);
		graphics.fill(left, top + TITLE - 1, left + wide, top + TITLE, EDGE);
		graphics.text(font, Component.literal(title),
			left + PADDING, top + (TITLE - font.lineHeight) / 2 + 1, TEXT);

		graphics.fill(left, toolbarTop() + TOOLBAR - 1, left + wide, toolbarTop() + TOOLBAR, EDGE);
		for (Control control : controls()) drawControl(graphics, font, control, mouseX, mouseY);

		switch (tray) {
			case SYMBOLS -> drawSymbols(graphics, font, mouseX, mouseY);
			case COLOUR -> drawColours(graphics, font, mouseX, mouseY);
			case NONE -> { }
		}

		drawText(graphics, font);
		drawFooter(graphics, font);
		// Last, because it hangs over the writing area.
		if (showingFonts) drawFontList(graphics, font, mouseX, mouseY);
	}

	private void drawFooter(GuiGraphicsExtractor graphics, Font font) {
		graphics.fill(left, footerTop(), left + wide, footerTop() + 1, EDGE);

		// Said where the choice is made, and only when it is a problem. A typeface
		// that is not loaded draws in the ordinary one, which looks like the button
		// having done nothing — and "it does nothing" is what somebody concludes when
		// nothing tells them otherwise. It takes the place of the hints rather than
		// sharing the line, because a warning beside a list of shortcuts is a warning
		// nobody reads.
		Look wearing = writing.lookHere();
		boolean absent = wearing != null && wearing.font() != null
			&& !Fonts.loaded(wearing.font());

		Component said = absent
			? Component.literal("шрифт " + wearing.font() + " не загружен — рисуется обычным")
			: Component.literal(HINTS);
		graphics.text(font, said, left + PADDING,
			footerTop() + (FOOTER - font.lineHeight) / 2 + 1, absent ? 0xFFFFCA28 : TEXT_DIM);
	}

	private void drawControl(GuiGraphicsExtractor graphics, Font font, Control control,
			int mouseX, int mouseY) {
		// A rule between groups: no label, one pixel wide, and nothing to press.
		if (control.label() == null && control.wide() == 1) {
			graphics.fill(control.x(), control.y(),
				control.x() + 1, control.y() + control.tall(), control.colour());
			return;
		}

		boolean over = inside(control, mouseX, mouseY);
		boolean swatch = control.label() == null;

		graphics.fill(control.x(), control.y(), control.x() + control.wide(),
			control.y() + control.tall(), swatch ? control.colour() : over ? 0xFF232A34 : FIELD);
		if (control.on() || over) {
			graphics.fill(control.x(), control.y() + control.tall() - 2,
				control.x() + control.wide(), control.y() + control.tall(),
				swatch ? 0xFFFFFFFF : control.colour());
		}
		if (swatch) return;

		Component label = Component.literal(control.label());
		if (control.dressed() != null) label = label.copy().withStyle(Ink.styleOf(control.dressed()));
		graphics.text(font, label,
			control.x() + (control.wide() - font.width(label)) / 2,
			control.y() + (control.tall() - font.lineHeight) / 2 + 1,
			control.on() || over ? control.colour() : TEXT);
	}

	private void drawSymbols(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		drawTrayGround(graphics);
		int perRow = symbolsPerRow();
		int at = 0;
		for (int point = 0; point < SYMBOLS.length();
				point = SYMBOLS.offsetByCodePoints(point, 1), at++) {
			int x = textLeft() + (at % perRow) * TRAY_CELL;
			int y = trayTop() + GAP + (at / perRow) * TRAY_CELL;
			boolean over = mouseX >= x && mouseX < x + TRAY_CELL
				&& mouseY >= y && mouseY < y + TRAY_CELL;
			if (over) graphics.fill(x, y, x + TRAY_CELL, y + TRAY_CELL, 0xFF27313E);
			graphics.text(font, Component.literal(
				new String(Character.toChars(SYMBOLS.codePointAt(point)))),
				x + 3, y + 3, over ? ACCENT : TEXT);
		}
	}

	/**
	 * Three bars, one per channel, each showing what it would do.
	 *
	 * Not a hue square with a brightness strip, which is the usual thing and needs a
	 * pixel-by-pixel picture; there is nothing here to draw one with but filled
	 * rectangles. Three bars are drawn from about a hundred of them, and they have
	 * the advantage that each one shows the colour that moving <em>it</em> gives —
	 * the red bar is the current colour with its red swept, not a rainbow.
	 */
	private void drawColours(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		drawTrayGround(graphics);
		int rgb = channelled();

		for (int channel = 0; channel < 3; channel++) {
			int y = barTop(channel);
			int shift = 16 - channel * 8;
			for (int step = 0; step < BAR_WIDE; step += 2) {
				int value = step * 255 / (BAR_WIDE - 1);
				int shown = (rgb & ~(0xFF << shift)) | (value << shift);
				graphics.fill(barLeft() + step, y, barLeft() + step + 2, y + BAR, 0xFF000000 | shown);
			}
			int at = ((rgb >> shift) & 0xFF) * (BAR_WIDE - 1) / 255;
			graphics.fill(barLeft() + at - 1, y - 1, barLeft() + at + 2, y + BAR + 1, 0xFFECEFF1);
			graphics.fill(barLeft() + at, y, barLeft() + at + 1, y + BAR, 0xFF12161C);

			graphics.text(font, Component.literal("RGB".substring(channel, channel + 1)),
				barLeft() - 10, y + 2, TEXT_DIM);
		}

		// The colour itself, and its number, because somebody matching a colour to
		// something outside the game needs the number and nothing else will do.
		int preview = barLeft() + BAR_WIDE + PADDING;
		graphics.fill(preview, barTop(0), preview + 28, barTop(2) + BAR, 0xFF000000 | rgb);
		graphics.text(font, Component.literal(String.format("#%06x", rgb)),
			preview + 34, barTop(1) + 2, TEXT);
	}

	private void drawTrayGround(GuiGraphicsExtractor graphics) {
		graphics.fill(left, trayTop(), left + wide, trayTop() + trayTall(), 0xFF161B22);
		graphics.fill(left, trayTop() + trayTall() - 1, left + wide,
			trayTop() + trayTall(), EDGE);
	}

	private int barLeft() {
		return textLeft() + 12;
	}

	private int barTop(int channel) {
		return trayTop() + GAP + channel * (BAR + GAP / 2 + 1);
	}

	/**
	 * The typefaces, each written in itself.
	 *
	 * Drawn over the writing area rather than pushed into a tray of its own: a list
	 * that moves the text down and back up as it opens and shuts makes the line
	 * being edited jump about, and the line is the thing being looked at.
	 */
	private void drawFontList(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		List<String> named = Fonts.names();
		int rows = Math.min(named.size(), fontRowsThatFit());
		int listTop = toolbarTop() + TOOLBAR;

		graphics.fill(fontLeft - 1, listTop - 1, fontLeft + FONT_WIDE + 1,
			listTop + rows * FONT_ROW + 1, EDGE);
		graphics.fill(fontLeft, listTop, fontLeft + FONT_WIDE, listTop + rows * FONT_ROW, 0xFF12161C);

		Look wearing = writing.lookHere();
		String now = wearing == null || wearing.font() == null ? "" : wearing.font();

		for (int i = 0; i < rows; i++) {
			String name = named.get(i);
			int y = listTop + i * FONT_ROW;
			boolean over = mouseX >= fontLeft && mouseX < fontLeft + FONT_WIDE
				&& mouseY >= y && mouseY < y + FONT_ROW;
			if (over) graphics.fill(fontLeft, y, fontLeft + FONT_WIDE, y + FONT_ROW, 0xFF232A34);

			Component label = Component.literal(Fonts.shown(name));
			// Every name written in its own face — which is the whole point of a list
			// over a button that steps round: you can see what you are choosing.
			if (!name.isEmpty()) {
				label = label.copy().withStyle(Ink.styleOf(Look.PLAIN.withFont(name)));
			}
			graphics.text(font, label, fontLeft + 5, y + 3,
				name.equals(now) ? ACCENT : over ? TEXT : TEXT_DIM);
		}

		if (named.size() > rows) {
			graphics.text(font, Component.literal("…"),
				fontLeft + FONT_WIDE - 8, listTop + rows * FONT_ROW - FONT_ROW + 3, TEXT_DIM);
		}
	}

	private int fontRowsThatFit() {
		return Math.max(1, (footerTop() - (toolbarTop() + TOOLBAR)) / FONT_ROW);
	}

	private void drawText(GuiGraphicsExtractor graphics, Font font) {
		// A field with an edge, and it needs one. The body of the window and the
		// darker field behind the text are a few points apart in brightness, so
		// without a line the writing area had no boundary at all and the window read
		// as one empty rectangle with a toolbar stuck to the top of it.
		int fieldLeft = textLeft() - GAP;
		int fieldTop = textTop() - GAP;
		int fieldRight = textLeft() + textWide() + GAP;
		int fieldBottom = textTop() + textTall() + GAP;

		graphics.fill(fieldLeft, fieldTop, fieldRight, fieldBottom, FIELD);
		graphics.fill(fieldLeft, fieldTop, fieldRight, fieldTop + 1, EDGE);
		graphics.fill(fieldLeft, fieldBottom - 1, fieldRight, fieldBottom, EDGE);
		graphics.fill(fieldLeft, fieldTop, fieldLeft + 1, fieldBottom, EDGE);
		graphics.fill(fieldRight - 1, fieldTop, fieldRight, fieldBottom, EDGE);

		Layout.Placed placed = Ink.lay(font, writing.words(), textWide(), 2);
		drawSelection(graphics, font, placed);
		Ink.draw(graphics, font, placed, textLeft(), textTop(), TEXT);

		int[] caret = Ink.caretAt(font, placed, writing.caret());
		// A bar rather than a blink. A blink needs a clock, and nothing else in this
		// window needs one — and a caret that is invisible half the time is a caret
		// somebody loses in a wall of text.
		graphics.fill(textLeft() + caret[0], textTop() + caret[1],
			textLeft() + caret[0] + 1, textTop() + caret[1] + caret[2], ACCENT);
	}

	private void drawSelection(GuiGraphicsExtractor graphics, Font font, Layout.Placed placed) {
		if (!writing.hasSelection()) return;
		int from = writing.selectionStart();
		int to = writing.selectionEnd();

		for (Layout.Row row : placed.rows()) {
			for (Layout.Piece piece : row.pieces()) {
				int length = piece.text().codePointCount(0, piece.text().length());
				int start = Math.max(from, piece.from());
				int end = Math.min(to, piece.from() + length);
				if (start >= end) continue;

				int[] a = Ink.caretAt(font, placed, start);
				int[] b = Ink.caretAt(font, placed, end);
				graphics.fill(textLeft() + a[0], textTop() + piece.y(),
					textLeft() + b[0], textTop() + piece.y() + a[2], SELECTED);
			}
		}
	}

	private static boolean inside(Control control, double x, double y) {
		return x >= control.x() && x < control.x() + control.wide()
			&& y >= control.y() && y < control.y() + control.tall();
	}

	// ---------------------------------------------------------------- input

	public boolean mouseClicked(double mouseX, double mouseY, boolean shift) {
		// The list is over everything else in the window, so it is asked first — and
		// a click anywhere else shuts it, which is what a list that drops does.
		if (showingFonts) {
			pickFont(mouseX, mouseY);
			return true;
		}
		for (Control control : controls()) {
			if (inside(control, mouseX, mouseY)) {
				control.press().run();
				return true;
			}
		}
		if (tray == Tray.SYMBOLS && insertSymbol(mouseX, mouseY)) return true;
		if (tray == Tray.COLOUR && grabBar(mouseX, mouseY)) return true;

		if (mouseX >= textLeft() - GAP && mouseX < textLeft() + textWide() + GAP
				&& mouseY >= textTop() - GAP && mouseY < textTop() + textTall() + GAP) {
			writing.moveTo(pointUnder(mouseX, mouseY), shift);
			dragging = true;
			return true;
		}
		// Anything else inside the window is the window; anything outside it must not
		// reach the graph, because the graph is not what is being used. Closing on a
		// click outside would make a stray click end an edit somebody was in.
		return true;
	}

	private void pickFont(double mouseX, double mouseY) {
		showingFonts = false;
		List<String> named = Fonts.names();
		int rows = Math.min(named.size(), fontRowsThatFit());
		int listTop = toolbarTop() + TOOLBAR;
		if (mouseX < fontLeft || mouseX >= fontLeft + FONT_WIDE) return;

		int row = (int) ((mouseY - listTop) / FONT_ROW);
		if (row < 0 || row >= rows) return;
		String chosen = named.get(row);
		writing.dress(look -> look.withFont(chosen.isEmpty() ? null : chosen));
	}

	private boolean insertSymbol(double mouseX, double mouseY) {
		int perRow = symbolsPerRow();
		int column = (int) ((mouseX - textLeft()) / TRAY_CELL);
		int row = (int) ((mouseY - trayTop() - GAP) / TRAY_CELL);
		if (column < 0 || column >= perRow || row < 0 || row >= symbolRows()) return false;

		int index = row * perRow + column;
		int count = SYMBOLS.codePointCount(0, SYMBOLS.length());
		if (index >= count) return true;
		writing.insert(SYMBOLS.codePointAt(SYMBOLS.offsetByCodePoints(0, index)));
		return true;
	}

	/** Which bar was pressed, so dragging along it keeps changing that one channel. */
	private boolean grabBar(double mouseX, double mouseY) {
		for (int channel = 0; channel < 3; channel++) {
			if (mouseY >= barTop(channel) - 1 && mouseY < barTop(channel) + BAR + 1) {
				draggingChannel = channel;
				slide(mouseX);
				return true;
			}
		}
		return false;
	}

	private void slide(double mouseX) {
		int step = (int) Math.clamp(mouseX - barLeft(), 0, BAR_WIDE - 1);
		int value = step * 255 / (BAR_WIDE - 1);
		int shift = 16 - draggingChannel * 8;
		int now = (channelled() & ~(0xFF << shift)) | (value << shift);
		// One step to undo for the whole drag, not one per frame.
		writing.dress(look -> look.withColour(now), true);
	}

	private int pointUnder(double mouseX, double mouseY) {
		Font font = Minecraft.getInstance().font;
		Layout.Placed placed = Ink.lay(font, writing.words(), textWide(), 2);
		return Ink.pointAt(font, placed, (int) mouseX - textLeft(), (int) mouseY - textTop());
	}

	public boolean mouseDragged(double mouseX, double mouseY) {
		if (draggingChannel >= 0) {
			slide(mouseX);
			return true;
		}
		if (!dragging) return false;
		writing.moveTo(pointUnder(mouseX, mouseY), true);
		return true;
	}

	public void mouseReleased() {
		dragging = false;
		draggingChannel = -1;
	}

	private static final int KEY_ESCAPE = 256;
	private static final int KEY_ENTER = 257;
	private static final int KEY_TAB = 258;
	private static final int KEY_BACKSPACE = 259;
	private static final int KEY_DELETE = 261;
	private static final int KEY_RIGHT = 262;
	private static final int KEY_LEFT = 263;
	private static final int KEY_HOME = 268;
	private static final int KEY_END = 269;
	private static final int KEY_A = 65;
	private static final int KEY_C = 67;
	private static final int KEY_V = 86;
	private static final int KEY_X = 88;
	private static final int KEY_Y = 89;
	private static final int KEY_Z = 90;
	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	public boolean keyPressed(KeyEvent event) {
		boolean shift = (event.modifiers() & GLFW_SHIFT) != 0;
		boolean control = (event.modifiers() & GLFW_CONTROL) != 0;
		Minecraft client = Minecraft.getInstance();

		if (control) {
			switch (event.key()) {
				case KEY_A -> writing.selectAll();
				case KEY_C -> client.keyboardHandler.setClipboard(writing.selected());
				case KEY_X -> {
					client.keyboardHandler.setClipboard(writing.selected());
					writing.backspace();
				}
				case KEY_V -> writing.insert(client.keyboardHandler.getClipboard());
				// Ctrl+Z, and ctrl+shift+Z for the other direction. Ctrl+Y as well,
				// because half the world learned redo that way and the key is free.
				case KEY_Z -> {
					if (shift) writing.redo();
					else writing.undo();
				}
				case KEY_Y -> writing.redo();
				// The word jumps live here rather than with the plain arrows below,
				// because this branch answers every key with control held and returns.
				case KEY_LEFT -> writing.moveTo(writing.wordLeft(), shift);
				case KEY_RIGHT -> writing.moveTo(writing.wordRight(), shift);
				default -> {
					return true;
				}
			}
			return true;
		}

		switch (event.key()) {
			// Escape closes, and closing is all it does now: the line is already in the
			// node. It used to throw the edit away, which was the right meaning while
			// there was a "done" to press and the wrong one the moment there was not.
			case KEY_ESCAPE -> {
				if (showingFonts || tray != Tray.NONE) {
					// The innermost thing first, as escape means everywhere else here.
					showingFonts = false;
					tray = Tray.NONE;
				} else {
					finished = true;
				}
			}
			// Enter is a new line, not a way out. A line of dialogue with a break in it
			// is an ordinary thing to want, and the way out is an arm's length away.
			case KEY_ENTER -> writing.insert('\n');
			case KEY_BACKSPACE -> writing.backspace();
			case KEY_DELETE -> writing.delete();
			case KEY_LEFT -> writing.move(-1, shift);
			case KEY_RIGHT -> writing.move(1, shift);
			case KEY_HOME -> writing.toStart(shift);
			case KEY_END -> writing.toEnd(shift);
			// Swallowed rather than passed on: tab in a window with one field has
			// nowhere to go, and letting it through moves the focus of whatever is
			// behind, which is a graph the person cannot see.
			case KEY_TAB -> { }
			default -> {
				return true;
			}
		}
		return true;
	}

	public boolean charTyped(int codepoint) {
		writing.insert(codepoint);
		return true;
	}
}
