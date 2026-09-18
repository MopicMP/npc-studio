package com.mopicmp.npcstudio.client.server;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A number that can be typed, stepped or dragged.
 *
 * Taken from Blockbench, where every number behaves like this and it is the
 * thing people miss most in every other editor: an arrow at each end for one
 * step, and — the good part — pick the number up and move the mouse sideways to
 * run it up or down. Right is more, left is less, and the number under the
 * cursor is the one changing, so the hand never leaves the value it is thinking
 * about.
 *
 * Typing still works, because dragging is for finding a number and typing is for
 * knowing one. A drag only starts after a few pixels of travel, so a click that
 * wanders slightly is still a click and puts the caret in.
 *
 * Written rather than wrapped around a text box: the vanilla field would have to
 * be told to ignore the mouse it is sitting under, and a number needs about
 * fifteen keys, not the whole keyboard.
 */
public class NumberField extends AbstractWidget {

	private static final int FILL = 0xFF0E1116;
	private static final int EDGE = 0xFF2C333D;
	private static final int EDGE_LIT = 0xFF3D4855;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF6B7683;

	/** How wide the arrow at each end is. */
	private static final int ARROW = 11;

	/** Pixels of sideways travel worth one step. */
	private static final int PER_STEP = 6;

	/** Travel before a press becomes a drag rather than a click. */
	private static final int SLOP = 3;

	private final double min;
	private final double max;
	private final double step;
	private final int decimals;
	private final String suffix;
	private final Consumer<Double> changed;

	private double value;
	private String typed;
	private boolean editing;
	private double carried;
	private double travelled;
	private boolean dragging;

	public NumberField(int x, int y, int width, int height, Component label,
			double value, double min, double max, double step, int decimals, String suffix,
			Consumer<Double> changed) {
		super(x, y, width, height, label);
		this.value = value;
		this.min = min;
		this.max = max;
		this.step = step;
		this.decimals = decimals;
		this.suffix = suffix;
		this.changed = changed;
	}

	public double value() {
		return value;
	}

	public String text() {
		if (decimals == 0) return String.valueOf((long) Math.round(value));
		return String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
	}

	private void set(double wanted) {
		double before = value;
		value = Math.clamp(round(wanted), min, max);
		if (value != before) changed.accept(value);
	}

	/** Kept on the step, so dragging never leaves a number nobody could type. */
	private double round(double raw) {
		if (step <= 0) return raw;
		return Math.round(raw / step) * step;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
			int mouseX, int mouseY, float delta) {
		boolean lit = isHovered() || editing;
		graphics.fill(getX(), getY(), getX() + width, getY() + height, lit ? EDGE_LIT : EDGE);
		graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, FILL);
		if (editing) {
			graphics.fill(getX() + 1, getY() + height - 2, getX() + width - 1, getY() + height - 1,
				ACCENT);
		}

		var font = Minecraft.getInstance().font;
		int middle = getY() + (height - 8) / 2;

		// The arrows appear with the mouse rather than sitting there always: a row
		// of settings with two arrows on every line is a row of arrows.
		if (lit) {
			boolean onLeft = overLeft(mouseX, mouseY);
			boolean onRight = overRight(mouseX, mouseY);
			graphics.fill(getX() + 1, getY() + 1, getX() + ARROW, getY() + height - 1,
				onLeft ? 0xFF2C3541 : 0xFF171C23);
			graphics.fill(getX() + width - ARROW, getY() + 1, getX() + width - 1, getY() + height - 1,
				onRight ? 0xFF2C3541 : 0xFF171C23);
			graphics.text(font, "‹", getX() + (ARROW - font.width("‹")) / 2 + 1, middle,
				onLeft ? ACCENT : TEXT_DIM);
			graphics.text(font, "›", getX() + width - ARROW + (ARROW - font.width("›")) / 2 - 1,
				middle, onRight ? ACCENT : TEXT_DIM);
		}

		String shown = editing ? typed : text() + suffix;
		int room = width - (lit ? ARROW * 2 + 4 : 8);
		int textX = getX() + width / 2 - Math.min(font.width(shown), room) / 2;
		graphics.text(font, font.plainSubstrByWidth(shown, room), textX, middle,
			active ? TEXT : TEXT_DIM);
		if (editing) {
			int caret = textX + font.width(shown);
			graphics.fill(caret, middle - 1, caret + 1, middle + 9, ACCENT);
		}
	}

	private boolean overLeft(double mouseX, double mouseY) {
		return mouseY >= getY() && mouseY < getY() + height
			&& mouseX >= getX() && mouseX < getX() + ARROW;
	}

	private boolean overRight(double mouseX, double mouseY) {
		return mouseY >= getY() && mouseY < getY() + height
			&& mouseX >= getX() + width - ARROW && mouseX < getX() + width;
	}

	// ------------------------------------------------------------------ mouse

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active) return;
		if (overLeft(event.x(), event.y())) {
			set(value - step);
			return;
		}
		if (overRight(event.x(), event.y())) {
			set(value + step);
			return;
		}
		carried = value;
		travelled = 0;
		dragging = false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!active || overLeft(event.x(), event.y()) && !dragging) return false;
		travelled += dragX;
		if (!dragging && Math.abs(travelled) < SLOP) return true;
		dragging = true;

		// Held keys change what a pixel is worth, which is the difference between
		// finding a number and finding roughly a number.
		double scale = step;
		if (event.hasShiftDown()) scale = step / 10;
		if (event.hasControlDown()) scale = step * 10;
		set(carried + travelled / PER_STEP * scale);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			return true;
		}
		// A press that never travelled is a press: put the caret in and let it be
		// typed.
		if (isMouseOver(event.x(), event.y()) && !overLeft(event.x(), event.y())
			&& !overRight(event.x(), event.y()) && active) {
			editing = true;
			typed = text();
			setFocused(true);
		}
		return false;
	}

	// ------------------------------------------------------------------ keys

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!editing) return false;
		char c = event.codepoint() < 0x10000 ? (char) event.codepoint() : ' ';
		if (Character.isDigit(c) || c == '.' || c == '-' && typed.isEmpty()) {
			typed = typed + c;
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!editing) {
			// The arrows on the keyboard do what the arrows on the field do.
			if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
				set(value - step);
				return true;
			}
			if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
				set(value + step);
				return true;
			}
			return false;
		}
		switch (event.key()) {
			case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
				if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
				return true;
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
				commit();
				return true;
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
				editing = false;
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private void commit() {
		editing = false;
		try {
			set(Double.parseDouble(typed.trim()));
		} catch (NumberFormatException notANumber) {
			// What was typed was not a number, so the number does not change. The
			// field snaps back to what it was, which says so without a message.
		}
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		if (!focused && editing) commit();
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
