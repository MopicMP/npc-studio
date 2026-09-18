package com.mopicmp.npcstudio.client.workspace;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A panel that is an old screen, told a smaller size.
 *
 * <h2>Why this exists</h2>
 *
 * The editors being gathered here — the animation library, the wardrobe, the
 * build, the face, the dialogue graph — are about four thousand lines of layout
 * between them, and that layout is not guesswork: it is what those screens
 * settled into after being used. Retyping it into a new base class would be a
 * week of work whose entire visible result is that everything looks the same,
 * with a fresh crop of off-by-one mistakes for the trouble.
 *
 * A {@link Screen} already draws from its own {@code (0,0)} to its own {@code
 * width, height}, which is exactly what a panel does. So it is not adapted so
 * much as told a different size: {@code init(width, height)} with the panel's
 * measurements, and everything inside lays itself out to fit.
 *
 * <h2>What still has to be changed inside</h2>
 *
 * Navigation. A screen that says {@code setScreenAndShow} is asking to replace
 * everything on the display, and inside a workspace that means throwing the
 * workspace away. Those calls are the one thing each screen has to give up on
 * the way in, and there are only a handful in each.
 */
public abstract class ScreenPanel extends WorkspacePanel {

	private Screen inner;

	/**
	 * Makes the screen, or null when there is nothing to show yet.
	 *
	 * Null is ordinary rather than a failure: the dialogue graph has no dialogue
	 * until the server sends one, and a panel with nothing in it is the honest
	 * picture of that.
	 */
	protected abstract Screen make();

	/**
	 * Whether the screen has to be built afresh for a new size.
	 *
	 * Almost never: {@link Screen#init(int, int)} rebuilds the widgets for the
	 * new measurements and keeps the state, which is the whole point of it being
	 * separate from the constructor. A screen that keeps layout in fields set by
	 * its constructor is the exception, and says so by overriding this.
	 */
	protected boolean rebuildOnResize() {
		return false;
	}

	/**
	 * Room kept at the top for the panel's own controls.
	 *
	 * The screen inside believes it owns everything it is given, and it does —
	 * of what it is given. Anything the panel wants to add of its own has to be
	 * outside that, or the two draw over each other.
	 */
	protected int insetTop() {
		return 0;
	}

	/**
	 * How tall the screen inside needs to be, or zero when it fits whatever it is
	 * given.
	 *
	 * A grid scrolls itself and does not need this. A column of sliders does not:
	 * it lays out to a fixed height and anything past the bottom of the panel used
	 * to be simply gone — including the buttons at the end of it, which is how a
	 * panel comes to have a "done" you cannot press.
	 */
	protected int contentHeight() {
		return 0;
	}

	private int scroll;

	private int visibleHeight() {
		return Math.max(0, height - insetTop());
	}

	private int innerHeight() {
		return Math.max(visibleHeight(), contentHeight());
	}

	private int maxScroll() {
		return Math.max(0, innerHeight() - visibleHeight());
	}

	protected final Screen inner() {
		if (inner == null) inner = make();
		return inner;
	}

	/**
	 * Throws the screen away and builds another.
	 *
	 * For when what the screen is about has changed rather than how big it is —
	 * a different character selected, a different question being asked of it.
	 * Resizing deliberately does not do this, because a rebuild loses whatever
	 * was half typed or scrolled to.
	 */
	protected final void remake() {
		inner = make();
		if (inner != null && width > 0 && height > 0) inner.init(width, innerHeight());
	}

	/**
	 * What went wrong laying the screen out, or null when nothing did.
	 *
	 * Held rather than thrown, and this is not politeness. These screens were
	 * written against a whole display and some of their arithmetic quietly
	 * assumed one — the animation library worked out a divider position with a
	 * floor above its ceiling the first time it was two hundred pixels wide, and
	 * took the game down with it in the middle of drawing a frame. A panel that
	 * cannot lay itself out is a panel with a problem; it is not a reason for
	 * everything else on screen to stop.
	 */
	private String failed;

	@Override
	protected void build() {
		if (width <= 0 || height <= 0) return;
		if (inner == null || rebuildOnResize()) inner = make();
		if (inner == null) return;
		try {
			inner.init(width, Math.max(0, height - insetTop()));
			failed = null;
		} catch (RuntimeException problem) {
			failed = problem.toString();
			com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
				"Panel {} could not lay itself out at {}x{}: {}", id(), width, height, failed);
		}
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (failed != null) {
			graphics.text(font, net.minecraft.network.chat.Component.translatable(
				"npc_studio.panel.too_small"), 6, 6, 0xFF8A99A6);
			return;
		}
		if (inner == null || width <= 0 || height <= 0) return;
		int inset = insetTop();
		scroll = Math.min(scroll, maxScroll());
		try {
			int shift = inset - scroll;
			if (shift == 0) {
				inner.extractRenderState(graphics, mouseX, mouseY, delta);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(0, shift);
				inner.extractRenderState(graphics, mouseX, mouseY - shift, delta);
				graphics.pose().popMatrix();
			}
			if (maxScroll() > 0) drawScrollbar(graphics);
		} catch (RuntimeException problem) {
			failed = problem.toString();
			com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
				"Panel {} could not draw itself: {}", id(), failed);
		}
	}

	/** A thin mark saying there is more, and roughly how much of it is showing. */
	private void drawScrollbar(GuiGraphicsExtractor graphics) {
		int track = visibleHeight();
		int thumb = Math.max(12, track * track / innerHeight());
		int top = insetTop() + (track - thumb) * scroll / Math.max(1, maxScroll());
		graphics.fill(width - 3, insetTop(), width - 1, insetTop() + track, 0xFF1B2028);
		graphics.fill(width - 3, top, width - 1, top + thumb, 0xFF3A4450);
	}

	/** The mouse as the screen inside sees it: below the inset and above the scroll. */
	private MouseButtonEvent shifted(MouseButtonEvent event) {
		int shift = insetTop() - scroll;
		return shift == 0 ? event
			: new MouseButtonEvent(event.x(), event.y() - shift, event.buttonInfo());
	}

	@Override
	public List<? extends GuiEventListener> children() {
		// The screen is the only child. It routes to its own widgets, which is
		// what it was already doing when it filled the display.
		return inner == null ? List.of() : List.of(inner);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return inner != null && inner.mouseClicked(shifted(event), doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return inner != null && inner.mouseReleased(shifted(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return inner != null && inner.mouseDragged(shifted(event), dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (inner == null) return false;
		if (maxScroll() > 0) {
			// The panel scrolls before the screen inside gets a look, because a
			// column of sliders has nothing of its own to scroll and a grid that
			// does is never taller than its panel in the first place.
			scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (amountY * 16)));
			return true;
		}
		return inner.mouseScrolled(mouseX, mouseY - insetTop() + scroll, amountX, amountY);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (inner != null) inner.mouseMoved(mouseX, mouseY - insetTop() + scroll);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return inner != null && inner.keyPressed(event);
	}

	/**
	 * Asked of the screen, not of this.
	 *
	 * A panel routes clicks straight to the screen inside rather than through the
	 * container machinery, so this panel's own idea of what is focused is always
	 * nothing — the screen keeps that. Left to the default, a workspace would think
	 * nobody was ever typing in any of the five editors that live in here, which is
	 * every place there is a text box.
	 */
	@Override
	public boolean typing() {
		return inner != null && Typing.into(inner);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		return inner != null && inner.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return inner != null && inner.charTyped(event);
	}

	@Override
	public void tick() {
		if (inner != null) inner.tick();
	}

	@Override
	public void closed() {
		if (inner != null) inner.removed();
	}
}
