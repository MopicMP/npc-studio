package com.mopicmp.npcstudio.client.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A button in the editor's own style.
 *
 * The vanilla button is a raised stone slab, which is right for a game menu and
 * wrong sitting on a dark canvas next to wires — it reads as belonging to a
 * different program. This is the same flat treatment as everything else here:
 * a filled rectangle, a hairline edge, an accent that appears under the mouse.
 *
 * Drawn rather than textured, like the rest of the interface, so there is still
 * nothing to ship but code.
 */
public class FlatButton extends AbstractWidget {

	private static final int FILL = 0xFF1B2028;
	private static final int FILL_HOVER = 0xFF232A34;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;

	private final Runnable onPress;
	private final int accent;

	public FlatButton(int x, int y, int width, int height, Component label, int accent, Runnable onPress) {
		super(x, y, width, height, label);
		this.onPress = onPress;
		this.accent = accent;
	}

	@Override
	public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		onPress.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		boolean hovered = isHovered();
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();

		graphics.fill(left, top, right, bottom, EDGE);
		graphics.fill(left + 1, top + 1, right - 1, bottom - 1, hovered ? FILL_HOVER : FILL);
		// The accent appears as a stripe on hover rather than as a permanent
		// border: a row of six outlined buttons is louder than the graph they sit
		// under, and the graph is the thing being looked at.
		if (hovered) graphics.fill(left + 1, bottom - 2, right - 1, bottom - 1, accent);

		var font = net.minecraft.client.Minecraft.getInstance().font;
		graphics.text(font, getMessage(),
			left + (getWidth() - font.width(getMessage())) / 2,
			top + (getHeight() - font.lineHeight) / 2 + 1,
			hovered ? accent : TEXT);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		defaultButtonNarrationText(narration);
	}
}
