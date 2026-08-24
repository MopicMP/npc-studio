package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.scene.Cue;
import com.mopicmp.npcstudio.scene.Look;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The dark, and the words on it.
 *
 * <h2>Why this is a piece of interface and not a piece of the world</h2>
 *
 * Because a fade is not darkness. Turning the sun down would be the other way to
 * open a scene out of black and it is the wrong one: the water still glitters,
 * the torches still burn, the sky is still a sky — what you get is night, not a
 * fade. A fade is a sheet of black laid over the finished frame, after
 * everything else has been drawn, and that is exactly what this is.
 *
 * <h2>Why the last element of the game's own interface</h2>
 *
 * Because it has to be over the hotbar, the crosshair and the chat as well as
 * over the world, and because of when it is drawn. The workspace paints nothing
 * at all while a capture is running — that is how the panels stay out of the film
 * — so anything drawn as part of a panel is a thing the film does not contain.
 * The game's own interface is drawn either way, so the fade and the words land in
 * the film and on the screen from one piece of code.
 *
 * The consequence worth knowing: with the panels open, the dark covers the
 * viewport and the panels sit on top of it, which is right. You are looking at
 * the shot through them, and the shot is dark.
 *
 * <h2>The cursor is read per frame and not per tick</h2>
 *
 * {@code Playing.showing()} keeps a list of what should be on screen, refreshed
 * on the tick — and never refreshed at all while a capture is running, because a
 * capture moves the cursor itself and returns early from that tick. So the list
 * would have held whatever was true when filming started, for the whole film.
 * Asked here directly instead, at the cursor the frame is being drawn for, which
 * is also what stops a fade from arriving in twenty steps a second.
 */
public final class Titles {

	/**
	 * How long a line takes to arrive and to leave, in ticks.
	 *
	 * A quarter of a second each way. A title that appears between two frames
	 * reads as a glitch rather than as a title, and the alternative — asking
	 * somebody to key the fade around every line — would be making them do by hand
	 * the one thing that is the same every time.
	 *
	 * Taken out of the line's own span rather than added to it, so a line asked to
	 * last two seconds occupies two seconds.
	 */
	private static final double ARRIVES = 5;

	private Titles() { }

	/**
	 * How solid a line is at a moment, nought to one.
	 *
	 * Pure, and separate from the drawing, because it is the one part of this that
	 * has an answer somebody can be wrong about. Everything else is a rectangle.
	 *
	 * @param since how many ticks the line has been up
	 * @param lasts how long it was asked to stay
	 */
	public static double solidity(double since, int lasts) {
		if (lasts <= 0 || since < 0 || since >= lasts) return 0;
		// A short line gets a proportionally short arrival rather than none: half of
		// it in and half of it out is still legible, and it stops a line of three
		// ticks from being invisible because the fade is longer than the line.
		double edge = Math.min(ARRIVES, lasts / 2.0);
		if (edge <= 0) return 1;
		return Math.clamp(Math.min(since, lasts - since) / edge, 0, 1);
	}

	// ------------------------------------------------------------------ drawing

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Scene scene = Playing.scene();
		if (scene == null) return;
		double at = Playing.now(delta.getGameTimeDeltaPartialTick(true));

		// The frame rather than the window, and that is the whole of what was wrong
		// with the small view: the film is the viewport while one is showing, so a
		// title placed by fractions of the window landed off-centre in the picture
		// somebody was actually looking at. One answer, asked once, used by the fade,
		// the words and the crop alike. See {@link Framing}.
		Framing frame = Framing.now();

		float dark = Weather.fadeAt(at);
		if (dark > 0.002f) {
			int alpha = (int) Math.clamp(Math.round(dark * 255), 0, 255);
			graphics.fill(frame.left(), frame.top(),
				frame.left() + frame.wide(), frame.top() + frame.tall(), alpha << 24);
		}

		for (Cue line : scene.showingAt((int) Math.floor(at))) {
			double solid = solidity(at - line.at(), line.lasts());
			int alpha = (int) Math.clamp(Math.round(solid * 255), 0, 255);
			if (alpha > 2) draw(graphics, line, alpha, frame);
		}
	}

	/**
	 * One line, wherever it was put and however it was dressed.
	 *
	 * <h2>Why the frame is scaled rather than the font asked for a size</h2>
	 *
	 * Because the game's font has one size — eight pixels — and no other. What
	 * there is instead is the matrix everything is drawn through, so a title twice
	 * as tall is the same glyphs with the frame scaled by two while they are drawn.
	 * The position is worked out in the scaled space and not before it, or the
	 * scaling would move the line as well as enlarge it.
	 */
	private static void draw(GuiGraphicsExtractor graphics, Cue line, int alpha,
			Framing frame) {
		Look look = line.look();
		var font = Minecraft.getInstance().font;

		// No colour in the style, deliberately. A style's colour has no alpha and
		// takes precedence over the one handed to the drawing — so putting it here
		// made every caption fully opaque and threw away the arrival and the departure
		// worked out just above. The colour travels with the alpha instead, in the one
		// argument that can carry both.
		Style style = Style.EMPTY
			.withBold(look.bold())
			.withItalic(look.italic());
		if (!look.font().isEmpty()) {
			Identifier named = Identifier.tryParse(look.font());
			// An unparseable or missing font is drawn in the ordinary one rather than
			// refused. A resource pack that has been turned off should cost somebody a
			// typeface, not a title.
			if (named != null) style = style.withFont(new FontDescription.Resource(named));
		}

		graphics.pose().pushMatrix();
		graphics.pose().scale(look.size(), look.size());

		// Inside the scale, so a fraction of the frame stays a fraction of the frame:
		// the numbers the panel shows are about the picture, not about the glyphs.
		float x = (frame.left() + frame.wide() * look.x()) / look.size();
		float y = (frame.top() + frame.tall() * look.y()) / look.size();

		String[] rows = line.what().split("\n", -1);
		for (int i = 0; i < rows.length; i++) {
			if (rows[i].isEmpty()) continue;
			Component said = Component.literal(rows[i]).withStyle(style);
			float wide = font.width(said);
			graphics.text(font, said,
				Math.round(x + look.offsetOf(wide)),
				Math.round(y + i * (font.lineHeight + 1)),
				(alpha << 24) | (look.colour() & 0xFFFFFF), look.shadow());
		}
		graphics.pose().popMatrix();
	}
}
