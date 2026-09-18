package com.mopicmp.npcstudio.client.dialogue;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.wardrobe.Costumes;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.net.ShowPortraitPayload;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The figure standing at the edge of the scene.
 *
 * <h2>How it is sized</h2>
 *
 * By the height of the screen, not by the picture. A portrait is a person standing
 * beside the conversation, and a person is as tall as the frame — a drawing sized by
 * its own pixels would be a postage stamp on one monitor and fill the room on
 * another. So the height is a share of the screen and the width follows from the
 * picture's own proportions, which is what keeps a tall narrow figure narrow.
 *
 * <h2>Why it is cropped by the bottom of the screen</h2>
 *
 * Because that is what standing in front of the camera looks like, and it is what the
 * game this is taken from does. A whole figure with air under its feet reads as a
 * sticker; one that runs off the bottom reads as somebody in the room.
 *
 * <h2>A stack rather than a picture</h2>
 *
 * A figure arrives as several pictures with corners on a shared canvas — a body, an
 * outfit, a face — because that is how the drawings people have are actually made. The
 * canvas is scaled to the screen once and every layer follows it, so nothing here has to
 * know which layer is which: it draws what it was handed, in the order it was handed.
 *
 * A single whole picture is the same thing with one layer and no canvas, which is why
 * there is no second path through this file for it.
 *
 * <h2>Appearing and leaving</h2>
 *
 * One layer at a time, and the bookkeeping for it is in {@link Fading} — including why
 * it is per layer rather than per figure, which is the only interesting decision in it.
 * What is left here is the drawing: departing pictures first, then the ones that are
 * staying or arriving on top of them.
 */
public final class Portraits {

	private Portraits() { }

	/** How much of the screen's height a figure takes. Most of it, cropped at the feet. */
	private static final float TALL = 0.92f;

	/** How far in from the edge it stands, as a share of the width. */
	private static final float INSET = 0.02f;

	/**
	 * As many pictures as may be part-way through leaving at once.
	 *
	 * Twice what one figure may hold, so a whole portrait swapping for another whole
	 * portrait fits with room over. Past that the oldest are dropped: they are the
	 * faintest, and a list that grows without a bound because scenes changed quickly is
	 * a leak whatever it is holding.
	 */
	private static final int MOST_GOING = ShowPortraitPayload.MOST_LAYERS * 2;

	private static List<Fading.Held> standing = List.of();
	private static final List<Fading.Going> going = new ArrayList<>();

	private static int canvasWide;
	private static int canvasHigh;
	private static Effect.Portrait.Side side = Effect.Portrait.Side.RIGHT;
	private static boolean mirrored;

	public static void show(ShowPortraitPayload what) {
		long clock = Util.getMillis();
		List<ShowPortraitPayload.Layer> now = what.layers();

		// Anything that has gone starts leaving in the shape it was being drawn in, which
		// is the shape held here up to this line — see Fading.Going for why the old canvas
		// has to travel with it rather than being read off the new one.
		going.addAll(Fading.departing(standing, now,
			canvasWide, canvasHigh, side.ordinal(), mirrored, clock));
		// And anything that has finished leaving, or that has come back, comes off the
		// list — see Fading.stillGoing for the cloak that is taken off and put straight
		// back on, which is the case that shows if this is skipped.
		List<Fading.Going> kept = Fading.stillGoing(going, now, clock);
		going.clear();
		going.addAll(kept.size() <= MOST_GOING ? kept
			: kept.subList(kept.size() - MOST_GOING, kept.size()));

		standing = Fading.arriving(standing, now, clock);
		canvasWide = what.wide();
		canvasHigh = what.high();
		var sides = Effect.Portrait.Side.values();
		side = what.side() >= 0 && what.side() < sides.length
			? sides[what.side()] : Effect.Portrait.Side.RIGHT;
		mirrored = what.mirrored();
	}

	/**
	 * Dropped with the world and with the conversation: neither owns the next one.
	 *
	 * Without a fade, deliberately. A world going away is not a scene ending gracefully,
	 * and half a portrait dissolving over a loading screen would be the mod drawing on
	 * something that is not its conversation any more.
	 */
	public static void forget() {
		standing = List.of();
		going.clear();
	}

	public static boolean showing() {
		return !standing.isEmpty() || !going.isEmpty();
	}

	/**
	 * How big a figure is in its own pixels, or null until enough of it has arrived.
	 *
	 * <h2>Why a single picture has no canvas of its own</h2>
	 *
	 * Because the server does not know how big it is — a picture is a file on a shelf,
	 * and reading every header to fill in a number the client already holds would be work
	 * for nothing. So a stack of one with no canvas means "the picture is the canvas",
	 * and the answer waits until that picture has arrived.
	 */
	private static int[] canvasOf(List<ShowPortraitPayload.Layer> layers, int wide, int high) {
		if (wide > 0 && high > 0) return new int[] { wide, high };
		if (layers.size() != 1) return null;
		return Costumes.sizeOf(layers.get(0).picture());
	}

	/** Where a figure's canvas lands on this screen, and how far it was scaled to get there. */
	private record Frame(int left, int top, float scale, int canvasWide, boolean mirrored) { }

	private static Frame frameFor(GuiGraphicsExtractor graphics, int[] canvas,
			Effect.Portrait.Side against, boolean flipped) {
		int screenHigh = graphics.guiHeight();
		int screenWide = graphics.guiWidth();
		int high = (int) (screenHigh * TALL);
		int wide = Math.max(1, Math.round(high * (float) canvas[0] / canvas[1]));
		// Never wider than half the screen: a very wide drawing would otherwise cross
		// the middle and stand in front of the line it is meant to accompany.
		if (wide > screenWide / 2) {
			wide = screenWide / 2;
			high = Math.max(1, Math.round(wide * (float) canvas[1] / canvas[0]));
		}
		int inset = (int) (screenWide * INSET);
		int left = against == Effect.Portrait.Side.LEFT ? inset : screenWide - inset - wide;
		return new Frame(left, screenHigh - high, wide / (float) canvas[0], canvas[0], flipped);
	}

	/**
	 * Draws whatever is standing there and whatever is on its way out.
	 *
	 * Asking for a texture is what starts the fetch, and {@link Costumes#texture} carries
	 * the guard that makes that safe to do from drawing — asked once, not sixty times a
	 * second. A layer that has not landed yet is skipped rather than waited for: the rest
	 * of the figure is worth more than an empty edge of the screen, and the missing one
	 * appears a moment later without anything having to notice.
	 *
	 * The departing pictures go first so that the arriving ones cover them as they come
	 * in. The other order would have a face dissolving <em>over</em> the face replacing
	 * it, which is the same two pictures and the wrong story about which is which.
	 */
	public static void draw(GuiGraphicsExtractor graphics) {
		long clock = Util.getMillis();
		going.removeIf(each -> Fading.through(each.since(), clock) >= 1f);

		for (Fading.Going each : going) {
			int[] canvas = canvasOf(List.of(each.layer()), each.wide(), each.high());
			if (canvas == null) continue;
			var sides = Effect.Portrait.Side.values();
			var against = each.side() >= 0 && each.side() < sides.length
				? sides[each.side()] : Effect.Portrait.Side.RIGHT;
			drawLayer(graphics, each.layer(),
				frameFor(graphics, canvas, against, each.mirrored()),
				Fading.tint(1f - Fading.through(each.since(), clock)));
		}

		if (standing.isEmpty()) return;
		List<ShowPortraitPayload.Layer> layers = standing.stream().map(Fading.Held::layer).toList();
		int[] canvas = canvasOf(layers, canvasWide, canvasHigh);
		if (canvas == null) return;
		Frame frame = frameFor(graphics, canvas, side, mirrored);
		for (Fading.Held held : standing) {
			drawLayer(graphics, held.layer(), frame,
				Fading.tint(Fading.through(held.since(), clock)));
		}
	}

	private static void drawLayer(GuiGraphicsExtractor graphics, ShowPortraitPayload.Layer layer,
			Frame frame, int tint) {
		if (tint == 0) return;
		Identifier texture = Costumes.texture(layer.picture());
		int[] size = Costumes.sizeOf(layer.picture());
		if (texture == null || size == null) return;

		int drawWide = Math.max(1, Math.round(size[0] * frame.scale()));
		int drawHigh = Math.max(1, Math.round(size[1] * frame.scale()));
		// Mirroring is done to the whole figure, not to each picture: a layer flipped
		// where it stands would leave the eyes on the wrong side of a reversed face. So
		// the corner is reflected across the canvas as well as the drawing itself.
		int acrossCanvas = frame.mirrored()
			? frame.canvasWide() - layer.x() - size[0]
			: layer.x();
		int x = frame.left() + Math.round(acrossCanvas * frame.scale());
		int y = frame.top() + Math.round(layer.y() * frame.scale());

		// Flipped by drawing the picture from its right edge to its left, which costs
		// nothing and needs no second file. One drawing serves both sides, which is the
		// whole reason the flip is a property of the showing rather than of the picture.
		float from = frame.mirrored() ? size[0] : 0;
		float across = frame.mirrored() ? -size[0] : size[0];
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y,
			from, 0f, drawWide, drawHigh, (int) across, size[1], size[0], size[1], tint);
	}

	/** Whether anything is worth drawing right now, for the caller that owns the frame. */
	public static boolean ready() {
		return showing() && Minecraft.getInstance().level != null;
	}
}
