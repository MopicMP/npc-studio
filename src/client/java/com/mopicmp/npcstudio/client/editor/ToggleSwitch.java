package com.mopicmp.npcstudio.client.editor;

import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A switch, not a button.
 *
 * The difference is not decoration. A button says "something will happen when
 * you press me" and a switch says "this is how things are, and you may change
 * it" — and for a setting that is on or off, the second is the true statement.
 * A button has to be read to know its state; a switch shows it: the knob is on
 * the side that is true.
 *
 * What it does is written in a tooltip rather than beside it. A row of controls
 * with their explanations spelled out next to them is mostly explanation, and
 * these sit under a picture somebody is trying to look at.
 */
public class ToggleSwitch extends AbstractWidget {

	private static final int TRACK_OFF = 0xFF1B2028;
	private static final int TRACK_ON = 0xFF25404E;
	private static final int KNOB_OFF = 0xFF6E7A87;
	private static final int EDGE = 0xFF2C333D;

	private final BooleanSupplier state;
	private final Runnable onFlip;
	private final int accent;

	public ToggleSwitch(int x, int y, int width, int height, Component tooltip,
			int accent, BooleanSupplier state, Runnable onFlip) {
		super(x, y, width, height, tooltip);
		this.state = state;
		this.onFlip = onFlip;
		this.accent = accent;
		setTooltip(Tooltip.create(tooltip));
	}

	@Override
	public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		onFlip.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		boolean on = state.getAsBoolean();
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();

		graphics.fill(left, top, right, bottom, EDGE);
		graphics.fill(left + 1, top + 1, right - 1, bottom - 1, on ? TRACK_ON : TRACK_OFF);

		// The knob sits on the side that is true, so which way is on can be read
		// from across the screen without knowing what the control does.
		int knob = getHeight() - 4;
		int knobLeft = on ? right - 2 - knob : left + 2;
		graphics.fill(knobLeft, top + 2, knobLeft + knob, bottom - 2,
			on ? accent : isHovered() ? 0xFF8A99A6 : KNOB_OFF);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		defaultButtonNarrationText(narration);
	}
}
