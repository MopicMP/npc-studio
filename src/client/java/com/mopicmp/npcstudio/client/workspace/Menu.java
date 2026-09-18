package com.mopicmp.npcstudio.client.workspace;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A list that opens at a point, and the only one in the workspace.
 *
 * <h2>Why this exists</h2>
 *
 * There were two, written separately and drifting apart. The dock's own had
 * twelve-pixel rows and no icons; the top bar's had eighteen and both. So the
 * better one already existed and half the window did not know about it — and the
 * worse one was one of the small targets that made the workspace hard to work in
 * at all.
 *
 * A third was about to be written for the menu under the cursor in the world.
 * That one is a ring rather than a column, so it is not this class; but the parts
 * that are not the shape — what an entry is, when the list closes, what happens
 * to a click outside it — are here, so the ring inherits the decisions rather
 * than making them again.
 *
 * <h2>Why it scrolls, which the old ones did not</h2>
 *
 * Because raising the row from twelve to eighteen made the dock's menu taller
 * than the window. That menu lists every panel not currently open, which is
 * twenty-odd entries: at twelve pixels it was 240 and merely bad, at eighteen it
 * is 360 against a window with about 350 logical pixels of height, so the last
 * entries were off the bottom with no way to reach them.
 *
 * Worth saying plainly, because it is the same fault in miniature that the whole
 * workspace has: the answer is not really a scrollbar, it is that a menu offering
 * every panel in the mod is a menu that has stopped choosing. The scrollbar is
 * what keeps it usable until that list goes away.
 */
public final class Menu {

	/**
	 * One line: what it shows, whether it is already on, and what it does.
	 *
	 * The icon may be null. It is not optional because some entries deserve one
	 * less than others — it is that the dock's moves ("send this left") have no
	 * icon drawn for them yet, and a blank square is worse than an honest gap.
	 */
	public record Entry(Icon icon, Component label, boolean marked, Runnable act) {

		public static Entry of(Component label, Runnable act) {
			return new Entry(null, label, false, act);
		}

		public static Entry of(Icon icon, Component label, Runnable act) {
			return new Entry(icon, label, false, act);
		}
	}

	public static final int ROW = 18;

	/** How many rows are shown before it scrolls. */
	private static final int SHOWN = 10;

	private static final int LEAST_WIDE = 96;
	private static final int MOST_WIDE = 220;
	private static final int PAD = 5;

	private static final int PANEL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;
	private static final int HOVER = 0xFF232A34;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int BAR = 0xFF3A424D;

	private List<Entry> entries = List.of();
	private int x;
	private int y;
	private int width;
	private int scroll;

	public boolean isOpen() {
		return !entries.isEmpty();
	}

	public void close() {
		entries = List.of();
		scroll = 0;
	}

	/**
	 * Opens at a point, kept inside the rectangle it belongs to.
	 *
	 * The rectangle is the panel's or the screen's, and it is passed rather than
	 * assumed because this is used inside a docked panel, where the display's edges
	 * are not the ones that matter — a menu clipped by a scissor is a menu whose
	 * entries are there and cannot be seen.
	 */
	public void open(Font font, int px, int py, List<Entry> wanted,
			int boundsX, int boundsY, int boundsWidth, int boundsHeight) {
		open(font, px, py, wanted, boundsX, boundsY, boundsWidth, boundsHeight, 0);
	}

	/**
	 * The same, never narrower than something — the field it drops out of.
	 *
	 * <h2>Why a list has to be told this</h2>
	 *
	 * On its own it is as wide as its longest entry, which is right for a menu that
	 * opens at a cursor and wrong for one that opens under a button. A list narrower
	 * than the control it belongs to does not read as that control opening; it reads
	 * as something unrelated that has appeared nearby, and the eye has to work out
	 * which field it is about. Reported exactly that way.
	 *
	 * A floor rather than a fixed width, so a list of long entries still gets the
	 * room it needs.
	 */
	public void open(Font font, int px, int py, List<Entry> wanted,
			int boundsX, int boundsY, int boundsWidth, int boundsHeight, int least) {
		if (wanted.isEmpty()) return;
		entries = List.copyOf(wanted);
		scroll = 0;

		width = Math.max(LEAST_WIDE, least);
		for (Entry entry : entries) {
			int wide = PAD * 2 + font.width(entry.label())
				+ (entry.icon() == null ? 0 : Icon.SIZE + 4);
			width = Math.max(width, wide);
		}
		width = Math.min(width, Math.max(LEAST_WIDE, Math.min(MOST_WIDE, boundsWidth)));

		int tall = height();
		x = Math.max(boundsX, Math.min(px, boundsX + boundsWidth - width));
		// Upwards when there is no room below, exactly as the dropdown does: a list
		// running off the bottom is a list whose last entries cannot be reached.
		y = py + tall <= boundsY + boundsHeight ? py
			: Math.max(boundsY, py - tall);
	}

	private int shown() {
		return Math.min(SHOWN, entries.size());
	}

	public int height() {
		return shown() * ROW;
	}

	/** True when the click belonged to the menu — including a click that dismissed it. */
	public boolean click(double mouseX, double mouseY) {
		if (!isOpen()) return false;
		int row = rowAt(mouseX, mouseY);
		List<Entry> was = entries;
		close();
		if (row >= 0) was.get(row).act().run();
		return true;
	}

	public boolean scrolled(double amount) {
		if (!isOpen() || entries.size() <= SHOWN) return false;
		scroll = Math.clamp(scroll - (int) Math.signum(amount), 0, entries.size() - SHOWN);
		return true;
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height()) return -1;
		int row = scroll + (int) ((mouseY - y) / ROW);
		return row >= 0 && row < entries.size() ? row : -1;
	}

	public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (!isOpen()) return;
		int tall = height();
		graphics.fill(x - 1, y - 1, x + width + 1, y + tall + 1, EDGE);
		graphics.fill(x, y, x + width, y + tall, PANEL);

		int hovered = rowAt(mouseX, mouseY);
		for (int seat = 0; seat < shown(); seat++) {
			int at = scroll + seat;
			Entry entry = entries.get(at);
			int top = y + seat * ROW;
			if (at == hovered) graphics.fill(x, top, x + width, top + ROW, HOVER);

			int text = x + PAD;
			if (entry.icon() != null) {
				entry.icon().draw(graphics, x + PAD, top + (ROW - Icon.SIZE) / 2,
					entry.marked() ? ACCENT : TEXT_DIM);
				text += Icon.SIZE + 4;
			}
			// The answer in force, said in the one way that works whether or not the
			// entry has a picture. It used to be the icon's colour and nothing else, so
			// a list of plain words — which is most of them — marked nothing at all,
			// and "which one is it now" had no answer. Reported about the panels menu,
			// where the wardrobe was open and the list showed it as though it were not.
			graphics.text(font, entry.label(), text, top + (ROW - 8) / 2,
				entry.marked() ? ACCENT : at == hovered ? TEXT : TEXT_DIM);
		}

		if (entries.size() > SHOWN) bar(graphics, tall);
	}

	/**
	 * A thumb down the right edge, only when there is something out of sight.
	 *
	 * Without it a menu that scrolls is indistinguishable from one that has ten
	 * entries, and the eleventh is found only by somebody who happens to turn the
	 * wheel over it.
	 */
	private void bar(GuiGraphicsExtractor graphics, int tall) {
		int span = Math.max(8, tall * SHOWN / entries.size());
		int travel = tall - span;
		int top = y + (entries.size() == SHOWN ? 0 : travel * scroll / (entries.size() - SHOWN));
		graphics.fill(x + width - 2, top, x + width - 1, top + span, BAR);
	}
}
