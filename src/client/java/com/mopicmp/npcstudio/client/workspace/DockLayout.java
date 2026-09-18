package com.mopicmp.npcstudio.client.workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The arrangement, written down.
 *
 * Ids rather than panels, because a panel is a live thing with widgets and a
 * scroll position and a screen inside it, and none of that is an arrangement.
 * What is worth keeping is which panels were where and how the space was split
 * between them.
 *
 * <h2>The two ways a saved layout is wrong</h2>
 *
 * Both are ordinary and neither is an error. A layout saved before a panel
 * existed does not mention it — so anything missing is added afterwards, on the
 * side it calls home, rather than being lost until somebody thinks to look in
 * the dropdown. A layout saved after a panel was removed names something that
 * cannot be made — so unknown ids are dropped, and a split left with one child
 * becomes that child.
 */
public final class DockLayout {

	/** One node of a written-down tree. Public fields, because Gson reads them. */
	public static final class Saved {
		public boolean split;
		public boolean vertical;
		public float ratio;
		public Saved first;
		public Saved second;
		public List<String> tabs;
		public int active;
		public boolean collapsed;
	}

	public static final class SavedFloater {
		public String id;
		public int x;
		public int y;
		public int width;
		public int height;
	}

	public static final class SavedDock {

		/**
		 * Which arrangement this was written by.
		 *
		 * Absent — nought, since Gson leaves an unmentioned int alone — in every file
		 * written before the workspace stopped being columns. Those files describe a
		 * thing that no longer exists: a saved arrangement of twelve tabs in a
		 * column, restored faithfully, would put back exactly the arrangement the
		 * work is getting rid of.
		 *
		 * So they are not read. Not deleted either — {@link #before} keeps the last
		 * one, unread, because throwing away somebody's arrangement without asking
		 * is a different thing from not using it.
		 */
		public int version;

		public Saved root;
		public List<SavedFloater> floating = new ArrayList<>();
	}

	/** What this code writes, and what it agrees to read. */
	public static final int VERSION = 1;

	private DockLayout() { }

	// ------------------------------------------------------------- writing

	public static SavedDock of(Dock dock) {
		SavedDock written = new SavedDock();
		written.version = VERSION;
		written.root = write(dock.root());
		for (Dock.Floater floater : dock.floaters()) {
			SavedFloater one = new SavedFloater();
			one.id = floater.panel.id();
			one.x = floater.x;
			one.y = floater.y;
			one.width = floater.width;
			one.height = floater.height;
			written.floating.add(one);
		}
		return written;
	}

	private static Saved write(Dock.Node node) {
		Saved saved = new Saved();
		if (node instanceof Dock.Split branch) {
			saved.split = true;
			saved.vertical = branch.vertical;
			saved.ratio = branch.ratio;
			saved.first = write(branch.first);
			saved.second = write(branch.second);
			return saved;
		}
		Dock.Leaf leaf = (Dock.Leaf) node;
		saved.tabs = leaf.tabs.stream().map(WorkspacePanel::id).toList();
		saved.active = leaf.active;
		saved.collapsed = leaf.collapsed;
		return saved;
	}

	// ------------------------------------------------------------- reading

	/**
	 * Builds a dock from what was written, or null when nothing usable was.
	 *
	 * Null rather than an empty dock: an unreadable layout means "use the one we
	 * ship with", and a workspace with no panels in it is not that.
	 */
	public static Dock read(SavedDock written) {
		if (written == null || written.root == null) return null;
		// An arrangement from before the workspace stopped being columns. Reading it
		// would restore the thing being got rid of — see the note on the field.
		if (written.version != VERSION) return null;

		Set<String> placed = new LinkedHashSet<>();
		Dock.Node root = build(written.root, placed);
		if (root == null) return null;

		Dock dock = new Dock(root);
		if (written.floating != null) {
			for (SavedFloater one : written.floating) {
				WorkspacePanel panel = Panels.make(one.id);
				if (panel == null || !placed.add(one.id)) continue;
				dock.floaters().add(new Dock.Floater(panel,
					one.x, one.y, Math.max(120, one.width), Math.max(60, one.height)));
			}
		}

		// And nothing else. A panel the layout does not mention is a panel that is not
		// open, which is now the ordinary state of nearly all of them.
		//
		// This used to add every unmentioned panel to its home side, so that a panel
		// added to the mod would not go missing for anyone who had ever dragged
		// something. Under columns that was right. Under this workspace it is a
		// trapdoor: the shipped arrangement is the world and nothing else, so the first
		// reload after the first save would put all seventeen back down the sides —
		// rebuilding by hand the exact arrangement that could not fit, and calling it
		// restoring.
		//
		// Nothing goes missing now because nothing needed the layout to be found: the
		// strips down the edges, the two buttons in the top bar, the ring in the world
		// and the panels list all reach a panel that is not open.
		return dock;
	}

	private static Dock.Node build(Saved saved, Set<String> placed) {
		if (saved == null) return null;
		if (saved.split) {
			Dock.Node first = build(saved.first, placed);
			Dock.Node second = build(saved.second, placed);
			// A split that lost a child is not a split any more. Keeping it would
			// mean a divider with nothing on one side of it.
			if (first == null) return second;
			if (second == null) return first;
			return new Dock.Split(saved.vertical, saved.ratio, first, second);
		}

		List<WorkspacePanel> panels = new ArrayList<>();
		if (saved.tabs != null) {
			for (String id : saved.tabs) {
				WorkspacePanel panel = Panels.make(id);
				if (panel == null || !placed.add(id)) continue;
				panels.add(panel);
			}
		}
		if (panels.isEmpty()) return null;

		Dock.Leaf leaf = new Dock.Leaf(panels.toArray(new WorkspacePanel[0]));
		leaf.active = Math.max(0, Math.min(saved.active, panels.size() - 1));
		leaf.collapsed = saved.collapsed;
		return leaf;
	}
}
