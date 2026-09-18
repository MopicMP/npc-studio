package com.mopicmp.npcstudio.client.dialogue;

import java.util.List;

import com.mopicmp.npcstudio.net.GaugePayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * What the player is carrying, on screen.
 *
 * <h2>Why this exists at all</h2>
 *
 * Because a variable the player cannot see is a variable they do not know about. An
 * ability gated on energy, without this, is an ability that works sometimes for reasons
 * nobody in the game can find out — which is the same silence this project has spent its
 * time breaking everywhere else, arriving at last on the player's own side of the screen.
 *
 * <h2>Why this side knows nothing</h2>
 *
 * It is handed a label, a number and a corner. Documents, variables, scopes and
 * conditions all stay on the other side, which means a gauge cannot disagree with the
 * graph: there is no second reading of anything to drift.
 *
 * <h2>Why corners and not coordinates</h2>
 *
 * Because a coordinate is right on one screen and wrong on every other, and because the
 * four corners are what anybody means when they say where something should go. Several
 * in one corner stack downwards from it, so a second gauge does not land on the first.
 */
public final class Gauges {

	private Gauges() { }

	private static List<GaugePayloads.Shown> showing = List.of();

	/** How tall one gauge is, label and bar together. */
	private static final int ROW = 20;

	/** How wide a bar is drawn. Enough to read a share off, short enough to ignore. */
	private static final int WIDE = 72;

	private static final int EDGE = 0xFF1A1F26;
	private static final int TRACK = 0x80000000;

	public static void accept(List<GaugePayloads.Shown> now) {
		showing = List.copyOf(now);
	}

	/** Dropped with the world. The next one shows whatever its own documents say. */
	public static void forget() {
		showing = List.of();
	}

	/**
	 * How far down the top left corner is already spoken for.
	 *
	 * Asked by the watch mode, which writes its account down the same edge. Two things
	 * drawing from the same corner is a collision that costs nothing to avoid and is
	 * invisible until somebody turns the second one on — which, for a debugging tool,
	 * is exactly the moment they are least able to guess why the screen looks wrong.
	 */
	public static int takenTopLeft() {
		int rows = 0;
		for (GaugePayloads.Shown gauge : showing) {
			if ("top_left".equals(gauge.corner())) rows++;
		}
		return rows == 0 ? 0 : 8 + rows * ROW;
	}

	public static void draw(GuiGraphicsExtractor graphics) {
		if (showing.isEmpty()) return;
		Font font = Minecraft.getInstance().font;

		// Counted per corner so that two gauges in one corner stack rather than overlap,
		// and so that a gauge in another corner is not pushed down by them.
		int[] taken = new int[4];
		for (GaugePayloads.Shown gauge : showing) {
			int corner = cornerOf(gauge.corner());
			int y = rowTop(graphics, corner, taken[corner]++);
			int x = leftOf(graphics, corner);

			graphics.text(font, Component.literal(gauge.label()), x, y, gauge.colour());
			if ("number".equals(gauge.look())) {
				// The number where the bar would have been, so a screen of both lines up.
				graphics.text(font, Component.literal(number(gauge.value())),
					x, y + 10, gauge.colour());
				continue;
			}

			int top = y + 11;
			graphics.fill(x - 1, top - 1, x + WIDE + 1, top + 6, EDGE);
			graphics.fill(x, top, x + WIDE, top + 5, TRACK);
			int full = Math.round(WIDE * gauge.full());
			if (full > 0) graphics.fill(x, top, x + full, top + 5, gauge.colour());
		}
	}

	/** A number as somebody would write it: three, not three point nought. */
	private static String number(double value) {
		return value == Math.floor(value) && !Double.isInfinite(value)
			? String.valueOf((long) value) : String.valueOf(value);
	}

	private static int cornerOf(String written) {
		return switch (written) {
			case "top_right" -> 1;
			case "bottom_left" -> 2;
			case "bottom_right" -> 3;
			default -> 0;
		};
	}

	private static int leftOf(GuiGraphicsExtractor graphics, int corner) {
		return corner == 1 || corner == 3 ? graphics.guiWidth() - WIDE - 8 : 8;
	}

	/**
	 * Where a gauge's row starts.
	 *
	 * Downwards from the top and upwards from the bottom, so that in every corner the
	 * first one written is the one nearest that corner — which is what somebody
	 * ordering them in the editor means.
	 */
	private static int rowTop(GuiGraphicsExtractor graphics, int corner, int nth) {
		return corner < 2
			? 8 + nth * ROW
			: graphics.guiHeight() - 40 - nth * ROW;
	}
}
