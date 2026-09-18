package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A small window that floats over the canvas.
 *
 * <h2>What this replaces, and why it was not enough</h2>
 *
 * Two lists drawn straight onto the screen — the boxes, and how a document is shown.
 * They worked, and that was the whole of what could be said for them. Reported
 * plainly: no title, no way to close them but to click elsewhere, no way to move
 * them off whatever they were covering, no way back out of a choice, and any click
 * anywhere on the canvas made them vanish — including the click you were about to
 * make on the node underneath.
 *
 * That last one is the real fault rather than the cosmetic one. A panel that closes
 * when you look away cannot be worked <em>alongside</em> anything; it can only be
 * opened, used at once, and lost. So the boxes could not be consulted while
 * arranging the nodes that name them, which is the one time anybody wants them.
 *
 * <h2>Why one window and not two</h2>
 *
 * Because they are the same window with different contents, and writing that down
 * twice is how the first pair came to disagree about where their own rows were. A
 * pane is a title and a list of rows; everything else — the bar, the cross, the
 * dragging, the going back — belongs to the window and is written once.
 *
 * <h2>Why there is a history</h2>
 *
 * Because choosing is a place you go rather than a thing that happens to you. A row
 * that offers answers opens a pane of them, and the arrow takes you back to the list
 * you came from — so picking the wrong row costs one click instead of reopening the
 * window and finding your place in it again. Forward is there for the same reason it
 * is anywhere: having gone back, going forward again should not be a fresh journey.
 */
public final class Sheet {

	/**
	 * One row: what it is, what it says now, and what pressing it does.
	 *
	 * @param said   the answer in force, drawn at the far end in the brighter colour,
	 *               or null for a row that is an action rather than a setting
	 * @param marked whether this row <em>is</em> the answer, for a pane of choices
	 */
	public record Row(Component label, Component said, boolean marked, Runnable act) {

		public static Row of(Component label, Component said, Runnable act) {
			return new Row(label, said, false, act);
		}

		public static Row choice(Component label, boolean marked, Runnable act) {
			return new Row(label, null, marked, act);
		}
	}

	/**
	 * One page of the window.
	 *
	 * The rows are asked for rather than handed over, because they say what things
	 * are set to and that changes while the window is open. A list built once would
	 * go on showing the old answer until somebody closed it and opened it again.
	 */
	public record Pane(Component title, Supplier<List<Row>> rows) { }

	private static final int ROW = 16;
	private static final int BAR = 18;
	private static final int PAD = 6;
	private static final int MARK = 14;

	/** How many rows are shown before it scrolls. */
	private static final int ROWS = 10;

	private static final int BODY = 0xF01A1F26;
	private static final int TITLE_BAR = 0xFF232A34;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int DIM = 0xFF8A99A6;
	private static final int FAINT = 0xFF5A6570;
	private static final int HOVER = 0xFF232A34;

	private final List<Pane> history = new ArrayList<>();
	private int at = -1;
	private int from;

	/**
	 * Where it sits, kept between openings.
	 *
	 * Somebody who moved it out of their way meant it, and meant it for longer than
	 * one opening. Not saved to disk: it is about this session's screen rather than
	 * about the document.
	 */
	private int x = 12;
	private int y = 60;
	private boolean carrying;

	public boolean open() {
		return at >= 0 && at < history.size();
	}

	/** Opens the window on a page, forgetting wherever it had been before. */
	public void open(Pane pane) {
		history.clear();
		history.add(pane);
		at = 0;
		from = 0;
	}

	/**
	 * Goes to a page from the page in force, the way a link does.
	 *
	 * Anything that had been gone forward to is dropped, because it is no longer
	 * ahead of anywhere — a history that kept it would offer a forward arrow leading
	 * somewhere the reader never was.
	 */
	public void go(Pane pane) {
		if (!open()) {
			open(pane);
			return;
		}
		while (history.size() > at + 1) history.remove(history.size() - 1);
		history.add(pane);
		at++;
		from = 0;
	}

	public void close() {
		history.clear();
		at = -1;
		carrying = false;
	}

	/** Closes it if it is open on this page, and opens it there if it is not. */
	public void toggle(Pane pane) {
		if (open() && history.get(at).title().equals(pane.title())) close();
		else open(pane);
	}

	private boolean canGoBack() {
		return at > 0;
	}

	private boolean canGoOn() {
		return at >= 0 && at < history.size() - 1;
	}

	// ------------------------------------------------------------- the drawing

	private int wide(Font font) {
		if (!open()) return 0;
		Pane pane = history.get(at);
		int widest = font.width(pane.title()) + MARK * 3 + PAD * 2;
		for (Row row : pane.rows().get()) {
			int said = row.said() == null ? 0 : font.width(row.said()) + 16;
			widest = Math.max(widest, font.width(row.label()) + said + PAD * 2 + MARK);
		}
		return Math.clamp(widest, 180, 360);
	}

	private int tall(Font font) {
		if (!open()) return 0;
		int rows = Math.min(ROWS, Math.max(1, history.get(at).rows().get().size()));
		return BAR + rows * ROW + PAD;
	}

	public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (!open()) return;
		Pane pane = history.get(at);
		List<Row> rows = pane.rows().get();
		from = Math.clamp(from, 0, Math.max(0, rows.size() - ROWS));
		int wide = wide(font);
		int tall = tall(font);

		graphics.fill(x - 1, y - 1, x + wide + 1, y + tall + 1, EDGE);
		graphics.fill(x, y, x + wide, y + tall, BODY);
		graphics.fill(x, y, x + wide, y + BAR, TITLE_BAR);

		// The two arrows and the cross, at the end of the bar in that order. Greyed
		// rather than hidden when there is nowhere to go: a control that appears and
		// disappears moves the ones beside it, and the cross moving under a hand
		// reaching for it is the one thing this window must never do.
		//
		// Drawn as letters rather than from the icon sheet, which has no arrows in it.
		// These are typographic marks and not pictures of anything, which is the right
		// register for a title bar — the same three the rename field beside it uses.
		int marks = x + wide - MARK * 3 - PAD + 4;
		mark(graphics, font, "‹", marks, y, canGoBack(), mouseX, mouseY, DIM);
		mark(graphics, font, "›", marks + MARK, y, canGoOn(), mouseX, mouseY, DIM);
		mark(graphics, font, "×", marks + MARK * 2, y, true, mouseX, mouseY, 0xFFEF5350);

		graphics.text(font, pane.title(), x + PAD, y + 5, TEXT);

		int shown = Math.min(ROWS, rows.size());
		for (int i = 0; i < shown; i++) {
			Row row = rows.get(from + i);
			int top = y + BAR + i * ROW;
			boolean hovered = mouseX >= x && mouseX < x + wide
				&& mouseY >= top && mouseY < top + ROW;
			if (hovered) graphics.fill(x, top, x + wide, top + ROW, HOVER);
			graphics.text(font, row.label(), x + PAD + (row.marked() ? 10 : 0), top + 4,
				row.marked() ? 0xFF4FC3F7 : row.said() == null ? TEXT : DIM);
			// The tick against the answer in force, in the same colour as its words, so
			// the row is marked twice — which survives being read quickly and survives
			// being read by somebody who cannot separate those two colours.
			if (row.marked()) {
				graphics.text(font, Component.literal("·"), x + PAD, top + 4, 0xFF4FC3F7);
			}
			if (row.said() != null) {
				graphics.text(font, row.said(),
					x + wide - PAD - font.width(row.said()), top + 4, TEXT);
			}
		}
		if (rows.size() > shown) {
			Component more = Component.translatable("npc_studio.sheet.more",
				from + 1, from + shown, rows.size());
			graphics.text(font, more, x + wide - PAD - font.width(more), y + tall - 9, FAINT);
		}
	}

	/** One of the three marks on the bar, lit when the mouse is on it and it can be used. */
	private void mark(GuiGraphicsExtractor graphics, Font font, String glyph, int atX, int barTop,
			boolean usable, int mouseX, int mouseY, int lit) {
		boolean hovered = mouseX >= atX && mouseX < atX + MARK
			&& mouseY >= barTop && mouseY < barTop + BAR;
		graphics.text(font, Component.literal(glyph), atX + 3, barTop + 5,
			!usable ? FAINT : hovered ? lit : DIM);
	}

	// ------------------------------------------------------------ the pointing

	/**
	 * A click anywhere.
	 *
	 * @return whether it was ours. A click that was not ours is left entirely alone —
	 *         no closing, no swallowing — which is the difference between a window and
	 *         a thing that flinches.
	 */
	public boolean clicked(Font font, double mouseX, double mouseY) {
		if (!open()) return false;
		int wide = wide(font);
		int tall = tall(font);
		if (mouseX < x || mouseX >= x + wide || mouseY < y || mouseY >= y + tall) return false;

		if (mouseY < y + BAR) {
			int marks = x + wide - MARK * 3 - PAD + 4;
			if (mouseX >= marks + MARK * 2) {
				close();
				return true;
			}
			if (mouseX >= marks + MARK) {
				if (canGoOn()) { at++; from = 0; }
				return true;
			}
			if (mouseX >= marks) {
				if (canGoBack()) { at--; from = 0; }
				return true;
			}
			// Anywhere else along the bar carries it. The bar and nowhere else, so that
			// a hand aiming at a row cannot pick the window up instead.
			carrying = true;
			return true;
		}

		List<Row> rows = history.get(at).rows().get();
		int row = from + (int) ((mouseY - y - BAR) / ROW);
		if (row >= 0 && row < rows.size() && rows.get(row).act() != null) {
			rows.get(row).act().run();
		}
		return true;
	}

	public void dragged(double dx, double dy, int screenWide, int screenTall) {
		if (!carrying) return;
		x = (int) Math.clamp(x + dx, 0, Math.max(0, screenWide - 60));
		y = (int) Math.clamp(y + dy, 0, Math.max(0, screenTall - BAR));
	}

	/** Whether the bar is being held, so the screen knows the drag is ours. */
	public boolean carrying() {
		return carrying;
	}

	public void released() {
		carrying = false;
	}

	public boolean scrolled(Font font, double mouseX, double mouseY, double by) {
		if (!open()) return false;
		if (mouseX < x || mouseX >= x + wide(font)) return false;
		if (mouseY < y || mouseY >= y + tall(font)) return false;
		int many = history.get(at).rows().get().size();
		from = Math.clamp(from - (int) Math.signum(by), 0, Math.max(0, many - ROWS));
		return true;
	}
}
