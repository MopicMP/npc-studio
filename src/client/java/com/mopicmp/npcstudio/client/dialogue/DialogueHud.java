package com.mopicmp.npcstudio.client.dialogue;

import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * The line of dialogue drawn over the hotbar.
 *
 * Deliberately not a screen: the player keeps hold of the game, can still walk
 * and look around, and the world stays in front of them. That is what separates
 * a line spoken in passing from a scene that takes over.
 *
 * Everything is drawn from rectangles and text rather than from an image. The
 * default look only has to be clean and dark, since the ornate frames are the
 * map maker's business — which means no artwork to ship, and a theme that can
 * be described entirely by numbers when the theme system arrives.
 */
public final class DialogueHud {

	/** Where things sit and how big they are. These become theme values later. */
	private static final int HEIGHT = 34;
	private static final int MARGIN = 10;
	private static final int PADDING = 8;
	private static final int ABOVE_HOTBAR = 24;
	private static final int ICON = 20;
	private static final int OPTION_HEIGHT = 18;
	private static final int OPTION_GAP = 2;
	private static final int OPTION_WIDTH = 260;

	private static final int BACKGROUND = 0xCC101014;
	private static final int OPTION_BACKGROUND = 0xE0161A20;
	private static final int OPTION_HOVERED = 0xF0222833;
	private static final int BORDER = 0x33FFFFFF;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;

	/**
	 * How fast the line types itself out, in characters per second.
	 *
	 * Fast enough not to be a wait, slow enough to read as speech. A constant
	 * only until the settings exist; a map maker will want it slower for a solemn
	 * line and faster for a shopkeeper.
	 */
	public static float charsPerSecond() {
		return com.mopicmp.npcstudio.client.NpcStudioConfig.get().typingSpeed;
	}

	private DialogueHud() { }

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		DialogueClientState state = DialogueClientState.current();
		if (state == null) return;
		// The full-screen mode shows the same line in its own layout, and drawing
		// both put the question on screen twice.
		if (DialogueScreen.isOpen() || CutsceneScreen.isOpen()) return;

		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		int width = graphics.guiWidth() - MARGIN * 2;
		int left = MARGIN;
		int top = graphics.guiHeight() - ABOVE_HOTBAR - HEIGHT;

		graphics.fill(left, top, left + width, top + HEIGHT, BACKGROUND);
		// A single hairline rather than a frame: at this size a thicker border
		// reads as a box around the text instead of a surface under it.
		graphics.fill(left, top, left + width, top + 1, BORDER);
		graphics.fill(left, top + HEIGHT - 1, left + width, top + HEIGHT, BORDER);

		int cursor = left + PADDING;

		Entity speaker = client.level == null ? null : client.level.getEntity(state.npcId());
		if (speaker instanceof NpcEntity npc) {
			DialogueIcon.draw(graphics, npc, cursor, top + (HEIGHT - ICON) / 2, ICON);
			cursor += ICON + PADDING;
		}

		int textTop = top + (HEIGHT - font.lineHeight) / 2;

		if (!state.speaker().isEmpty()) {
			Component name = Component.literal(state.speaker());
			graphics.text(font, name, cursor, textTop, ACCENT);
			int nameWidth = font.width(name);
			// A short rule under the name. The one flourish worth keeping by default:
			// it separates who is speaking from what they said without a second colour.
			graphics.fill(cursor, textTop + font.lineHeight + 1,
				cursor + nameWidth, textTop + font.lineHeight + 2, ACCENT);
			cursor += nameWidth + PADDING;
		}

		graphics.text(font, Component.literal(state.visibleText(charsPerSecond())), cursor, textTop, TEXT);

		drawOptions(graphics, font, state, left, top);
	}

	/**
	 * The answers, stacked above the bar.
	 *
	 * Above rather than below, because below is the hotbar. They are narrow
	 * rather than full width: a line of dialogue runs the width of the screen
	 * because it is prose, but an answer is a thing to be picked, and something
	 * pickable should look like it has edges.
	 */
	private static void drawOptions(GuiGraphicsExtractor graphics, Font font,
			DialogueClientState state, int left, int barTop) {
		var options = state.options();
		if (options.isEmpty()) return;

		int hovered = hoveredOption(state);
		int bottom = barTop - 4;
		int top = bottom - (OPTION_HEIGHT + OPTION_GAP) * options.size();

		for (int i = 0; i < options.size(); i++) {
			var option = options.get(i);
			int y = top + (OPTION_HEIGHT + OPTION_GAP) * i;
			boolean isHovered = i == hovered;
			int colour = colourOf(option.colour());

			graphics.fill(left, y, left + OPTION_WIDTH, y + OPTION_HEIGHT,
				isHovered ? OPTION_HOVERED : OPTION_BACKGROUND);
			// A bar down the left edge in the option's own colour, thicker when the
			// mouse is over it — the same signal twice, so it survives being read
			// quickly or by someone who cannot separate those colours.
			graphics.fill(left, y, left + (isHovered ? 3 : 2), y + OPTION_HEIGHT, colour);
			if (isHovered) {
				graphics.fill(left, y, left + OPTION_WIDTH, y + 1, colour);
				graphics.fill(left, y + OPTION_HEIGHT - 1, left + OPTION_WIDTH, y + OPTION_HEIGHT, colour);
			}

			int textY = y + (OPTION_HEIGHT - font.lineHeight) / 2;
			graphics.text(font, Component.literal(String.valueOf(option.index())),
				left + PADDING, textY, TEXT_DIM);
			graphics.text(font, Component.literal(option.label()),
				left + PADDING + 12, textY, isHovered ? colour : TEXT);
		}

		if (hovered < 0) {
			graphics.text(font, Component.literal("press T, then click an answer"),
				left + PADDING, top - font.lineHeight - 3, TEXT_DIM);
		}
	}

	/**
	 * Which answer the mouse is over, or -1.
	 *
	 * Only while the chat screen is open, because that is the only time the game
	 * gives the player a cursor without taking the world away. It is a stopgap —
	 * the full-screen mode will have a proper pointer of its own — but it means
	 * answers can be clicked today instead of typed as numbers.
	 */
	public static int hoveredOption(DialogueClientState state) {
		Minecraft client = Minecraft.getInstance();
		if (!DialogueClientState.isChatOpen()) return -1;

		double scale = client.getWindow().getGuiScale();
		int mouseX = (int) (client.mouseHandler.xpos() / scale);
		int mouseY = (int) (client.mouseHandler.ypos() / scale);

		int barTop = client.getWindow().getGuiScaledHeight() - ABOVE_HOTBAR - HEIGHT;
		int count = state.options().size();
		int top = barTop - 4 - (OPTION_HEIGHT + OPTION_GAP) * count;

		if (mouseX < MARGIN || mouseX > MARGIN + OPTION_WIDTH) return -1;
		int index = (mouseY - top) / (OPTION_HEIGHT + OPTION_GAP);
		return index >= 0 && index < count ? index : -1;
	}

	/**
	 * A colour name from the dialogue turned into something to draw with.
	 *
	 * Only the handful a writer actually reaches for. An unknown name falls back
	 * to the accent rather than failing, because a mistyped colour should not
	 * take the option away — the player still has to be able to answer.
	 */
	private static int colourOf(String name) {
		return switch (name) {
			case "red" -> 0xFFEF5350;
			case "green" -> 0xFF66BB6A;
			case "yellow" -> 0xFFFFCA28;
			case "pink", "magenta" -> 0xFFEC407A;
			case "grey", "gray" -> 0xFF90A4AE;
			default -> ACCENT;
		};
	}
}
