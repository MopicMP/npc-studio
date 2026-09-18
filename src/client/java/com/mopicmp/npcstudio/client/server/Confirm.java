package com.mopicmp.npcstudio.client.server;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A small window over everything, for the things that cannot be undone.
 *
 * There is exactly one kind of question worth stopping somebody for, and it is
 * not "are you sure" — it is "here is what will have happened by the time you
 * could change your mind". Replacing a server's core is one: a world opened by a
 * newer version is written in the newer format and the older one will not read
 * it again. That is not a warning to put in a status line at the bottom of a
 * column, where it appears at the same moment the download starts.
 *
 * Drawn and clicked by the screen that owns it, like {@link Dropdown}, and for
 * the same reason: it has to cover what it is asking about.
 */
public final class Confirm {

	private static final int SCRIM = 0xB0000000;
	private static final int PANEL = 0xFF1B2028;
	private static final int EDGE = 0xFF3A424D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int WARN = 0xFFFFB74D;

	private static final int WIDTH = 260;
	private static final int PAD = 10;
	private static final int BUTTON = 20;

	private boolean open;
	private Component title = Component.empty();
	private Component body = Component.empty();
	private Component yes = Component.empty();
	private Component no = Component.empty();
	private Runnable onYes;

	private int x;
	private int y;
	private int height;

	public boolean isOpen() {
		return open;
	}

	private boolean single;

	public void ask(Component title, Component body, Component yes, Component no, Runnable onYes) {
		this.title = title;
		this.body = body;
		this.yes = yes;
		this.no = no;
		this.onYes = onYes;
		this.single = false;
		this.open = true;
	}

	/**
	 * The same window with one button, for something that has already happened.
	 *
	 * A failure needs telling, not deciding: offering "cancel" beside a server
	 * that has already died is offering to cancel the past.
	 */
	public void tell(Component title, Component body, Component ok) {
		this.title = title;
		this.body = body;
		this.yes = ok;
		this.no = Component.empty();
		this.onYes = null;
		this.single = true;
		this.open = true;
	}

	public void close() {
		open = false;
		onYes = null;
	}

	private void place(Font font, int screenWidth, int screenHeight) {
		int lines = font.split(body, WIDTH - PAD * 2).size();
		height = PAD + 10 + 6 + lines * 10 + PAD + BUTTON + PAD;
		x = screenWidth / 2 - WIDTH / 2;
		y = Math.max(4, screenHeight / 2 - height / 2);
	}

	/** Takes every click while it is open: that is what a question in the way is. */
	public boolean click(double mouseX, double mouseY) {
		if (!open) return false;
		int buttonY = y + height - PAD - BUTTON;
		int half = single ? WIDTH - PAD * 2 : (WIDTH - PAD * 2 - 6) / 2;
		if (mouseY >= buttonY && mouseY < buttonY + BUTTON) {
			if (mouseX >= x + PAD && mouseX < x + PAD + half) {
				Runnable run = onYes;
				close();
				if (run != null) run.run();
				return true;
			}
			if (mouseX >= x + PAD + half + 6 && mouseX < x + WIDTH - PAD) {
				close();
				return true;
			}
		}
		// Everything else is swallowed rather than passed through: a question
		// that can be clicked past is a question that has been answered by
		// accident.
		return true;
	}

	public void draw(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight,
			int mouseX, int mouseY) {
		if (!open) return;
		place(font, screenWidth, screenHeight);
		graphics.fill(0, 0, screenWidth, screenHeight, SCRIM);
		graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + height + 1, EDGE);
		graphics.fill(x, y, x + WIDTH, y + height, PANEL);
		graphics.fill(x, y, x + WIDTH, y + 1, WARN);

		graphics.text(font, title, x + PAD, y + PAD, TEXT);
		graphics.textWithWordWrap(font, body, x + PAD, y + PAD + 16, WIDTH - PAD * 2, TEXT_DIM);

		int buttonY = y + height - PAD - BUTTON;
		int half = single ? WIDTH - PAD * 2 : (WIDTH - PAD * 2 - 6) / 2;
		button(graphics, font, x + PAD, buttonY, half, yes, WARN, mouseX, mouseY);
		if (!single) {
			button(graphics, font, x + PAD + half + 6, buttonY, half, no, TEXT_DIM, mouseX, mouseY);
		}
	}

	private void button(GuiGraphicsExtractor graphics, Font font, int left, int top, int width,
			Component label, int accent, int mouseX, int mouseY) {
		boolean lit = mouseX >= left && mouseX < left + width
			&& mouseY >= top && mouseY < top + BUTTON;
		graphics.fill(left, top, left + width, top + BUTTON, lit ? 0xFF2C3541 : 0xFF161A20);
		graphics.fill(left, top, left + width, top + 1, accent);
		graphics.text(font, label, left + (width - font.width(label)) / 2, top + (BUTTON - 8) / 2,
			lit ? accent : TEXT);
	}
}
