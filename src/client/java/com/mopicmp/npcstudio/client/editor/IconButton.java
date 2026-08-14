package com.mopicmp.npcstudio.client.editor;


import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A small square button with a shape drawn on it.
 *
 * The animation picker's transport controls and its view switches. Drawn rather
 * than textured, like the rest of the editor, so there is still nothing to ship
 * but code — and small enough that a row of them reads as a toolbar rather than
 * as a stack of labelled choices.
 *
 * The shape is a supplier rather than a value because two of these change under
 * the pointer: play becomes pause, and a label counts up. Asking every frame is
 * cheaper than remembering to set it.
 */
public class IconButton extends AbstractWidget {

	private static final int FILL = 0xFF1B2028;
	private static final int FILL_HOVER = 0xFF262F3A;
	private static final int MARK = 0xFFECEFF1;

	/** What is drawn on the face of the button. */
	public enum Shape { PLAY, PAUSE, RESTART, GRID, FACE, TEXT }

	private final Runnable onPress;
	private final int accent;
	private final Supplier<Shape> shape;
	private final Supplier<String> text;

	public IconButton(int x, int y, int size, Supplier<Shape> shape, Supplier<String> text,
			int accent, Component tooltip, Runnable onPress) {
		super(x, y, size, size, tooltip);
		this.shape = shape;
		this.text = text;
		this.accent = accent;
		this.onPress = onPress;
		setTooltip(Tooltip.create(tooltip));
	}

	/** One that always shows the same shape. */
	public static IconButton of(int x, int y, int size, Shape shape, int accent,
			Component tooltip, Runnable onPress) {
		return new IconButton(x, y, size, () -> shape, () -> "", accent, tooltip, onPress);
	}

	/**
	 * One that shows a word or a number instead of a shape.
	 *
	 * Wider than it is tall, which is why the width is set after construction: the
	 * shaped ones are square by definition and a label is whatever length it is.
	 */
	public static IconButton labelled(int x, int y, int width, int height,
			Supplier<String> text, int accent, Component tooltip, Runnable onPress) {
		IconButton made = new IconButton(x, y, height, () -> Shape.TEXT, text,
			accent, tooltip, onPress);
		made.setWidth(width);
		return made;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onPress.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
			int mouseX, int mouseY, float delta) {
		boolean hovered = isHovered();
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();
		int mark = hovered ? accent : MARK;

		graphics.fill(left, top, right, bottom, hovered ? FILL_HOVER : FILL);

		switch (shape.get()) {
			case TEXT -> {
				var font = Minecraft.getInstance().font;
				String label = text.get();
				graphics.text(font, Component.literal(label),
					left + (getWidth() - font.width(label)) / 2,
					top + (getHeight() - font.lineHeight) / 2 + 1, mark);
			}
			case PAUSE -> {
				int from = top + 4;
				int to = bottom - 4;
				graphics.fill(left + 5, from, left + 7, to, mark);
				graphics.fill(right - 7, from, right - 5, to, mark);
			}
			case PLAY -> {
				// A triangle drawn a row at a time, widest in the middle, so it comes
				// out symmetrical at any size without any arithmetic about slopes.
				int tall = getHeight() - 8;
				for (int i = 0; i < tall; i++) {
					int from = Math.abs(i - tall / 2);
					int wide = tall / 2 - from + 1;
					graphics.fill(left + 6, top + 4 + i, left + 6 + wide, top + 5 + i, mark);
				}
			}
			case RESTART -> {
				graphics.fill(left + 5, top + 5, right - 5, top + 7, mark);
				graphics.fill(left + 5, top + 5, left + 7, bottom - 5, mark);
				graphics.fill(left + 5, bottom - 7, right - 7, bottom - 5, mark);
				graphics.fill(right - 8, top + 4, right - 6, top + 9, mark);
			}
			case FACE -> {
				graphics.fill(left + 4, top + 4, right - 4, bottom - 4, mark);
				int inner = hovered ? FILL_HOVER : FILL;
				graphics.fill(left + 6, top + 7, left + 8, top + 10, inner);
				graphics.fill(right - 8, top + 7, right - 6, top + 10, inner);
				graphics.fill(left + 7, bottom - 8, right - 7, bottom - 6, inner);
			}
			case GRID -> {
				for (int row = 0; row < 2; row++) {
					for (int column = 0; column < 2; column++) {
						int x = left + 5 + column * 5;
						int y = top + 5 + row * 5;
						graphics.fill(x, y, x + 3, y + 3, mark);
					}
				}
			}
		}

		if (hovered) graphics.fill(left, bottom - 1, right, bottom, accent);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
