package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.net.EditorPayloads;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.IconTextButton.Spec;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
public class GraphEditorScreen extends Screen
		implements com.mopicmp.npcstudio.client.workspace.Typing.Aware {

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

	/**
	 * Not final any more, because a panel cannot be replaced by a new one of
	 * itself the way a screen could. Restoring a draft swaps this instead.
	 */
	private EditorState state;
	private final Map<String, Integer> x = new HashMap<>();
	private final Map<String, Integer> y = new HashMap<>();

	private int panX;
	private int panY;

	/**
	 * How close the graph is drawn, one being life size.
	 *
	 * A conversation of thirty nodes does not fit a strip along the bottom of a
	 * workspace at life size, and panning around one box at a time to see the
	 * shape of it defeats the point of drawing it as a shape.
	 */
	private float zoom = 1f;

	/** How far the node's own panel has been scrolled, when it is taller than the room. */
	private int panelScroll;
	private String dragging;
	private boolean panning;

	private String linkFrom;
	private int linkExit;

	private NodePanel panel;

	/*
	 * There was an `inline` here: a one-line box that opened over a node when its
	 * words were clicked, so a typo could be fixed without reaching for the panel.
	 *
	 * It is gone, and the reason is that it had quietly become a way of losing work.
	 * It was a plain Minecraft field over text that is no longer plain — a line now
	 * carries colour, size and a face per stretch — so it could show none of that and
	 * offer none of it. Two ways to edit one line, one of which cannot see half of
	 * what is there, is not a shortcut; it is a second door into a smaller room.
	 *
	 * Clicking the words now selects the node, the same as clicking its title. One
	 * road to the text, and it is the one that can carry all of it.
	 */

	/**
	 * The text editor, when it is open.
	 *
	 * Drawn over this screen and offered the mouse and the keyboard before anything
	 * else, exactly as the list of node kinds and the list of skills already are.
	 * That is the one arrangement that works both as a panel in the workspace and
	 * as a screen on its own — a screen of its own would throw the workspace away,
	 * and a panel cannot exist when there is no workspace.
	 */
	private com.mopicmp.npcstudio.client.text.WordsEditor writer;

	/**
	 * Opens the text editor on one line, and puts the answer back where it came from.
	 *
	 * The window is the only place a line can be given a colour, a face or a size,
	 * so it is opened from the node's own panel — beside the field it is about,
	 * rather than from the row of buttons along the bottom, which is about the
	 * document.
	 */
	public void write(String title, com.mopicmp.npcstudio.dialogue.text.Words start,
			java.util.function.Consumer<com.mopicmp.npcstudio.dialogue.text.Words> kept) {
		writer = new com.mopicmp.npcstudio.client.text.WordsEditor(title, start, kept);
		writer.fit(width, height);
		// So the widgets underneath go away now rather than at the next resize.
		rebuild();
	}

	/**
	 * A list opened at a field of the node panel, for the fields with too many answers.
	 *
	 * <h2>What this replaces</h2>
	 *
	 * A button that stepped to the next answer on every press. That is a fine control
	 * for four of anything and a bad one for sixteen: choosing the verb of an
	 * {@code act} meant pressing the same button up to fifteen times, reading the
	 * label each time to find out whether you had gone past it, and having no way
	 * back but going round again. Reported as exactly that — the node does too many
	 * things and picking one is very awkward.
	 *
	 * The workspace's own list, not a new one. It already scrolls, already marks the
	 * answer in force, and already opens upwards when it would run off the bottom —
	 * three things a second implementation would have got wrong in its own way.
	 */
	private final com.mopicmp.npcstudio.client.workspace.Menu picker =
		new com.mopicmp.npcstudio.client.workspace.Menu();

	/**
	 * Opens the list at a field, with one entry marked as the answer now in force.
	 *
	 * Called by the panel, which knows what the choices are and where its own field
	 * is; the screen owns the list because a list has to be drawn over everything and
	 * offered the mouse before everything, and the panel is neither of those.
	 */
	public void pick(int atX, int atY, int wide,
			java.util.List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> entries) {
		picker.open(font, atX, atY, entries, 0, 0, width, height, wide);
	}

	/**
	 * The name of the document, while somebody is changing it.
	 *
	 * <h2>Why the name was read-only until now</h2>
	 *
	 * Not on purpose. A dialogue is stored under its own id, so the name in the
	 * corner is a fact about where the document lives rather than a field of it — and
	 * nothing had ever been written to change where it lives. Reported as exactly
	 * that: the name is there, and there is no way to alter it.
	 *
	 * <h2>Why it is renamed in place rather than in a window</h2>
	 *
	 * Because there is one thing to type and it is already on the screen, in the
	 * corner, being read. A window would be a box in the middle of the canvas asking
	 * for a word that is written six inches away — and it would cover the graph the
	 * name belongs to. So the name turns into a field where it stands, and turns
	 * back when it is done.
	 *
	 * Null while nobody is renaming, which is nearly always.
	 */
	private FlatField naming;

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
		// reflex it was. Rarer than it was, now that the graph saves itself — what
		// reaches here is what the server would not take.
		String now = written();
		if (now != null && !now.equals(lastSaved)) DialogueDrafts.keep(state.id(), now);
	}

	@Override
	protected void init() {
		open = this;
		if (x.isEmpty()) recall();
		// What names the other documents use, for the list a variable is chosen from.
		// Asked for on opening rather than held from last time: another document may
		// have been written since, and a list of names that no longer exist is worse
		// than no list, because it is trusted.
		KnownVariables.ask();

		// Nothing is built while the text window is open, and this is the fix for a
		// fault that looked like a drawing order and was not one. A screen draws its
		// widgets *last*, after everything the screen itself drew — so the fields of
		// the node panel and the row of buttons along the bottom came out on top of a
		// window that is supposed to be over them, and were clickable through it.
		//
		// Drawing the window later would have hidden it and left them clickable. The
		// honest answer is that a modal window means the things behind it are not
		// there for the moment, so they are not made.
		if (writer != null) {
			writer.fit(width, height);
			return;
		}

		int bar = height - 26;

		// One row, laid out by what there is room for rather than by what the
		// labels happen to measure. It used to be eight fixed widths adding up to
		// six hundred pixels, and in a panel the last three were past the edge —
		// which is not only untidy, it is unreachable.
		int across = width - 16 - panelWidth();
		IconTextButton.row(8, bar, across, 20, java.util.List.of(
			// One button and a list, rather than a button per kind. There were four,
			// which covered four of the ten kinds the language has — and the six left
			// out were the six that make a graph do anything: the waits, the branch,
			// the call. A row of buttons cannot grow to ten, so it never grew at all
			// and half the language stayed reachable only by editing json.
			new Spec(Icon.ADD, Component.translatable("npc_studio.graph.add"),
				0xFF4FC3F7, this::toggleAdding),
			new Spec(Icon.RESET, Component.translatable("npc_studio.graph.rearrange"),
				0xFFFFCA28, () -> { x.clear(); layout(); }),
			// The skills this document holds, in a list of their own. They are
			// nodes in the same graph and always were, but they are a different
			// sort of thing to look at: what a character knows how to do, as
			// against what this particular character is doing about it.
			new Spec(Icon.BODY, Component.translatable("npc_studio.graph.ways"),
				0xFF9575CD, this::toggleBrain),
			// The boxes drawn on the map. They belong to the document rather than to
			// any one node — two nodes name the same box, which is the whole point of
			// them having names — so the list of them belongs beside the skills rather
			// than inside a node panel.
			new Spec(Icon.SPOT, Component.translatable("npc_studio.graph.areas"),
				0xFFBA68C8, this::toggleAreas),
			// How this document's lines are put in front of a player. Beside the boxes
			// rather than in the editor's own settings, because it belongs to the
			// document and travels with it — the editor's settings are about this
			// person's editor, and these are about everybody's copy of this scene.
			// The document itself: what it is, what it grants, what it shows, and how it
			// speaks. It was called "showing" and held only the last of those, and the
			// three added since were put there because it was the nearest page — which
			// is how a page about subtitles came to decide whether a document is a
			// conversation at all.
			new Spec(Icon.FILE, Component.translatable("npc_studio.graph.document"),
				0xFF4FC3F7, this::toggleShowing),
			// What this document is allowed to remember. Beside the boxes and the ways
			// in because it is the same kind of thing: a fact about the whole document
			// that several nodes point at, so it cannot live inside any one of them.
			new Spec(Icon.ASSETS, Component.translatable("npc_studio.graph.vars"),
				0xFF66BB6A, this::toggleVariables),
			// Watching the graph decide things while it runs. Beside the variables
			// because it answers the question the variables page cannot: what a name
			// holds is knowable here, but which arm took it is only knowable while
			// somebody is standing in front of a character.
			new Spec(Icon.EYES, Component.translatable("npc_studio.graph.watch"),
				0xFFE0A34A, () -> com.mopicmp.npcstudio.client.dialogue.Watching
					.turn(!com.mopicmp.npcstudio.client.dialogue.Watching.on())),
			// Forgetting that I have played this one, so I can play it again. Beside the
			// watching because both are the same kind of thing — neither edits the
			// document, both are for the person testing it — and it touches nobody but
			// whoever pressed it; see EditorPayloads.Replay.
			new Spec(Icon.REWIND, Component.translatable("npc_studio.graph.replay"),
				0xFFE0A34A, () -> ClientPlayNetworking.send(
					new EditorPayloads.Replay(state.id()))),
			new Spec(Icon.SETTINGS, Component.translatable("npc_studio.graph.settings"),
				0xFF8A99A6, () -> {
					if (!WorkspaceScreen.embedded()) {
						minecraft.setScreenAndShow(new SettingsScreen(this));
					}
				}),
			// The nearest character's own settings. Editing what somebody says and
			// editing how they look are different jobs but the same session.
			new Spec(Icon.CHARACTER, Component.translatable("npc_studio.graph.npc"),
				0xFF8A99A6, () -> NpcSettingsButton.open(this)),
			// No Save. The graph saves itself — see keepUp() — and a button that is
			// never the thing that saved your work is a button that teaches a habit it
			// then fails to honour. Ctrl+S still means "now", for the hands that will
			// press it anyway.
			new Spec(Icon.DRAFTS, Component.translatable("npc_studio.graph.drafts"),
				0xFFFFCA28, () -> {
					if (!WorkspaceScreen.embedded()) {
						minecraft.setScreenAndShow(new DraftsScreen(this, state.id(), this::restore));
					}
				})), this::addRenderableWidget, true);

		// The name, and the way to change it. A pencil beside it rather than a field
		// always open: the name is read constantly and typed into about once a week, so
		// what it wants to be most of the time is a word.
		if (naming == null) {
			addRenderableWidget(new FlatButton(pencilX(), 5, PENCIL_WIDE, 14,
				Component.literal("✎"), 0xFFFFCA28, this::startNaming));
		} else {
			naming.setX(8);
			naming.setY(4);
			naming.setWidth(Math.min(180, Math.max(80, width / 3)));
			addRenderableWidget(naming);
			addRenderableWidget(new FlatButton(naming.getX() + naming.getWidth() + 4, 5, 16, 14,
				Component.literal("✓"), 0xFF66BB6A, this::finishNaming));
			addRenderableWidget(new FlatButton(naming.getX() + naming.getWidth() + 24, 5, 16, 14,
				Component.literal("×"), 0xFFEF5350, () -> {
					naming = null;
					rebuild();
				}));
		}

		// The way to another dialogue, in the far corner rather than in the row along
		// the bottom.
		//
		// Asked for, and right for what it does. Every other button in that row edits
		// the document you are looking at: add a node, lay it out, name a skill,
		// settings. This one leaves for a different document altogether, and a door out
		// of the room does not belong in the row of tools you are working with. It also
		// sat third of seven, which is where a hand lands by accident.
		//
		// Left of the node panel when there is one, because the panel is drawn over the
		// canvas and a button underneath it is a button nobody can press.
		addRenderableWidget(new IconTextButton(
			width - panelWidth() - 8 - IconTextButton.FOLDED, 6,
			IconTextButton.FOLDED, 20, Icon.BROWSE,
			Component.translatable("npc_studio.graph.dialogues"), 0xFF8A99A6,
			() -> ClientPlayNetworking.send(new EditorPayloads.Browse(true))));

		if (panel != null) {
			// The way out of the panel, and it is added before the panel's own fields
			// rather than with them, because everything in the panel is laid out from a
			// top that moves as the panel scrolls — a close button built that way would
			// scroll off the top of exactly the long node that made you want it.
			addRenderableWidget(new FlatButton(width - 20, 4, 14, 14,
				Component.literal("×"), 0xFFEF5350, () -> select(-1)));

			// Built from above the top edge by however far it is scrolled, and
			// anything landing outside the panel is not added at all. Simpler than
			// clipping: a widget that was never added cannot be drawn over the
			// canvas and cannot be clicked through it either.
			panelScroll = Math.max(0, Math.min(panelScroll,
				Math.max(0, panel.contentHeight() - height + 30)));
			panel.build(font, width - NodePanel.WIDTH, -panelScroll, height + panelScroll,
				widget -> {
					if (widget.getY() < 0 || widget.getY() + widget.getHeight() > height) return;
					addRenderableWidget(widget);
				});
		}
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
	/**
	 * Puts the boxes back where they were, and lays out whatever was never placed.
	 *
	 * <h2>Why it is both and not one or the other</h2>
	 *
	 * Because a document can be half arranged, and that is the ordinary case rather
	 * than an odd one: somebody drags six boxes into a shape, saves, comes back, and
	 * adds a seventh. Laying the whole thing out again would undo their work to place
	 * one node; placing none of it would leave the seventh at the origin under the
	 * first.
	 *
	 * So what is remembered is honoured, and only what is not is laid out — and the
	 * laying out then avoids the columns that are already occupied, so a new node
	 * arrives beside the graph rather than on top of it.
	 */
	private void recall() {
		for (var entry : state.places().entrySet()) {
			if (node(entry.getKey()) == null) continue;
			x.put(entry.getKey(), entry.getValue().x());
			y.put(entry.getKey(), entry.getValue().y());
		}
		// Nothing remembered at all is a document nobody has arranged, which is every
		// graph written before this and every graph written by hand. Those want the
		// old behaviour exactly.
		if (x.isEmpty()) {
			layout();
			return;
		}
		layoutTheRest();
	}

	/**
	 * Lays out only the nodes that have no place of their own.
	 *
	 * Under the arrangement rather than into it: guessing where in somebody's shape a
	 * new node belongs is guessing, and a box that lands on top of another is worse
	 * than one that lands in a row underneath where it can be seen and dragged.
	 */
	private void layoutTheRest() {
		int below = 44;
		for (var entry : y.entrySet()) {
			Node placed = node(entry.getKey());
			if (placed == null) continue;
			below = Math.max(below, entry.getValue() + boxHeight(placed) + ROW_GAP);
		}
		int row = 0;
		for (Node node : state.nodes()) {
			if (x.containsKey(node.id())) continue;
			x.put(node.id(), 20 + row % 6 * (BOX_WIDTH + COLUMN_GAP));
			y.put(node.id(), below + row / 6 * (boxHeight(node) + ROW_GAP));
			row++;
		}
	}

	/** Hands the arrangement to the document, which is what saves it. */
	private void keepPlaces() {
		Map<String, com.mopicmp.npcstudio.dialogue.Pin> now = new java.util.LinkedHashMap<>();
		for (Node node : state.nodes()) {
			Integer px = x.get(node.id());
			Integer py = y.get(node.id());
			if (px == null || py == null) continue;
			now.put(node.id(), new com.mopicmp.npcstudio.dialogue.Pin(px, py));
		}
		state.places(now);
	}

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

	/** How wide a node's box is: the standard one, or whatever a card was made. */
	static int boxWidth(Node node) {
		return node instanceof Node.Comment comment ? comment.wide() : BOX_WIDTH;
	}

	private static int boxHeight(Node node) {
		// A card is its own height: rows of text and the air around them. Asked here
		// rather than only where it is drawn, because this is what the hit test and the
		// dragging use — a card you cannot grab where you can see it is worse than one
		// drawn wrong.
		if (node instanceof Node.Comment comment) {
			return comment.rows() * 9 + PAD * 2;
		}
		return TITLE_HEIGHT + Math.max(1, node.exits().size()) * PORT_ROW + 4;
	}

	static int colourOf(Node node) {
		return switch (node) {
			// Warm and unlike anything else on the canvas. Every other colour here names
			// a kind of step; this one has to say "not a step" at a glance, from across a
			// graph, without being read.
			case Node.Comment _ -> 0xFFB08A5A;
			case Node.Line _ -> 0xFF4FC3F7;
			case Node.Choice _ -> 0xFFBA68C8;
			case Node.Set _ -> 0xFF66BB6A;
			// The same green as a write, darker. Forgetting is writing — it puts a
			// declared default back where a value was — and a colour of its own would
			// claim it is a different kind of thing than it is.
			case Node.Forget _ -> 0xFF3E8E45;
			case Node.Branch _ -> 0xFFFFCA28;
			// A fork the graph decides for itself, in the same yellow as the fork it is
			// asked about: both are one node in and several out, and a reader looking for
			// "where does this split" wants to find them by the same colour.
			case Node.Chance _ -> 0xFFFFCA28;
			case Node.Act _ -> 0xFFFF8A65;
			case Node.End _ -> 0xFF78909C;
			// The three that wait on something other than a person. Grey-blue, near
			// enough to `end` to read as "nothing is happening here".
			case Node.Every _, Node.Until _, Node.Pressed _ -> 0xFF90A4AE;
			// A route. Green like the map's own markers rather than grey like the
			// other waits, because this is the one node whose content is somewhere in
			// the world — a box the same colour as the posts standing on the ground.
			case Node.Walk _ -> 0xFF9CCC65;
			// Calls into the brain, in the brain's own colour, so a scenario reads
			// at a glance as mostly-its-own with a few borrowings.
			case Node.Do _, Node.Stop _ -> 0xFF9575CD;
		};
	}

	static String titleOf(Node node) {
		return Component.translatable("npc_studio.title." + kindOf(node)).getString();
	}

	/**
	 * What sort of node this is, as the one word the rest of the editor keys off.
	 *
	 * Split out from {@link #titleOf} on the day the boxes were translated. The word
	 * on the box is now whatever language somebody is running; the word this returns
	 * is the one the colours, the icons and the dictionary all look up by, and those
	 * must not move when a language does.
	 */
	static String kindOf(Node node) {
		return switch (node) {
			case Node.Comment _ -> "comment";
			case Node.Line _ -> "line";
			case Node.Choice _ -> "choice";
			case Node.Set _ -> "set";
			case Node.Forget _ -> "forget";
			case Node.Branch _ -> "branch";
			case Node.Chance _ -> "chance";
			case Node.Act _ -> "act";
			case Node.End _ -> "end";
			case Node.Every _ -> "every";
			case Node.Until _ -> "until";
			case Node.Pressed _ -> "pressed";
			case Node.Do _ -> "do";
			case Node.Stop _ -> "stop";
			case Node.Walk _ -> "walk";
		};
	}

	/**
	 * What each exit row says: the answer's own words, or the line it will speak.
	 *
	 * <h2>Why these are words and not strings</h2>
	 *
	 * Because a line carries how it is drawn, and this is the place a writer looks at
	 * while writing. Reported plainly: colouring a line in the text window changed
	 * nothing anywhere in the editor, so the only way to see the colour was to go and
	 * play the conversation. It was drawn through {@code plain()}, which is exactly
	 * the call that throws the decoration away.
	 *
	 * Everything else on a row is a fact about the node rather than something
	 * somebody wrote — a count of points, the name of a skill — and those are plain
	 * because they never had a look to lose.
	 */
	private static List<com.mopicmp.npcstudio.dialogue.text.Words> rowsOf(Node node) {
		return switch (node) {
			// Nothing. A comment has no ports — the rows are what the wires come out of —
			// and its writing is drawn by the card itself, at the size somebody chose.
			case Node.Comment _ -> List.of();
			// Shortened by the words rather than by how they are written down: the box on
			// the canvas shows what is said, and a colour spelled out in the middle of it
			// would eat the fifteen characters there is room for.
			case Node.Choice choice ->
				choice.options().stream().map(o -> shorten(o.label(), 15)).toList();
			case Node.Line line -> List.of(shorten(line.text(), 15));
			case Node.End _ -> List.of();
			// A box saying "next" told the reader nothing they could not already see
			// from the wire coming out of it. What it does is the useful part.
			case Node.Act act -> List.of(said(NodePanel.effectName(act.effect())));
			case Node.Set set -> List.of(said(set.variable()
				+ (set.how() == Node.Set.Change.ADD ? " +=" : " =")));
			// What it puts back, in the words the node itself uses. "everything" is the
			// ordinary case and the one worth reading from across a graph: it is the
			// node that makes a scene playable twice.
			case Node.Forget forget -> List.of(said(forget.everything()
				? "forget all" : "forget " + forget.naming().size()));
			case Node.Branch branch -> {
				// One row per exit, or the ports and the rows stop lining up and a
				// wire appears to leave from the wrong place.
				var rows = new java.util.ArrayList<com.mopicmp.npcstudio.dialogue.text.Words>();
				for (int i = 0; i < branch.arms().size(); i++) rows.add(said("if " + (i + 1)));
				rows.add(said("otherwise"));
				yield List.copyOf(rows);
			}
			// One row per way out, numbered. The number is the useful part: it is what
			// tells apart four wires leaving one box, and there is nothing else to say
			// about a way that is chosen by a throw.
			case Node.Chance chance -> {
				var rows = new java.util.ArrayList<com.mopicmp.npcstudio.dialogue.text.Words>();
				for (int i = 0; i < chance.ways().size(); i++) rows.add(said("· " + (i + 1)));
				yield List.copyOf(rows);
			}
			// What it is standing there for, which is the whole content of both.
			case Node.Every every -> List.of(said(every.ticks() + " ticks"));
			case Node.Until _ -> List.of(said("until"));
			// One row per thing waited on, named after it, because that is what tells six
			// wires leaving one box apart — and the name is what the author has to check
			// against the `show` that put the thing there.
			case Node.Pressed waiting ->
				waiting.presses().stream().map(press -> said(press.shown())).toList();
			case Node.Do call -> List.of(said(call.segment()));
			case Node.Stop stop -> List.of(said("stop " + stop.segment()));
			// How many points, because that is the one thing about a route that can be
			// read from a box on a canvas. Where they are is a fact about the world and
			// belongs in the world, which is where they are drawn.
			case Node.Walk walk -> List.of(said(walk.route().size() + " × ·"));
		};
	}

	/** A row that never carried a look, cut to the room a box has. */
	private static com.mopicmp.npcstudio.dialogue.text.Words said(String text) {
		return com.mopicmp.npcstudio.dialogue.text.Words.of(shorten(text, 15));
	}

	private static String shorten(String text, int limit) {
		return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
	}

	/**
	 * The same cut, made without losing what the writer put on the words.
	 *
	 * Counted in code points rather than in chars, because {@code first} counts them
	 * that way and a box is measured in letters — an emoji is one letter of the
	 * fifteen there is room for, not two halves of one.
	 */
	private static com.mopicmp.npcstudio.dialogue.text.Words shorten(
			com.mopicmp.npcstudio.dialogue.text.Words words, int limit) {
		if (words.length() <= limit) return words;
		var runs = new java.util.ArrayList<>(words.first(limit - 1).runs());
		// The ellipsis is the editor speaking, not the author, so it is plain and
		// deliberately not given the look of whatever it happens to follow.
		runs.add(new com.mopicmp.npcstudio.dialogue.text.Words.Run("…",
			com.mopicmp.npcstudio.dialogue.text.Look.PLAIN));
		return new com.mopicmp.npcstudio.dialogue.text.Words(runs);
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
			// Made pointing at the first skill there is rather than at nothing. A new
			// node whose skill is blank is one more thing that looks finished and does
			// nothing, and the panel's picker cannot show what an empty name means.
			case "do" -> new Node.Do(id, firstSkill(), com.mopicmp.npcstudio.dialogue.Mark.TARGET,
				java.util.Map.of(), id);
			case "stop" -> new Node.Stop(id, firstSkill(), id);
			// Half a second, which is a pause rather than a stop — a fresh timer of
			// nought ticks is a graph that spins, and the guard that catches it would
			// be the author's first experience of the node.
			// Made with no points at all, and that is the one node it is right to make
			// empty. A route's content is places in the world, and there is no place a
			// fresh one could be put that would be less wrong than none — a guessed
			// point under the author's feet is a point they have to find and remove.
			// The panel says so out loud rather than looking finished and going nowhere.
			case "walk" -> new Node.Walk(id,
				com.mopicmp.npcstudio.dialogue.Route.NOWHERE, id);
			// Named after the node, so it already has the one field nothing else works
			// without — and so that a second one is a second name rather than a silent
			// overwrite of the first. Two steps in front of the anchor and one up, which
			// is roughly eye level for somebody standing at it: a hologram at the anchor's
			// feet is one nobody sees until they look down.
			case "show" -> new Node.Act(id,
				new com.mopicmp.npcstudio.dialogue.Effect.Show(id,
					com.mopicmp.npcstudio.dialogue.Shown.of(
						Component.translatable("npc_studio.shown.fresh").getString()),
					new com.mopicmp.npcstudio.dialogue.Route.Point.Go(2, 1, 0)), id);
			case "unshow" -> new Node.Act(id,
				new com.mopicmp.npcstudio.dialogue.Effect.Unshow(""), id);
			// With one row already, pointing back at itself like every other fresh node.
			// A wait with no rows is one nothing can ever move, which the validator
			// refuses — and a node that is born refused is a poor first impression.
			case "pressed" -> new Node.Pressed(id,
				List.of(new com.mopicmp.npcstudio.dialogue.Node.Press("", id)));
			case "every" -> new Node.Every(id, 10, id);
			// Waiting on nothing is waiting for ever. It starts on the first reading
			// there is, which is at least a question somebody can then change, rather
			// than a node that is finished-looking and never moves.
			case "until" -> new Node.Until(id,
				new com.mopicmp.npcstudio.dialogue.Condition.Compare(
					com.mopicmp.npcstudio.dialogue.Sense.KNOWN.get(0),
					com.mopicmp.npcstudio.dialogue.Scope.SENSE,
					com.mopicmp.npcstudio.dialogue.Condition.Op.EQ,
					com.mopicmp.npcstudio.dialogue.Value.of(true)), id);
			case "branch" -> new Node.Branch(id,
				List.of(new Node.Arm(new com.mopicmp.npcstudio.dialogue.Condition.Always(), id)),
				id);
			// Two ways rather than one, because one way is not a fork and a node that has
			// to be added to before it means anything reads as broken on the canvas. Both
			// point back at it, the same as every other fresh node here.
			case "comment" -> new Node.Comment(id);
			case "chance" -> new Node.Chance(id, List.of(id, id));
			case "set" -> new Node.Set(id, "", com.mopicmp.npcstudio.dialogue.Scope.CHARACTER,
				com.mopicmp.npcstudio.dialogue.Value.of(true), id);
			// Everything this document declares, and where it has been — which is what
			// "play it again" means, and what a fresh one should already be doing.
			case "forget" -> new Node.Forget(id, id);
			default -> new Node.End(id);
		});
		// Dropped where the person is looking rather than at the end of a column,
		// so a new box does not have to be gone and found.
		x.put(id, -panX + (width - panelWidth()) / 2 - BOX_WIDTH / 2);
		y.put(id, -panY + height / 2);
		select(state.nodes().size() - 1);
	}

	/** Something for a new call to point at, or nothing if there is nothing. */
	private String firstSkill() {
		var all = DialogueNames.skillsFor(state.id(), state.segments().keySet());
		return all.isEmpty() ? "" : all.get(0);
	}

	private void select(int index) {
		panel = index < 0 ? null : new NodePanel(this, state, index);
		rebuild();
	}

	/** How wide the pencil beside the name is, and where it sits. */
	private static final int PENCIL_WIDE = 16;

	/**
	 * Where the pencil sits, which is also where everything after the name starts.
	 *
	 * One place, because there are two things that have to agree about it — the
	 * button and the note saying whether the work is saved — and when they were
	 * worked out separately they overlapped.
	 */
	private int pencilX() {
		return 8 + font.width(state.id()) + 6;
	}

	/** Turns the name into a field, with what it is called already in it. */
	private void startNaming() {
		naming = new FlatField(8, 4, 160, 16, Component.translatable("npc_studio.graph.rename"),
			0xFFFFCA28, value -> { });
		naming.setMaxLength(Dialogue.LONGEST_NAME);
		naming.setValue(state.id());
		rebuild();
		naming.setFocused(true);
		setFocused(naming);
	}

	/**
	 * Asks the server for the new name, and waits to be told.
	 *
	 * Nothing is changed here. The document lives on the server under its own name,
	 * and only the server can say whether the new one is free — so the editor asks,
	 * and the renamed document arrives back the ordinary way and rebuilds this
	 * screen. Writing the new name in locally first would show a rename that has not
	 * happened, on a document every character in the world is still calling something
	 * else.
	 */
	private void finishNaming() {
		if (naming == null) return;
		String wanted = naming.getValue().trim();
		naming = null;
		rebuild();
		if (wanted.isEmpty() || wanted.equals(state.id())) return;
		ClientPlayNetworking.send(new EditorPayloads.Rename(state.id(), wanted));
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
		java.util.function.Consumer<String> answer = picked -> {
			onPick.accept(picked);
			rebuild();
		};
		if (WorkspaceScreen.embedded()) {
			// About nobody in particular: a gesture on a node belongs to the document,
			// and the document is played by whichever character runs it. Minus one is
			// the honest answer, and it is what turns the "has the selection moved"
			// check off — there is nothing here for it to have moved away from.
			com.mopicmp.npcstudio.client.workspace.Workspace.askAnimation(
				new com.mopicmp.npcstudio.client.workspace.Workspace.Pick(-1, current, answer));
			return;
		}
		minecraft.setScreenAndShow(new AnimationPickerScreen(this, current,
			() -> AnimationPickerScreen.lookFor(state.id()), answer));
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

	/**
	 * The skills, over the canvas.
	 *
	 * <h2>Why a list and not a second editor</h2>
	 *
	 * Because a skill is not a separate document — it is a named way into this
	 * one. Two editors would mean two copies of the same nodes and a question
	 * about which is right; a list that jumps the canvas to a skill's first node
	 * has neither problem, and the wires between a scenario and the skill it calls
	 * stay visible because they were never taken apart.
	 */
	private void toggleBrain() {
		showingAdd = false;
		sheet.toggle(waysInPane());
	}

	/**
	 * The named ways into this document.
	 *
	 * <h2>Why this is no longer called what she knows how to do</h2>
	 *
	 * Because it is not only that any more. A named way in was invented for a
	 * character's skills — "how to fight", reached by another node — and the same thing
	 * is now what a box on the map starts when somebody walks into it. One mechanism,
	 * two uses, and a name describing one of them leaves the other with nowhere anybody
	 * would look. Asked in exactly those words: what is this section and where is it.
	 *
	 * <h2>And why it stopped being a list drawn on the screen</h2>
	 *
	 * It was the last of the three. The other two became pages of the window when it
	 * turned out that a panel closing on any click cannot be worked alongside the graph
	 * it is about — and this one was worse, because the whole point of a row here is to
	 * take you to the node it names, and it shut itself on the way.
	 */
	private Sheet.Pane waysInPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.ways.title"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			for (var entry : state.segments().entrySet()) {
				String at = entry.getValue();
				rows.add(Sheet.Row.of(Component.literal(entry.getKey()),
					Component.literal(at),
					// Jumping rather than opening: the way in is right there in the same
					// graph, and taking somebody to it is more use than hiding the rest.
					() -> {
						int index = state.nodeIds().indexOf(at);
						if (index >= 0) select(index);
					}));
			}
			// The node in hand becomes a way in, named after itself. Renaming it is the
			// node panel's job and already works — a way in is stored by node id, and the
			// rename walks every reference.
			boolean picked = panel != null;
			rows.add(Sheet.Row.of(Component.translatable(picked
					? "npc_studio.graph.ways.make" : "npc_studio.graph.ways.pick"),
				null, !picked ? null : () -> {
					String at = state.nodes().get(panel.index()).id();
					state.segment(at, at);
					sheet.go(waysInPane());
				}));
			return rows;
		});
	}

	/**
	 * The boxes drawn on the map, over the canvas, in the same manner as the skills.
	 *
	 * <h2>Why a list here and not a field on a node</h2>
	 *
	 * Because a box is named by more than one node on purpose. The stretch that
	 * springs a trap is asked about in an {@code until}; the wall is raised in one
	 * {@code act} and dropped in another. Kept inside whichever node happened to
	 * draw it, the other two would be pointing at a field of a box on the far side
	 * of the canvas — and deleting that node would take the box with it.
	 */
	/*
	 * There were two flags here — one for the boxes, one for the showing — each with
	 * a toggle that had to remember to clear the other. They are one window now, and a
	 * window that knows which page it is on has no need of a flag per page. The two
	 * pages themselves are further down; the window is {@link Sheet}.
	 */

	private static final int BRAIN_ROW = 16;

	/**
	 * The kinds of node, in the order somebody meets them.
	 *
	 * Talking first, because most graphs are conversations; then the two ways of
	 * standing still, which is what turns a conversation into behaviour; then the
	 * call, which is what a brain is for.
	 */
	private static final java.util.List<String> KINDS = java.util.List.of(
		"line", "choice", "animation", "walk", "set", "forget", "branch", "chance",
		"every", "until", "do", "stop", "end", "comment");

	/**
	 * The three that are about a place rather than about a person, offered only there.
	 *
	 * <h2>Why these are not in the list above</h2>
	 *
	 * Because the four tabs exist to mean something. A document about a character is a
	 * conversation, and a conversation that hangs a menu in the air is a document filed
	 * under the wrong tab — the room it decorates outlives the talk. Kept out of the
	 * common list, the tab a thing belongs to is the tab it can be built on.
	 *
	 * A file that holds one anyway still runs it. This decides what is offered, not what
	 * is allowed: refusing to carry out a verb because of which tab its document sits
	 * under would be a graph that reads correctly and does nothing.
	 */
	private static final java.util.List<String> PLACE_KINDS =
		java.util.List.of("show", "unshow", "pressed");

	/**
	 * The four that only mean something when a character is carrying the document.
	 *
	 * A gesture, a route and the two that call into a brain. Offered on a place, a player
	 * or a thing they are boxes that cannot do anything: there is no body in the room, and
	 * the runtime says so in the log rather than on the canvas. Reported as exactly that —
	 * nodes that are not needed in those categories.
	 *
	 * The rest of the language is not split up, and should not be. A line, a question, a
	 * variable, a fork, a wait and an ending are the same in all four, which is the whole
	 * claim behind having one language and four tabs.
	 */
	private static final java.util.List<String> BODY_KINDS =
		java.util.List.of("animation", "walk", "do", "stop");

	/** What may be added to the document in hand. */
	private java.util.List<String> kinds() {
		if (state.kind() == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE) return KINDS;
		var all = new java.util.ArrayList<>(KINDS);
		all.removeAll(BODY_KINDS);
		if (state.kind() == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.LOCATION) {
			// Before `end` and `comment`, which are the two everything finishes with, so
			// the new ones sit among the verbs rather than after the full stop.
			all.addAll(all.indexOf("end"), PLACE_KINDS);
		}
		return java.util.List.copyOf(all);
	}

	private boolean showingAdd;

	private void toggleAdding() {
		showingAdd = !showingAdd;
	}

	/** The list of node kinds, over the canvas, in the same manner as the skills. */
	private void drawAdding(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!showingAdd) return;
		var kinds = kinds();
		int wide = 180;
		int left = 8;
		int top = height - 30 - kinds.size() * BRAIN_ROW - 8;

		graphics.fill(left, top, left + wide, top + kinds.size() * BRAIN_ROW + 6, 0xF01A1F26);
		graphics.fill(left, top, left + wide, top + 1, 0xFF2C333D);
		for (int i = 0; i < kinds.size(); i++) {
			int y = top + BRAIN_ROW * i + 4;
			boolean hovered = mouseY >= y - 2 && mouseY < y + BRAIN_ROW - 2
				&& mouseX >= left && mouseX < left + wide;
			if (hovered) graphics.fill(left, y - 2, left + wide, y + BRAIN_ROW - 2, 0xFF232A34);
			graphics.text(font, Component.translatable("npc_studio.graph.kind." + kinds.get(i)),
				left + 6, y + 2, 0xFFECEFF1);
		}
	}

	private boolean addingClicked(double mouseX, double mouseY) {
		if (!showingAdd) return false;
		var kinds = kinds();
		int wide = 180;
		int left = 8;
		int top = height - 30 - kinds.size() * BRAIN_ROW - 8;
		int row = (int) ((mouseY - top - 4) / BRAIN_ROW);
		showingAdd = false;
		if (mouseX >= left && mouseX < left + wide && row >= 0 && row < kinds.size()) {
			add(kinds.get(row));
		}
		return true;
	}

	/**
	 * The floating window the boxes and the showing are both pages of.
	 *
	 * One window rather than two, because they were two and the two disagreed with
	 * themselves about where their own rows were. See {@link Sheet}, which carries the
	 * whole of the difference between a list drawn on a screen and something anybody
	 * would call a window.
	 */
	private final Sheet sheet = new Sheet();

	private void toggleAreas() {
		showingAdd = false;
		sheet.toggle(areasPane());
	}

	private void toggleShowing() {
		showingAdd = false;
		sheet.toggle(showingPane());
	}

	private void toggleVariables() {
		showingAdd = false;
		sheet.toggle(variablesPane());
	}

	/**
	 * What this document remembers: the variables it is allowed to name.
	 *
	 * <h2>Why a declaration is required at all</h2>
	 *
	 * Because without one a mistyped name is a second variable that is always zero, and
	 * the graph takes the wrong branch for ever with nothing anywhere to notice. That is
	 * the failure with no symptom, and it is worth one line of ceremony.
	 *
	 * What was wrong was never the rule — it was that the rule had no door. The
	 * validator said "reads gold, which is not declared" and there was nowhere in the
	 * whole editor to declare anything.
	 *
	 * <h2>Why the missing ones are offered rather than made</h2>
	 *
	 * Because the type is a guess. It is a good one — what is written into a variable,
	 * or what it is compared against — but a name used two ways can only be guessed one
	 * way, and quietly picking would turn a disagreement the validator can name into a
	 * silence. Offered as a row, it is a click, and the click is somebody agreeing.
	 */
	private Sheet.Pane variablesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.vars.title"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var document = state.build();

			// The undeclared ones first, because they are the reason anybody opens this
			// page: they are what the red line at the top is about.
			var missing = com.mopicmp.npcstudio.dialogue.Variables.undeclared(document);
			for (var each : missing.entrySet()) {
				rows.add(Sheet.Row.of(
					Component.literal(each.getKey()).withColor(0xEF5350),
					Component.translatable("npc_studio.graph.vars.declare_as",
						Component.translatable("npc_studio.graph.vars.type_" + each.getValue())),
					() -> {
						state.variable(each.getKey(), each.getValue());
						refreshPanel();
						sheet.go(variablesPane());
					}));
			}

			var idle = com.mopicmp.npcstudio.dialogue.Variables.unused(document);
			for (var each : state.variables().entrySet()) {
				boolean scrap = idle.contains(each.getKey());
				rows.add(Sheet.Row.of(
					// Grey when nothing mentions it, which is the one thing worth knowing
					// before taking it away. The same treatment a box nobody names gets.
					Component.literal(each.getKey()).withColor(scrap ? 0x8A99A6 : 0xECEFF1),
					Component.translatable("npc_studio.graph.vars.type_" + each.getValue()),
					() -> sheet.go(variablePane(each.getKey()))));
			}

			if (rows.isEmpty()) {
				// A page with nothing on it reads as broken; one that says what is
				// missing reads as an instruction.
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.vars.none"),
					null, null));
			}
			return rows;
		});
	}

	/** One variable: what type it is, and taking it away. */
	private Sheet.Pane variablePane(String name) {
		return new Sheet.Pane(Component.literal(name), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			String now = state.variables().get(name);
			for (String type : java.util.List.of("flag", "number", "text")) {
				rows.add(Sheet.Row.choice(
					Component.translatable("npc_studio.graph.vars.type_" + type),
					type.equals(now), () -> {
						state.variable(name, type);
						refreshPanel();
						sheet.go(variablesPane());
					}));
			}
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.vars.drop"), null,
				() -> {
					// The nodes that name it are left alone. They stop being valid until
					// it is declared again, which is far better than silently rewriting
					// somebody's graph because they pressed a cross.
					state.variable(name, null);
					refreshPanel();
					sheet.go(variablesPane());
				}));
			return rows;
		});
	}

	/**
	 * The boxes drawn on the map.
	 *
	 * A row is a name and how big the box is. The name is grey when nothing points at
	 * it, which is the one thing worth knowing before taking it away: a box nobody
	 * names is scrap, and a box two nodes name is a trap that stops working — and the
	 * trap failing silently is the whole reason this is drawn rather than asked for.
	 */
	private Sheet.Pane areasPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.areas.title"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var used = com.mopicmp.npcstudio.dialogue.Dialogue.areasUsed(state.nodes());
			for (var entry : state.areas().entrySet()) {
				String name = entry.getKey();
				rows.add(new Sheet.Row(
					Component.literal(name).withColor(used.contains(name) ? 0xECEFF1 : 0x8A99A6),
					Component.literal(sizeOf(entry.getValue())), false,
					() -> sheet.go(areaPane(name))));
			}
			rows.add(Sheet.Row.of(Component.translatable(state.areas().isEmpty()
					? "npc_studio.graph.areas.none" : "npc_studio.graph.areas.new"),
				null, () -> {
					// Nothing is written until the second corner lands, so a name with
					// nothing under it never reaches the document.
					sheet.close();
					com.mopicmp.npcstudio.client.map.Routing.startBox(state, state.freshArea(), false);
				}));
			// Places that watch a block. On the same page as the boxes because they are
			// the same kind of thing — a standing rule that starts a way in with nobody
			// having clicked anybody — and the only difference is what sets it off.
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.watches"),
				Component.literal(String.valueOf(state.triggers().stream()
					.filter(one -> !one.place().isEmpty()).count())),
				() -> sheet.go(watchesPane())));
			// Carrying the whole lot to where the build went. Offered here rather than on
			// each box because it is one act on the document: a build is moved once, and
			// doing it a box at a time is how one gets left behind.
			if (!state.areas().isEmpty()) {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.shift"),
					null, () -> {
						sheet.close();
						com.mopicmp.npcstudio.client.map.Routing.startShift(state, false);
					}));
			}
			return rows;
		});
	}

	/** What can be done with one box, as a page of its own rather than as a row of marks. */
	private Sheet.Pane areaPane(String name) {
		return new Sheet.Pane(Component.literal(name), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var box = state.areas().get(name);
			// Where it is, before anything that can be done to it.
			//
			// It said only how big it was, and that is the half that cannot go wrong. A
			// box drawn as steps from a character sits wherever she happens to be and
			// turns with her, and the size is the same either way — so the one thing that
			// actually breaks a trap was the one thing this page did not show, and a box
			// pointing at a hole under the world spawn looked exactly like a good one.
			rows.add(new Sheet.Row(Component.translatable("npc_studio.graph.areas.where"),
				whereIs(box), false, null));
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.draw"), null, () -> {
				sheet.close();
				com.mopicmp.npcstudio.client.map.Routing.startBox(state, name, false);
			}));
			rows.addAll(theRestOf(name, box));
			return rows;
		});
	}

	/**
	 * Where a box actually is, in whichever way it is written.
	 *
	 * A box in coordinates says its low corner, which is a thing somebody can fly to
	 * and check. A box in steps says so instead of saying a number, because the
	 * number would be measured from a character who may not be there — and three
	 * confident coordinates that mean "wherever she stands" are worse than no
	 * coordinates at all.
	 */
	private Component whereIs(com.mopicmp.npcstudio.dialogue.Area box) {
		if (box == null) return Component.translatable("npc_studio.graph.areas.starts_nothing");
		if (box.needsAnchor()) {
			return Component.translatable("npc_studio.graph.areas.where_steps")
				.withColor(0xE0A34A);
		}
		int[][] corners = box.world(0, 0, 0, com.mopicmp.npcstudio.dialogue.Route.Facing.SOUTH);
		return Component.literal(corners[0][0] + " " + corners[0][1] + " " + corners[0][2]);
	}

	private java.util.List<Sheet.Row> theRestOf(String name, com.mopicmp.npcstudio.dialogue.Area box) {
		var rows = new java.util.ArrayList<Sheet.Row>();
		// The two ways out are the same pair a route and a home already offer, for
		// the same two jobs — walk the ground yourself to find out whether it can be
		// walked, or fly the scene camera over it to lay out something longer than
		// the view.
		rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.draw_scene"), null, () -> {
			sheet.close();
			com.mopicmp.npcstudio.client.map.Routing.startBox(state, name, true);
		}));
		// Only for a box that has the fault, and it is worth saying why it is offered
		// rather than done. Rewriting is exact — the box lands over the same blocks —
		// but only while the character is standing where she stood when it was drawn.
		// Doing that silently on open would quietly bake in whatever position she
		// happened to be in, so it is a button somebody presses while looking at her.
		if (box != null && box.needsAnchor()) {
			var anchor = com.mopicmp.npcstudio.client.map.Routing.anchorFor(state);
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.pin"),
				anchor == null ? Component.translatable("npc_studio.graph.areas.pin_nobody") : null,
				anchor == null ? null : () -> {
					pin(name, box, anchor);
					sheet.go(areaPane(name));
				}));
		}
		// What walking into it starts. On the box's own page rather than in a list of
		// its own, because a trigger is a fact about this box and there is nowhere
		// else anybody would look for it.
		var trigger = state.triggerAbout(name);
		rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.starts"),
			trigger == null
				? Component.translatable("npc_studio.graph.areas.starts_nothing")
				: Component.literal(trigger.wayIn()),
			() -> sheet.go(triggerPane(name))));
		// How often, and only for a box that starts something. On a box that starts
		// nothing the question has no subject, and a row answering it would be a
		// setting about nothing — which is how a page teaches somebody that half of it
		// does not matter.
		if (trigger != null) {
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.again"),
				againName(trigger.again()), () -> sheet.go(againPane(name))));
		}
		rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.areas.drop"), null, () -> {
			// Taken away without touching the nodes that name it. They stop meaning
			// anything until a box of that name exists again, and that is far better
			// than silently rewriting somebody's graph because they hit a cross.
			state.area(name, null);
			refreshPanel();
			sheet.go(areasPane());
		}));
		return rows;
	}

	/**
	 * Writes a box down in coordinates, over the ground it is standing on.
	 *
	 * The character is asked for her home and the quarter she faces rather than
	 * where she is standing this second, because that is the pair the steps were
	 * measured against — reading her live position would move the box by however far
	 * she has wandered since.
	 */
	private void pin(String name, com.mopicmp.npcstudio.dialogue.Area box,
			com.mopicmp.npcstudio.entity.NpcEntity anchor) {
		var home = anchor.home();
		state.area(name, box.allWrittenFrom(
			com.mopicmp.npcstudio.dialogue.Route.From.WORLD,
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			anchor.homeFacing()));
		refreshPanel();
	}

	/**
	 * Which of this document's ways in a box starts, if any.
	 *
	 * Only the named ones. A trigger pointing at {@code start} would be a place that
	 * begins the conversation a character has when you click her, which is two
	 * different scenes wearing one name — and the way to have both is to give the
	 * second one a name of its own, which is what a segment is.
	 */
	/**
	 * Places that watch a block, and the places that could.
	 *
	 * <h2>Why this is a list of places and not a list of watches</h2>
	 *
	 * Because a watch has no name of its own — it is filed under the place it watches,
	 * the same way a box trigger is filed under the box. Listing the places and saying
	 * beside each what it is watching for makes "nothing yet" a row somebody can press
	 * rather than an empty page with a button on it.
	 */
	private Sheet.Pane watchesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.watches"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			for (var spot : com.mopicmp.npcstudio.client.map.Spots.all()) {
				var watch = state.triggerAbout(spot.name());
				boolean on = watch != null && !watch.place().isEmpty();
				rows.add(new Sheet.Row(
					Component.literal(spot.name()).withColor(on ? 0xECEFF1 : 0x8A99A6),
					!on ? Component.translatable("npc_studio.graph.watches.nothing")
						: Component.literal((watch.onUse() ? "↳ " : "") + watch.block()),
					false, () -> sheet.go(watchPane(spot.name()))));
			}
			if (rows.isEmpty()) {
				// Said rather than left blank, the same as the ways in below: a page with
				// nothing on it reads as broken, and a page that says what is missing
				// reads as an instruction.
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.watches.no_places"),
					null, null));
			}
			// And the rules about a kind of block rather than about a spot. On this page
			// because they are the same sort of thing, in their own section because they
			// are the ones that answer everywhere — a difference worth seeing before
			// pressing anything, not after somebody builds a second campfire.
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.kinds"),
				Component.literal(String.valueOf(state.triggers().stream()
					.filter(one -> one.cause()
						instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny)
					.count())),
				() -> sheet.go(kindsPane())));
			return rows;
		});
	}

	/**
	 * Rules about a kind of block, anywhere on the map.
	 *
	 * <h2>Why these are apart from the ones about a place</h2>
	 *
	 * Because they differ in the way that matters most: one answers a particular fire,
	 * the other answers every fire there is or ever will be. Listed together they would
	 * look alike, and the difference would be found by whoever built the second one.
	 */
	private Sheet.Pane kindsPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.kinds"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			for (var trigger : state.triggers()) {
				// Blocks and items together on this page, because they are the same kind
				// of rule — about a kind of thing rather than about a spot — and telling
				// them apart is the author's business, not a reason for two pages.
				String about = switch (trigger.cause()) {
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny(String block) -> block;
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem(String item) -> item;
					default -> null;
				};
				if (about == null) continue;
				rows.add(Sheet.Row.of(Component.literal(about),
					Component.literal(trigger.wayIn()), () -> sheet.go(watchPane(about))));
			}
			if (state.segments().isEmpty()) {
				rows.add(Sheet.Row.of(
					Component.translatable("npc_studio.graph.areas.starts_none"), null, null));
				return rows;
			}
			// One of each, not one per way in. Listing every way in twice made four ways
			// in into eight rows of "new rule", which is a page that grows out of a
			// menu — and the way in is chosen on the page this lands on anyway.
			//
			// Started as a campfire and a flint and steel, because a working line to
			// edit teaches more than an empty field and a trip to a wiki.
			String first = state.segments().keySet().iterator().next();
			rows.add(Sheet.Row.of(
				Component.translatable("npc_studio.graph.kinds.new"), null, () -> {
					String fresh = state.freshKind();
					state.watch(fresh,
						new com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny(fresh), first);
					sheet.go(watchPane(fresh));
				}));
			rows.add(Sheet.Row.of(
				Component.translatable("npc_studio.graph.kinds.new_item"), null, () -> {
					String fresh = "minecraft:flint_and_steel";
					state.watch(fresh,
						new com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem(fresh), first);
					sheet.go(watchPane(fresh));
				}));
			return rows;
		});
	}

	/** One place, or one kind of block: what it answers, and what that starts. */
	private Sheet.Pane watchPane(String place) {
		return new Sheet.Pane(Component.literal(place), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var watch = state.triggerAbout(place);
			// A rule about a kind is filed under the block itself, so it has no place to
			// name — and nothing on this page may offer it one.
			boolean kind = watch != null && (watch.cause()
				instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny
				|| watch.cause() instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem);
			boolean item = watch != null && watch.cause() instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem;
			boolean on = watch != null;

			rows.add(Sheet.Row.choice(
				Component.translatable("npc_studio.graph.watches.nothing"), !on, () -> {
					state.watch(place, null, null);
					sheet.go(watchesPane());
				}));
			for (String segment : state.segments().keySet()) {
				rows.add(Sheet.Row.choice(Component.literal(segment),
					on && segment.equals(watch.wayIn()), () -> {
						// Kept as whatever it already was, or started as waiting for a
						// campfire — a working example to edit rather than an empty
						// field and a wiki page.
						state.watch(place, on ? watch.cause()
							: new com.mopicmp.npcstudio.dialogue.Trigger.Cause.Became(place, "minecraft:campfire[lit=true]"), segment);
						sheet.go(watchPane(place));
					}));
			}
			if (state.segments().isEmpty()) {
				rows.add(Sheet.Row.of(
					Component.translatable("npc_studio.graph.areas.starts_none"), null, null));
			}
			if (on) {
				// Watched or answered. Two different things filed the same way: one waits
				// for the block to become something, the other waits for somebody to
				// click it — and the second is what lets a graph give the player a rule
				// the game has not got, like putting a fire out by hand.
				// A rule about a kind always answers; there is nothing for it to wait
				// for, and offering the choice would offer a state it cannot be in.
				if (!kind) {
					rows.add(Sheet.Row.of(Component.translatable(watch.onUse()
							? "npc_studio.graph.watches.on_use"
							: "npc_studio.graph.watches.on_change"),
						null, () -> {
							state.watch(place, watch.onUse()
									? new com.mopicmp.npcstudio.dialogue.Trigger.Cause.Became(place, watch.block())
									: new com.mopicmp.npcstudio.dialogue.Trigger.Cause.Used(place, watch.block()),
								watch.wayIn());
							sheet.go(watchPane(place));
						}));
				}
				// What to watch for, typed. There are a few thousand blocks and rather
				// more states, so a list would either be that long or would be a
				// shortlist somebody's block is not on — and the game already writes this
				// string on the debug screen, which is a better place to read it from
				// than our menu.
				rows.add(Sheet.Row.of(Component.translatable(item
						? "npc_studio.graph.kinds.item"
						: watch.onUse()
							? "npc_studio.graph.watches.only_when"
							: "npc_studio.graph.watches.block"),
					Component.literal(watch.block()), () -> write(
						Component.translatable("npc_studio.graph.watches.block").getString(),
						com.mopicmp.npcstudio.dialogue.text.Words.of(watch.block()), words -> {
							String block = words.plain().trim();
							// A rule about a kind is filed under the block, so renaming
							// the block renames the rule: the old entry goes and the new
							// one takes its place.
							if (kind) {
								state.watch(place, null, null);
								state.watch(block, item
									? new com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem(block)
									: new com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny(block), watch.wayIn());
								sheet.go(watchPane(block));
								return;
							}
							state.watch(place, watch.onUse()
									? new com.mopicmp.npcstudio.dialogue.Trigger.Cause.Used(place, block)
									: new com.mopicmp.npcstudio.dialogue.Trigger.Cause.Became(place, block),
								watch.wayIn());
							sheet.go(watchPane(place));
						})));
				// And whether the game still does its own thing. Only a rule answering a
				// click can be in the way of anything, so only a rule is asked.
				if (watch.onUse()) {
					rows.add(Sheet.Row.of(Component.translatable(watch.instead()
							? "npc_studio.graph.watches.instead" : "npc_studio.graph.watches.beside"),
						null, () -> {
							state.watchInstead(place, !watch.instead());
							sheet.go(watchPane(place));
						}));
				}
			}
			return rows;
		});
	}

	private Sheet.Pane triggerPane(String area) {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.areas.starts"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var bound = state.triggerAbout(area);
			String now = bound == null ? null : bound.wayIn();
			rows.add(Sheet.Row.choice(
				Component.translatable("npc_studio.graph.areas.starts_nothing"), now == null,
				() -> {
					state.trigger(area, null);
					sheet.go(areaPane(area));
				}));
			for (String segment : state.segments().keySet()) {
				rows.add(Sheet.Row.choice(Component.literal(segment), segment.equals(now), () -> {
					state.trigger(area, segment);
					// And the box is written down where it is, if it was not already.
					//
					// Here rather than left to the author, because binding a box to a way in
					// is the exact moment its frame stops being a preference and becomes
					// wrong: a place has no character, so steps measured from one land at the
					// origin. Doing it silently is right in this one case — the alternative is
					// an author who bound a trigger, walked into the box and got nothing, with
					// no error anywhere to read.
					var box = state.areas().get(area);
					var anchor = com.mopicmp.npcstudio.client.map.Routing.anchorFor(state);
					if (box != null && box.needsAnchor() && anchor != null) pin(area, box, anchor);
					sheet.go(areaPane(area));
				}));
			}
			if (state.segments().isEmpty()) {
				// Said rather than left blank. A page with one row on it reads as broken;
				// a page that says what is missing reads as an instruction.
				rows.add(Sheet.Row.of(
					Component.translatable("npc_studio.graph.areas.starts_none"), null, null));
			}
			return rows;
		});
	}

	/**
	 * How often a box may start its scene.
	 *
	 * <h2>Why the second answer is worth having at all</h2>
	 *
	 * Because the two are different scenes, not one scene with a knob on it. A doorway
	 * that greets you belongs on the first; the moment a room is entered for the first
	 * time belongs on the second, and left on the first it plays again the instant the
	 * scene ends and the player is still standing in the box.
	 *
	 * Each says what it costs underneath it, because "once" is the word people expect
	 * to mean "once for ever" and it does not mean that here — it is counted for as
	 * long as the player is on the server. Saying so on the row is the difference
	 * between a setting and a surprise a week later.
	 */
	private Sheet.Pane againPane(String area) {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.areas.again"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var trigger = state.triggerAbout(area);
			var now = trigger == null
				? com.mopicmp.npcstudio.dialogue.Trigger.Again.EVERY_TIME : trigger.again();
			for (var each : com.mopicmp.npcstudio.dialogue.Trigger.Again.values()) {
				rows.add(Sheet.Row.choice(againName(each), each == now, () -> {
					state.triggerAgain(area, each);
					refreshPanel();
					sheet.go(areaPane(area));
				}));
			}
			return rows;
		});
	}

	private static Component againName(com.mopicmp.npcstudio.dialogue.Trigger.Again again) {
		return Component.translatable("npc_studio.graph.areas.again_" + again.name().toLowerCase());
	}

	/**
	 * How big a box is, as three numbers.
	 *
	 * Worked out against an anchor of nothing, and that is not a shortcut: both
	 * corners are written in the same frame, so moving the anchor moves both and the
	 * distance between them does not change. A box written as steps has a size in the
	 * editor even though it has no place until a character stands somewhere.
	 */
	private static String sizeOf(com.mopicmp.npcstudio.dialogue.Area box) {
		int[][] corners = box.world(0, 0, 0, com.mopicmp.npcstudio.dialogue.Route.Facing.SOUTH);
		return (corners[1][0] - corners[0][0] + 1) + "×"
			+ (corners[1][1] - corners[0][1] + 1) + "×"
			+ (corners[1][2] - corners[0][2] + 1);
	}

	/**
	 * Everybody who speaks in this document, and what colour their name is.
	 *
	 * <h2>Why the list is found rather than kept</h2>
	 *
	 * Because a speaker is not a thing anybody creates. It is a name typed into a
	 * line, and the set of them is whatever the lines currently say — so a list held
	 * beside the document would be a second copy that goes stale the moment somebody
	 * renames a character on one line and not on another. Read off the nodes, it
	 * cannot disagree with them.
	 *
	 * A colour is remembered under the name even after nobody says it any more. That
	 * is deliberate: renaming a character back should not lose the colour they had,
	 * and a handful of unused entries in a map costs nothing next to that.
	 */
	private Sheet.Pane voicesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.voices.title"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var named = new java.util.LinkedHashSet<String>();
			for (Node node : state.nodes()) {
				String who = switch (node) {
					case Node.Line line -> line.speaker();
					case Node.Choice choice -> choice.speaker();
					default -> "";
				};
				if (!who.isEmpty()) named.add(who);
			}
			for (String who : named) {
				String colour = state.voices().getOrDefault(who, "");
				rows.add(new Sheet.Row(
					Component.literal(who).withColor(
						com.mopicmp.npcstudio.dialogue.text.Tint.of(colour) & 0xFFFFFF),
					colour.isEmpty()
						? Component.translatable("npc_studio.graph.voices.plain")
						: Component.translatable("npc_studio.colour." + colour),
					false, () -> sheet.go(voicePane(who))));
			}
			if (named.isEmpty()) {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.voices.none"),
					null, null));
			}
			return rows;
		});
	}

	/** The colours one name may be drawn in, from the one list the drawing reads. */
	private Sheet.Pane voicePane(String who) {
		return new Sheet.Pane(Component.literal(who), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			String now = state.voices().getOrDefault(who, "");
			rows.add(Sheet.Row.choice(Component.translatable("npc_studio.graph.voices.plain"),
				now.isEmpty(), () -> {
					state.voice(who, "");
					sheet.go(voicesPane());
				}));
			for (String colour : com.mopicmp.npcstudio.dialogue.text.Tint.NAMES) {
				rows.add(Sheet.Row.choice(
					Component.translatable("npc_studio.colour." + colour).withColor(
						com.mopicmp.npcstudio.dialogue.text.Tint.of(colour) & 0xFFFFFF),
					colour.equals(now), () -> {
						state.voice(who, colour);
						sheet.go(voicesPane());
					}));
			}
			return rows;
		});
	}

	/**
	 * How this document asks to be shown, as a list of decisions with their answers.
	 *
	 * Not one of these is a number somebody types. They are choices — this wide or
	 * that wide, this many rows, never or after ten seconds — and a choice offered as
	 * an empty box is a box you have to already know the answer to fill. Every row
	 * opens a page of the answers there are, with the one in force marked.
	 */
	private static String kindKey(com.mopicmp.npcstudio.dialogue.Dialogue.Kind kind) {
		return switch (kind) {
			case SCENE -> "npc_studio.graph.document.scene";
			case PLAYER -> "npc_studio.graph.document.player";
			case THING -> "npc_studio.graph.document.thing";
			case LOCATION -> "npc_studio.graph.document.location";
		};
	}

	/**
	 * What a place says about its own ground.
	 *
	 * <h2>Why this page exists before the ground does</h2>
	 *
	 * Because the kind and the settings are refused when they disagree, and a refusal
	 * holds the save back. Without somewhere to write these, choosing "a place" would
	 * hand somebody a document they cannot save and no page that says why.
	 *
	 * Laying the ground out is not built yet, so the region named here is a name and
	 * nothing else today. That is on purpose: a name in a file format is the expensive
	 * thing to change later, and the rest of it is not.
	 */
	private Sheet.Pane locationPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.document.location"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var place = state.location().orElse(null);
			if (place == null) return rows;

			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.location.region"),
				Component.literal(place.region().isEmpty() ? "—" : place.region()),
				() -> write(
					Component.translatable("npc_studio.graph.location.region").getString(),
					com.mopicmp.npcstudio.dialogue.text.Words.of(place.region()), words -> {
						String now = words.plain().trim();
						state.location(new com.mopicmp.npcstudio.dialogue.Location(now,
							place.resets(), place.mayEdit(), place.rules()));
						sheet.go(locationPane());
					})));
			// "Rebuilt every visit" rather than "resets", because what it does is put
			// the place back the way its author built it — and the one document where
			// this is off is the hub, which is the thing that survives.
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.location.resets"),
				Component.translatable(place.resets()
					? "npc_studio.graph.location.resets.every"
					: "npc_studio.graph.location.resets.never"),
				() -> {
					state.location(new com.mopicmp.npcstudio.dialogue.Location(place.region(),
						!place.resets(), place.mayEdit(), place.rules()));
					sheet.go(locationPane());
				}));
			return rows;
		});
	}

	/**
	 * Properties that are simply true while something holds.
	 *
	 * <h2>Why a table and not a graph</h2>
	 *
	 * Everything else in this editor is event-driven: something happens, a graph runs. A
	 * standing property is not something that happens; it is something that is, with no
	 * occasion and no order. Drawn as a graph it would want a branch, and a branch
	 * implies an order things are tried in — which would be pretending.
	 *
	 * The graph is still the larger half of any ability: spending, filling back up,
	 * forbidding it somewhere. This is only the switch, and the graph moves what the
	 * switch reads.
	 */
	/**
	 * What the player is shown of what they carry.
	 *
	 * A table like the standing properties, and on the same page, because a gauge is not
	 * an event either: it does not happen, it is on screen while something holds.
	 */
	private Sheet.Pane gaugesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.gauges"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			for (var gauge : state.gauges()) {
				rows.add(Sheet.Row.of(Component.literal(gauge.label()),
					Component.literal(gauge.variable()),
					() -> sheet.go(gaugeOnePane(gauge.label()))));
			}
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.new"),
				null, () -> {
					String fresh = state.freshGauge();
					state.gauge(fresh, com.mopicmp.npcstudio.dialogue.Gauge.bar(fresh, "", 100));
					sheet.go(gaugeOnePane(fresh));
				}));
			return rows;
		});
	}

	/** One gauge: what it reads, how it is drawn, where, and while what. */
	private Sheet.Pane gaugeOnePane(String label) {
		return new Sheet.Pane(Component.literal(label), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var gauge = state.gaugeNamed(label);
			if (gauge == null) return rows;

			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.label"),
				Component.literal(gauge.label()), () -> write(
					Component.translatable("npc_studio.graph.gauges.label").getString(),
					com.mopicmp.npcstudio.dialogue.text.Words.of(gauge.label()), words -> {
						String now = words.plain().trim();
						if (now.isEmpty() || now.equals(label)) return;
						state.gauge(label, null);
						state.gauge(now, new com.mopicmp.npcstudio.dialogue.Gauge(now, gauge.variable(), gauge.scope(),
							gauge.look(), gauge.most(), gauge.corner(), gauge.colour(),
							gauge.when()));
						sheet.go(gaugeOnePane(now));
					})));
			// Which variable, chosen from what this document declares rather than typed:
			// a name with a letter out of it shows nought for ever, which reads as a
			// stamina that never fills.
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.variable"),
				Component.literal(gauge.variable().isEmpty()
					? "\u2014" : gauge.variable()),
				() -> sheet.go(gaugeVariablePane(label))));
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.look"),
				Component.translatable(gauge.look() == com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR
					? "npc_studio.graph.gauges.bar" : "npc_studio.graph.gauges.number"),
				() -> {
					state.gauge(label, gauge.looking(gauge.look() == com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR
						? com.mopicmp.npcstudio.dialogue.Gauge.Look.NUMBER : com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR, gauge.most()));
					sheet.go(gaugeOnePane(label));
				}));
			// Only a bar has anything to be full of. Asked for only there, rather than
			// asked for always and ignored half the time.
			if (gauge.look() == com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR) {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.most"),
					Component.literal(String.valueOf(gauge.most())), () -> write(
						Component.translatable("npc_studio.graph.gauges.most").getString(),
						com.mopicmp.npcstudio.dialogue.text.Words.of(String.valueOf(gauge.most())),
						words -> {
							state.gauge(label, gauge.looking(com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR,
								number(words.plain(), gauge.most())));
							sheet.go(gaugeOnePane(label));
						})));
			}
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.corner"),
				Component.translatable(cornerKey(gauge.corner())),
				() -> sheet.go(gaugeCornerPane(label))));
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.drop"),
				null, () -> {
					state.gauge(label, null);
					sheet.go(gaugesPane());
				}));
			return rows;
		});
	}

	/** Which of this document's numbers the gauge shows. */
	private Sheet.Pane gaugeVariablePane(String label) {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.gauges.variable"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var gauge = state.gaugeNamed(label);
			if (gauge == null) return rows;
			for (var declared : state.variables().entrySet()) {
				if (!"number".equals(declared.getValue())) continue;
				String name = declared.getKey();
				rows.add(Sheet.Row.choice(Component.literal(name),
					name.equals(gauge.variable()), () -> {
						state.gauge(label, gauge.reading(name, gauge.scope()));
						sheet.go(gaugeOnePane(label));
					}));
			}
			if (rows.isEmpty()) {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.gauges.no_numbers"),
					null, null));
			}
			return rows;
		});
	}

	/** Which corner it sits in. */
	private Sheet.Pane gaugeCornerPane(String label) {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.gauges.corner"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var gauge = state.gaugeNamed(label);
			if (gauge == null) return rows;
			for (var corner : com.mopicmp.npcstudio.dialogue.Gauge.Corner.values()) {
				rows.add(Sheet.Row.choice(Component.translatable(cornerKey(corner)),
					corner == gauge.corner(), () -> {
						state.gauge(label, gauge.placed(corner, gauge.colour()));
						sheet.go(gaugeOnePane(label));
					}));
			}
			return rows;
		});
	}

	private static String cornerKey(com.mopicmp.npcstudio.dialogue.Gauge.Corner corner) {
		return switch (corner) {
			case TOP_LEFT -> "npc_studio.graph.gauges.top_left";
			case TOP_RIGHT -> "npc_studio.graph.gauges.top_right";
			case BOTTOM_LEFT -> "npc_studio.graph.gauges.bottom_left";
			case BOTTOM_RIGHT -> "npc_studio.graph.gauges.bottom_right";
		};
	}

	private Sheet.Pane standingPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.standing"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			for (var rule : state.standing()) {
				rows.add(Sheet.Row.of(Component.literal(rule.name()),
					Component.literal(shortName(rule.attribute())),
					() -> sheet.go(standingOnePane(rule.name()))));
			}
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.standing.new"),
				null, () -> {
					String fresh = state.freshStanding();
					// A jump, because it is the property everybody tries first and because
					// a sample that does nothing teaches that the feature does nothing.
					state.standing(fresh, com.mopicmp.npcstudio.dialogue.Standing.always(fresh, "minecraft:jump_strength",
						com.mopicmp.npcstudio.dialogue.Effect.Trait.How.TIMES_BASE, 0.5));
					sheet.go(standingOnePane(fresh));
				}));
			return rows;
		});
	}

	/** One standing property: what it gives, how much, and while what holds. */
	private Sheet.Pane standingOnePane(String name) {
		return new Sheet.Pane(Component.literal(name), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var rule = state.standingNamed(name);
			if (rule == null) return rows;

			rows.add(Sheet.Row.of(Component.translatable("npc_studio.node.trait_name"),
				Component.literal(rule.name()), () -> write(
					Component.translatable("npc_studio.node.trait_name").getString(),
					com.mopicmp.npcstudio.dialogue.text.Words.of(rule.name()), words -> {
						String now = words.plain().trim();
						if (now.isEmpty() || now.equals(name)) return;
						state.standing(name, null);
						state.standing(now, new com.mopicmp.npcstudio.dialogue.Standing(now, rule.when(), rule.attribute(),
							rule.how(), rule.amount()));
						sheet.go(standingOnePane(now));
					})));
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.node.trait_what"),
				Component.literal(shortName(rule.attribute())),
				() -> sheet.go(standingWhatPane(name))));
			// An ability is on or off — half a double jump is not a thing — so the
			// amount is not asked for. Shown as what it is instead, rather than as a
			// number that would be typed and ignored.
			if (com.mopicmp.npcstudio.dialogue.Knack.is(rule.attribute())) {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.standing.on_off"),
					null, null));
			} else {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.node.trait_amount"),
					Component.literal(rule.amount() + " " + howWord(rule.how())),
					() -> sheet.go(standingAmountPane(name))));
			}
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.standing.when"),
				Component.translatable(rule.when() instanceof com.mopicmp.npcstudio.dialogue.Condition.Always
					? "npc_studio.graph.standing.always"
					: "npc_studio.graph.standing.conditional"),
				() -> sheet.go(standingWhenPane(name))));
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.standing.drop"),
				null, () -> {
					state.standing(name, null);
					sheet.go(standingPane());
				}));
			return rows;
		});
	}

	/** Which of the game's own forty properties this one gives. */
	private Sheet.Pane standingWhatPane(String name) {
		return new Sheet.Pane(Component.translatable("npc_studio.node.trait_what"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var rule = state.standingNamed(name);
			if (rule == null) return rows;
			var names = new java.util.ArrayList<String>();
			for (var id : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.keySet()) {
				names.add(id.toString());
			}
			names.sort(String::compareTo);
			// This mod's own, first, because they are the few and because an author has
			// no business knowing which of these the game happens to keep and which we
			// wrote. "Jump twice" and "jump higher" are the same wish said two ways.
			names.addAll(0, com.mopicmp.npcstudio.dialogue.Knack.KNOWN);
			for (String each : names) {
				rows.add(Sheet.Row.choice(Component.literal(shortName(each)),
					each.equals(rule.attribute()), () -> {
						state.standing(name, rule.giving(each, rule.how(), rule.amount()));
						sheet.go(standingOnePane(name));
					}));
			}
			return rows;
		});
	}

	/** How much, and which of the game's three ways the number is read. */
	private Sheet.Pane standingAmountPane(String name) {
		return new Sheet.Pane(Component.translatable("npc_studio.node.trait_amount"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var rule = state.standingNamed(name);
			if (rule == null) return rows;
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.node.trait_amount"),
				Component.literal(String.valueOf(rule.amount())), () -> write(
					Component.translatable("npc_studio.node.trait_amount").getString(),
					com.mopicmp.npcstudio.dialogue.text.Words.of(String.valueOf(rule.amount())), words -> {
						state.standing(name, rule.giving(rule.attribute(), rule.how(),
							number(words.plain(), rule.amount())));
						sheet.go(standingAmountPane(name));
					})));
			for (var how : com.mopicmp.npcstudio.dialogue.Effect.Trait.How.values()) {
				rows.add(Sheet.Row.choice(Component.translatable(howKeyOf(how)),
					how == rule.how(), () -> {
						state.standing(name, rule.giving(rule.attribute(), how, rule.amount()));
						sheet.go(standingAmountPane(name));
					}));
			}
			return rows;
		});
	}

	/**
	 * While what: all the time, or while a flag of this document is set.
	 *
	 * <h2>Why only those two here</h2>
	 *
	 * Because the condition language goes deeper than this page can draw, and drawing an
	 * approximation and saving it would quietly rewrite what somebody wrote — the fault
	 * this project has met three times. A condition this page cannot show is left exactly
	 * as it was and said to be there; the file is where it can be changed.
	 */
	private Sheet.Pane standingWhenPane(String name) {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.standing.when"), () -> {
			var rows = new java.util.ArrayList<Sheet.Row>();
			var rule = state.standingNamed(name);
			if (rule == null) return rows;

			rows.add(Sheet.Row.choice(Component.translatable("npc_studio.graph.standing.always"),
				rule.when() instanceof com.mopicmp.npcstudio.dialogue.Condition.Always, () -> {
					state.standing(name, rule.whenever(new com.mopicmp.npcstudio.dialogue.Condition.Always()));
					sheet.go(standingOnePane(name));
				}));
			for (var declared : state.variables().entrySet()) {
				if (!"flag".equals(declared.getValue())) continue;
				String variable = declared.getKey();
				var wanted = new com.mopicmp.npcstudio.dialogue.Condition.Compare(variable,
					com.mopicmp.npcstudio.dialogue.Scope.PLAYER, com.mopicmp.npcstudio.dialogue.Condition.Op.EQ,
					com.mopicmp.npcstudio.dialogue.Value.of(true));
				rows.add(Sheet.Row.choice(
					Component.translatable("npc_studio.graph.standing.while", variable),
					wanted.equals(rule.when()), () -> {
						state.standing(name, rule.whenever(wanted));
						sheet.go(standingOnePane(name));
					}));
			}
			if (rows.size() == 1) {
				rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.standing.no_flags"),
					null, null));
			}
			return rows;
		});
	}

	private static String howKeyOf(com.mopicmp.npcstudio.dialogue.Effect.Trait.How how) {
		return switch (how) {
			case ADD -> "npc_studio.node.trait_add";
			case TIMES_BASE -> "npc_studio.node.trait_times_base";
			case TIMES_ALL -> "npc_studio.node.trait_times_all";
		};
	}

	private static String howWord(com.mopicmp.npcstudio.dialogue.Effect.Trait.How how) {
		return Component.translatable(howKeyOf(how)).getString();
	}

	/** The last part of a registry name, which is the part anybody reads. */
	private static String shortName(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	/** A number typed into a box, or what was there when it is not one. */
	private static double number(String typed, double was) {
		try {
			return Double.parseDouble(typed.trim().replace(',', '.'));
		} catch (NumberFormatException notANumber) {
			return was;
		}
	}

	private Sheet.Pane showingPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.graph.document"), () -> {
			var m = state.manner();
			// In the order somebody would ask them: what is this, what does it grant,
			// what does it show, and only then how it speaks. The first decides what the
			// rest of the editor will let you do, so it goes first.
			var rows = new java.util.ArrayList<Sheet.Row>();
			// What the document is, said and not asked. It is chosen by the tab it was
			// made in, and there is deliberately no way to change it here: this row and
			// the tab would be two answers to one question, and a document that changed
			// tabs because somebody touched a setting three pages in is a document that
			// has moved without appearing to.
			//
			// Kept rather than dropped because it is the one line on this page that says
			// where you are, and the page below it differs by sort.
			rows.add(Sheet.Row.of(Component.translatable("npc_studio.graph.document.is"),
				Component.translatable(kindKey(state.kind())), null));
			// And only then, and only for a place, its ground. Shown here rather than
			// always, because a conversation has no ground and a row that is grey on
			// every document but one is a row nobody reads.
			if (state.kind() == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.LOCATION) {
				rows.add(setting("npc_studio.graph.document.location",
					Component.literal(state.location()
						.map(place -> place.region().isEmpty() ? "—" : place.region())
						.orElse("—")),
					this::locationPane));
			}
			rows.addAll(java.util.List.of(
				// Beside the kind because it is the other thing here about what the
				// document does rather than about how it looks — and because a standing
				// property only means something once you know what the document is.
				setting("npc_studio.graph.standing",
					Component.literal(String.valueOf(state.standing().size())),
					this::standingPane),
				// And what the player sees of any of it. Beside the properties because a
				// variable nobody can see is a variable nobody knows about, and an
				// ability gated on one is an ability that works sometimes.
				setting("npc_studio.graph.gauges",
					Component.literal(String.valueOf(state.gauges().size())),
					this::gaugesPane),
				setting("npc_studio.showing.width",
					Component.translatable("npc_studio.showing.width_of", (int) (m.width() * 100)),
					this::sharesPane),
				setting("npc_studio.showing.lines",
					Component.translatable("npc_studio.showing.lines_of", m.lines()),
					this::countsPane),
				setting("npc_studio.showing.pace",
					m.pace() == com.mopicmp.npcstudio.dialogue.Manner.PLAYER_PACE
						? Component.translatable("npc_studio.showing.pace_player")
						: Component.translatable("npc_studio.showing.pace_of", (int) m.pace()),
					this::pacesPane),
				setting("npc_studio.showing.breathes",
					Component.translatable(m.breathes()
						? "npc_studio.showing.breathes_yes" : "npc_studio.showing.breathes_no"),
					() -> yesNoPane("npc_studio.showing.breathes", m.breathes(),
						on -> state.manner(state.manner().withBreathes(on)),
						"npc_studio.showing.breathes_yes", "npc_studio.showing.breathes_no")),
				setting("npc_studio.showing.holds",
					Component.translatable(m.holdsPlayer()
						? "npc_studio.showing.holds_yes" : "npc_studio.showing.holds_no"),
					() -> yesNoPane("npc_studio.showing.holds", m.holdsPlayer(),
						on -> state.manner(state.manner().withHoldsPlayer(on)),
						"npc_studio.showing.holds_yes", "npc_studio.showing.holds_no")),
				setting("npc_studio.showing.idle",
					m.staysUp()
						? Component.translatable("npc_studio.showing.never")
						: Component.translatable("npc_studio.showing.idle_of", m.idleTicks() / 20),
					this::idlesPane),
				setting("npc_studio.showing.range",
					m.survivesDistance()
						? Component.translatable("npc_studio.showing.never")
						: Component.translatable("npc_studio.showing.range_of", (int) m.range()),
					this::rangesPane),
				// Who speaks in this document and in what colour. On this page because
				// it is a decision about how the document is shown rather than about any
				// one line — the same kind of thing as how wide the bar is.
				setting("npc_studio.graph.voices",
					Component.literal(String.valueOf(state.voices().size())),
					this::voicesPane)));
			return rows;
		});
	}

	private Sheet.Row setting(String label, Component said,
			java.util.function.Supplier<Sheet.Pane> page) {
		return Sheet.Row.of(Component.translatable(label), said, () -> sheet.go(page.get()));
	}

	/**
	 * One answer on a page of them: pick it, and come straight back to the list.
	 *
	 * Back rather than staying, because picking is finished business — and the window
	 * returning to where it was is what makes the choice feel like it happened to the
	 * row rather than somewhere else.
	 */
	private Sheet.Row answer(Component said, boolean now, Runnable act) {
		return Sheet.Row.choice(said, now, () -> {
			act.run();
			refreshPanel();
			sheet.go(showingPane());
		});
	}

	private Sheet.Pane sharesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.showing.width"), () -> {
			var m = state.manner();
			var out = new java.util.ArrayList<Sheet.Row>();
			// Quarters and thirds rather than every percent, because the difference
			// between sixty and sixty-two is not one anybody can see, and offering it is
			// offering somebody a decision they cannot make.
			for (int per : new int[] { 30, 40, 50, 60, 75, 100 }) {
				float share = per / 100f;
				out.add(answer(Component.translatable("npc_studio.showing.width_of", per),
					Math.abs(m.width() - share) < 0.005f,
					() -> state.manner(state.manner().withWidth(share))));
			}
			return out;
		});
	}

	private Sheet.Pane countsPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.showing.lines"), () -> {
			var m = state.manner();
			var out = new java.util.ArrayList<Sheet.Row>();
			for (int rows = 1; rows <= com.mopicmp.npcstudio.dialogue.Manner.MOST_LINES; rows++) {
				int many = rows;
				out.add(answer(Component.translatable("npc_studio.showing.lines_of", many),
					m.lines() == many, () -> state.manner(state.manner().withLines(many))));
			}
			return out;
		});
	}

	private Sheet.Pane pacesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.showing.pace"), () -> {
			var m = state.manner();
			var out = new java.util.ArrayList<Sheet.Row>();
			// The player's own setting first, and it is the ordinary answer. A document
			// that overrules a reading speed should be doing it on purpose.
			out.add(answer(Component.translatable("npc_studio.showing.pace_player"),
				m.pace() == com.mopicmp.npcstudio.dialogue.Manner.PLAYER_PACE,
				() -> state.manner(state.manner()
					.withPace(com.mopicmp.npcstudio.dialogue.Manner.PLAYER_PACE))));
			for (int fast : new int[] { 12, 25, 45, 80, 150 }) {
				float pace = fast;
				out.add(answer(Component.translatable("npc_studio.showing.pace_of", fast),
					m.pace() == pace, () -> state.manner(state.manner().withPace(pace))));
			}
			return out;
		});
	}

	private Sheet.Pane yesNoPane(String title, boolean now,
			java.util.function.Consumer<Boolean> set, String yes, String no) {
		return new Sheet.Pane(Component.translatable(title), () -> java.util.List.of(
			answer(Component.translatable(yes), now, () -> set.accept(true)),
			answer(Component.translatable(no), !now, () -> set.accept(false))));
	}

	private Sheet.Pane idlesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.showing.idle"), () -> {
			var m = state.manner();
			var out = new java.util.ArrayList<Sheet.Row>();
			for (int seconds : new int[] { 3, 5, 10, 20, 40 }) {
				int ticks = seconds * 20;
				out.add(answer(Component.translatable("npc_studio.showing.idle_of", seconds),
					m.idleTicks() == ticks, () -> state.manner(state.manner().withIdleTicks(ticks))));
			}
			// Never, which is what a scene wants when the next thing to happen is the
			// player answering rather than the clock running out.
			out.add(answer(Component.translatable("npc_studio.showing.never"), m.staysUp(),
				() -> state.manner(state.manner()
					.withIdleTicks(com.mopicmp.npcstudio.dialogue.Manner.STAYS))));
			return out;
		});
	}

	private Sheet.Pane rangesPane() {
		return new Sheet.Pane(Component.translatable("npc_studio.showing.range"), () -> {
			var m = state.manner();
			var out = new java.util.ArrayList<Sheet.Row>();
			for (int blocks : new int[] { 6, 12, 24, 48 }) {
				double far = blocks;
				out.add(answer(Component.translatable("npc_studio.showing.range_of", blocks),
					Math.abs(m.range() - far) < 0.01,
					() -> state.manner(state.manner().withRange(far))));
			}
			out.add(answer(Component.translatable("npc_studio.showing.never"), m.survivesDistance(),
				() -> state.manner(state.manner().withRange(0))));
			return out;
		});
	}


	// ------------------------------------------------------- keeping it saved

	/**
	 * How long the graph has to stand still before it is kept.
	 *
	 * A second. Short enough that walking away from the keyboard has already saved
	 * by the time you have stood up, long enough that typing a sentence is one save
	 * and not forty — which matters because a save is a whole document over the
	 * wire, not a keystroke.
	 */
	private static final int SETTLE = 20;

	/** The graph as it stood when it was last sent, for telling changes from none. */
	private String lastSaved;

	/** What it said last tick, for telling "still being typed" from "finished". */
	private String lastSeen;

	private int settle;

	/** What the corner says about all this, and in what colour. */
	private Component keeping = Component.empty();
	private int keepingColour = 0xFF8A99A6;

	/**
	 * Saves without being asked.
	 *
	 * <h2>Why on a pause rather than on every change</h2>
	 *
	 * Because every change means every letter. The document goes over the wire
	 * whole — there is no format for "this one field" and inventing one would mean
	 * a second way of writing a dialogue for the server to get wrong — so a save is
	 * worth about a sentence, not a keystroke.
	 *
	 * The graph is read rather than a flag being watched, and that is on purpose.
	 * A flag has to be set at every place that edits a node, and this editor edits
	 * nodes from about thirty of them; the thirty-first would be written without it
	 * and the symptom would be work that silently stops being saved. Reading what
	 * the document actually says cannot be forgotten anywhere.
	 *
	 * <h2>Why a broken graph is written to disk instead</h2>
	 *
	 * The server refuses a graph that does not validate, and it is right to: an
	 * unfinished wire is not something other people's characters should start
	 * running. But half-finished is the normal state of a document being written,
	 * and "your work is not being saved right now" has to be more than a red line
	 * somebody may not be looking at. So it goes to the same drafts folder that
	 * catches an editor closed by accident — into one file that keeps being
	 * replaced, rather than a new file a second, or the ten slots there would be
	 * full of the last ten seconds and the useful copy would be gone.
	 */
	@Override
	public void tick() {
		super.tick();

		// The text window hands its line back here rather than when it is shut, so a
		// line is in the node from the moment it is typed — and the graph's own saving,
		// just below, then carries it to the server without being told twice.
		if (writer != null) writer.tick();

		// Where the boxes are is written into the document before it is asked what it
		// says, so that dragging one is a change like any other and is saved by the
		// same one mechanism a moment later. Doing it when the mouse is let go would
		// be a second way of saying "this changed", and a second way is a thing to
		// forget to call — which is how the arrangement came to be lost in the first
		// place.
		keepPlaces();

		String now = written();
		if (now == null) return;
		if (!now.equals(lastSeen)) {
			lastSeen = now;
			settle = SETTLE;
			if (!now.equals(lastSaved)) {
				keeping = Component.translatable("npc_studio.graph.keeping");
				keepingColour = 0xFF8A99A6;
			}
			return;
		}
		// Nought means this state has already been dealt with, one way or the other.
		if (settle == 0 || --settle > 0) return;
		keepUp(now);
	}

	private void keepUp(String json) {
		if (json.equals(lastSaved)) return;

		DialogueValidator.Problem stopper = firstError();
		if (stopper != null) {
			DialogueDrafts.keepUnsent(state.id(), json);
			keeping = Component.translatable("npc_studio.graph.not_kept",
				(stopper.where() == null ? "" : stopper.where() + ": ") + stopper.message());
			keepingColour = 0xFFEF5350;
			return;
		}

		ClientPlayNetworking.send(new EditorPayloads.Save(json));
		lastSaved = json;
		keeping = Component.translatable("npc_studio.graph.kept");
		keepingColour = 0xFF66BB6A;
	}

	/** The first thing wrong badly enough that the server would refuse it. */
	private DialogueValidator.Problem firstError() {
		for (DialogueValidator.Problem problem : state.problems()) {
			if (problem.severity() == DialogueValidator.Severity.ERROR) return problem;
		}
		return null;
	}

	/**
	 * The graph as text, or null when it cannot be written down at all.
	 *
	 * Guarded because this used to happen when somebody pressed a button and now
	 * happens twenty times a second. A document the writer refuses is a real state
	 * — it is what a half-typed field can briefly amount to — and while it cost one
	 * refused save it was survivable. As the heartbeat of the editor it would be an
	 * exception thrown out of every tick, which is the game closing, with an hour of
	 * graph inside it.
	 */
	private String written() {
		try {
			return state.toJson();
		} catch (RuntimeException cannot) {
			keeping = Component.translatable("npc_studio.graph.not_kept",
				String.valueOf(cannot.getMessage()));
			keepingColour = 0xFFEF5350;
			// Nothing is armed: there is nothing to settle on until the graph changes
			// again, and it will be looked at afresh when it does.
			settle = 0;
			return null;
		}
	}

	/**
	 * Puts a draft back in place of what is on screen.
	 *
	 * Nothing is sent from here, but it no longer follows that nothing is sent: the
	 * restored graph is what is on the screen now, and what is on the screen is what
	 * gets kept a second later. That is the right way round. While saving was a
	 * button, restoring and saving were deliberately two decisions; now that the
	 * document keeps itself, a restore that quietly never reached the server would
	 * be a restore that did nothing at all the moment you looked away.
	 */
	private void restore(String json) {
		EditorState restored = EditorState.from(json, state.otherNames());
		if (WorkspaceScreen.embedded()) {
			// A panel cannot replace itself with a second copy of itself. The
			// restored dialogue takes over the one already here instead.
			adopt(restored);
			return;
		}
		minecraft.setScreenAndShow(new GraphEditorScreen(restored));
	}

	/**
	 * Takes on another dialogue without becoming another screen.
	 *
	 * Everything worked out from the graph goes with it: the laid-out positions
	 * belong to the graph that was here, and keeping them would leave the new
	 * one's boxes standing where the old one's used to be.
	 */
	private void adopt(EditorState fresh) {
		state = fresh;
		panel = null;
		x.clear();
		y.clear();
		panX = 0;
		panY = 0;
		rebuildWidgets();
	}

	/**
	 * What the server made of the last save.
	 *
	 * An empty message is the ordinary answer now that saving happens by itself: a
	 * save that went through has nothing to tell anybody, and a line saying "Saved."
	 * appearing once a second while somebody types is noise that trains the eye to
	 * ignore the place warnings appear. The server speaks only when it has something
	 * to say — such as that the graph points at a place this world has not got.
	 */
	public void saveResult(boolean ok, String message) {
		saveFailed = !ok;
		saveMessage = message == null ? "" : message;
	}


	// ------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Before everything, including the buttons: the window covers them, and a
		// window you can click through is not a window.
		if (writer != null) {
			boolean shift = (event.modifiers() & 0x0001) != 0;
			writer.mouseClicked(event.x(), event.y(), shift);
			dropWriterIfDone();
			return true;
		}
		// Before the buttons as well as before the canvas: an open list covers
		// both, and a list you can click through is not a list.
		//
		// The picker first of the three, because it is the innermost: it is opened
		// from a field of the panel, and the panel is drawn over the canvas that the
		// other two sit on.
		if (picker.click(event.x(), event.y())) {
			rebuild();
			return true;
		}
		if (addingClicked(event.x(), event.y())) return true;
		// Before the widgets, because a grip shares its bar with the cross that gets rid
		// of the arm — and a grip that only takes hold where no button happens to be is
		// a grip with holes in it that nobody can see.
		if (panel != null && panel.grab(event.x(), event.y())) return true;
		// The window first, and — this is the point of it — a click that misses the
		// window is left entirely alone. It used to close both lists, which meant they
		// could never be worked alongside the graph they are about.
		if (sheet.clicked(font, event.x(), event.y())) return true;
		if (super.mouseClicked(event, doubleClick)) return true;

		int mx = (int) (event.x() / zoom) - panX;
		int my = (int) (event.y() / zoom) - panY;

		for (int i = state.nodes().size() - 1; i >= 0; i--) {
			Node node = state.nodes().get(i);
			int bx = x.getOrDefault(node.id(), 0);
			int by = y.getOrDefault(node.id(), 0);

			var rows = rowsOf(node);
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
			// A card is as wide as somebody made it, and this is what makes it grabbable
			// where it is drawn rather than only across the first hundred pixels of it.
			if (mx < bx || mx > bx + boxWidth(node) || my < by || my > by + h) continue;

			if (linkFrom != null) {
				connect(linkFrom, linkExit, node.id());
				linkFrom = null;
				return true;
			}

			// The title bar carries the box about; everywhere else in it selects.
			// The rows used to open a box of their own — see the note where `inline`
			// used to be — and now they do what the title does, so there is one way
			// to the words rather than two of unequal power.
			if (my <= by + TITLE_HEIGHT) dragging = node.id();
			select(i);
			return true;
		}

		if (linkFrom != null) linkFrom = null;
		else if (event.x() < width - panelWidth()) panning = true;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (writer != null) return writer.mouseDragged(event.x(), event.y());
		// The window is carried by its own bar and only by its bar, so a drag that
		// began on a node goes on being about that node however far it travels.
		if (sheet.carrying()) {
			sheet.dragged(dx, dy, width, height);
			return true;
		}
		if (panel != null && panel.carrying()) {
			panel.carry(event.y());
			return true;
		}
		if (dragging != null) {
			x.merge(dragging, (int) (dx / zoom), Integer::sum);
			y.merge(dragging, (int) (dy / zoom), Integer::sum);
			return true;
		}
		if (panning) {
			panX += (int) (dx / zoom);
			panY += (int) (dy / zoom);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (writer != null) {
			writer.mouseReleased();
			return true;
		}
		// Before the widgets get it, and before anything is cleared: putting an arm down
		// rebuilds the panel, and a release delivered to a button that the rebuild has
		// already replaced is a click landing on whatever took its place.
		if (panel != null && panel.carrying()) {
			panel.drop();
			return true;
		}
		dragging = null;
		panning = false;
		sheet.released();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		// The open list first. It is drawn over the panel, so the wheel over it belongs
		// to it — scrolling the panel underneath a list would move the list away from
		// the field it was opened at.
		if (picker.scrolled(amountY)) return true;
		// And the floating window next, for the same reason: it is over the canvas, and
		// the wheel over a list that scrolls belongs to the list rather than to the zoom.
		if (sheet.scrolled(font, mouseX, mouseY, amountY)) return true;
		if (panel != null && mouseX >= width - NodePanel.WIDTH) {
			panelScroll -= (int) (amountY * 16);
			rebuildWidgets();
			return true;
		}

		// Zoomed about the cursor, so whatever is under it stays under it. Zooming
		// about the corner means chasing the thing being looked at across the
		// canvas with the other hand.
		float was = zoom;
		zoom = net.minecraft.util.Mth.clamp(zoom * (float) Math.exp(amountY * 0.16), 0.3f, 2.5f);
		if (zoom == was) return true;
		panX += (int) (mouseX / zoom - mouseX / was);
		panY += (int) (mouseY / zoom - mouseY / was);
		return true;
	}

	private static final int KEY_DELETE = 261;
	private static final int KEY_S = 83;
	private static final int GLFW_CONTROL = 0x0002;

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// Enter finishes a rename, because that is what enter means in a field with
		// one line in it. The tick beside it stays for the hand already on the mouse.
		if (naming != null && (event.key() == 257 || event.key() == 335)) {
			finishNaming();
			return true;
		}
		if (writer != null) {
			writer.keyPressed(event);
			dropWriterIfDone();
			return true;
		}
		// Delete removes the selected node, which is the one gesture everybody
		// tries first and the one thing the panel's own button was the only way to
		// do. Not while a box is being typed in: there the key belongs to the text.
		if (event.key() == KEY_DELETE && panel != null && !typing()) {
			deleteSelected();
			rebuildWidgets();
			return true;
		}
		// The one thing left of the Save button. It does not save anything that would
		// not have been saved a second later anyway — it is here because the hand
		// presses it regardless, and a key that a person believes saves their work
		// must not be a key that does nothing.
		if (event.key() == KEY_S && (event.modifiers() & GLFW_CONTROL) != 0) {
			settle = 0;
			String now = written();
			if (now != null) keepUp(now);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (writer != null) return writer.charTyped(event.codepoint());
		return super.charTyped(event);
	}

	/**
	 * Whether the keyboard belongs to a field rather than to the graph.
	 *
	 * The text window counts, and it has to say so itself: it holds no widget, so
	 * the walk down the focus chain finds nothing and would answer that nobody is
	 * typing — while somebody is typing a sentence into it.
	 */
	private boolean typing() {
		return com.mopicmp.npcstudio.client.workspace.Typing.into(this);
	}

	/**
	 * The text window takes every letter while it is open, and has to say so.
	 *
	 * It holds no widget of its own — it draws its caret and reads the keyboard
	 * itself — so nothing in the focus chain can be found to answer for it.
	 */
	@Override
	public boolean takesLetters() {
		return writer != null;
	}

	/**
	 * Shuts the text window, and says whether there was one.
	 *
	 * Escape is answered by the workspace before the panels get the key, and the
	 * innermost thing has to be closed first — otherwise pressing escape over an
	 * open text window closes the panel behind it and takes the window with it.
	 */
	public boolean escape() {
		// The innermost thing on the screen, and escape means "get me out of this"
		// innermost first. Without this an open list had no way out but a click
		// somewhere, which is the one-way door this method exists to prevent.
		if (picker.isOpen()) {
			picker.close();
			return true;
		}
		// A half-typed name is dropped rather than sent. Escape over a field means
		// "forget it", everywhere, and a rename that happened because somebody
		// changed their mind would be the one edit here that cannot be undone.
		if (naming != null) {
			naming = null;
			rebuild();
			return true;
		}
		if (writer == null) return false;
		writer.tick();
		writer = null;
		rebuild();
		return true;
	}

	/**
	 * Lets the window go, having first taken what it was holding.
	 *
	 * The line is handed back on the tick, so a window closed by the same press that
	 * typed the last letter would be dropped before that tick came — and the letter
	 * with it. Asking one more time on the way out costs one comparison and closes
	 * the only gap this arrangement has.
	 */
	private void dropWriterIfDone() {
		if (writer == null || !writer.finished()) return;
		writer.tick();
		writer = null;
		rebuild();
	}

	private void connect(String fromId, int exit, String toId) {
		int index = state.nodeIds().indexOf(fromId);
		Node from = node(fromId);
		if (from == null) return;

		state.replace(index, switch (from) {
			// Nothing leads out of writing, so there is no wire to move. Reached only if
			// something ever offers to drag one, which nothing does.
			case Node.Comment comment -> comment;
			// Rebuilt whole rather than through the short form. It was built through the
			// six-part one, which silently dropped how long the line lasts every time
			// somebody dragged a wire out of it — and would now drop the face and the
			// colour of the name as well. A constructor that exists for old files is not
			// a constructor for live edits.
			case Node.Line line -> line.withNext(toId);
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
			case Node.Set set -> new Node.Set(set.id(), set.variable(), set.scope(),
				set.value(), set.how(), toId);
			case Node.Forget forget -> new Node.Forget(forget.id(), forget.everything(),
				forget.variables(), forget.scope(), forget.visited(), toId);
			case Node.Act act -> new Node.Act(act.id(), act.effect(), toId);
			// One wire per thing waited on, moved by which row it left from — the same
			// way a fork's arms are, because it is the same shape: several ways out,
			// each belonging to one row of the box.
			case Node.Pressed waiting -> {
				List<com.mopicmp.npcstudio.dialogue.Node.Press> presses =
					new ArrayList<>(waiting.presses());
				if (exit < presses.size()) {
					presses.set(exit, new com.mopicmp.npcstudio.dialogue.Node.Press(
						presses.get(exit).shown(), toId));
				}
				yield new Node.Pressed(waiting.id(), presses);
			}
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
			case Node.Every every -> new Node.Every(every.id(), every.ticks(), toId);
			case Node.Until until -> new Node.Until(until.id(), until.condition(), toId);
			case Node.Do call ->
				new Node.Do(call.id(), call.segment(), call.target(), call.with(), toId);
			case Node.Stop stop -> new Node.Stop(stop.id(), stop.segment(), toId);
			case Node.Walk walk -> new Node.Walk(walk.id(), walk.route(), toId);
			case Node.Chance chance -> {
				List<String> ways = new ArrayList<>(chance.ways());
				if (exit < ways.size()) ways.set(exit, toId);
				yield new Node.Chance(chance.id(), ways);
			}
		});

		carrySpeaker(fromId, toId);
	}

	/**
	 * The node at the far end of a new wire takes the speaker from this end.
	 *
	 * Only when it has none of its own — an empty field is a question nobody has
	 * answered yet, and a name somebody typed is an answer, and the two must never
	 * be confused. So this fills in blanks and overwrites nothing, and the field
	 * goes on being an ordinary field afterwards.
	 *
	 * Which speaker, and why it is not simply the node the wire left, is
	 * {@link SpeakerCarry}'s business and is explained there.
	 */
	private void carrySpeaker(String fromId, String toId) {
		if (toId == null) return;
		int landed = state.nodeIds().indexOf(toId);
		if (landed < 0) return;

		Node was = state.nodes().get(landed);
		Node now = SpeakerCarry.given(was, SpeakerCarry.after(state.nodes(), fromId));
		if (now == was) return;

		state.replace(landed, now);
		// The panel may be showing the very node that has just been given a name, and
		// a field holding the old value is worse than no field: it is what somebody
		// will believe when they look away and back.
		if (panel != null && panel.index() == landed) refreshPanel();
	}

	// ------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.pose().pushMatrix();
		graphics.pose().scale(zoom, zoom);
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
				(int) (mouseX / zoom), (int) (mouseY / zoom), WIRE_LIVE);
		}

		for (Node node : state.nodes()) box(graphics, node);
		graphics.pose().popMatrix();

		legend(graphics);
		if (panel != null) {
			panel.draw(graphics, font, width - NodePanel.WIDTH, -panelScroll, height + panelScroll);
		}
		sheet.draw(graphics, font, mouseX, mouseY);
		drawAdding(graphics, mouseX, mouseY);
		if (writer != null) writer.draw(graphics, font, mouseX, mouseY);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// After the widgets, and that is the whole reason it is drawn here rather than
		// with the other two lists above. A screen draws its widgets last, so anything
		// this screen paints before that call comes out *underneath* the panel's own
		// buttons — which for a list opened at one of those buttons means a list you
		// cannot read. The same fault the text window carries a note about, met again
		// and answered differently: that one stops the widgets being built at all,
		// which is right for a window and far too heavy for a dropdown.
		if (picker.isOpen()) {
			graphics.nextStratum();
			picker.draw(graphics, font, mouseX, mouseY);
		}
	}

	private void grid(GuiGraphicsExtractor graphics) {
		int step = 24;
		int across = (int) (width / zoom) + step;
		int down = (int) (height / zoom) + step;
		for (int gx = panX % step; gx < across; gx += step) graphics.fill(gx, 0, gx + 1, down, GRID);
		for (int gy = panY % step; gy < down; gy += step) graphics.fill(0, gy, across, gy + 1, GRID);
	}

	private void box(GuiGraphicsExtractor graphics, Node node) {
		if (node instanceof Node.Comment comment) {
			card(graphics, comment);
			return;
		}
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

		var rows = rowsOf(node);
		for (int i = 0; i < rows.size(); i++) {
			int py = by + TITLE_HEIGHT + i * PORT_ROW;
			// Through Ink rather than through the font directly, because that is what
			// knows how to draw a line that has been decorated. TEXT is what a stretch
			// nobody coloured falls back to, so an ordinary line looks exactly as it did.
			com.mopicmp.npcstudio.client.text.Ink.draw(graphics, font, rows.get(i),
				bx + 4, py + 2, 0, 0, TEXT);
			graphics.fill(bx + BOX_WIDTH - 1, py + PORT_ROW / 2 - 2,
				bx + BOX_WIDTH + 3, py + PORT_ROW / 2 + 2, accent);
		}
		if (rows.isEmpty()) {
			graphics.text(font, Component.literal("stop"), bx + 4, by + TITLE_HEIGHT + 2, TEXT_DIM);
		}
	}

	/**
	 * Writing on the canvas, drawn as a card rather than as a box with ports.
	 *
	 * <h2>Why it looks like a node and not like one</h2>
	 *
	 * Like one, because it is: it is dragged, selected and added exactly as everything
	 * else is, and anything that looked like a label would invite being treated as
	 * decoration. Unlike one in every detail that says "the conversation passes through
	 * here" — no title bar, no ports down the side, and a warm colour nothing else on
	 * the canvas uses.
	 *
	 * <h2>The lines to what it is about</h2>
	 *
	 * Thin, dim, and drawn straight rather than as the curves the wires use. A wire is
	 * where the conversation goes; this is only "these two things are related", and
	 * making it look like a wire would be saying something untrue about the graph in the
	 * one place people read the graph from.
	 */
	private void card(GuiGraphicsExtractor graphics, Node.Comment comment) {
		int bx = panX + x.getOrDefault(comment.id(), 0);
		int by = panY + y.getOrDefault(comment.id(), 0);
		int wide = comment.wide();
		int high = comment.rows() * font.lineHeight + PAD * 2;
		boolean selected = panel != null
			&& state.nodes().get(panel.index()).id().equals(comment.id());

		// To each node it is about, from the middle of the card to the middle of theirs.
		for (String about : comment.about()) {
			if (!x.containsKey(about)) continue;
			Node other = node(about);
			if (other == null) continue;
			tether(graphics, bx + wide / 2, by + high / 2,
				panX + x.get(about) + BOX_WIDTH / 2,
				panY + y.get(about) + boxHeight(other) / 2);
		}

		graphics.fill(bx - 1, by - 1, bx + wide + 1, by + high + 1,
			selected ? CARD_ACCENT : CARD_EDGE);
		graphics.fill(bx, by, bx + wide, by + high, CARD_BODY);

		if (comment.text().isBlank()) {
			graphics.text(font, Component.translatable("npc_studio.graph.kind.comment"),
				bx + PAD, by + PAD, CARD_EDGE);
			return;
		}
		int inside = wide - PAD * 2;
		var rows = wrap(comment.text(), inside);
		for (int i = 0; i < comment.rows() && i < rows.size(); i++) {
			String plain = rows.get(i);
			// The last row that fits says the writing goes on, and only when it does. The
			// mark is fitted rather than appended: added afterwards it is the part that
			// runs off the edge, so the card would be cut without saying so.
			if (i == comment.rows() - 1 && rows.size() > comment.rows()) {
				while (!plain.isEmpty() && font.width(plain + "\u2026") > inside) {
					plain = plain.substring(0, plain.length() - 1);
				}
				plain = plain + "\u2026";
			}
			graphics.text(font, Component.literal(plain),
				bx + PAD, by + PAD + i * font.lineHeight, CARD_TEXT);
		}
	}

	/** A dotted straight line from a card to something it is about. */
	private void tether(GuiGraphicsExtractor graphics, int fromX, int fromY, int toX, int toY) {
		int steps = Math.max(1, (Math.abs(toX - fromX) + Math.abs(toY - fromY)) / 7);
		for (int i = 0; i <= steps; i += 2) {
			int px = fromX + (toX - fromX) * i / steps;
			int py = fromY + (toY - fromY) * i / steps;
			graphics.fill(px, py, px + 1, py + 1, CARD_EDGE);
		}
	}

	/**
	 * Breaks writing into rows that fit, at spaces where there are any.
	 *
	 * Not the font's own splitter, which gives back sequences of glyphs rather than
	 * text — and the last row has to be shortened by hand to make room for the mark that
	 * says the writing goes on. A comment is plain text, so nothing is lost by wrapping
	 * it here, and what is gained is being able to say where it was cut.
	 *
	 * A word longer than the whole card is broken mid-word rather than left to run off
	 * the edge. It looks worse and it is honest, which is the right way round.
	 */
	private java.util.List<String> wrap(String text, int room) {
		var rows = new java.util.ArrayList<String>();
		if (room <= 0) return rows;
		StringBuilder row = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = row.isEmpty() ? word : row + " " + word;
			if (font.width(candidate) <= room) {
				row = new StringBuilder(candidate);
				continue;
			}
			if (!row.isEmpty()) {
				rows.add(row.toString());
				row = new StringBuilder();
			}
			while (font.width(word) > room && word.length() > 1) {
				int fits = 1;
				while (fits < word.length() && font.width(word.substring(0, fits + 1)) <= room) {
					fits++;
				}
				rows.add(word.substring(0, fits));
				word = word.substring(fits);
			}
			row = new StringBuilder(word);
		}
		if (!row.isEmpty()) rows.add(row.toString());
		return rows;
	}

	/** The air inside a card, on every side. */
	private static final int PAD = 4;

	private static final int CARD_BODY = 0xEE231D14;
	private static final int CARD_EDGE = 0xFF6B563A;
	private static final int CARD_ACCENT = 0xFFB08A5A;
	private static final int CARD_TEXT = 0xFFD8C4A4;

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
		// Not while it is being typed into: the field is drawn in the same place and
		// the old name underneath it would read as a second name nobody asked for.
		if (naming != null) return;
		graphics.text(font, Component.literal(state.id()), 8, 8, TEXT);
		// Beside the name rather than at the bottom with the problems. Whether the
		// work is being kept is not a problem with the document, it is a fact about
		// the document, and it belongs where the document is named.
		//
		// Past the pencil, not on top of it. It used to start ten pixels after the
		// name, and the pencil starts six pixels after the name and is sixteen wide —
		// so the note lay across the button always, and only a long enough name made
		// it obvious. Both are placed from the one number now, so moving either moves
		// the other with it.
		if (!keeping.getString().isEmpty()) {
			graphics.text(font, keeping, pencilX() + PENCIL_WIDE + 8, 8, keepingColour);
		}
		graphics.text(font, Component.translatable("npc_studio.graph.legend"), 8, 20, TEXT_DIM);

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
