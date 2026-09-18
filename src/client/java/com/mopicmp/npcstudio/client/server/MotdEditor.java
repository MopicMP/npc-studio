package com.mopicmp.npcstudio.client.server;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The little window that appears beside the message of the day.
 *
 * The format is section signs followed by a letter, and nobody has ever enjoyed
 * typing them. So they are never typed: a colour is a square, a style is a
 * button, and what they produce is shown underneath as the server list will draw
 * it — which is the only place the answer is ever visible.
 *
 * <b>Why the first version of this did nothing.</b> It inserted the codes with
 * {@link EditBox#insertText}, which puts everything through
 * {@code StringUtil.filterText} — and that strips the section sign, because it
 * is the character the chat filter exists to remove. So every press inserted the
 * letter without its sign and the message stayed plain. The value is spliced and
 * set instead, which is not filtered; the caret is put back afterwards, because
 * setting a value moves it to the end.
 */
public final class MotdEditor {

	private static final int PANEL = 0xFF1B2028;
	private static final int EDGE = 0xFF3A424D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	/** The sixteen colours, in the game's own order. */
	private static final char[] CODES =
		{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

	private static final int[] SWATCH = {
		0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA,
		0xFFFFAA00, 0xFFAAAAAA, 0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF,
		0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF};

	/** Style codes, and what each looks like written in itself. */
	private static final char[] STYLES = {'l', 'o', 'n', 'm', 'k', 'r'};

	private static final int CELL = 12;
	private static final int COLUMNS = 8;
	private static final int PAD = 6;
	private static final int WIDTH = PAD * 2 + COLUMNS * CELL;

	private boolean open;
	private EditBox field;
	private int x;
	private int y;

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
		field = null;
	}

	/** Follows the field it belongs to, and appears only while it is being used. */
	public void follow(EditBox field, int x, int y, int screenHeight) {
		this.field = field;
		this.x = x;
		this.y = Math.clamp(y, 4, Math.max(4, screenHeight - height() - 4));
		this.open = true;
	}

	private int height() {
		return PAD + 8 + 2 * CELL + 6 + CELL + 4 + PAD + 12;
	}

	/** The corner that shuts it, because a window with no way out is a trap. */
	private boolean overClose(double mouseX, double mouseY) {
		return mouseX >= x + WIDTH - 12 && mouseX < x + WIDTH - 2
			&& mouseY >= y + 2 && mouseY < y + 12;
	}

	public int width() {
		return WIDTH;
	}

	/**
	 * Put a code where the caret is.
	 *
	 * Spliced and set rather than inserted, for the reason in the class note. The
	 * caret is moved without keeping the highlight, or the next thing typed would
	 * replace what was just written.
	 */
	private void put(String code) {
		if (field == null) return;
		String value = field.getValue();
		int at = Math.clamp(field.getCursorPosition(), 0, value.length());
		field.setValue(value.substring(0, at) + code + value.substring(at));
		field.moveCursorTo(Math.min(field.getValue().length(), at + code.length()), false);
	}

	/** Takes the click if it landed on a colour or a style. */
	public boolean click(double mouseX, double mouseY) {
		if (!open || field == null) return false;
		if (mouseX < x || mouseX >= x + WIDTH || mouseY < y || mouseY >= y + height()) return false;
		if (overClose(mouseX, mouseY)) {
			close();
			return true;
		}

		int top = y + PAD + 8;
		int local = (int) (mouseX - x - PAD);
		int column = local / CELL;
		if (column >= 0 && column < COLUMNS) {
			int row = (int) ((mouseY - top) / CELL);
			if (row == 0 || row == 1) {
				put("§" + CODES[row * COLUMNS + column]);
				return true;
			}
		}
		int styleTop = top + 2 * CELL + 6;
		if (mouseY >= styleTop && mouseY < styleTop + CELL) {
			int at = (int) ((mouseX - x - PAD) / CELL);
			if (at >= 0 && at < STYLES.length) {
				put("§" + STYLES[at]);
				return true;
			}
		}
		// Inside the window but not on anything: swallowed, so the field behind
		// does not take the caret away from what is being written.
		return true;
	}

	public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (!open || field == null) return;
		int high = height();
		graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + high + 1, EDGE);
		graphics.fill(x, y, x + WIDTH, y + high, PANEL);
		graphics.fill(x, y, x + WIDTH, y + 1, ACCENT);

		graphics.text(font, Component.translatable("npc_studio.server.motd.tools"),
			x + PAD, y + 3, TEXT_DIM);
		boolean closing = overClose(mouseX, mouseY);
		graphics.text(font, "✕", x + WIDTH - 10, y + 3, closing ? TEXT : TEXT_DIM);

		int top = y + PAD + 8;
		for (int at = 0; at < CODES.length; at++) {
			int column = at % COLUMNS;
			int row = at / COLUMNS;
			int left = x + PAD + column * CELL;
			int cellTop = top + row * CELL;
			boolean lit = mouseX >= left && mouseX < left + CELL - 1
				&& mouseY >= cellTop && mouseY < cellTop + CELL - 1;
			graphics.fill(left - 1, cellTop - 1, left + CELL, cellTop + CELL,
				lit ? TEXT : EDGE);
			graphics.fill(left, cellTop, left + CELL - 1, cellTop + CELL - 1, SWATCH[at]);
		}

		int styleTop = top + 2 * CELL + 6;
		for (int at = 0; at < STYLES.length; at++) {
			int left = x + PAD + at * CELL;
			boolean lit = mouseX >= left && mouseX < left + CELL - 1
				&& mouseY >= styleTop && mouseY < styleTop + CELL;
			graphics.fill(left, styleTop, left + CELL - 1, styleTop + CELL,
				lit ? 0xFF2C3541 : 0xFF12161C);
			// Each style written in itself: bold is bold, italic leans over.
			String mark = "§" + STYLES[at] + (STYLES[at] == 'r' ? "R" : "A");
			graphics.text(font, mark, left + 3, styleTop + 2, lit ? ACCENT : TEXT);
		}

		String preview = field.getValue();
		graphics.text(font, font.plainSubstrByWidth(preview.isEmpty() ? "…" : preview,
			WIDTH - PAD * 2), x + PAD, y + high - PAD - 8, TEXT);
	}
}
