package com.mopicmp.npcstudio.client.editor;

import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A slider in the editor's own style.
 *
 * The value is read through a supplier rather than held here, for the same
 * reason the icon buttons draw their own state: a widget that keeps its own
 * copy of a number is a second place for that number to live, and the two
 * disagree the first time anything else changes it.
 */
public class FlatSlider extends AbstractWidget {

	private static final int TRACK = 0xFF1B2028;
	private static final int FILLED = 0xFF25404E;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;

	private final double lowest;
	private final double highest;
	private final double step;
	private final DoubleSupplier value;
	private final Consumer<Double> onChange;
	private final String label;
	private final int accent;

	public FlatSlider(int x, int y, int width, int height, String label,
			double lowest, double highest, double step, int accent,
			DoubleSupplier value, Consumer<Double> onChange) {
		super(x, y, width, height, Component.literal(label));
		this.label = label;
		this.lowest = lowest;
		this.highest = highest;
		this.step = step;
		this.accent = accent;
		this.value = value;
		this.onChange = onChange;
	}

	private void takeFrom(double mouseX) {
		double along = Math.clamp((mouseX - getX()) / getWidth(), 0, 1);
		double raw = lowest + along * (highest - lowest);
		// Snapped, because a size of 1.03 is nobody's intention and the number is
		// shown — a slider that reads 1.00 and means 0.997 invites a bug report.
		onChange.accept(Math.round(raw / step) * step);
	}

	@Override
	public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		takeFrom(event.x());
	}

	@Override
	protected void onDrag(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
		takeFrom(event.x());
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();

		double along = (value.getAsDouble() - lowest) / (highest - lowest);
		int knob = left + (int) (Math.clamp(along, 0, 1) * (getWidth() - 4));

		graphics.fill(left, top, right, bottom, EDGE);
		graphics.fill(left + 1, top + 1, right - 1, bottom - 1, TRACK);
		graphics.fill(left + 1, top + 1, knob, bottom - 1, FILLED);
		graphics.fill(knob, top + 1, knob + 4, bottom - 1, isHovered() ? accent : TEXT);

		var font = net.minecraft.client.Minecraft.getInstance().font;
		String shown = label + "  " + String.format(java.util.Locale.ROOT, "%.2f", value.getAsDouble());
		graphics.text(font, Component.literal(shown),
			left + (getWidth() - font.width(shown)) / 2,
			top + (getHeight() - font.lineHeight) / 2 + 1, TEXT);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		defaultButtonNarrationText(narration);
	}
}
