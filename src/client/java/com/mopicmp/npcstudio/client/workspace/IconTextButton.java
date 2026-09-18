package com.mopicmp.npcstudio.client.workspace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A button that gives up its words before it gives up its place.
 *
 * <h2>The problem it solves</h2>
 *
 * Buttons in these panels were laid out at whatever width their label happened
 * to need, in a row, on a screen that filled the display. Put that row in a
 * panel and the last two buttons are past the edge — and being past the edge is
 * not only ugly, it is unreachable: the only way to press them was to widen the
 * panel until they came back, which is a strange thing to have to do to press
 * a button.
 *
 * <h2>How it behaves</h2>
 *
 * There are two forms and the button picks between them from the width it has
 * been given. Wide enough, and it is an icon with its label beside it. Not wide
 * enough, and it is the icon alone, square, with the label moved to the
 * tooltip. Nothing is lost in the narrow form except reading it at a glance,
 * and the narrow form is {@link #FOLDED} pixels wide — so a row of them fits in
 * a panel that could not hold two of the wide ones.
 */
public class IconTextButton extends AbstractWidget {

	/** How wide a button is with nothing but its icon. */
	public static final int FOLDED = 20;

	private static final int FILL = 0xFF1B2028;
	private static final int FILL_HOVER = 0xFF232A34;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;

	private final Icon icon;
	private final int accent;
	private final Runnable onPress;

	public IconTextButton(int x, int y, int width, int height, Icon icon, Component label,
			int accent, Runnable onPress) {
		super(x, y, width, height, label);
		this.icon = icon;
		this.accent = accent;
		this.onPress = onPress;
		setTooltip(Tooltip.create(label));
	}

	/** How wide this button would like to be to show its words. */
	public static int wide(Component label) {
		return Icon.SIZE + 6 + Minecraft.getInstance().font.width(label) + 12;
	}

	/** Whether there is room for the words at the width it was given. */
	private boolean roomForWords() {
		return width >= wide(getMessage());
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
			int mouseX, int mouseY, float delta) {
		boolean lit = isHoveredOrFocused() && active;
		graphics.fill(getX(), getY(), getX() + width, getY() + height, lit ? FILL_HOVER : FILL);
		graphics.fill(getX(), getY(), getX() + width, getY() + 1, lit ? accent : EDGE);

		int ink = active ? (lit ? TEXT : accent) : 0xFF55606B;
		int middle = getY() + (height - Icon.SIZE) / 2;

		if (!roomForWords()) {
			icon.draw(graphics, getX() + (width - Icon.SIZE) / 2, middle, ink);
			return;
		}
		icon.draw(graphics, getX() + 5, middle, ink);
		graphics.text(Minecraft.getInstance().font, getMessage(),
			getX() + 5 + Icon.SIZE + 4, getY() + (height - 8) / 2, active ? TEXT : 0xFF55606B);
	}

	@Override
	public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (active) onPress.run();
	}

	/**
	 * Enter and space press it, as they do on every other button anywhere.
	 *
	 * This is built on the plain widget rather than on the game's own button,
	 * which brings the drawing and nothing else — so keyboard use has to be
	 * written in, and it was not. Tab reached a control and then there was no way
	 * to use it.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (active && (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
			|| event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
			|| event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)) {
			onPress.run();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}

	// ------------------------------------------------------------------ rows

	/** One button, before it knows where it is going. */
	public record Spec(Icon icon, Component label, int accent, Runnable onPress) { }

	/**
	 * Lays a row of buttons into the width there is, and says how tall it came out.
	 *
	 * All of them fold together or none does. A row where three buttons have
	 * words and two do not reads as a row of five different controls, and the
	 * eye spends longer working out that they are the same kind of thing than it
	 * saves from the three labels.
	 *
	 * If even the folded row will not fit, it wraps. That is the last resort and
	 * it is still better than the alternative, which is buttons off the edge that
	 * cannot be pressed at all.
	 */
	public static int row(int left, int top, int across, int height, java.util.List<Spec> specs,
			java.util.function.Consumer<IconTextButton> add) {
		return row(left, top, across, height, specs, add, false);
	}

	/**
	 * The same, for a row anchored to the bottom of a screen rather than to the top.
	 *
	 * <h2>Why this had to be told</h2>
	 *
	 * A wrapped row grows downwards, which is right for a row that starts at the top and
	 * exactly wrong for one that sits along the bottom: the second row lands below the
	 * screen and its buttons cannot be pressed at all. Which is the failure the wrapping
	 * was added to prevent, arriving from the other direction.
	 *
	 * It is a flag rather than a guess from the coordinates, because a row four pixels
	 * from the bottom of a panel is not the same thing as a row four pixels from the
	 * bottom of the window, and only the caller knows which it is.
	 *
	 * @param upwards true when {@code top} is where the <em>last</em> row should sit
	 */
	public static int row(int left, int top, int across, int height, java.util.List<Spec> specs,
			java.util.function.Consumer<IconTextButton> add, boolean upwards) {
		if (specs.isEmpty()) return 0;
		int gap = 4;
		if (upwards) {
			// Measured first, then placed: how many rows it comes to is what decides
			// where the first one starts, and that cannot be known until it is laid out.
			int rows = rowsFor(across, specs, gap);
			top -= (rows - 1) * (height + gap);
		}

		int wanted = 0;
		for (Spec spec : specs) wanted += wide(spec.label()) + gap;
		boolean words = wanted - gap <= across;


		int perRow = words ? 0 : Math.max(1, (across + gap) / (FOLDED + gap));
		int x = left;
		int y = top;
		int rows = 1;

		for (int i = 0; i < specs.size(); i++) {
			Spec spec = specs.get(i);
			int span = words ? wide(spec.label()) : FOLDED;
			if (!words && i > 0 && i % perRow == 0) {
				x = left;
				y += height + gap;
				rows++;
			}
			add.accept(new IconTextButton(x, y, span, height,
				spec.icon(), spec.label(), spec.accent(), spec.onPress()));
			x += span + gap;
		}
		return rows * height + (rows - 1) * gap;
	}

	/** How many rows a set of buttons will come to in the width there is. */
	private static int rowsFor(int across, java.util.List<Spec> specs, int gap) {
		int wanted = 0;
		for (Spec spec : specs) wanted += wide(spec.label()) + gap;
		if (wanted - gap <= across) return 1;
		int perRow = Math.max(1, (across + gap) / (FOLDED + gap));
		return (specs.size() + perRow - 1) / perRow;
	}
}
