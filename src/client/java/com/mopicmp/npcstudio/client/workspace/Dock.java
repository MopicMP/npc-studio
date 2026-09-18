package com.mopicmp.npcstudio.client.workspace;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Where the panels are and how they are moved.
 *
 * <h2>The tree</h2>
 *
 * A layout is a tree of splits ending in leaves, and a leaf is a stack of tabs.
 * The viewport is an ordinary leaf, which is the point: "let this one fill the
 * screen" is then a ratio, not a special case, and the world showing through is
 * a property of one panel rather than a hole cut in the layout.
 *
 * <h2>Why a tree and not four fixed zones</h2>
 *
 * Four zones would have been a day's work and would have covered almost
 * everything asked for. It was refused deliberately: once panels can be dragged
 * anywhere, the arrangement stops being something the mod decides and becomes
 * something the person building a scene decides — and the arrangement that suits
 * building a ship is not the one that suits timing a gesture.
 *
 * <h2>Coordinates</h2>
 *
 * Everything here is in display coordinates. Panels are not: see {@link
 * WorkspacePanel}. The translation happens at the one place that hands over, in
 * {@link #renderLeaf} and {@link #intoPanel}.
 */
public final class Dock {

	/**
	 * Height of a tab bar, which is also the height of a collapsed leaf.
	 *
	 * Eighteen because an icon is sixteen and wants a pixel either side. The bar
	 * was fifteen when it held nothing but words.
	 */
	static final int TABS = 18;

	/** The gap between two children of a split, and the width of the grip in it. */
	static final int SPLITTER = 4;

	/** How near an edge a drop has to be before it means "split" rather than "tab". */
	private static final float EDGE = 0.25f;

	static final int CANVAS = 0xFF101318;
	static final int PANEL = 0xFF161A20;
	static final int BAR = 0xFF12161C;
	static final int TAB_ON = 0xFF1B2028;
	static final int TAB_HOVER = 0xFF232A34;
	static final int EDGE_LINE = 0xFF2C333D;
	static final int TEXT = 0xFFECEFF1;
	static final int TEXT_DIM = 0xFF8A99A6;
	static final int ACCENT = 0xFF4FC3F7;
	static final int DROP = 0x554FC3F7;

	// ------------------------------------------------------------- the tree

	public abstract static sealed class Node permits Split, Leaf {
		int x;
		int y;
		int width;
		int height;

		abstract void layout(int left, int top, int across, int down);

		abstract int minimumWidth();

		abstract int minimumHeight();

		abstract void collect(List<Leaf> into);
	}

	/** Two nodes side by side, or one above the other. */
	public static final class Split extends Node {

		boolean vertical;
		float ratio;
		Node first;
		Node second;

		public Split(boolean vertical, float ratio, Node first, Node second) {
			this.vertical = vertical;
			this.ratio = ratio;
			this.first = first;
			this.second = second;
		}

		@Override
		void layout(int left, int top, int across, int down) {
			x = left;
			y = top;
			width = across;
			height = down;

			int span = (vertical ? down : across) - SPLITTER;
			int firstSpan = Math.round(span * ratio);

			// A collapsed child takes only its strip and the rest goes to its
			// neighbour. Without this, collapsing left a panel-sized hole with a
			// title bar floating at the top of it, which reads as something broken
			// rather than as something folded away.
			int folded = folded(vertical);
			if (first instanceof Leaf head && head.collapsed) {
				firstSpan = Math.min(folded, span);
			} else if (second instanceof Leaf tail && tail.collapsed) {
				firstSpan = Math.max(0, span - folded);
			}

			// Clamped against what the children say they need, so dragging a
			// splitter into a panel stops at the panel rather than folding it away.
			int leastFirst = vertical ? first.minimumHeight() : first.minimumWidth();
			int leastSecond = vertical ? second.minimumHeight() : second.minimumWidth();
			firstSpan = Mth.clamp(firstSpan, Math.min(leastFirst, span), Math.max(span - leastSecond, 0));
			// When the two minimums together do not fit, the clamp above can put the
			// divider past the end and hand the second child a negative size. There
			// is no arrangement that satisfies everyone here, so the rule is simply
			// that nobody gets a negative rectangle.
			firstSpan = Mth.clamp(firstSpan, 0, Math.max(span, 0));

			if (vertical) {
				first.layout(left, top, across, firstSpan);
				second.layout(left, top + firstSpan + SPLITTER, across, span - firstSpan);
			} else {
				first.layout(left, top, firstSpan, down);
				second.layout(left + firstSpan + SPLITTER, top, across - firstSpan - SPLITTER, down);
			}
		}

		/**
		 * How much room a folded child keeps.
		 *
		 * Stacked, that is the title bar — which is the whole of what is left to
		 * look at. Side by side it has to be wide enough for the three buttons in
		 * that bar, or folding a panel would leave nothing to unfold it with.
		 */
		private static int folded(boolean vertical) {
			return vertical ? TABS : BUTTON * 3 + 4;
		}

		@Override
		int minimumWidth() {
			if (first instanceof Leaf head && head.collapsed && !vertical) {
				return folded(false) + second.minimumWidth() + SPLITTER;
			}
			if (second instanceof Leaf tail && tail.collapsed && !vertical) {
				return first.minimumWidth() + folded(false) + SPLITTER;
			}
			return vertical ? Math.max(first.minimumWidth(), second.minimumWidth())
				: first.minimumWidth() + second.minimumWidth() + SPLITTER;
		}

		@Override
		int minimumHeight() {
			if (first instanceof Leaf head && head.collapsed && vertical) {
				return folded(true) + second.minimumHeight() + SPLITTER;
			}
			if (second instanceof Leaf tail && tail.collapsed && vertical) {
				return first.minimumHeight() + folded(true) + SPLITTER;
			}
			return vertical ? first.minimumHeight() + second.minimumHeight() + SPLITTER
				: Math.max(first.minimumHeight(), second.minimumHeight());
		}

		@Override
		void collect(List<Leaf> into) {
			first.collect(into);
			second.collect(into);
		}

		/** The gap between the children: what you grab to change the ratio. */
		boolean grip(double px, double py) {
			if (vertical) {
				int at = first.y + first.height;
				return px >= x && px < x + width && py >= at && py < at + SPLITTER;
			}
			int at = first.x + first.width;
			return py >= y && py < y + height && px >= at && px < at + SPLITTER;
		}
	}

	/** A stack of panels sharing one rectangle, one of them showing. */
	public static final class Leaf extends Node {

		final List<WorkspacePanel> tabs = new ArrayList<>();
		int active;
		boolean collapsed;

		/** Public because a second mode now builds its own starting layout. */
		public Leaf(WorkspacePanel... panels) {
			for (WorkspacePanel panel : panels) tabs.add(panel);
		}

		WorkspacePanel showing() {
			return tabs.isEmpty() ? null : tabs.get(Mth.clamp(active, 0, tabs.size() - 1));
		}

		@Override
		void layout(int left, int top, int across, int down) {
			x = left;
			y = top;
			width = across;
			// Takes what it is given. How much a folded leaf gets is the split's
			// decision, because only the split knows which way it is folding and who
			// the freed room belongs to.
			height = down;
			WorkspacePanel panel = showing();
			if (panel != null && !collapsed) {
				panel.screenX = left;
				panel.screenY = top + TABS;
				// Never below what the panel says it needs, even when the leaf is
				// smaller than that. A panel drawn too wide is clipped by the
				// scissor and looks cut off; a panel told it is eighty pixels wide
				// does arithmetic nobody wrote for eighty pixels. Cut off is a
				// picture of the problem. The other one was a crash.
				panel.resize(Math.max(across, panel.minimumWidth()),
					Math.max(down - TABS, panel.minimumHeight()));
			}
		}

		@Override
		int minimumWidth() {
			int least = 80;
			for (WorkspacePanel panel : tabs) least = Math.max(least, panel.minimumWidth());
			return least;
		}

		@Override
		int minimumHeight() {
			if (collapsed) return TABS;
			int least = 0;
			for (WorkspacePanel panel : tabs) least = Math.max(least, panel.minimumHeight());
			return least + TABS;
		}

		@Override
		void collect(List<Leaf> into) {
			into.add(this);
		}

		boolean inBar(double px, double py) {
			return px >= x && px < x + width && py >= y && py < y + TABS;
		}

		boolean inBody(double px, double py) {
			return !collapsed && px >= x && px < x + width && py >= y + TABS && py < y + height;
		}
	}

	/** A panel that has been pulled out of the tree and floats over it. */
	public static final class Floater {
		final WorkspacePanel panel;
		int x;
		int y;
		int width;
		int height;

		Floater(WorkspacePanel panel, int x, int y, int width, int height) {
			this.panel = panel;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean inBar(double px, double py) {
			return px >= x && px < x + width && py >= y && py < y + TABS;
		}

		boolean inBody(double px, double py) {
			return px >= x && px < x + width && py >= y + TABS && py < y + height;
		}

		/** The bottom right corner, which is what resizes it. */
		boolean inGrip(double px, double py) {
			return px >= x + width - 8 && px < x + width && py >= y + height - 8 && py < y + height;
		}

		/**
		 * Either upright edge, which is what changes the width.
		 *
		 * The corner alone was enough while every floater was a small box somebody had
		 * dragged out into the middle. It stopped being enough when panels started
		 * arriving as full-height slabs against an edge: the corner of one of those is
		 * in the very bottom corner of the window, and the thing anybody actually
		 * wants to pull is the long side facing the world.
		 *
		 * Four pixels, and outside the body rather than inside it, so that a list or a
		 * field hard against the edge of a panel is still clickable.
		 */
		int atSide(double px, double py) {
			if (py < y + TABS || py >= y + height) return 0;
			if (px >= x - 3 && px < x + 4) return -1;
			if (px >= x + width - 4 && px < x + width + 3) return 1;
			return 0;
		}
	}

	// ------------------------------------------------------------ the state

	private Node root;
	private final List<Floater> floaters = new ArrayList<>();
	private final List<Leaf> leaves = new ArrayList<>();

	private int x;
	private int y;
	private int width;
	private int height;

	/** The one panel filling everything, or null when the layout is as laid out. */
	private Leaf maximized;

	private Split heldSplitter;
	private Floater heldFloater;
	private boolean resizingFloater;

	/** Which upright edge is being pulled: -1 left, 1 right, 0 the corner. */
	private int heldSide;
	private double heldX;
	private double heldY;

	private WorkspacePanel carried;
	private Leaf carriedFrom;
	private Floater carriedFloater;
	private double carryX;
	private double carryY;
	private boolean carrying;

	/** Which panel gets the keyboard: the last one clicked in. */
	private WorkspacePanel focused;

	public Dock(Node root) {
		this.root = root;
	}

	public Node root() {
		return root;
	}

	public List<Floater> floaters() {
		return floaters;
	}

	/**
	 * The panel each leaf is currently showing, tab for tab.
	 *
	 * Not the same as every panel there is: a tab behind another has a size and a
	 * place from the last time it was in front, and asking one of those where it is
	 * would answer with somewhere nothing is drawn.
	 */
	public List<WorkspacePanel> visible() {
		List<WorkspacePanel> found = new ArrayList<>();
		gather(root, found);
		return found;
	}

	private void gather(Node node, List<WorkspacePanel> into) {
		if (node instanceof Leaf leaf) {
			WorkspacePanel showing = leaf.showing();
			if (showing != null && !leaf.collapsed) into.add(showing);
			return;
		}
		if (node instanceof Split split) {
			gather(split.first, into);
			gather(split.second, into);
		}
	}

	public List<WorkspacePanel> panels() {
		List<WorkspacePanel> all = new ArrayList<>();
		for (Leaf leaf : leavesOf(root)) all.addAll(leaf.tabs);
		for (Floater floater : floaters) all.add(floater.panel);
		return all;
	}

	private List<Leaf> leavesOf(Node node) {
		List<Leaf> found = new ArrayList<>();
		node.collect(found);
		return found;
	}

	public void layout(int left, int top, int across, int down) {
		this.x = left;
		this.y = top;
		this.width = across;
		this.height = down;

		if (maximized != null) {
			maximized.layout(left, top, across, down);
			leaves.clear();
			leaves.add(maximized);
		} else {
			root.layout(left, top, across, down);
			leaves.clear();
			root.collect(leaves);
		}

		// Who lays a panel out is who it belongs to. Written here rather than when a
		// panel is added because a panel moves between leaves and floaters all day,
		// and this runs after every one of those moves.
		for (Leaf leaf : leaves) {
			for (WorkspacePanel panel : leaf.tabs) panel.owner = this;
		}

		for (Floater floater : floaters) {
			floater.panel.owner = this;
			floater.x = Mth.clamp(floater.x, left - floater.width + 40, left + across - 40);
			floater.y = Mth.clamp(floater.y, top, top + down - TABS);
			floater.panel.screenX = floater.x;
			floater.panel.screenY = floater.y + TABS;
			floater.panel.resize(Math.max(floater.width, floater.panel.minimumWidth()),
				Math.max(floater.height - TABS, floater.panel.minimumHeight()));
		}
	}

	// --------------------------------------------------------------- drawing

	public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (maximized == null) drawSplitters(graphics, root, mouseX, mouseY);

		for (Leaf leaf : leaves) {
			renderLeaf(graphics, leaf, mouseX, mouseY, delta);
		}

		for (Floater floater : floaters) {
			graphics.nextStratum();
			renderFloater(graphics, floater, mouseX, mouseY, delta);
		}

		if (carrying) {
			graphics.nextStratum();
			drawCarried(graphics, mouseX, mouseY);
		}

		if (menu.isOpen()) {
			graphics.nextStratum();
			drawMenu(graphics, mouseX, mouseY);
		}
	}

	private void drawSplitters(GuiGraphicsExtractor graphics, Node node, int mouseX, int mouseY) {
		if (!(node instanceof Split split)) return;
		boolean lit = split == heldSplitter || split.grip(mouseX, mouseY);
		if (split.vertical) {
			int at = split.first.y + split.first.height;
			graphics.fill(split.x, at, split.x + split.width, at + SPLITTER, lit ? ACCENT : CANVAS);
		} else {
			int at = split.first.x + split.first.width;
			graphics.fill(at, split.y, at + SPLITTER, split.y + split.height, lit ? ACCENT : CANVAS);
		}
		drawSplitters(graphics, split.first, mouseX, mouseY);
		drawSplitters(graphics, split.second, mouseX, mouseY);
	}

	private void renderLeaf(GuiGraphicsExtractor graphics, Leaf leaf, int mouseX, int mouseY, float delta) {
		// A leaf holding nothing draws nothing, bar included.
		//
		// The root leaf is the one that can be emptied and not removed: {@link #prune}
		// leaves it standing, because a tree with no root is not a tree. It used to go
		// on drawing its title bar — a strip with a menu, a collapse and a maximise on
		// it, over a rectangle of nothing, none of which does anything. That reads as
		// the workspace having broken, and it became easy to reach the moment every
		// panel grew a cross.
		//
		// Drawing nothing is right rather than merely tidy: the leaf still takes drops,
		// so a panel dragged back lands in it, and until then the world shows through
		// where it is — which is what the workspace is supposed to look like with
		// nothing open.
		if (leaf.tabs.isEmpty()) return;

		WorkspacePanel panel = leaf.showing();
		boolean seeThrough = panel != null && panel.transparent();

		drawBar(graphics, leaf.x, leaf.y, leaf.width, leaf.tabs, leaf.active, leaf.collapsed,
			leaf == maximized, mouseX, mouseY, seeThrough, false);

		if (leaf.collapsed || panel == null) return;

		int bodyTop = leaf.y + TABS;
		int bodyHeight = leaf.height - TABS;
		if (bodyHeight <= 0) return;

		// The body is painted for everything except the viewport, which is a hole
		// on purpose: the world was drawn before the interface and stays visible
		// exactly where nothing is painted over it.
		if (!seeThrough) {
			graphics.fill(leaf.x, bodyTop, leaf.x + leaf.width, bodyTop + bodyHeight, PANEL);
		}

		drawPanel(graphics, panel, leaf.x, bodyTop, leaf.width, bodyHeight, mouseX, mouseY, delta);
	}

	private void renderFloater(GuiGraphicsExtractor graphics, Floater floater,
			int mouseX, int mouseY, float delta) {
		graphics.fill(floater.x - 1, floater.y - 1, floater.x + floater.width + 1,
			floater.y + floater.height + 1, EDGE_LINE);
		drawBar(graphics, floater.x, floater.y, floater.width, List.of(floater.panel), 0, false,
			false, mouseX, mouseY, false, true);
		int bodyTop = floater.y + TABS;
		int bodyHeight = floater.height - TABS;
		if (bodyHeight <= 0) return;
		graphics.fill(floater.x, bodyTop, floater.x + floater.width, bodyTop + bodyHeight, PANEL);
		drawPanel(graphics, floater.panel, floater.x, bodyTop, floater.width, bodyHeight,
			mouseX, mouseY, delta);

		drawSideGrip(graphics, floater, bodyTop, bodyHeight, mouseX, mouseY);

		// The corner grip: three steps, which is enough to read as "pull here".
		for (int step = 0; step < 3; step++) {
			int at = 2 + step * 3;
			graphics.fill(floater.x + floater.width - at - 1, floater.y + floater.height - 3,
				floater.x + floater.width - at, floater.y + floater.height - 2, TEXT_DIM);
		}
	}

	/**
	 * The upright edge, drawn when the hand is on it or pulling it.
	 *
	 * <h2>Why it had to be drawn at all</h2>
	 *
	 * It was reported as the width not being adjustable, and it was — the edge took
	 * the drag perfectly well. Nothing said so. A four-pixel strip that behaves
	 * differently from the four pixels either side of it, with no mark and no
	 * cursor change, is a feature only the person who wrote it can find.
	 *
	 * The game gives no way to change the pointer, so the mark is the whole of the
	 * affordance: a line down the edge under the cursor, brighter while it is being
	 * pulled, and the notches that say which way it moves.
	 */
	private void drawSideGrip(GuiGraphicsExtractor graphics, Floater floater,
			int bodyTop, int bodyHeight, int mouseX, int mouseY) {
		boolean pulling = heldFloater == floater && resizingFloater && heldSide != 0;
		int side = pulling ? heldSide : floater.atSide(mouseX, mouseY);
		if (side == 0) return;

		int edge = side < 0 ? floater.x : floater.x + floater.width - 2;
		graphics.fill(edge, bodyTop, edge + 2, bodyTop + bodyHeight, pulling ? ACCENT : TEXT_DIM);

		// Three notches at the middle, the mark every draggable edge in every
		// program wears. Without them the line reads as a border.
		int middle = bodyTop + bodyHeight / 2;
		for (int step = -1; step <= 1; step++) {
			int at = middle + step * 4;
			graphics.fill(edge - 1, at, edge + 3, at + 1, pulling ? ACCENT : TEXT);
		}
	}

	/**
	 * Hands the panel its rectangle and lets it draw in its own coordinates.
	 *
	 * The scissor is set while the matrix is already translated, because the
	 * scissor is transformed by that matrix — so {@code (0,0,w,h)} here means the
	 * panel's own rectangle on the display, and a panel cannot spill over its
	 * neighbour however wrong its arithmetic is.
	 */
	private void drawPanel(GuiGraphicsExtractor graphics, WorkspacePanel panel,
			int left, int top, int across, int down, int mouseX, int mouseY, float delta) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(left, top);
		graphics.enableScissor(0, 0, across, down);
		panel.extractRenderState(graphics, mouseX - left, mouseY - top, delta);
		graphics.disableScissor();
		graphics.pose().popMatrix();
	}

	private void drawBar(GuiGraphicsExtractor graphics, int left, int top, int across,
			List<WorkspacePanel> tabs, int active, boolean collapsed, boolean full,
			int mouseX, int mouseY, boolean seeThrough, boolean floating) {
		graphics.fill(left, top, left + across, top + TABS, seeThrough ? 0xCC12161C : BAR);
		graphics.fill(left, top + TABS - 1, left + across, top + TABS, EDGE_LINE);

		int buttons = BUTTONS * BUTTON;
		int room = across - buttons;

		// Names as long as they all fit, icons alone when they do not. All or none,
		// so a bar does not read as a mixture of two kinds of thing — and with
		// icons alone a leaf holding five panels still shows all five, which is the
		// whole point: a tab you cannot see is a panel you cannot reach.
		boolean named = named(tabs, room);
		int at = left;
		for (int i = 0; i < tabs.size(); i++) {
			int span = tabWidth(tabs.get(i), named);
			if (at + span > left + room) break;
			boolean on = i == active;
			boolean hovered = mouseX >= at && mouseX < at + span && mouseY >= top && mouseY < top + TABS;
			if (on || hovered) {
				graphics.fill(at, top, at + span, top + TABS - 1, on ? TAB_ON : TAB_HOVER);
			}
			if (on) graphics.fill(at, top, at + span, top + 1, ACCENT);
			int ink = on ? ACCENT : TEXT_DIM;
			iconOf(tabs.get(i)).draw(graphics, at + 3, top + (TABS - Icon.SIZE) / 2, ink);
			if (named) {
				graphics.text(font(), tabs.get(i).title(), at + 3 + Icon.SIZE + 3, top + 5,
					on ? TEXT : TEXT_DIM);
			}
			at += span;
		}

		int right = left + across;
		WorkspacePanel showing = tabs.isEmpty() ? null
			: tabs.get(Math.max(0, Math.min(active, tabs.size() - 1)));

		drawGlyph(graphics, right - BUTTON * 4, top, Icon.MENU, mouseX, mouseY);
		drawGlyph(graphics, right - BUTTON * 3, top, collapsed ? Icon.EXPAND : Icon.COLLAPSE,
			mouseX, mouseY);
		// The third means "give it the window" for a docked panel and "put it back in
		// the layout" for a floating one, and those are different enough acts to be
		// worth different marks.
		//
		// Absent for a docked panel that says it cannot be worked in beside anything
		// else. Such a panel has two states and not three — filling the window, or not
		// there — so a button offering to shrink it is a button offering the one state
		// it has declared useless. It was three separate doors to that state: this
		// mark, Ctrl and space, and escape. The mark is not drawn, the other two are
		// refused, and the way out is the cross.
		boolean fills = !floating && showing != null && showing.wholeWindow();
		if (!fills) {
			drawGlyph(graphics, right - BUTTON * 2, top,
				floating ? Icon.DOCK : full ? Icon.RESTORE : Icon.MAXIMIZE, mouseX, mouseY);
		}

		// And the cross, which was the one thing every window in every program has
		// and this one did not. Closing was in the menu behind the first glyph all
		// along — that is, behind a list, which is exactly the place nobody looks for
		// something this ordinary.
		if (showing == null || showing.closable()) {
			drawGlyph(graphics, right - BUTTON, top, Icon.CLOSE, mouseX, mouseY);
		}
	}

	/** How many marks sit at the right of a bar: menu, collapse, whole-window, close. */
	static final int BUTTONS = 4;

	/** The icon a panel is known by, or a plain box for one that has not claimed one. */
	static Icon iconOf(WorkspacePanel panel) {
		return iconOf(panel.id());
	}

	static Icon iconOf(String id) {
		try {
			return Icon.valueOf(id.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException unnamed) {
			return Icon.ASSETS;
		}
	}

	/**
	 * Whether this bar is showing names, asked by drawing and by hit testing alike.
	 *
	 * It has to be one answer in one place. It was two: the bar decided to fold to
	 * icons when the names would not fit, and every click went on measuring tabs
	 * at their full named width — so the first tab swallowed the space of three
	 * and pressing an icon selected whatever used to be there.
	 */
	private boolean named(List<WorkspacePanel> tabs, int room) {
		int total = 0;
		for (WorkspacePanel panel : tabs) total += tabWidth(panel, true);
		return total <= room;
	}

	private boolean named(Leaf leaf) {
		return named(leaf.tabs, leaf.width - BUTTONS * BUTTON);
	}

	static final int BUTTON = 16;

	/**
	 * The three marks at the right of every title bar.
	 *
	 * Drawn from the sheet now rather than from rectangles. The rectangles were
	 * honest for a bar, a box and three dots and hopeless for everything else,
	 * and a workspace where the chrome is drawn one way and every button in
	 * every panel another looks like two programs sharing a window.
	 */
	private void drawGlyph(GuiGraphicsExtractor graphics, int left, int top, Icon icon,
			int mouseX, int mouseY) {
		boolean hovered = mouseX >= left && mouseX < left + BUTTON && mouseY >= top && mouseY < top + TABS;
		if (hovered) graphics.fill(left, top, left + BUTTON, top + TABS - 1, TAB_HOVER);
		icon.draw(graphics, left + (BUTTON - Icon.SIZE) / 2, top + (TABS - Icon.SIZE) / 2,
			hovered ? TEXT : TEXT_DIM);
	}

	private void drawCarried(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Drop drop = dropAt(mouseX, mouseY);
		if (drop != null) {
			int[] rect = drop.highlight();
			graphics.fill(rect[0], rect[1], rect[2], rect[3], DROP);
			graphics.outline(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], ACCENT);
		}
		int span = tabWidth(carried);
		graphics.fill(mouseX - 6, mouseY - 6, mouseX - 6 + span, mouseY + TABS - 6, TAB_ON);
		graphics.outline(mouseX - 6, mouseY - 6, span, TABS, ACCENT);
		graphics.text(font(), carried.title(), mouseX, mouseY - 2, TEXT);
	}

	private int tabWidth(WorkspacePanel panel, boolean named) {
		return named ? Icon.SIZE + font().width(panel.title()) + 12 : Icon.SIZE + 6;
	}

	/** The ghost carried under the cursor, which always has room for its name. */
	private int tabWidth(WorkspacePanel panel) {
		return tabWidth(panel, true);
	}

	private net.minecraft.client.gui.Font font() {
		return net.minecraft.client.Minecraft.getInstance().font;
	}

	// ----------------------------------------------------------- where a drop lands

	private enum Side { TAB, LEFT, RIGHT, TOP, BOTTOM, FLOAT }

	private final class Drop {
		final Leaf leaf;
		final Side side;
		final int index;

		Drop(Leaf leaf, Side side, int index) {
			this.leaf = leaf;
			this.side = side;
			this.index = index;
		}

		int[] highlight() {
			if (leaf == null) {
				return new int[] { (int) carryX - 90, (int) carryY - 8, (int) carryX + 90, (int) carryY + 112 };
			}
			int left = leaf.x;
			int top = leaf.y;
			int right = leaf.x + leaf.width;
			int bottom = leaf.y + leaf.height;
			return switch (side) {
				case LEFT -> new int[] { left, top, left + leaf.width / 2, bottom };
				case RIGHT -> new int[] { left + leaf.width / 2, top, right, bottom };
				case TOP -> new int[] { left, top, right, top + leaf.height / 2 };
				case BOTTOM -> new int[] { left, top + leaf.height / 2, right, bottom };
				default -> new int[] { left, top, right, bottom };
			};
		}
	}

	private Drop dropAt(double px, double py) {
		if (px < x || py < y || px >= x + width || py >= y + height) {
			return new Drop(null, Side.FLOAT, 0);
		}
		for (Leaf leaf : leaves) {
			if (px < leaf.x || px >= leaf.x + leaf.width) continue;
			if (py < leaf.y || py >= leaf.y + leaf.height) continue;

			if (leaf.inBar(px, py)) return new Drop(leaf, Side.TAB, tabIndexAt(leaf, px));

			float across = (float) (px - leaf.x) / leaf.width;
			float down = (float) (py - leaf.y) / leaf.height;
			float fromEdge = Math.min(Math.min(across, 1 - across), Math.min(down, 1 - down));
			if (fromEdge > EDGE) return new Drop(leaf, Side.TAB, leaf.tabs.size());

			if (Math.min(across, 1 - across) < Math.min(down, 1 - down)) {
				return new Drop(leaf, across < 0.5f ? Side.LEFT : Side.RIGHT, 0);
			}
			return new Drop(leaf, down < 0.5f ? Side.TOP : Side.BOTTOM, 0);
		}
		return new Drop(null, Side.FLOAT, 0);
	}

	private int tabIndexAt(Leaf leaf, double px) {
		boolean named = named(leaf);
		int at = leaf.x;
		for (int i = 0; i < leaf.tabs.size(); i++) {
			int span = tabWidth(leaf.tabs.get(i), named);
			if (px < at + span / 2.0) return i;
			at += span;
		}
		return leaf.tabs.size();
	}

	// -------------------------------------------------------------- rearranging

	private void detach(WorkspacePanel panel) {
		if (carriedFloater != null) {
			floaters.remove(carriedFloater);
			carriedFloater = null;
			return;
		}
		if (carriedFrom == null) return;
		carriedFrom.tabs.remove(panel);
		carriedFrom.active = Math.max(0, Math.min(carriedFrom.active, carriedFrom.tabs.size() - 1));
		if (carriedFrom.tabs.isEmpty()) prune(carriedFrom);
	}

	/** Takes an emptied leaf out of the tree, leaving its sibling in the parent's place. */
	private void prune(Leaf empty) {
		// Before the root is let off, not after. The root leaf stays in the tree when
		// it empties — a tree needs a root — but an empty leaf holding the whole window
		// is a window with nothing in it and no bar to press, which is what closing a
		// maximised graph out of the root leaf did.
		if (maximized == empty) {
			maximized = null;
			byItself = false;
		}
		if (root == empty) return;
		root = without(root, empty);
	}

	private Node without(Node node, Leaf empty) {
		if (!(node instanceof Split split)) return node;
		if (split.first == empty) return split.second;
		if (split.second == empty) return split.first;
		split.first = without(split.first, empty);
		split.second = without(split.second, empty);
		return split;
	}

	private void replace(Node old, Node fresh) {
		if (root == old) {
			root = fresh;
			return;
		}
		replaceIn(root, old, fresh);
	}

	private void replaceIn(Node node, Node old, Node fresh) {
		if (!(node instanceof Split split)) return;
		if (split.first == old) {
			split.first = fresh;
			return;
		}
		if (split.second == old) {
			split.second = fresh;
			return;
		}
		replaceIn(split.first, old, fresh);
		replaceIn(split.second, old, fresh);
	}

	private void drop(Drop drop) {
		WorkspacePanel panel = carried;
		if (drop.leaf != null && drop.side != Side.TAB
			&& drop.leaf.tabs.size() == 1 && drop.leaf.tabs.contains(panel)) {
			return;
		}

		// Where it came from, kept across the detaching that forgets it. Whatever is
		// showing there afterwards has to be asked whether it needs the window — a
		// leaf that has just lost the graph is a leaf holding a room-sized hole.
		Leaf from = carriedFrom;
		detach(panel);
		if (from != null && from != drop.leaf && !from.tabs.isEmpty()) fitShown(from);

		if (drop.leaf == null || drop.side == Side.FLOAT) {
			floaters.add(new Floater(panel, (int) carryX - 90, (int) carryY - 8, 180, 120));
			focused = panel;
			return;
		}

		if (drop.side == Side.TAB) {
			int index = Mth.clamp(drop.index, 0, drop.leaf.tabs.size());
			drop.leaf.tabs.add(index, panel);
			drop.leaf.active = index;
			drop.leaf.collapsed = false;
			focused = panel;
			// And the panel that has just arrived in front takes the window if it is one
			// of the ones that cannot be used without it. Every other place the shown tab
			// changes already asks this; a drop was the one that did not, so a graph
			// dragged into a bar landed in whatever room that bar had.
			fitShown(drop.leaf);
			return;
		}

		Leaf fresh = new Leaf(panel);
		boolean vertical = drop.side == Side.TOP || drop.side == Side.BOTTOM;
		boolean firstIsNew = drop.side == Side.LEFT || drop.side == Side.TOP;
		Split split = firstIsNew
			? new Split(vertical, 0.35f, fresh, drop.leaf)
			: new Split(vertical, 0.65f, drop.leaf, fresh);
		replace(drop.leaf, split);
		focused = panel;
		fitShown(fresh);
	}

	// ------------------------------------------------------------------ input

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double px = event.x();
		double py = event.y();

		// The menu answers before anything else and closes on any click, including
		// one that misses it. A menu that stays open after you have gone elsewhere
		// is a menu that eats the next click.
		if (clickMenu(px, py)) return true;

		for (int i = floaters.size() - 1; i >= 0; i--) {
			Floater floater = floaters.get(i);
			if (floater.inGrip(px, py)) {
				heldFloater = floater;
				resizingFloater = true;
				heldSide = 0;
				heldX = px;
				heldY = py;
				bringToFront(floater);
				return true;
			}
			int side = floater.atSide(px, py);
			if (side != 0) {
				heldFloater = floater;
				resizingFloater = true;
				heldSide = side;
				heldX = px;
				heldY = py;
				bringToFront(floater);
				return true;
			}
			if (floater.inBar(px, py)) {
				bringToFront(floater);
				int button = barButton(floater.x + floater.width, px, py, floater.y);
				if (button == 0) {
					openMenu(null, floater, (int) px, (int) py);
					return true;
				}
				if (button == 2) {
					dockBack(floater);
					return true;
				}
				if (button == 3) {
					close(floater.panel);
					return true;
				}
				carried = floater.panel;
				carriedFloater = floater;
				carriedFrom = null;
				carryX = px;
				carryY = py;
				heldFloater = floater;
				resizingFloater = false;
				heldX = px;
				heldY = py;
				focused = floater.panel;
				return true;
			}
			if (floater.inBody(px, py)) {
				focused = floater.panel;
				return press(floater.panel, event, floater.x, floater.y + TABS, doubleClick);
			}
		}

		if (maximized == null && clickSplitter(root, px, py)) return true;

		for (Leaf leaf : leaves) {
			if (leaf.inBar(px, py)) return clickBar(leaf, event, doubleClick);
			if (leaf.inBody(px, py)) {
				WorkspacePanel panel = leaf.showing();
				if (panel == null) return false;
				focused = panel;
				return press(panel, event, leaf.x, leaf.y + TABS, doubleClick);
			}
		}
		return false;
	}

	/**
	 * Hands a press to a panel and remembers that it has it until the button comes up.
	 *
	 * <h2>Why capture, rather than asking where the mouse is</h2>
	 *
	 * Because a drag does not stay inside the rectangle it started in, and it is not
	 * meant to. Routing every drag to whichever panel is under the cursor means a
	 * pull that crosses the edge of a panel is handed to the neighbour — which
	 * ignores it, having seen no press — and the drag simply stops. From the other
	 * side of the screen that reads as a handle that moves a little and then gives
	 * up, and it applied to everything: the camera, the splitters, the gizmo.
	 *
	 * The offsets are kept with it. Nothing rearranges the layout while a button is
	 * down, so where the panel was at the press is where it still is.
	 */
	private WorkspacePanel pressed;
	private int pressedX;
	private int pressedY;

	private boolean press(WorkspacePanel panel, MouseButtonEvent event, int x, int y,
			boolean doubleClick) {
		pressed = panel;
		pressedX = x;
		pressedY = y;
		boolean took = panel.mouseClicked(moved(event, x, y), doubleClick);
		if (!took) pressed = null;
		return took;
	}

	private boolean clickSplitter(Node node, double px, double py) {
		if (!(node instanceof Split split)) return false;
		if (split.grip(px, py)) {
			heldSplitter = split;
			return true;
		}
		return clickSplitter(split.first, px, py) || clickSplitter(split.second, px, py);
	}

	private boolean clickBar(Leaf leaf, MouseButtonEvent event, boolean doubleClick) {
		// Nothing is drawn there, so nothing is pressed there. Swallowed rather than
		// passed on: an empty leaf is still a surface, and a click that fell through it
		// to the world would move the camera by pressing a bar somebody can see the
		// world through.
		if (leaf.tabs.isEmpty()) return true;

		double px = event.x();
		double py = event.y();
		int button = barButton(leaf.x + leaf.width, px, py, leaf.y);
		if (button == 0) {
			// The menu is where the moves that are awkward to drag live, and there
			// is no getting away from needing one: "put this back on the right" has
			// no gesture.
			openMenu(leaf, null, (int) px, (int) py);
			return true;
		}
		if (button == 1) {
			leaf.collapsed = !leaf.collapsed;
			return true;
		}
		if (button == 2) {
			// Through the one method rather than by setting the field, which is how this
			// door came to behave differently from the other two: it never touched
			// `byItself`, so a graph the dock had taken the window for could be shrunk
			// here and the dock went on believing it had chosen the arrangement.
			maximize(leaf);
			return true;
		}
		if (button == 3) {
			WorkspacePanel showing = leaf.showing();
			// Swallowed rather than ignored when the panel refuses: nothing is drawn
			// there, so the press is on empty bar and must not fall through to
			// whatever the bar is over.
			if (showing != null && showing.closable()) close(showing);
			return true;
		}

		boolean named = named(leaf);
		// Where the drawing stops, and therefore where the pressing stops. The bar
		// gives up on the first tab that would not fit; the click loop walked the whole
		// list, so the part of a half-drawn tab that spills under the buttons selected
		// a tab nobody could see. It is the same fault as the one this file already
		// carries a note about — two answers to "is this bar showing names" — and it
		// got likelier when the bar grew a fourth button and the room shrank.
		int room = leaf.width - BUTTONS * BUTTON;
		int at = leaf.x;
		for (int i = 0; i < leaf.tabs.size(); i++) {
			int span = tabWidth(leaf.tabs.get(i), named);
			if (at + span > leaf.x + room) break;
			if (px >= at && px < at + span) {
				if (leaf.active != i) {
					WorkspacePanel was = leaf.showing();
					if (was != null) was.closed();
					leaf.active = i;
					leaf.tabs.get(i).opened();
					fitShown(leaf);
				}
				focused = leaf.tabs.get(i);
				carried = leaf.tabs.get(i);
				carriedFrom = leaf;
				carriedFloater = null;
				carryX = px;
				carryY = py;
				return true;
			}
			at += span;
		}
		return true;
	}

	/** Which of the marks at the right of a bar was hit, or -1. */
	private int barButton(int right, double px, double py, int top) {
		if (py < top || py >= top + TABS) return -1;
		for (int i = 0; i < BUTTONS; i++) {
			int left = right - BUTTON * (BUTTONS - i);
			if (px >= left && px < left + BUTTON) return i;
		}
		return -1;
	}

	private void bringToFront(Floater floater) {
		floaters.remove(floater);
		floaters.add(floater);
	}

	void dockBack(Floater floater) {
		floaters.remove(floater);
		Leaf home = leaves.isEmpty() ? null : leaves.get(0);
		if (home == null) {
			root = new Leaf(floater.panel);
			return;
		}
		home.tabs.add(floater.panel);
		home.active = home.tabs.size() - 1;
	}

	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double px = event.x();
		double py = event.y();

		if (heldSplitter != null) {
			dragSplitter(px, py);
			return true;
		}
		if (carried != null) {
			// Four pixels of slack, so that clicking a tab selects it and does not
			// tear it out because a hand moved.
			if (!carrying && (Math.abs(px - carryX) > 4 || Math.abs(py - carryY) > 4)) {
				carrying = true;
				// And the window goes back the moment a panel is picked up to be moved.
				//
				// A panel the dock had given the whole window to was a panel with nowhere
				// to be put: every leaf but its own is out of the layout while it fills the
				// screen, and a drop onto its own leaf when it is the only tab there is
				// refused as a move that changes nothing. So it could be dragged and never
				// went anywhere — reported as refusing to be moved about half the time,
				// which is exactly how often the graph had taken the window.
				//
				// Only room the dock took for itself. A maximise somebody pressed is
				// theirs, and dragging a tab is not a request to undo it.
				if (byItself && maximized != null && maximized.tabs.contains(carried)) {
					giveBackWindow();
				}
			}
			carryX = px;
			carryY = py;
			if (carrying) {
				if (heldFloater != null && carriedFloater != null) {
					heldFloater.x += (int) dragX;
					heldFloater.y += (int) dragY;
				}
				return true;
			}
		}
		if (heldFloater != null) {
			if (resizingFloater && heldSide == -1) {
				// The left edge: the panel grows leftwards, so its corner moves with the
				// hand and its width grows by as much as the corner moved back. Without
				// moving the corner the panel would appear to slide away from the hand
				// pulling it.
				int least = Math.max(120, heldFloater.panel.minimumWidth());
				int wide = Math.max(least, heldFloater.width - (int) dragX);
				heldFloater.x += heldFloater.width - wide;
				heldFloater.width = wide;
			} else if (resizingFloater && heldSide == 1) {
				heldFloater.width = Math.max(
					Math.max(120, heldFloater.panel.minimumWidth()),
					heldFloater.width + (int) dragX);
			} else if (resizingFloater) {
				heldFloater.width = Math.max(120, heldFloater.width + (int) dragX);
				heldFloater.height = Math.max(60, heldFloater.height + (int) dragY);
			} else {
				heldFloater.x += (int) dragX;
				heldFloater.y += (int) dragY;
			}
			return true;
		}

		// Whoever took the press keeps the drag, wherever the cursor has got to.
		if (pressed != null) {
			return pressed.mouseDragged(moved(event, pressedX, pressedY), dragX, dragY);
		}
		return false;
	}

	private void dragSplitter(double px, double py) {
		Split split = heldSplitter;
		if (split.vertical) {
			int span = split.height - SPLITTER;
			if (span > 0) split.ratio = Mth.clamp((float) (py - split.y) / span, 0f, 1f);
		} else {
			int span = split.width - SPLITTER;
			if (span > 0) split.ratio = Mth.clamp((float) (px - split.x) / span, 0f, 1f);
		}
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		if (heldSplitter != null) {
			heldSplitter = null;
			return true;
		}
		if (carrying && carried != null) {
			drop(dropAt(event.x(), event.y()));
			carried = null;
			carriedFrom = null;
			carriedFloater = null;
			carrying = false;
			heldFloater = null;
			return true;
		}
		carried = null;
		carriedFrom = null;
		carriedFloater = null;
		carrying = false;
		if (heldFloater != null) {
			// Written down at the end of the pull rather than during it, and against the
			// panel's id rather than the panel, so that dismissing it and calling it back
			// returns the width that was chosen instead of the one the code picked.
			if (resizingFloater) {
				PanelWidths.remember(heldFloater.panel.id(), heldFloater.width);
			}
			heldFloater = null;
			resizingFloater = false;
			return true;
		}

		// To whoever took the press, and to nobody else. A release delivered to the
		// panel the cursor happens to be over leaves the one that is mid-drag still
		// holding on, which is a gizmo that keeps following the mouse after the
		// button is up.
		if (pressed != null) {
			WorkspacePanel had = pressed;
			pressed = null;
			return had.mouseReleased(moved(event, pressedX, pressedY));
		}

		double px = event.x();
		double py = event.y();
		for (Floater floater : floaters) {
			if (floater.inBody(px, py)) {
				return floater.panel.mouseReleased(moved(event, floater.x, floater.y + TABS));
			}
		}
		for (Leaf leaf : leaves) {
			if (!leaf.inBody(px, py)) continue;
			WorkspacePanel panel = leaf.showing();
			if (panel == null) return false;
			return panel.mouseReleased(moved(event, leaf.x, leaf.y + TABS));
		}
		return false;
	}

	public boolean mouseScrolled(double px, double py, double amountX, double amountY) {
		for (int i = floaters.size() - 1; i >= 0; i--) {
			Floater floater = floaters.get(i);
			if (floater.inBody(px, py)) {
				return floater.panel.mouseScrolled(px - floater.x, py - floater.y - TABS, amountX, amountY);
			}
		}
		for (Leaf leaf : leaves) {
			if (!leaf.inBody(px, py)) continue;
			WorkspacePanel panel = leaf.showing();
			return panel != null
				&& panel.mouseScrolled(px - leaf.x, py - leaf.y - TABS, amountX, amountY);
		}
		return false;
	}

	public void mouseMoved(double px, double py) {
		for (Leaf leaf : leaves) {
			if (!leaf.inBody(px, py)) continue;
			WorkspacePanel panel = leaf.showing();
			if (panel != null) panel.mouseMoved(px - leaf.x, py - leaf.y - TABS);
		}
		for (Floater floater : floaters) {
			if (floater.inBody(px, py)) {
				floater.panel.mouseMoved(px - floater.x, py - floater.y - TABS);
			}
		}
	}

	public boolean keyPressed(KeyEvent event) {
		return focused != null && focused.keyPressed(event);
	}

	public boolean keyReleased(KeyEvent event) {
		return focused != null && focused.keyReleased(event);
	}

	public boolean charTyped(CharacterEvent event) {
		return focused != null && focused.charTyped(event);
	}

	public WorkspacePanel focused() {
		return focused;
	}

	/** Whether the caret is in a field in the panel that has the keyboard. */
	public boolean typing() {
		return focused != null && focused.typing();
	}

	/**
	 * Undoes whatever the workspace is in the middle of, and says whether it did.
	 *
	 * Two states can be entered by accident and neither announces the way out: a
	 * panel filling the screen, and a tab being carried. Escape is where a person
	 * looks for the way out of both, and giving it to them costs one method.
	 */
	public boolean escape() {
		// Whatever else escape undoes, it lets go. A panel left holding a press it
		// will never be told the end of is a panel stuck in the middle of a drag.
		pressed = null;

		// The panel first, because whatever it has open is the innermost thing on
		// screen and escape means "get me out of this" innermost-first. Without this
		// a menu opened over the world had no way out but clicking somewhere, which
		// is the kind of one-way door this method exists to prevent.
		WorkspacePanel showing = focused;
		if (showing != null && showing.escape()) return true;
		if (carried != null || carrying) {
			carried = null;
			carriedFrom = null;
			carriedFloater = null;
			carrying = false;
			return true;
		}
		if (menu.isOpen()) {
			menu.close();
			return true;
		}
		if (maximized != null) {
			WorkspacePanel filling = maximized.showing();

			// A panel that took the window on its own is stepped off, not taken away.
			//
			// Escape means "get me out of this", and for a graph of nodes the way out is
			// not a smaller graph — handing the window back leaves it squeezed into the
			// state it declares it cannot be worked in. So escape moves to another tab of
			// the same bar, which is what "out of the graph" means when the graph is one
			// tab among several.
			//
			// <h2>It used to close it, and that was the bug</h2>
			//
			// {@code close(filling)} takes the panel out of the layout altogether. The
			// layout is saved on the way out, so the tab was gone for good: open the
			// graph from the panels menu, press escape to leave the workspace, come back
			// — and the bar has no graph in it. Reported as having to summon it and dock
			// it again every single time before any work could start.
			//
			// The reasoning was sound when a maximised graph was the only thing on the
			// screen and "put it away" was the only way out that meant anything. It
			// stopped being sound when panels started arriving as tabs beside each other:
			// there is somewhere to go now, and escape is not the button that deletes
			// things. The cross is.
			//
			// Only when the dock took the window unasked. Somebody who pressed the
			// maximise button themselves is asking for it back, not asking to leave, and
			// `byItself` is exactly that difference.
			if (byItself && filling != null && filling.wholeWindow()) {
				Leaf leaf = maximized;
				maximized = null;
				byItself = false;
				int beside = besideIn(leaf, filling);
				if (beside >= 0) {
					filling.closed();
					leaf.active = beside;
					leaf.tabs.get(beside).opened();
					fitShown(leaf);
				}
				return true;
			}
			maximized = null;
			// Cleared with it, which it never was. A stale "the dock chose this" left
			// over from a window nobody is filling any more is a flag waiting to make
			// the next decision on the wrong grounds.
			byItself = false;
			return true;
		}
		return false;
	}

	/**
	 * Which other tab of this leaf to step onto, or -1 when there is nowhere to go.
	 *
	 * The world first if it is in the same bar, because that is what somebody leaving
	 * a graph is going back to look at. Failing that, anything that is not itself a
	 * panel demanding the whole window — stepping off one of those onto another is
	 * not stepping off anything.
	 */
	private int besideIn(Leaf leaf, WorkspacePanel leaving) {
		int fallback = -1;
		for (int i = 0; i < leaf.tabs.size(); i++) {
			WorkspacePanel each = leaf.tabs.get(i);
			if (each == leaving) continue;
			if (each.id().equals("viewport")) return i;
			if (!each.wholeWindow() && fallback < 0) fallback = i;
		}
		return fallback;
	}

	public void tick() {
		for (WorkspacePanel panel : panels()) panel.ticked();
	}

	public void closed() {
		for (WorkspacePanel panel : panels()) panel.closed();
	}

	// ------------------------------------------------------------------ moves

	/**
	 * Puts a panel against one whole side of the workspace.
	 *
	 * Named directions rather than a dropped rectangle, because this is what the
	 * menu asks for and because "put it back on the right" is a thing somebody
	 * says out loud. Dragging covers the arrangements nobody can name.
	 */
	/**
	 * Puts a panel down as a slab against an edge, over whatever is behind it.
	 *
	 * The shape the workspace is moving to: the world fills the window and a panel
	 * is something summoned over it and dismissed again, rather than a column that
	 * is always there taking room whether or not it is being used.
	 *
	 * A floater rather than a new kind of thing, because a floater is already
	 * exactly this — a panel with a bar, over the rest, that can be moved and
	 * closed. The only thing missing was a way to say where to put one.
	 */
	public void floatAt(WorkspacePanel panel, int left, int top, int wide, int tall) {
		detach(panel);
		floaters.add(new Floater(panel, left, top, wide, tall));
		focused = panel;
	}

	/** The floating panel with this id, or null when it is not out. */
	public WorkspacePanel floating(String id) {
		for (Floater floater : floaters) {
			if (floater.panel.id().equals(id)) return floater.panel;
		}
		return null;
	}

	public void send(WorkspacePanel panel, String where) {
		Leaf from = leafOf(panel);
		carried = panel;
		carriedFrom = from;
		carriedFloater = floaterOf(panel);
		carryX = x + width / 2.0;
		carryY = y + height / 2.0;
		Side side = switch (where) {
			case "left" -> Side.LEFT;
			case "right" -> Side.RIGHT;
			case "top" -> Side.TOP;
			case "bottom" -> Side.BOTTOM;
			default -> Side.FLOAT;
		};
		if (side == Side.FLOAT) {
			detach(panel);
			floaters.add(new Floater(panel, x + width / 2 - 120, y + height / 2 - 80, 240, 160));
		} else {
			detach(panel);
			Leaf fresh = new Leaf(panel);
			boolean vertical = side == Side.TOP || side == Side.BOTTOM;
			boolean firstIsNew = side == Side.LEFT || side == Side.TOP;
			Node whole = root;
			root = firstIsNew
				? new Split(vertical, 0.25f, fresh, whole)
				: new Split(vertical, 0.75f, whole, fresh);
			fitShown(fresh);
		}
		carried = null;
		carriedFrom = null;
		carriedFloater = null;
	}

	/**
	 * Gives a leaf the window, or takes it back — except where taking it back is a
	 * state the panel has said it cannot be used in.
	 *
	 * The refusal is silent because there is nothing to say: the mark that would have
	 * asked for this is not drawn on such a bar, so the only ways here are the key
	 * and a caller, and neither is somebody reading a button. The way out is the
	 * cross, or escape, which puts it away rather than shrinking it.
	 */
	public void maximize(Leaf leaf) {
		if (maximized == leaf) {
			WorkspacePanel filling = leaf.showing();
			if (filling != null && filling.wholeWindow()) return;
			maximized = null;
			byItself = false;
			return;
		}
		maximized = leaf;
		byItself = false;
	}

	/**
	 * Set when the dock maximised on its own rather than being asked to.
	 *
	 * The difference decides what happens when the tab changes: room the dock took
	 * for a graph it gives back when the graph is not being looked at, and room
	 * somebody took deliberately it keeps. Without the distinction, one of the two
	 * has to be wrong — either a deliberate maximise collapses the moment you
	 * change tab, or a graph leaves the whole window taken behind it.
	 */
	private boolean byItself;

	/**
	 * Gives the window to a panel that cannot be used without it, and takes it back.
	 *
	 * Called wherever the shown panel changes, which is more places than it looks:
	 * a tab click, a panel dropped into a leaf, a layout read off disk.
	 */
	void fitShown(Leaf leaf) {
		WorkspacePanel showing = leaf.showing();
		if (showing != null && showing.wholeWindow()) {
			if (maximized == null) {
				maximized = leaf;
				byItself = true;
			}
			return;
		}
		if (byItself && maximized == leaf) {
			maximized = null;
			byItself = false;
		}
	}

	/**
	 * Hands back a window the dock took for itself, and says nothing about one that
	 * was asked for.
	 *
	 * The whole of the distinction {@link #byItself} exists for, offered outside so
	 * that opening the workspace can put the world in front without also undoing a
	 * maximise somebody chose. Room the dock took, the dock can give back; room a
	 * person took is theirs.
	 */
	public void giveBackWindow() {
		if (!byItself) return;
		maximized = null;
		byItself = false;
	}

	/**
	 * The same, for a layout that has just been read off disk.
	 *
	 * Without it a workspace saved while looking at the graph reopens with the graph
	 * squeezed back into its column — the arrangement the panel exists to escape,
	 * restored faithfully every time.
	 */
	public void fitAll() {
		for (Leaf leaf : leavesOf(root)) fitShown(leaf);
	}

	/** The leaf a key should act on: whatever is being typed into, or the maximised one. */
	public Leaf focusedLeaf() {
		Leaf of = focused == null ? null : leafOf(focused);
		return of != null ? of : maximized;
	}

	public Leaf maximized() {
		return maximized;
	}

	public void close(WorkspacePanel panel) {
		carried = panel;
		carriedFrom = leafOf(panel);
		carriedFloater = floaterOf(panel);
		detach(panel);
		carried = null;
		carriedFrom = null;
		carriedFloater = null;
		panel.closed();
	}

	public Leaf leafOf(WorkspacePanel panel) {
		for (Leaf leaf : leavesOf(root)) {
			if (leaf.tabs.contains(panel)) return leaf;
		}
		return null;
	}

	private Floater floaterOf(WorkspacePanel panel) {
		for (Floater floater : floaters) {
			if (floater.panel == panel) return floater;
		}
		return null;
	}

	/** Shows a panel that is somewhere in the layout, opening its tab if it is hidden. */
	public boolean reveal(String id) {
		for (Leaf leaf : leavesOf(root)) {
			for (int i = 0; i < leaf.tabs.size(); i++) {
				if (!leaf.tabs.get(i).id().equals(id)) continue;
				leaf.collapsed = false;
				leaf.active = i;
				focused = leaf.tabs.get(i);
				// A panel that cannot be worked in beside anything else needs the window
				// whichever way it was reached, and being revealed is a way of reaching it.
				fitShown(leaf);
				return true;
			}
		}
		for (Floater floater : floaters) {
			if (floater.panel.id().equals(id)) {
				bringToFront(floater);
				focused = floater.panel;
				return true;
			}
		}
		return false;
	}

	public void add(WorkspacePanel panel, String where) {
		if (reveal(panel.id())) return;
		send(panel, where);
	}

	/**
	 * Opens a panel as a tab beside what is already on the screen.
	 *
	 * <h2>Why this exists next to {@link #add}</h2>
	 *
	 * Because "put it where its kind belongs" and "put it here with the rest" are
	 * different requests, and the panels menu was making the first when it meant the
	 * second. Asked for the dialogue, {@code add} split the layout, gave the new leaf
	 * its own half, and then — because a graph cannot be worked in beside anything —
	 * the dock handed it the whole window. From the outside that is a window opening
	 * on top of everything, which is what it was reported as.
	 *
	 * A tab is what somebody means when they open a panel from a list of panels: the
	 * same bar, one more name in it, and the arrangement they had built left alone.
	 * Splitting is a thing you do by dragging, where you can see where it lands.
	 */
	public void addAsTab(WorkspacePanel panel) {
		if (reveal(panel.id())) return;
		Leaf home = leafOf(focused);
		if (home == null) home = leaves.isEmpty() ? null : leaves.get(0);
		if (home == null) {
			// No leaf at all, which happens only with everything closed. Then there is
			// nothing to be a tab of, and its own home is the honest answer.
			add(panel, Panels.homeOf(panel.id()));
			return;
		}
		home.tabs.add(panel);
		home.active = home.tabs.size() - 1;
		home.collapsed = false;
		focused = panel;
		fitShown(home);
	}

	// ----------------------------------------------------------------- the menu

	private final Menu menu = new Menu();

	private void openMenu(Leaf leaf, Floater floater, int px, int py) {
		WorkspacePanel panel = floater != null ? floater.panel : leaf.showing();
		if (panel == null) return;
		List<Menu.Entry> entries = new ArrayList<>();
		entries.add(Menu.Entry.of(word("left"), () -> send(panel, "left")));
		entries.add(Menu.Entry.of(word("right"), () -> send(panel, "right")));
		entries.add(Menu.Entry.of(word("top"), () -> send(panel, "top")));
		entries.add(Menu.Entry.of(word("bottom"), () -> send(panel, "bottom")));
		if (floater != null) {
			entries.add(Menu.Entry.of(Icon.DOCK, word("dock"), () -> dockBack(floater)));
		} else {
			entries.add(Menu.Entry.of(word("float"), () -> send(panel, "float")));
			entries.add(Menu.Entry.of(Icon.MAXIMIZE, word("full"), () -> maximize(leaf)));
		}
		entries.add(Menu.Entry.of(Icon.CLOSE, word("close"), () -> close(panel)));

		// And every panel that is not on screen, opened right here as a tab beside
		// this one. The top bar has offered this all along, which is a different
		// place from where somebody is looking when they think "I want the model
		// editor next to this" — and being told where a thing is kept is not the
		// same as it being to hand.
		for (String id : Panels.known()) {
			if (holds(id)) continue;
			entries.add(Menu.Entry.of(iconOf(id),
				Component.translatable("npc_studio.dock.open", Panels.titleOf(id)),
				() -> {
					WorkspacePanel made = Panels.make(id);
					if (made != null) addTo(leaf, made);
				}));
		}

		menu.open(font(), px, py, entries, x, y, width, height);
	}

	private boolean holds(String id) {
		for (WorkspacePanel panel : panels()) {
			if (panel.id().equals(id)) return true;
		}
		return false;
	}

	/** Puts a panel in as a tab of one leaf, or wherever it can go when there is none. */
	private void addTo(Leaf leaf, WorkspacePanel panel) {
		if (leaf == null) {
			add(panel, Panels.homeOf(panel.id()));
			return;
		}
		leaf.tabs.add(panel);
		leaf.active = leaf.tabs.size() - 1;
		leaf.collapsed = false;
		focused = panel;
	}

	private static Component word(String key) {
		return Component.translatable("npc_studio.dock." + key);
	}

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		menu.draw(graphics, font(), mouseX, mouseY);
	}

	private boolean clickMenu(double px, double py) {
		return menu.click(px, py);
	}

	// --------------------------------------------------------------- plumbing

	private static MouseButtonEvent moved(MouseButtonEvent event, int left, int top) {
		return new MouseButtonEvent(event.x() - left, event.y() - top, event.buttonInfo());
	}
}
