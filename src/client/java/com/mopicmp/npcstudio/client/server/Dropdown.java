package com.mopicmp.npcstudio.client.server;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A list that opens over the screen and closes when something is chosen.
 *
 * Every choice in the manager is made through one of these, and that is a rule
 * rather than a preference: a button that cycles hides how many answers there
 * are and makes the last one four presses away, and a hundred versions laid out
 * as a column is a wall. A dropdown says how many there are, shows where you are
 * in them, and takes one press to leave alone.
 *
 * Owned by the screen rather than being a widget, because a widget may not draw
 * outside its own rectangle and this has to draw over everything below it. The
 * screen keeps one, draws it last, and offers it the mouse first.
 */
public final class Dropdown {

	/** One line of the list: the value that is stored, and what is shown. */
	public record Option(String value, Component label) {
		public static Option of(String value) {
			return new Option(value, Component.literal(value));
		}
	}

	private static final int ROW = 13;
	private static final int SHOWN = 9;

	private static final int FILL = 0xFF1B2028;
	private static final int HOVER = 0xFF2C3541;
	private static final int EDGE = 0xFF3A424D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	private List<Option> options = List.of();
	private Consumer<String> chosen;
	private String current = "";
	private int x;
	private int y;
	private int width;
	private int scroll;
	private boolean open;

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
		options = List.of();
		chosen = null;
	}

	/**
	 * Open under a control.
	 *
	 * Opens upwards instead when there is not room below — a list that runs off
	 * the bottom of the window is a list whose last entries cannot be reached.
	 */
	public void open(int x, int belowY, int width, int screenHeight,
			List<Option> options, String current, Consumer<String> chosen) {
		this.options = options;
		this.chosen = chosen;
		this.current = current == null ? "" : current;
		this.width = Math.max(60, width);
		this.x = x;
		this.scroll = 0;
		this.open = true;

		int rows = Math.min(SHOWN, options.size());
		int height = rows * ROW + 2;
		this.y = belowY + height <= screenHeight ? belowY : Math.max(0, belowY - height - 18);

		// Opened on the value that is set, so a long list starts where somebody
		// left it rather than at the top.
		int at = indexOf(this.current);
		if (at >= SHOWN) scroll = Math.min(at - SHOWN + 1, Math.max(0, options.size() - SHOWN));
	}

	private int indexOf(String value) {
		for (int at = 0; at < options.size(); at++) {
			if (options.get(at).value().equals(value)) return at;
		}
		return -1;
	}

	/** Takes the click if it belongs to the list. Anything else closes it. */
	public boolean click(double mouseX, double mouseY) {
		if (!open) return false;
		int rows = Math.min(SHOWN, options.size());
		if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + rows * ROW + 2) {
			int row = (int) ((mouseY - y - 1) / ROW);
			int at = scroll + row;
			if (at >= 0 && at < options.size()) {
				Consumer<String> tell = chosen;
				String value = options.get(at).value();
				close();
				if (tell != null) tell.accept(value);
			}
			return true;
		}
		// A press anywhere else means "not that one", which is an answer and not
		// a click on whatever happened to be underneath.
		close();
		return true;
	}

	public boolean scroll(double dy) {
		if (!open) return false;
		scroll = Math.clamp(scroll - (int) Math.signum(dy) * 2, 0,
			Math.max(0, options.size() - SHOWN));
		return true;
	}

	public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (!open) return;
		int rows = Math.min(SHOWN, options.size());
		int height = rows * ROW + 2;

		graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, EDGE);
		graphics.fill(x, y, x + width, y + height, FILL);

		for (int row = 0; row < rows; row++) {
			int at = scroll + row;
			if (at >= options.size()) break;
			Option option = options.get(at);
			int top = y + 1 + row * ROW;
			boolean under = mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + ROW;
			if (under) graphics.fill(x, top, x + width, top + ROW, HOVER);
			boolean isCurrent = option.value().equals(current);
			graphics.text(font, option.label(), x + 4, top + 3,
				isCurrent ? ACCENT : under ? TEXT : TEXT_DIM);
		}

		if (options.size() > SHOWN) {
			// A hint that there is more, drawn as the piece of the list one is
			// looking at rather than as a number.
			int barTop = y + 1 + (int) ((float) scroll / options.size() * (height - 2));
			int barHeight = Math.max(6, (int) ((float) SHOWN / options.size() * (height - 2)));
			graphics.fill(x + width - 2, barTop, x + width - 1, barTop + barHeight, ACCENT);
		}
	}
}
