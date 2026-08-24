package com.mopicmp.npcstudio.client.scene;

import net.minecraft.client.Minecraft;

/**
 * Where the film is, inside the window.
 *
 * <h2>The confusion this settles</h2>
 *
 * The world is drawn across the whole window and the panels are laid over the
 * edges of it, so the rectangle left visible is the middle of the picture rather
 * than the whole of it. Which made the viewport a liar: a title centred in the
 * film sat off-centre in the hole being watched through, and the shot judged
 * there was not the shot that came out. It was reported exactly that way — the
 * text and the framing shifting between the small view and the full one — and
 * nothing had shifted.
 *
 * <h2>Cropping rather than drawing it twice</h2>
 *
 * The obvious fix is a second render into a window of its own, at whatever size
 * the film wants. That is a large thing — six mixins into the middle of the
 * renderer, see {@code docs/deferred.md} — and it is not what this needs.
 *
 * Cropping gives the same answer for nothing. The pixels inside the viewport were
 * drawn with the window's own camera, so keeping only those is an honest crop of a
 * photograph: same perspective, same lens, fewer pixels. What you see there is
 * then exactly what is written, which was the whole complaint.
 *
 * <h2>What it buys beyond that</h2>
 *
 * The panels no longer have to be hidden while filming. They are outside the
 * frame, so they cannot be in it — which means the shot stays visible while it is
 * being shot, and there is somewhere to put a progress bar that the camera cannot
 * see. Both of those were impossible while the film was the whole window.
 *
 * <h2>The price, said plainly</h2>
 *
 * The film is as large as the viewport and no larger. A panel taking half the
 * window is a film of half the width. The way to a bigger one is to make the
 * viewport bigger — maximise it, or press the frame button in the toolbar and
 * take the whole window.
 *
 * @param left  the frame's left edge, in the interface's own units
 * @param top   its top edge
 * @param wide  how wide it is
 * @param tall  how tall it is
 */
public record Framing(int left, int top, int wide, int tall) {

	/**
	 * Whether the film is the viewport or the whole window.
	 *
	 * <h2>Why both, and why the window is the default</h2>
	 *
	 * The crop is the better answer to "what I see is what I get": the viewport
	 * becomes the frame, the panels stay up while it films, and a title centred in
	 * the film is centred in the picture being watched. It costs resolution — the
	 * film is as large as the hole in the panels and no larger, which on a laptop
	 * with a wide right-hand column is well under seven hundred and twenty lines.
	 *
	 * The whole window costs the panels: they would be in the film, so they are
	 * hidden for the duration, and composing then means pressing the frame button
	 * to see the shot. In exchange the film is every pixel the machine drew.
	 *
	 * Neither is right for everybody, so it is a switch, and it starts on the
	 * window — which is what this did before the crop existed and what was asked
	 * for back.
	 */
	private static boolean toViewport;

	public static boolean toViewport() {
		return toViewport;
	}

	public static void toViewport(boolean on) {
		toViewport = on;
	}

	/**
	 * Where the film is at this moment.
	 *
	 * The viewport when that is what was asked for and one is showing; the whole
	 * window otherwise — which covers the bare view, where the panels have stood
	 * aside, and an ordinary scene played with no workspace open at all.
	 */
	public static Framing now() {
		var window = Minecraft.getInstance().getWindow();
		int across = window.getGuiScaledWidth();
		int down = window.getGuiScaledHeight();

		int[] hole = toViewport
			? com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.viewport() : null;
		if (hole == null) return new Framing(0, 0, across, down);

		// Clamped to the window, because a panel dragged half off the edge is an
		// ordinary state and a frame reaching outside the picture is not.
		int left = Math.clamp(hole[0], 0, across);
		int top = Math.clamp(hole[1], 0, down);
		int wide = Math.clamp(hole[2], 0, across - left);
		int tall = Math.clamp(hole[3], 0, down - top);
		if (wide < 16 || tall < 16) return new Framing(0, 0, across, down);
		return new Framing(left, top, wide, tall);
	}

	/**
	 * The same rectangle in the framebuffer's own pixels: left, top, wide, tall.
	 *
	 * <h2>Why this is not the same numbers</h2>
	 *
	 * Because the interface is measured in scaled units and the picture is measured
	 * in pixels, and the two differ by whatever the interface scale is set to — two
	 * or three, usually. Cropping with the wrong one takes a quarter of the frame
	 * and looks like the camera moved.
	 *
	 * Both edges are rounded to an even pixel. Encoders refuse an odd size rather
	 * than rounding it themselves, and a viewport is an odd number of pixels wide
	 * about half the time.
	 */
	public int[] inPixels() {
		var window = Minecraft.getInstance().getWindow();
		double scaleX = window.getWidth() / (double) Math.max(1, window.getGuiScaledWidth());
		double scaleY = window.getHeight() / (double) Math.max(1, window.getGuiScaledHeight());

		int x = (int) Math.round(left * scaleX);
		int y = (int) Math.round(top * scaleY);
		int w = (int) Math.round(wide * scaleX);
		int h = (int) Math.round(tall * scaleY);

		w = Math.min(w, window.getWidth() - x) & ~1;
		h = Math.min(h, window.getHeight() - y) & ~1;
		return new int[] { x, y, Math.max(2, w), Math.max(2, h) };
	}
}
