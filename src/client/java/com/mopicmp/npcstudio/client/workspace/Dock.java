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

		if (!menu.isEmpty()) {
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
		WorkspacePanel panel = leaf.showing();
		boolean seeThrough = panel != null && panel.transparent();

		drawBar(graphics, leaf.x, leaf.y, leaf.width, leaf.tabs, leaf.active, leaf.collapsed,
			leaf == maximized, mouseX, mouseY, seeThrough);

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
			false, mouseX, mouseY, false);
		int bodyTop = floater.y + TABS;
		int bodyHeight = floater.height - TABS;
		if (bodyHeight <= 0) return;
		graphics.fill(floater.x, bodyTop, floater.x + floater.width, bodyTop + bodyHeight, PANEL);
		drawPanel(graphics, floater.panel, floater.x, bodyTop, floater.width, bodyHeight,
			mouseX, mouseY, delta);

		// The corner grip: three steps, which is enough to read as "pull here".
		for (int step = 0; step < 3; step++) {
			int at = 2 + step * 3;
			graphics.fill(floater.x + floater.width - at - 1, floater.y + floater.height - 3,
				floater.x + floater.width - at, floater.y + floater.height - 2, TEXT_DIM);
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
			int mouseX, int mouseY, boolean seeThrough) {
		graphics.fill(left, top, left + across, top + TABS, seeThrough ? 0xCC12161C : BAR);
		graphics.fill(left, top + TABS - 1, left + across, top + TABS, EDGE_LINE);

		int buttons = 3 * BUTTON;
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
		drawGlyph(graphics, right - BUTTON * 3, top, Icon.MENU, mouseX, mouseY);
		drawGlyph(graphics, right - BUTTON * 2, top, collapsed ? Icon.EXPAND : Icon.COLLAPSE,
			mouseX, mouseY);
		drawGlyph(graphics, right - BUTTON, top, full ? Icon.RESTORE : Icon.MAXIMIZE, mouseX, mouseY);
	}

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
		return named(leaf.tabs, leaf.width - 3 * BUTTON);
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
		if (root == empty) return;
		if (maximized == empty) maximized = null;
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

		detach(panel);

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
			maximized = maximized == leaf ? null : leaf;
			return true;
		}

		boolean named = named(leaf);
		int at = leaf.x;
		for (int i = 0; i < leaf.tabs.size(); i++) {
			int span = tabWidth(leaf.tabs.get(i), named);
			if (px >= at && px < at + span) {
				if (leaf.active != i) {
					WorkspacePanel was = leaf.showing();
					if (was != null) was.closed();
					leaf.active = i;
					leaf.tabs.get(i).opened();
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

	/** Which of the three buttons at the right of a bar was hit, or -1. */
	private int barButton(int right, double px, double py, int top) {
		if (py < top || py >= top + TABS) return -1;
		for (int i = 0; i < 3; i++) {
			int left = right - BUTTON * (3 - i);
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
			if (!carrying && (Math.abs(px - carryX) > 4 || Math.abs(py - carryY) > 4)) carrying = true;
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
			if (resizingFloater) {
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
		if (carried != null || carrying) {
			carried = null;
			carriedFrom = null;
			carriedFloater = null;
			carrying = false;
			return true;
		}
		if (!menu.isEmpty()) {
			menu = List.of();
			return true;
		}
		if (maximized != null) {
			maximized = null;
			return true;
		}
		return false;
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
		}
		carried = null;
		carriedFrom = null;
		carriedFloater = null;
	}

	public void maximize(Leaf leaf) {
		maximized = maximized == leaf ? null : leaf;
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

	// ----------------------------------------------------------------- the menu

	/** One line of the panel menu: what it says and what it does. */
	private record Entry(Component label, Runnable act) { }

	private List<Entry> menu = List.of();
	private int menuX;
	private int menuY;

	private static final int MENU_ROW = 12;
	private static final int MENU_WIDTH = 108;

	private void openMenu(Leaf leaf, Floater floater, int px, int py) {
		WorkspacePanel panel = floater != null ? floater.panel : leaf.showing();
		if (panel == null) return;
		List<Entry> entries = new ArrayList<>();
		entries.add(new Entry(word("left"), () -> send(panel, "left")));
		entries.add(new Entry(word("right"), () -> send(panel, "right")));
		entries.add(new Entry(word("top"), () -> send(panel, "top")));
		entries.add(new Entry(word("bottom"), () -> send(panel, "bottom")));
		if (floater != null) {
			entries.add(new Entry(word("dock"), () -> dockBack(floater)));
		} else {
			entries.add(new Entry(word("float"), () -> send(panel, "float")));
			entries.add(new Entry(word("full"), () -> maximize(leaf)));
		}
		entries.add(new Entry(word("close"), () -> close(panel)));

		// And every panel that is not on screen, opened right here as a tab beside
		// this one. The top bar has offered this all along, which is a different
		// place from where somebody is looking when they think "I want the model
		// editor next to this" — and being told where a thing is kept is not the
		// same as it being to hand.
		for (String id : Panels.known()) {
			if (holds(id)) continue;
			entries.add(new Entry(
				Component.translatable("npc_studio.dock.open", Panels.titleOf(id)),
				() -> {
					WorkspacePanel made = Panels.make(id);
					if (made != null) addTo(leaf, made);
				}));
		}

		menu = entries;
		menuX = Math.min(px, x + width - MENU_WIDTH);
		menuY = Math.min(py, y + height - entries.size() * MENU_ROW);
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
		int bottom = menuY + menu.size() * MENU_ROW;
		graphics.fill(menuX - 1, menuY - 1, menuX + MENU_WIDTH + 1, bottom + 1, EDGE_LINE);
		graphics.fill(menuX, menuY, menuX + MENU_WIDTH, bottom, PANEL);
		for (int i = 0; i < menu.size(); i++) {
			int top = menuY + i * MENU_ROW;
			boolean hovered = mouseX >= menuX && mouseX < menuX + MENU_WIDTH
				&& mouseY >= top && mouseY < top + MENU_ROW;
			if (hovered) graphics.fill(menuX, top, menuX + MENU_WIDTH, top + MENU_ROW, TAB_HOVER);
			graphics.text(font(), menu.get(i).label(), menuX + 6, top + 2, hovered ? TEXT : TEXT_DIM);
		}
	}

	private boolean clickMenu(double px, double py) {
		if (menu.isEmpty()) return false;
		int row = (int) ((py - menuY) / MENU_ROW);
		boolean inside = px >= menuX && px < menuX + MENU_WIDTH && row >= 0 && row < menu.size();
		List<Entry> entries = menu;
		menu = List.of();
		if (inside) entries.get(row).act().run();
		return true;
	}

	// --------------------------------------------------------------- plumbing

	private static MouseButtonEvent moved(MouseButtonEvent event, int left, int top) {
		return new MouseButtonEvent(event.x() - left, event.y() - top, event.buttonInfo());
	}
}
