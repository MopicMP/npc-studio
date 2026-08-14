package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.net.EditorPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The dialogue as boxes and wires.
 *
 * A list told you what nodes existed but not what shape the conversation was;
 * whether a branch rejoined, whether an answer led anywhere, whether the whole
 * thing was one straight line — all of that had to be held in the head.
 *
 * Everything happens on this one screen. Editing a node used to open a screen
 * of its own, which hid the graph exactly when you needed to see it, so the
 * fields now live in a panel at the side and small edits happen in the box
 * itself. There is no "Done": changes land as they are typed, and Save sends
 * the whole thing.
 *
 * Positions are worked out from the graph rather than stored. Depth from the
 * start gives the column, order within that depth gives the row, so a
 * conversation lays itself out left to right in the order it is read. They can
 * be nudged, but the nudge lasts as long as the screen does: persisting them
 * would mean putting editor state into the format datapack authors write, or
 * keeping a second file in step. Neither is worth it before the layout has been
 * lived with.
 */
public class GraphEditorScreen extends Screen {

	private static final int BOX_WIDTH = 118;
	private static final int TITLE_HEIGHT = 13;
	private static final int PORT_ROW = 12;
	private static final int COLUMN_GAP = 66;
	private static final int ROW_GAP = 16;
	private static final int PORT = 5;

	private static final int CANVAS = 0xFF101318;
	private static final int GRID = 0xFF171B21;
	private static final int BOX_BODY = 0xFF1B2028;
	private static final int BOX_EDGE = 0xFF2C333D;
	private static final int WIRE_LIVE = 0xFF4FC3F7;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;

	private static GraphEditorScreen open;

	public static GraphEditorScreen current() {
		return open;
	}

	private final EditorState state;
	private final Map<String, Integer> x = new HashMap<>();
	private final Map<String, Integer> y = new HashMap<>();

	private int panX;
	private int panY;
	private String dragging;
	private boolean panning;

	private String linkFrom;
	private int linkExit;

	private NodePanel panel;
	private EditBox inline;

	private String saveMessage = "";
	private boolean saveFailed;

	public GraphEditorScreen(EditorState state) {
		super(Component.literal("Dialogue"));
		this.state = state;
		// What arrived from the server. Anything different from this on the way out
		// is an unsaved change and worth keeping.
		this.lastSaved = state.toJson();
	}

	@Override
	public void removed() {
		if (open == this) open = null;

		// Leaving with changes that were never sent writes a draft. Escape is
		// pressed by reflex, and an hour of graph should not depend on which
		// reflex it was.
		String now = state.toJson();
		if (!now.equals(lastSaved)) DialogueDrafts.keep(state.id(), now);
	}

	@Override
	protected void init() {
		open = this;
		if (x.isEmpty()) layout();

		int bar = height - 26;
		int at = 8;
		at = tool(at, bar, 58, "+ line", 0xFF4FC3F7, () -> add("line"));
		at = tool(at, bar, 62, "+ choice", 0xFFBA68C8, () -> add("choice"));
		at = tool(at, bar, 52, "+ end", 0xFF78909C, () -> add("end"));
		at = tool(at, bar, 82, "+ action", 0xFFFF8A65, () -> add("animation"));
		at = tool(at + 8, bar, 76, "re-arrange", 0xFFFFCA28, () -> { x.clear(); layout(); });
		tool(at, bar, 62, "dialogues", 0xFF8A99A6,
			() -> ClientPlayNetworking.send(new EditorPayloads.Browse()));

		// Kept clear of the panel: the panel now runs the full height, and a save
		// button underneath it would be a button you cannot press.
		addRenderableWidget(new FlatButton(width - 72 - panelWidth(), bar, 64, 20,
			Component.literal("save"), 0xFF66BB6A, this::save));
		tool(at + 66, bar, 66, "settings", 0xFF8A99A6,
			() -> minecraft.setScreenAndShow(new SettingsScreen(this)));
		// The nearest character's own settings. Editing what somebody says and
		// editing how they look are different jobs, but they are the same session,
		// and closing the editor to go and crouch at an NPC is a detour through the
		// world to reach a screen the game could simply show.
		tool(at + 136, bar, 50, "npc", 0xFF8A99A6, () -> NpcSettingsButton.open(this));
		tool(at + 190, bar, 56, "drafts", 0xFFFFCA28,
			() -> minecraft.setScreenAndShow(new DraftsScreen(this, state.id(), this::restore)));

		if (panel != null) {
			panel.build(font, width - NodePanel.WIDTH, 0, height, this::addRenderableWidget);
		}
		if (inline != null) addRenderableWidget(inline);
	}

	private int tool(int at, int bar, int width, String label, int accent, Runnable action) {
		addRenderableWidget(new FlatButton(at, bar, width, 20, Component.literal(label), accent, action));
		return at + width + 4;
	}

	private int panelWidth() {
		return panel == null ? 0 : NodePanel.WIDTH;
	}

	// ------------------------------------------------------------ layout

	/**
	 * Places every node: column by how far it is from the start, row by order.
	 *
	 * Breadth-first so a node reached by two routes settles at the depth of the
	 * shorter one, which is where a reader would look for it. Anything the start
	 * cannot reach goes in a column of its own rather than being dropped — an
	 * orphan is a mistake to see, not to hide.
	 */
	private void layout() {
		Map<String, Integer> depth = new HashMap<>();
		Deque<String> queue = new ArrayDeque<>();
		if (state.nodeIds().contains(state.start())) {
			depth.put(state.start(), 0);
			queue.add(state.start());
		}
		while (!queue.isEmpty()) {
			String id = queue.poll();
			Node node = node(id);
			if (node == null) continue;
			for (String exit : node.exits()) {
				if (exit != null && !depth.containsKey(exit) && node(exit) != null) {
					depth.put(exit, depth.get(id) + 1);
					queue.add(exit);
				}
			}
		}

		int orphans = depth.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
		Map<Integer, Integer> used = new HashMap<>();
		for (Node node : state.nodes()) {
			int column = depth.getOrDefault(node.id(), orphans);
			int row = used.merge(column, 1, Integer::sum) - 1;
			x.put(node.id(), 20 + column * (BOX_WIDTH + COLUMN_GAP));
			y.put(node.id(), 44 + row * (boxHeight(node) + ROW_GAP));
		}
	}

	private Node node(String id) {
		for (Node node : state.nodes()) {
			if (node.id().equals(id)) return node;
		}
		return null;
	}

	private static int boxHeight(Node node) {
		return TITLE_HEIGHT + Math.max(1, node.exits().size()) * PORT_ROW + 4;
	}

	static int colourOf(Node node) {
		return switch (node) {
			case Node.Line _ -> 0xFF4FC3F7;
			case Node.Choice _ -> 0xFFBA68C8;
			case Node.Set _ -> 0xFF66BB6A;
			case Node.Branch _ -> 0xFFFFCA28;
			case Node.Act _ -> 0xFFFF8A65;
			case Node.End _ -> 0xFF78909C;
		};
	}

	static String titleOf(Node node) {
		return switch (node) {
			case Node.Line _ -> "line";
			case Node.Choice _ -> "choice";
			case Node.Set _ -> "set";
			case Node.Branch _ -> "branch";
			case Node.Act _ -> "act";
			case Node.End _ -> "end";
		};
	}

	/** What each exit row says: the answer's own words, or the line it will speak. */
	private static List<String> rowsOf(Node node) {
		return switch (node) {
			case Node.Choice choice -> choice.options().stream().map(o -> shorten(o.label(), 15)).toList();
			case Node.Line line -> List.of(shorten(line.text(), 15));
			case Node.End _ -> List.of();
			// A box saying "next" told the reader nothing they could not already see
			// from the wire coming out of it. What it does is the useful part.
			case Node.Act act -> List.of(shorten(NodePanel.effectName(act.effect()), 15));
			case Node.Set set -> List.of(shorten(set.variable() + " =", 15));
			case Node.Branch branch -> {
				// One row per exit, or the ports and the rows stop lining up and a
				// wire appears to leave from the wrong place.
				var rows = new java.util.ArrayList<String>();
				for (int i = 0; i < branch.arms().size(); i++) rows.add("if " + (i + 1));
				rows.add("otherwise");
				yield List.copyOf(rows);
			}
		};
	}

	private static String shorten(String text, int limit) {
		return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
	}

	// ------------------------------------------------------------ editing

	private void add(String type) {
		String id = state.freshId(type);
		state.add(switch (type) {
			case "line" -> new Node.Line(id, "", "…", Presentation.SUBTITLE, null, id);
			case "choice" -> new Node.Choice(id, "?", List.of(new Node.Option("…", id)));
			// An animation on its own is an `act` node — the model already had one,
			// so the block the writer asked for turns out to be a block we have.
			case "animation" -> new Node.Act(id,
				new com.mopicmp.npcstudio.dialogue.Effect.PlayAnimation("wave",
					com.mopicmp.npcstudio.client.entity.NpcGestures.lengthOf("wave")), id);
			default -> new Node.End(id);
		});
		// Dropped where the person is looking rather than at the end of a column,
		// so a new box does not have to be gone and found.
		x.put(id, -panX + (width - panelWidth()) / 2 - BOX_WIDTH / 2);
		y.put(id, -panY + height / 2);
		select(state.nodes().size() - 1);
	}

	private void select(int index) {
		closeInline();
		panel = index < 0 ? null : new NodePanel(this, state, index);
		rebuild();
	}

	public void refreshPanel() {
		rebuild();
	}

	/**
	 * Opens the animation picker over this screen.
	 *
	 * The graph goes away while it is open, which is the one place that is right:
	 * choosing a gesture is about watching it, and a canvas behind would only be
	 * something else moving.
	 */
	public void pickAnimation(String current, java.util.function.Consumer<String> onPick) {
		// The preview wears the skin of an NPC that actually uses this dialogue, so
		// the gesture is judged on the character it will belong to.
		minecraft.setScreenAndShow(new AnimationPickerScreen(this, current,
			() -> AnimationPickerScreen.lookFor(state.id()), picked -> {
				onPick.accept(picked);
				rebuild();
			}));
	}

	public void deleteSelected() {
		if (panel == null) return;
		state.remove(panel.index());
		panel = null;
		rebuild();
	}

	/** Keeps the positions with the node when it is renamed. */
	public void renamed(String from, String to) {
		Integer px = x.remove(from);
		Integer py = y.remove(from);
		if (px != null) x.put(to, px);
		if (py != null) y.put(to, py);
	}

	void rebuild() {
		clearWidgets();
		init();
	}

	private void save() {
		ClientPlayNetworking.send(new EditorPayloads.Save(state.toJson()));
		// Written down as the last thing that was meant to be kept. If the save is
		// refused, the editor stays open with the changes still in it and the draft
		// is only a second copy; if it succeeds, this is what was saved.
		lastSaved = state.toJson();
	}

	/** The graph as it stood when it was last sent, for telling changes from none. */
	private String lastSaved;

	/**
	 * Puts a draft back in place of what is on screen.
	 *
	 * Nothing is sent: restoring is loading, not saving. Whoever asked for it can
	 * look at what came back and decide, which is the whole reason the two are
	 * separate buttons.
	 */
	private void restore(String json) {
		EditorState restored = EditorState.from(json, state.otherNames());
		minecraft.setScreenAndShow(new GraphEditorScreen(restored));
	}

	public void saveResult(boolean ok, String message) {
		saveFailed = !ok;
		saveMessage = message;
	}

	/**
	 * A box for the one thing on a row, floating over the box itself.
	 *
	 * The panel can change everything, but reaching for it to fix a typo in a
	 * line is more travel than the fix is worth. Clicking the words edits the
	 * words.
	 */
	private void openInline(Node node, int row, int boxX, int boxY) {
		closeInline();
		int index = state.nodeIds().indexOf(node.id());
		inline = new EditBox(font, boxX + 2, boxY + TITLE_HEIGHT + row * PORT_ROW,
			BOX_WIDTH - 4, PORT_ROW, Component.literal("text"));
		inline.setMaxLength(512);

		switch (node) {
			case Node.Line line -> {
				inline.setValue(line.text());
				inline.setResponder(value -> state.replace(index, new Node.Line(line.id(),
					line.speaker(), value, line.mode(), line.animation(), line.next())));
			}
			case Node.Choice choice -> {
				if (row >= choice.options().size()) return;
				inline.setValue(choice.options().get(row).label());
				inline.setResponder(value -> {
					Node.Choice now = (Node.Choice) state.nodes().get(index);
					List<Node.Option> options = new ArrayList<>(now.options());
					Node.Option old = options.get(row);
					options.set(row, new Node.Option(value, old.colour(), old.condition(), old.next()));
					state.replace(index, new Node.Choice(now.id(), now.speaker(), now.prompt(), now.mode(), options));
				});
			}
			default -> { return; }
		}
		rebuild();
		inline.setFocused(true);
		setFocused(inline);
	}

	private void closeInline() {
		if (inline != null) {
			removeWidget(inline);
			inline = null;
		}
	}

	// ------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;

		int mx = (int) event.x() - panX;
		int my = (int) event.y() - panY;

		for (int i = state.nodes().size() - 1; i >= 0; i--) {
			Node node = state.nodes().get(i);
			int bx = x.getOrDefault(node.id(), 0);
			int by = y.getOrDefault(node.id(), 0);

			List<String> rows = rowsOf(node);
			for (int row = 0; row < rows.size(); row++) {
				int px = bx + BOX_WIDTH;
				int py = by + TITLE_HEIGHT + row * PORT_ROW + PORT_ROW / 2;
				if (Math.abs(mx - px) <= PORT && Math.abs(my - py) <= PORT) {
					// Right button cuts the wire. A graph you can only add to is a
					// graph you end up rebuilding from scratch to change your mind,
					// and there was no other way to undo a connection.
					if (event.button() == 1) {
						connect(node.id(), row, null);
						linkFrom = null;
					} else {
						linkFrom = node.id();
						linkExit = row;
					}
					return true;
				}
			}

			int h = boxHeight(node);
			if (mx < bx || mx > bx + BOX_WIDTH || my < by || my > by + h) continue;

			if (linkFrom != null) {
				connect(linkFrom, linkExit, node.id());
				linkFrom = null;
				return true;
			}

			// The title bar is the node; the rows are its contents.
			if (my <= by + TITLE_HEIGHT) {
				dragging = node.id();
				select(i);
			} else {
				int row = (my - by - TITLE_HEIGHT) / PORT_ROW;
				if (row < rows.size()) openInline(node, row, bx + panX, by + panY);
				else select(i);
			}
			return true;
		}

		closeInline();
		if (linkFrom != null) linkFrom = null;
		else if (event.x() < width - panelWidth()) panning = true;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			x.merge(dragging, (int) dx, Integer::sum);
			y.merge(dragging, (int) dy, Integer::sum);
			return true;
		}
		if (panning) {
			panX += (int) dx;
			panY += (int) dy;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		panning = false;
		return super.mouseReleased(event);
	}

	private void connect(String fromId, int exit, String toId) {
		int index = state.nodeIds().indexOf(fromId);
		Node from = node(fromId);
		if (from == null) return;

		state.replace(index, switch (from) {
			case Node.Line line -> new Node.Line(line.id(), line.speaker(), line.text(),
				line.mode(), line.animation(), toId);
			case Node.Choice choice -> {
				List<Node.Option> options = new ArrayList<>(choice.options());
				if (exit < options.size()) {
					Node.Option old = options.get(exit);
					options.set(exit, new Node.Option(old.label(), old.colour(), old.condition(), toId));
				}
				yield new Node.Choice(choice.id(), choice.speaker(), choice.prompt(), choice.mode(), options);
			}
			// Everything else that goes somewhere. These were left out at first and
			// it showed as a graph where a "set" or an "act" could be created and
			// then never wired to anything.
			case Node.Set set -> new Node.Set(set.id(), set.variable(), set.scope(), set.value(), toId);
			case Node.Act act -> new Node.Act(act.id(), act.effect(), toId);
			case Node.Branch branch -> {
				List<Node.Arm> arms = new ArrayList<>(branch.arms());
				if (exit < arms.size()) {
					Node.Arm old = arms.get(exit);
					arms.set(exit, new Node.Arm(old.condition(), toId));
					yield new Node.Branch(branch.id(), arms, branch.otherwise());
				}
				// The last port on a branch is the "otherwise" arm — the one taken
				// when nothing else matched.
				yield new Node.Branch(branch.id(), arms, toId);
			}
			case Node.End end -> end;
		});
	}

	// ------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		grid(graphics);

		for (Node from : state.nodes()) {
			List<String> exits = from.exits();
			for (int i = 0; i < exits.size(); i++) {
				String to = exits.get(i);
				if (to == null || node(to) == null) continue;
				wire(graphics,
					panX + x.getOrDefault(from.id(), 0) + BOX_WIDTH,
					panY + y.getOrDefault(from.id(), 0) + TITLE_HEIGHT + i * PORT_ROW + PORT_ROW / 2,
					panX + x.getOrDefault(to, 0),
					panY + y.getOrDefault(to, 0) + TITLE_HEIGHT / 2,
					colourOf(from) & 0xAAFFFFFF);
			}
		}
		if (linkFrom != null) {
			wire(graphics,
				panX + x.getOrDefault(linkFrom, 0) + BOX_WIDTH,
				panY + y.getOrDefault(linkFrom, 0) + TITLE_HEIGHT + linkExit * PORT_ROW + PORT_ROW / 2,
				mouseX, mouseY, WIRE_LIVE);
		}

		for (Node node : state.nodes()) box(graphics, node);

		legend(graphics);
		if (panel != null) panel.draw(graphics, font, width - NodePanel.WIDTH, 0, height);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void grid(GuiGraphicsExtractor graphics) {
		int step = 24;
		for (int gx = panX % step; gx < width; gx += step) graphics.fill(gx, 0, gx + 1, height, GRID);
		for (int gy = panY % step; gy < height; gy += step) graphics.fill(0, gy, width, gy + 1, GRID);
	}

	private void box(GuiGraphicsExtractor graphics, Node node) {
		int bx = panX + x.getOrDefault(node.id(), 0);
		int by = panY + y.getOrDefault(node.id(), 0);
		int h = boxHeight(node);
		int accent = colourOf(node);
		boolean start = node.id().equals(state.start());
		boolean selected = panel != null && state.nodes().get(panel.index()).id().equals(node.id());

		graphics.fill(bx - 1, by - 1, bx + BOX_WIDTH + 1, by + h + 1,
			selected ? accent : start ? (accent & 0x88FFFFFF) : BOX_EDGE);
		graphics.fill(bx, by, bx + BOX_WIDTH, by + h, BOX_BODY);
		graphics.fill(bx, by, bx + BOX_WIDTH, by + TITLE_HEIGHT, accent & 0x44FFFFFF);

		graphics.text(font, Component.literal(titleOf(node)), bx + 4, by + 3, accent);
		String id = shorten(node.id(), 11);
		graphics.text(font, Component.literal(id), bx + BOX_WIDTH - 4 - font.width(id), by + 3, TEXT_DIM);

		if (!start) graphics.fill(bx - 3, by + 4, bx + 1, by + 9, BOX_EDGE);

		List<String> rows = rowsOf(node);
		for (int i = 0; i < rows.size(); i++) {
			int py = by + TITLE_HEIGHT + i * PORT_ROW;
			graphics.text(font, Component.literal(rows.get(i)), bx + 4, py + 2, TEXT);
			graphics.fill(bx + BOX_WIDTH - 1, py + PORT_ROW / 2 - 2,
				bx + BOX_WIDTH + 3, py + PORT_ROW / 2 + 2, accent);
		}
		if (rows.isEmpty()) {
			graphics.text(font, Component.literal("stop"), bx + 4, by + TITLE_HEIGHT + 2, TEXT_DIM);
		}
	}

	/**
	 * A wire, as a chain of small squares along a curve.
	 *
	 * There is no line to draw with here, only filled rectangles, so the curve is
	 * sampled and each sample filled — and the samples are counted from its
	 * length, or a long wire came out as a dotted line.
	 *
	 * The control points reach a fixed distance sideways rather than half the
	 * span. Half the span meant that two distant boxes were joined by an enormous
	 * shallow arc that wandered across everything in between; a short horizontal
	 * lead-out followed by a near-straight run says the same thing and stays
	 * where it is put.
	 */
	private static void wire(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int colour) {
		int span = Math.abs(x2 - x1) + Math.abs(y2 - y1);
		int reach = Math.min(56, Math.max(18, span / 4));
		int cx1 = x1 + reach;
		int cx2 = x2 - reach;
		int steps = Math.clamp(span / 3, 12, 96);

		for (int i = 0; i <= steps; i++) {
			float t = (float) i / steps;
			float u = 1 - t;
			float px = u * u * u * x1 + 3 * u * u * t * cx1 + 3 * u * t * t * cx2 + t * t * t * x2;
			float py = u * u * u * y1 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y2;
			graphics.fill((int) px, (int) py, (int) px + 2, (int) py + 2, colour);
		}
	}

	private void legend(GuiGraphicsExtractor graphics) {
		graphics.text(font, Component.literal(state.id()), 8, 8, TEXT);
		graphics.text(font, Component.literal(
			"title bar: move & select   ·   words: edit here   ·   port then box: connect   ·   right-click a port: disconnect"),
			8, 20, TEXT_DIM);

		int at = height - 44;
		if (!saveMessage.isEmpty()) {
			graphics.text(font, Component.literal(saveMessage), 8, at, saveFailed ? 0xFFEF5350 : 0xFF66BB6A);
			at -= 11;
		}
		List<DialogueValidator.Problem> problems = state.problems();
		for (int i = 0; i < Math.min(3, problems.size()); i++) {
			DialogueValidator.Problem problem = problems.get(i);
			boolean fatal = problem.severity() == DialogueValidator.Severity.ERROR;
			String where = problem.where() == null ? "" : problem.where() + ": ";
			graphics.text(font, Component.literal(where + problem.message()),
				8, at, fatal ? 0xFFEF5350 : 0xFFFFCA28);
			at -= 11;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
