package com.mopicmp.npcstudio.client.server;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text field in this screen's own clothes.
 *
 * The vanilla field is a black hole with a white hairline, drawn for a game menu
 * in 2011; sitting on a flat dark panel next to flat buttons it reads as a
 * fragment of a different program. What is <em>not</em> done here is writing a
 * text field from scratch — selection, the caret, the clipboard, moving by word,
 * every key on the keyboard — because reimplementing all of that to change a
 * rectangle is how a screen acquires its own text bugs.
 *
 * So the vanilla one keeps doing the typing with its border switched off, and
 * the frame is drawn behind it: a filled panel, a hairline edge, and an accent
 * line along the bottom when it has the keyboard. The line is how a field says
 * it is listening, which the vanilla one never says at all.
 */
public final class Field {

	private Field() {
	}

	private static final int FILL = 0xFF0E1116;
	private static final int EDGE = 0xFF2C333D;
	private static final int EDGE_FOCUSED = 0xFF3D4855;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_OFF = 0xFF6B7683;

	/** Where the text starts inside the frame. */
	public static final int INSET = 5;

	/**
	 * A field that draws nothing of its own.
	 *
	 * The width given is the frame's; the box inside it is narrower by the inset
	 * on both sides, so the text never touches the edge it is drawn against.
	 */
	public static EditBox make(Font font, int x, int y, int width, int height, Component label) {
		int frameX = x;
		int frameY = y;
		int frameWide = width;
		int frameTall = height;
		EditBox box = new EditBox(font, x + INSET, y + (height - 8) / 2,
			width - INSET * 2, 8, label) {

			/**
			 * The frame takes the press, not the line of text inside it.
			 *
			 * The box is eight pixels tall — the height of a letter — sitting in
			 * the middle of a frame more than twice that. Left alone, most of what
			 * is drawn as a text field is dead: a click near its top or its bottom
			 * reaches nothing, so the field never takes the keyboard, the typing
			 * goes wherever the focus happened to be, and the button beside it is
			 * then pressed with an empty field behind it. What looks like one
			 * control has to answer as one control.
			 */
			@Override
			public boolean isMouseOver(double mouseX, double mouseY) {
				return active && visible
					&& mouseX >= frameX && mouseX < frameX + frameWide
					&& mouseY >= frameY && mouseY < frameY + frameTall;
			}
		};
		box.setBordered(false);
		box.setTextColor(TEXT);
		box.setTextColorUneditable(TEXT_OFF);
		return box;
	}

	/**
	 * The frame, drawn before the field.
	 *
	 * Takes the frame's own rectangle rather than the box's, because the box was
	 * made smaller to sit inside it — asking the widget where it is would draw
	 * the frame inside the frame.
	 */
	public static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
			boolean focused, boolean editable) {
		graphics.fill(x, y, x + width, y + height, focused ? EDGE_FOCUSED : EDGE);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, FILL);
		if (focused) graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, ACCENT);
		if (!editable) graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x30000000);
	}
}
