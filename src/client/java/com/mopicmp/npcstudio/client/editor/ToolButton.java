package com.mopicmp.npcstudio.client.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A small square button with a picture on it and a name it only says when asked.
 *
 * <h2>Why the name is not on the button</h2>
 *
 * Because a toolbar of named buttons is not a toolbar, it is a menu, and a menu
 * has to live down one side of the window where it takes up the room the picture
 * wanted. Every drawing program in the world settled this a long time ago: the
 * picture is on the button and the name appears when you rest on it.
 *
 * The name is handed back to the screen rather than drawn in a floating box, so
 * that it lands in the one line of text the screen already keeps for saying what
 * is going on. One place where words appear is easier to read than words that
 * come and go wherever the pointer happens to be.
 *
 * <h2>Why it is not {@link IconButton}</h2>
 *
 * That one draws one of six fixed shapes for the animation picker's transport
 * controls, and its picture is a switch statement. This one is handed a picture
 * from {@link Icons}, because a marking tool has twenty of them and twenty cases
 * in a switch is not a design, it is a list that has escaped.
 */
public class ToolButton extends AbstractWidget {

	private static final int FILL = 0xFF1B2028;
	private static final int FILL_HOVER = 0xFF272F3A;
	private static final int FILL_ON = 0xFF2E3A48;
	private static final int EDGE = 0xFF2C333D;
	private static final int INK = 0xFFC7D0DA;
	private static final int INK_ON = 0xFFECEFF1;

	private final String[] icon;
	private final int accent;
	private final Runnable onPress;
	private final java.util.function.BooleanSupplier lit;
	private final String says;

	/**
	 * @param icon   the picture, from {@link Icons}
	 * @param accent the colour of the {@code +} cells in it — the one thing that
	 *               tells four eye-shaped buttons apart at a glance
	 * @param says   what this does, in words, for the line at the top
	 * @param lit    whether this is the one currently chosen
	 */
	public ToolButton(int x, int y, int size, String[] icon, int accent, String says,
			java.util.function.BooleanSupplier lit, Runnable onPress) {
		super(x, y, size, size, Component.literal(says));
		this.icon = icon;
		this.accent = accent;
		this.says = says;
		this.lit = lit;
		this.onPress = onPress;
	}

	public String says() {
		return says;
	}

	public boolean on() {
		return lit != null && lit.getAsBoolean();
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onPress.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
			int mouseX, int mouseY, float delta) {
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();
		boolean chosen = on();

		graphics.fill(left, top, right, bottom,
			chosen ? FILL_ON : isHovered() ? FILL_HOVER : FILL);
		// The chosen one is outlined in its own accent rather than merely lit, so
		// that which tool is in your hand is answerable from across the window.
		int edge = chosen ? accent : EDGE;
		graphics.fill(left, top, right, top + 1, edge);
		graphics.fill(left, bottom - 1, right, bottom, edge);
		graphics.fill(left, top, left + 1, bottom, edge);
		graphics.fill(right - 1, top, right, bottom, edge);

		Icons.draw(graphics, icon, left, top, getWidth(), getHeight(),
			chosen ? INK_ON : INK, accent);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
