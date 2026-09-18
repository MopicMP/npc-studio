package com.mopicmp.npcstudio.client.workspace;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.workspace.panel.ViewportPanel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The one door.
 *
 * Everything the mod could do used to be behind a screen of its own, each one
 * filling the display and hiding the rest. That is why building anything was so
 * awkward: to give a character a gesture you left the place where you could see
 * the character. This screen replaces all of them with one arrangement of
 * panels over a live world.
 *
 * It draws no background. That is the whole trick of the viewport — the world
 * is already on the display by the time a screen is asked to draw, so a
 * rectangle nobody paints is a rectangle you can see the world through.
 */
public class WorkspaceScreen extends Screen {

	/** The strip along the top: what the workspace is, and the way to a hidden panel. */
	private static final int TOOLBAR = 16;

	private static final int BAR = 0xFF12161C;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int PANEL = 0xFF161A20;
	private static final int HOVER = 0xFF232A34;

	/**
	 * The layout, kept past the life of the screen.
	 *
	 * Somebody who has arranged their panels and then closed the workspace to
	 * look at something has not asked to have that arrangement thrown away.
	 */
	private static Dock dock;

	private static WorkspaceScreen open;

	private final Menu menu = new Menu();

	public static void show(Minecraft client) {
		if (dock == null) {
			var config = com.mopicmp.npcstudio.client.NpcStudioConfig.get();
			dock = DockLayout.read(config.workspace);
			// An arrangement from before the columns went is put aside rather than
			// read, once, and the shipped one takes over. See the config field.
			if (dock == null && config.workspace != null
					&& config.workspace.version != DockLayout.VERSION) {
				config.workspaceBefore = config.workspace;
				config.workspace = null;
			}
		}
		if (dock == null) dock = new Dock(defaultLayout());
		dock.fitAll();
		sceneFirst();
		WorkspaceScreen screen = new WorkspaceScreen();
		open = screen;
		client.setScreenAndShow(screen);
	}

	public static WorkspaceScreen current() {
		return open;
	}

	/**
	 * Whether the old editors are running as panels rather than as screens.
	 *
	 * One question with one answer, asked from inside those editors wherever they
	 * were about to hand the whole display to something else. Asking "am I on
	 * screen" of the workspace is better than each editor carrying a flag: a flag
	 * has to be set correctly at every construction site, and the sites are what
	 * keep changing.
	 */
	public static boolean embedded() {
		return open != null;
	}

	/**
	 * Where the viewport is on the display, or null when there is not one showing.
	 *
	 * The film's own rectangle. Asked by {@code Framing}, which is the one place
	 * that decides what "the frame" means, so that the captions, the fade and the
	 * crop all agree about it rather than each working it out.
	 *
	 * Null while the panels have stood aside, because then the frame is the whole
	 * window — which is also the answer when no workspace is open at all.
	 */
	public static int[] viewport() {
		if (dock == null || open == null || clean) return null;
		for (WorkspacePanel panel : dock.visible()) {
			if (panel instanceof ViewportPanel) {
				return new int[] { panel.originX(), panel.originY(), panel.width, panel.height };
			}
		}
		return null;
	}

	/**
	 * The world, in front, whatever the layout was doing when it was put away.
	 *
	 * <h2>What this is answering</h2>
	 *
	 * "Opening the menu shows the dialogue window instead of ours, always." Two
	 * things put it there and both are undone here. A tab bar may hold several
	 * panels and whichever was last in front is the one the saved layout brings
	 * back; and a panel that cannot be worked in beside anything else — the graph is
	 * the only one — is given the whole window on the way in by {@link Dock#fitAll},
	 * which then covers the strips and the bar as well.
	 *
	 * Neither is wrong on its own. What is wrong is that the first thing seen on
	 * opening a workspace built around a world is not the world. So the last act
	 * before the screen appears is to put the scene in front, and the graph is one
	 * press away in the corner where it now lives.
	 *
	 * Several tabs are still several tabs: this changes which is in front, and takes
	 * nothing out of the bar.
	 */
	private static void sceneFirst() {
		if (dock == null) return;
		// The window back first, or revealing the viewport would leave it showing
		// underneath a graph that is still drawn over the whole screen.
		dock.giveBackWindow();
		reveal("viewport");
	}

	/** Brings a panel into view, making it if it is not in the layout at all. */
	public static void reveal(String id) {
		if (dock == null) return;
		if (dock.reveal(id)) return;
		WorkspacePanel made = Panels.make(id);
		// As a tab of whatever is already on the screen, rather than by cutting the
		// layout in two. See Dock.addAsTab: a panel arriving because somebody asked
		// for it by name is a panel that belongs beside the others, and the graph in
		// particular came out as a window over everything.
		if (made != null) dock.addAsTab(made);
	}

	/**
	 * Hands a packet to whichever panel was waiting for it.
	 *
	 * The answer travels to a panel rather than to a screen now, and a panel may
	 * not be in the layout at all — somebody who has closed the character panel
	 * has said they do not want it, and a packet is not a reason to bring it
	 * back.
	 */
	public static <T extends WorkspacePanel> void deliver(Class<T> kind, java.util.function.Consumer<T> what) {
		if (dock == null) return;
		for (WorkspacePanel panel : dock.panels()) {
			if (kind.isInstance(panel)) what.accept(kind.cast(panel));
		}
	}

	/** Where the panels start out, which is now: nowhere. */
	private static Dock.Node defaultLayout() {
		// The world, and nothing else.
		//
		// What was here before was two columns and a stack: eight panels down the
		// right, two down the left, the graph and the timeline under the viewport.
		// It could not fit — measured, not felt: for the animation panel to get the
		// 340 pixels it asks for at its share of the width, the window would have had
		// to be wider than the screen, at any position of any splitter.
		//
		// So nothing starts open. Panels are summoned from the strips down the edges
		// and dismissed again, and what is on the screen at any moment is what
		// somebody asked for rather than everything that exists.
		return new Dock.Leaf(new ViewportPanel());
	}

	private WorkspaceScreen() {
		super(Component.translatable("npc_studio.workspace.title"));
	}

	@Override
	protected void init() {
		if (!WorkspaceCamera.active()) WorkspaceCamera.take();
		// The outline belongs to the workspace, not to the character. Lit on the
		// way in and put out on the way out, so nobody in ordinary play meets an
		// NPC glowing because somebody was editing it an hour ago.
		Workspace.lit(true);
	}

	// --------------------------------------------------------------- drawing

	/**
	 * Nothing at all.
	 *
	 * Overridden rather than left alone: the inherited version washes the world
	 * out behind an in-world screen, and a washed-out world is not what a scene
	 * is being built against.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	/**
	 * Whether the workspace is standing aside to leave only the scene.
	 *
	 * For filming: with a capture running, or with something outside the game
	 * recording the window, the panels are the one thing in the shot that is not
	 * the scene. Drawing nothing is the whole of it — the screen stays open, so
	 * the camera stays where it was put and the keyboard still answers, which is
	 * why this is a flag rather than closing the window. Closing it hands the
	 * camera back to the player mid-shot.
	 *
	 * The game's own interface — the hotbar, the crosshair — is not covered by
	 * this and cannot be from here: the flag that hid it moved in this version and
	 * is no longer a field anybody outside can set. F1 does it, by hand, and that
	 * is worth one keypress rather than a guess at where it went.
	 */
	private static boolean clean;

	public static boolean clean() {
		return clean;
	}

	public static void clean(boolean only) {
		clean = only;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (clean) {
			// One dim line, and not while anything is being filmed. With the panels
			// gone there is nothing on screen at all, which is indistinguishable from
			// the workspace having broken — and it was reported as exactly that. A
			// capture is the one case where the screen must be truly empty, and there
			// the way back is the key it always was.
			if (!com.mopicmp.npcstudio.client.scene.Capture.running()) {
				Component hint = Component.translatable("npc_studio.workspace.hidden");
				graphics.text(font, hint, width - 6 - font.width(hint), height - 12, TEXT_DIM);
			}
			return;
		}

		// The world gets the whole window between the strips. It is not a column
		// among columns any more: everything else is summoned over it and dismissed,
		// which is the arrangement this workspace is being moved to.
		dock.layout(Rail.WIDTH, TOOLBAR, width - Rail.WIDTH * 2, height - TOOLBAR);
		drawToolbar(graphics, mouseX, mouseY);
		dock.render(graphics, mouseX, mouseY, delta);
		graphics.nextStratum();
		drawRails(graphics, mouseX, mouseY);
		drawName(graphics, mouseX, mouseY);

		if (menu.isOpen()) {
			graphics.nextStratum();
			drawMenu(graphics, mouseX, mouseY);
		}
		if (landings.isOpen()) {
			graphics.nextStratum();
			drawLandingMenu(graphics, mouseX, mouseY);
		}
	}

	/**
	 * What sits along the top, as icons.
	 *
	 * Words were there first and had to go for the same reason every other row of
	 * buttons did: they are as wide as the language they are written in, and a
	 * row measured in Russian nouns is a row that runs off the end of a narrow
	 * window. An icon is sixteen pixels in every language.
	 */
	private static final int TOOL = 20;

	private void drawToolbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, width, TOOLBAR, BAR);
		graphics.fill(0, TOOLBAR - 1, width, TOOLBAR, EDGE);
		graphics.text(font, title, 6, 5, TEXT);

		drawTool(graphics, panelsLeft(), Icon.PANELS, mouseX, mouseY, false);
		drawTool(graphics, playerLeft(),
			Workspace.showPlayer() ? Icon.PLAYER_ON : Icon.PLAYER_OFF,
			mouseX, mouseY, Workspace.showPlayer());
		drawTool(graphics, bareLeft(), Icon.BARE, mouseX, mouseY, clean);
		drawTool(graphics, benchLeft(), Icon.GRAPH, mouseX, mouseY, showing("graph"));

		// Where edits are landing, as the icon of the receiver in force. Lit when it
		// has been pinned there, plain when it is following the work — so the one
		// state somebody set on purpose is the one that stands out.
		drawTool(graphics, landingLeft(), Landing.now().icon, mouseX, mouseY,
			Landing.pinned() != null);

		if (dock.maximized() != null) {
			// Left of the corner button rather than in it. The graph moved into that
			// corner, and a line of text under a button is a line nobody can read and a
			// button nobody can see. Dropped entirely when there is no room left for it:
			// it is a note about the state of the window, and the buttons are the way out
			// of that state.
			Component hint = Component.translatable("npc_studio.workspace.maximized");
			int at = benchLeft() - 8 - font.width(hint);
			if (at > landingLeft() + TOOL) graphics.text(font, hint, at, 5, ACCENT);
		}
	}

	private void drawTool(GuiGraphicsExtractor graphics, int left, Icon icon,
			int mouseX, int mouseY, boolean on) {
		boolean hovered = mouseX >= left && mouseX < left + TOOL && mouseY < TOOLBAR;
		if (hovered || on) graphics.fill(left, 0, left + TOOL, TOOLBAR - 1, on ? 0xFF27313E : HOVER);
		icon.draw(graphics, left + (TOOL - Icon.SIZE) / 2, (TOOLBAR - Icon.SIZE) / 2,
			hovered || on ? ACCENT : TEXT_DIM);
	}

	private int panelsLeft() {
		return 10 + font.width(title);
	}

	private int playerLeft() {
		return panelsLeft() + TOOL + 2;
	}

	/**
	 * Standing the panels aside to see the shot as it will be filmed.
	 *
	 * <h2>Why this had to become a button</h2>
	 *
	 * Because the viewport is not the frame and looks exactly as though it were.
	 * The world is drawn across the whole window and the panels are laid over the
	 * edges of it, so the rectangle left visible is the middle of the picture rather
	 * than the whole of it — and a title centred in the film is therefore off-centre
	 * in the hole you are watching through.
	 *
	 * That was reported as the text and the framing shifting between the small view
	 * and the full one, and nothing had shifted: the small view had never been the
	 * shot. The only honest fix short of drawing the world twice is to make it one
	 * press to see the real thing.
	 *
	 * Escape brings the panels back, which it already did.
	 */
	private int bareLeft() {
		return playerLeft() + TOOL + 2;
	}

	/**
	 * The receiver: where the next edit in any panel is written down.
	 *
	 * <h2>Why this is one control for the whole window rather than one per panel</h2>
	 *
	 * Because the confusion it removes is a confusion about the moment, not about
	 * the panel. "Am I building a scene or setting the world up" has one answer at
	 * a time, and every panel has to give the same one — a lighting slider keying
	 * the timeline while a costume dresses the character would be two receivers in
	 * one afternoon and no way to see it.
	 *
	 * It follows the work on its own and is only touched to disagree with it, which
	 * is why it sits here as a small icon rather than as a mode anybody has to
	 * choose before starting.
	 */
	/**
	 * Block programming, which had no door at all and was reported missing.
	 *
	 * It was never on a rail on purpose — by the ladder it is a destination rather
	 * than a side panel, and putting it on an edge would have said it lives there.
	 * But the door I left it was the panels menu, which is a list of two dozen
	 * names, and a thing findable only by reading a list of two dozen names is a
	 * thing that has been lost.
	 *
	 * So it gets its own button, like the modelling mode has its own key, and it
	 * takes the window when it opens because a graph of blocks cannot be worked in
	 * beside anything else.
	 *
	 * <h2>Which panel this is, corrected</h2>
	 *
	 * The graph — boxes and wires. It first pointed at the bench, which is a column
	 * of seven buttons for trying things on a character and is not programming of any
	 * kind; the complaint it was answering said the panel is workable full screen and
	 * impossible in a small one, which is true of a graph and of nothing else here.
	 * The bench keeps its place in the panels list, where a piece of scaffolding
	 * belongs.
	 *
	 * <h2>Why it sits in the far corner rather than in the row</h2>
	 *
	 * Asked for, and it is the right place for what it does. The other four buttons
	 * change how the window is looked at — which panels, whether the player shows,
	 * whether the panels stand aside, where an edit lands. This one leaves for
	 * somewhere else entirely: the graph takes the whole window when it opens. A door
	 * out of the room does not belong in the row of light switches, and the far corner
	 * is where every program in the world keeps the thing that changes what you are
	 * looking at.
	 */
	private int benchLeft() {
		// The corner, unless the window is too narrow to have one — then it falls in
		// beside the row rather than landing on top of the receiver, because two
		// buttons in one place is one button nobody can press.
		return Math.max(landingLeft() + TOOL + 8, width - 6 - TOOL);
	}

	private int landingLeft() {
		return bareLeft() + TOOL + 8;
	}

	/**
	 * Opens the graph or puts it away, and never as a slab against an edge.
	 *
	 * Into the tree rather than out on a rail, because it is the one panel that asks
	 * for the whole window and the dock only knows how to give that to a leaf. A
	 * floating graph would be a graph in a two-hundred-pixel box, which is the state
	 * it was reported broken in.
	 */
	private void toggleGraph() {
		for (WorkspacePanel panel : dock.panels()) {
			if (panel.id().equals("graph")) {
				dock.close(panel);
				return;
			}
		}
		WorkspacePanel made = Panels.make("graph");
		if (made != null) dock.addAsTab(made);
	}

	/**
	 * The receivers on offer, with "follow the work" first.
	 *
	 * First because it is the answer nearly always wanted and the way back from any
	 * pin. A list whose default is buried is a list people pin themselves into.
	 */
	private void openLandingMenu() {
		List<Menu.Entry> entries = new ArrayList<>();
		// Null is the un-pinned state rather than a fourth receiver, and it is drawn
		// with whatever the work is currently choosing so the row is never a shrug.
		entries.add(new Menu.Entry(Icon.FOCUS,
			Component.translatable("npc_studio.landing.follow"),
			Landing.pinned() == null, () -> Landing.pin(null)));
		for (Landing row : Landing.offered()) {
			entries.add(new Menu.Entry(row.icon, row.title(),
				row == Landing.pinned(), () -> Landing.pin(row)));
		}
		landings.open(font, landingLeft(), TOOLBAR, entries, 0, 0, width, height);
	}

	/**
	 * The third of these, found while merging the other two.
	 *
	 * It sat in the same file as the panels menu, drawn by its own copy of the same
	 * loop against the same constants. That is how many there were: not two, three
	 * — which is the argument for the widget rather than against it.
	 */
	private final Menu landings = new Menu();

	private void drawLandingMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		landings.draw(graphics, font, mouseX, mouseY);
	}

	private boolean clickLanding(double px, double py) {
		return landings.click(px, py);
	}

	// ------------------------------------------------------------- the menu


	/**
	 * Every panel there is, with the ones already showing marked.
	 *
	 * A dropdown rather than a row of buttons: the list is going to keep growing
	 * as the mod does, and a row that grows is a row that eventually reaches the
	 * other side of the display.
	 */
	private void openPanelsMenu() {
		List<Menu.Entry> entries = new ArrayList<>();
		for (String id : Panels.known()) {
			// The mark is what this menu is for as much as the list is: it answers
			// "is it already up somewhere" without hunting the docks for it.
			entries.add(new Menu.Entry(Dock.iconOf(id),
				Component.translatable("npc_studio.panel." + id), showing(id),
				() -> {
					if (dock.reveal(id)) return;
					WorkspacePanel made = Panels.make(id);
					if (made != null) dock.addAsTab(made);
				}));
		}
		// Last, and separated by being last: the way out of any arrangement that
		// has gone wrong. Panels can be closed, dragged into each other and stacked
		// until nothing is where it was, and until this existed the only way back
		// was to work out what had been done and undo it by hand.
		entries.add(new Menu.Entry(Icon.RESET,
			Component.translatable("npc_studio.workspace.reset"), false,
			() -> {
				dock.closed();
				dock = new Dock(defaultLayout());
			}));

		menu.open(font, panelsLeft(), TOOLBAR, entries, 0, 0, width, height);
	}

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		menu.draw(graphics, font, mouseX, mouseY);
	}

	/**
	 * Whether a panel is not merely open but actually on the screen right now.
	 *
	 * Asked from outside by whatever has an answer to show and has to decide whether
	 * showing it in the panel is enough. Different from {@link #showing} in the two
	 * ways that matter to that question: no workspace at all counts as no, and so
	 * does the workspace standing aside to be filmed.
	 */
	public static boolean watching(String id) {
		if (open == null || dock == null || clean) return false;
		if (dock.floating(id) != null) return true;
		// The panel in front of its leaf, not merely somewhere in the layout. A tab
		// behind another tab is a panel nobody can read, which is the same as closed
		// for the purpose of deciding whether to say something out loud.
		for (WorkspacePanel panel : dock.visible()) {
			if (panel.id().equals(id)) return true;
		}
		return false;
	}

	private boolean showing(String id) {
		for (WorkspacePanel panel : dock.panels()) {
			if (panel.id().equals(id)) return true;
		}
		return false;
	}

	private boolean clickMenu(double px, double py) {
		return menu.click(px, py);
	}

	// -------------------------------------------------------------- the two edges

	/**
	 * Draws the strips of icons down both edges.
	 *
	 * Lit for whatever is out, so the strip answers "is it already up" without
	 * anything being opened to find out — the same mark the panels menu carries,
	 * for the same reason.
	 */
	private void drawRails(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, TOOLBAR, Rail.WIDTH, height, BAR);
		graphics.fill(width - Rail.WIDTH, TOOLBAR, width, height, BAR);
		graphics.fill(Rail.WIDTH, TOOLBAR, Rail.WIDTH + 1, height, EDGE);
		graphics.fill(width - Rail.WIDTH - 1, TOOLBAR, width - Rail.WIDTH, height, EDGE);

		drawRail(graphics, Rail.left(), 0, mouseX, mouseY);
		drawRail(graphics, Rail.right(), width - Rail.WIDTH, mouseX, mouseY);
	}

	private void drawRail(GuiGraphicsExtractor graphics, List<String> ids, int left,
			int mouseX, int mouseY) {
		for (int i = 0; i < ids.size(); i++) {
			int top = TOOLBAR + 2 + i * Rail.WIDTH;
			boolean out = dock.floating(ids.get(i)) != null;
			boolean hovered = mouseX >= left && mouseX < left + Rail.WIDTH
				&& mouseY >= top && mouseY < top + Rail.WIDTH;
			if (out || hovered) {
				graphics.fill(left, top, left + Rail.WIDTH, top + Rail.WIDTH,
					out ? PANEL : HOVER);
			}
			Dock.iconOf(ids.get(i)).draw(graphics,
				left + (Rail.WIDTH - Icon.SIZE) / 2, top + (Rail.WIDTH - Icon.SIZE) / 2,
				out ? ACCENT : hovered ? TEXT : TEXT_DIM);
		}
	}

	// ------------------------------------------------------------- what is that

	/**
	 * The name of whichever icon the hand is on, and only that one.
	 *
	 * <h2>Why this was missing and why it matters here</h2>
	 *
	 * Nine icons carry the whole window — five in the top strip, six down the two
	 * edges — and not one of them said what it was. That is the trade the icons were
	 * chosen for: a row of words is as wide as the language it is written in, so a
	 * toolbar in Russian runs off a narrow window. But the trade was only half made.
	 * Every icon <em>inside</em> a panel already keeps its word in a tooltip, because
	 * those are real widgets; the strips are drawn by hand and got nothing, so the
	 * one part of the window somebody meets first is the one part that cannot be
	 * read.
	 *
	 * One label rather than all of them, which is the same rule the ring already
	 * follows for the same reason: nine words around the edges of a window are nine
	 * words over the work.
	 *
	 * Drawn after the strips and before any menu, so it sits over the strip it
	 * belongs to and under anything opened on purpose.
	 */
	private void drawName(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Component name;
		int left;
		int top;

		if (mouseY < TOOLBAR) {
			int at = toolAt(mouseX);
			if (at < 0) return;
			name = toolName(at);
			left = IconName.leftUnderTool(width, toolLeft(at), font.width(name));
			top = TOOLBAR + 3;
		} else {
			boolean right = mouseX >= width - Rail.WIDTH;
			List<String> ids = mouseX < Rail.WIDTH ? Rail.left() : right ? Rail.right() : null;
			if (ids == null) return;
			int which = IconName.railIndex(TOOLBAR, mouseY, ids.size());
			if (which < 0) return;
			name = Panels.titleOf(ids.get(which));
			left = IconName.leftBesideRail(width, right, font.width(name));
			top = IconName.topBesideRail(TOOLBAR, which);
		}

		int wide = IconName.wide(font.width(name));
		graphics.fill(left - 1, top - 1, left + wide + 1, top + IconName.TALL + 1, EDGE);
		graphics.fill(left, top, left + wide, top + IconName.TALL, BAR);
		graphics.text(font, name, left + IconName.PAD, top + 2, TEXT);
	}

	/**
	 * The five of the top strip, in order, as one list.
	 *
	 * Named in one place rather than five, because the drawing, the click and now the
	 * label all have to agree about which button is where — and they were three
	 * separate chains of {@code + TOOL + 2} that had to be kept in step by hand.
	 */
	private static final String[] TOOLS = {
		"npc_studio.workspace.panels", "npc_studio.workspace.player",
		"npc_studio.workspace.bare", "npc_studio.workspace.blocks",
		"npc_studio.workspace.landing" };

	private int toolLeft(int at) {
		return switch (at) {
			case 0 -> panelsLeft();
			case 1 -> playerLeft();
			case 2 -> bareLeft();
			case 3 -> benchLeft();
			default -> landingLeft();
		};
	}

	private int toolAt(int mouseX) {
		for (int i = 0; i < TOOLS.length; i++) {
			int left = toolLeft(i);
			if (mouseX >= left && mouseX < left + TOOL) return i;
		}
		return -1;
	}

	private Component toolName(int at) {
		return Component.translatable(TOOLS[at]);
	}

	/** Which rail button the click landed on, or null. */
	private String railAt(double px, double py) {
		if (py < TOOLBAR) return null;
		List<String> ids = px < Rail.WIDTH ? Rail.left()
			: px >= width - Rail.WIDTH ? Rail.right() : null;
		if (ids == null) return null;
		// The same sum as the label's, from the same place. It was two copies, and two
		// copies of "which button is this" is how a name ends up naming its neighbour.
		int which = IconName.railIndex(TOOLBAR, py, ids.size());
		return which < 0 ? null : ids.get(which);
	}

	/**
	 * Summons a panel against its own edge, or dismisses the one already there.
	 *
	 * The whole arrangement in one method: the world has the window, and a panel is
	 * something that comes out over it when asked and goes away again. Pressing the
	 * same button twice leaves nothing behind, which is what makes it safe to press
	 * — a button that only ever adds is a button that fills the screen.
	 */
	private void toggleRail(String id) {
		WorkspacePanel out = dock.floating(id);
		if (out != null) {
			dock.close(out);
			return;
		}
		summonHere(id);
	}

	/**
	 * Brings a panel out against its edge, whoever asked — a rail button or the ring.
	 *
	 * Static so that the menu under the cursor can reach it: the ring's whole job
	 * for a character is to bring the right panel to the right character, and it
	 * has no other way in.
	 */
	public static void summon(String id) {
		if (open == null) return;
		if (open.dock.floating(id) != null) return;
		open.summonHere(id);
	}

	/**
	 * How wide a summoned panel is.
	 *
	 * <h2>The bug this replaces, which was mine</h2>
	 *
	 * A fixed 150 was chosen for the rails without asking the panels what they
	 * need, and the dock does not clip: {@code floater.panel.resize(Math.max(
	 * floater.width, panel.minimumWidth()), ...)}. So a panel asking for 300 was
	 * drawn 300 wide inside a 150 frame, and every field and button in it ran out
	 * past the edge. That is what "почти во всех панелях выходят за рамки" was.
	 *
	 * <h2>The second bug, which was the fix for the first</h2>
	 *
	 * The repair was "the minimum, or half the window, whichever is larger". On a
	 * window of about six hundred logical pixels half the window is roughly what the
	 * wider panels ask for anyway, so nearly every panel came out at the ceiling —
	 * reported as opening too wide, and it was: a panel taking most of the window is
	 * the columns coming back one panel at a time.
	 *
	 * Half the window was never a good default. It is a limit, and a limit makes a
	 * poor opening offer. So a panel opens at exactly what it says it needs and not
	 * a pixel more, which is the narrowest it can be drawn correctly in, and after
	 * that the width is whatever it was last pulled to — see {@link PanelWidths}.
	 */
	private int widthFor(WorkspacePanel panel) {
		int room = Math.max(Rail.PANEL, width - Rail.WIDTH * 2);
		int least = Math.max(Rail.PANEL, panel.minimumWidth());

		// What was chosen by hand beats what was worked out, and only the two hard
		// edges apply to it: never under what the panel needs to draw itself, never
		// over the room there is between the strips.
		int kept = PanelWidths.of(panel.id());
		int wanted = kept > 0 ? kept : least;
		return Math.min(room, Math.max(least, wanted));
	}

	private void summonHere(String id) {
		WorkspacePanel panel = Panels.make(id);
		if (panel == null) return;

		int wide = widthFor(panel);
		boolean right = !Rail.left().contains(id);
		int left = right ? width - Rail.WIDTH - wide : Rail.WIDTH;
		dock.floatAt(panel, left, TOOLBAR, wide, height - TOOLBAR);
	}

	// ------------------------------------------------------------------ input

	/**
	 * Whether the mouse is being ignored because there is nothing on screen to hit.
	 *
	 * <h2>The fault</h2>
	 *
	 * With the panels stood aside nothing is drawn — and everything went on answering.
	 * The strips, the top bar and every panel kept their hit areas, so a click
	 * anywhere near an edge toggled a panel nobody could see, opened a list nobody
	 * could read, or scrolled something invisible.
	 *
	 * That is bad everywhere and worst here, because this mode exists for looking at
	 * the shot and for filming it. The one moment somebody is judging a frame is the
	 * one moment a stray click must not quietly rearrange the window behind it.
	 *
	 * <h2>The camera goes quiet too, and that is a decision rather than a side effect</h2>
	 *
	 * Orbiting used to work here by accident — the press still reached the viewport
	 * even with nothing drawn — while flying did not, because the letters are read
	 * once a frame from inside the drawing and the drawing does not happen. Half a
	 * camera is not worth keeping, and the whole of one does not belong here anyway:
	 * this mode is for judging a frame, and nudging the frame is composing it, which
	 * is what the window with the panels in it is for.
	 *
	 * Keys are untouched: escape and H are the way back, and a way back that could be
	 * hidden along with everything else would be no way back at all.
	 */
	private boolean deaf() {
		return clean;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (deaf()) return true;
		if (clickLanding(event.x(), event.y())) return true;
		if (clickMenu(event.x(), event.y())) return true;

		// The edges before the dock, because they are drawn over it. A press that
		// reached the world through a strip would move the camera behind the button
		// somebody was pressing.
		String rail = railAt(event.x(), event.y());
		if (rail != null) {
			toggleRail(rail);
			return true;
		}
		if (event.x() < Rail.WIDTH || event.x() >= width - Rail.WIDTH) {
			// The empty part of a strip. Swallowed rather than passed through for the
			// same reason: it is a surface, not a hole.
			if (event.y() >= TOOLBAR) return true;
		}

		if (event.y() < TOOLBAR) {
			if (event.x() >= panelsLeft() && event.x() < panelsLeft() + TOOL) openPanelsMenu();
			if (event.x() >= playerLeft() && event.x() < playerLeft() + TOOL) {
				Workspace.showPlayer(!Workspace.showPlayer());
			}
			// A toggle, not a one-way switch. It set `clean = true` and nothing set it
			// back, and with the panels gone there is no button on screen to press —
			// so pressing it looked exactly like the workspace breaking. Escape and H
			// always brought it back, but a way out nobody was told about is not a way
			// out. Now the same button returns, and while it is on there is a line
			// saying so.
			if (event.x() >= bareLeft() && event.x() < bareLeft() + TOOL) clean = !clean;
			if (event.x() >= benchLeft() && event.x() < benchLeft() + TOOL) toggleGraph();
			if (event.x() >= landingLeft() && event.x() < landingLeft() + TOOL) openLandingMenu();
			return true;
		}
		return dock.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		// Not gated on deaf(). A button pressed before the panels stood aside has to be
		// told it came up, or whatever took that press is left holding it — a splitter
		// that follows the mouse for ever, which is the fault the dock's own capture
		// note is about.
		return dock.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (deaf()) return true;
		return dock.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (deaf()) return true;
		// The open list first. It is drawn over everything, so the wheel over it
		// belongs to it — turning a panel underneath a menu is the panel answering a
		// gesture aimed at something covering it.
		if (menu.scrolled(amountY) || landings.scrolled(amountY)) return true;
		return dock.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (deaf()) return;
		dock.mouseMoved(mouseX, mouseY);
	}

	private static final int GLFW_ESCAPE = 256;
	private static final int GLFW_SPACE = 32;
	private static final int GLFW_Z = 90;
	private static final int GLFW_H = 72;
	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Escape means "get me out of this", and the innermost this comes first: a
		// tab being carried, then an open menu, then a panel filling the screen,
		// and only when none of those is happening does it mean close the
		// workspace. Straight to closing was how a stuck layout became a lost one.
		if (event.key() == GLFW_ESCAPE) {
			// Out of the bare shot first, and before anything else. With the panels
			// gone there is nothing on the screen to press, so this is the only way
			// back — and a mode you can enter and not leave is a trap however good
			// the picture is. A running capture is stopped by the same key for the
			// same reason.
			// A running export first, wherever it is running. It used to be reachable
			// only from the bare view, because a capture always hid the panels; now the
			// film is the viewport and the panels stay up, so escape has to reach it
			// from the ordinary workspace as well.
			if (com.mopicmp.npcstudio.client.scene.Capture.running()) {
				com.mopicmp.npcstudio.client.scene.Capture.stop();
				return true;
			}
			if (clean) {
				clean = false;
				return true;
			}
			if (landings.isOpen()) {
				landings.close();
				return true;
			}
			// The name box, before the dock rather than through it. The dock offers
			// escape to the focused panel only, and the box is drawn by the viewport
			// while somebody may well have clicked into a panel beside it — which would
			// leave a box on the screen with no key that reaches it.
			if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) {
				com.mopicmp.npcstudio.client.map.Naming.cancel();
				return true;
			}
			if (dock.escape()) return true;
			if (menu.isOpen()) {
				menu.close();
				return true;
			}
		}
		// After the panels have had it, so a box being typed into keeps its keys, and
		// before the screen's own, which does nothing with this one.
		//
		// Ctrl and space, which is what Blender uses for the same act, because the
		// gesture is worth learning once rather than per program. It exists at all
		// because until now the only way to give a panel the window was the third of
		// three ten-pixel buttons in an eighteen-pixel bar — that is, to escape a
		// cramped window you first had to hit the smallest target in it.
		// Undo before the panels, unlike the maximise below it, and the difference is
		// deliberate. Ctrl+Z inside a text box means "take back what I typed", which
		// no box here implements, so letting a box swallow it would make the key
		// silently do nothing depending on where the cursor last was.
		if (event.key() == GLFW_Z && (event.modifiers() & GLFW_CONTROL) != 0) {
			if ((event.modifiers() & GLFW_SHIFT) != 0) {
				com.mopicmp.npcstudio.client.edit.History.redo();
			} else {
				com.mopicmp.npcstudio.client.edit.History.undo();
			}
			return true;
		}

		if (event.key() == GLFW_SPACE && (event.modifiers() & GLFW_CONTROL) != 0) {
			Dock.Leaf leaf = dock.focusedLeaf();
			if (leaf != null) {
				dock.maximize(leaf);
				return true;
			}
		}
		if (dock.keyPressed(event)) return true;

		// H is the fastest thing in the window: everything the workspace is for is
		// judged by looking at the world without it, and until now that took a trip to
		// a button in the top strip and escape to come back.
		//
		// Being after the panels is not enough to keep it out of somebody's sentence,
		// which is what this comment used to claim. A text box declines a plain letter
		// — the letter arrives separately, as a character, not as a key — so the box
		// says "not mine", the workspace takes that as nobody's, and the panels
		// disappear halfway through a line of dialogue while the H lands in the field
		// behind them. The question has to be asked the other way round: not whether
		// anybody took the key, but whether anybody is expecting letters.
		if (event.key() == GLFW_H && !lettersAreSpokenFor()) {
			clean = !clean;
			return true;
		}
		return super.keyPressed(event);
	}

	/**
	 * Whether a plain letter is somebody's word rather than a shortcut.
	 *
	 * Two places can be taking letters: a field in a panel, and the box for naming a
	 * place, which is drawn over the world and is not a widget at all — so it has to
	 * be asked separately, exactly as escape asks it separately a few lines above.
	 */
	private boolean lettersAreSpokenFor() {
		return com.mopicmp.npcstudio.client.map.Naming.isOpen() || dock.typing();
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		return dock.keyReleased(event) || super.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return dock.charTyped(event) || super.charTyped(event);
	}

	@Override
	public void tick() {
		WorkspaceCamera.tick();
		dock.tick();
	}

	@Override
	public void removed() {
		if (open == this) open = null;
		Workspace.lit(false);
		// A half-typed name goes with the window it was being typed in. It is held
		// statically because two different screens draw it, so leaving it set would
		// mean the next ring opened in ordinary play arrived with somebody's abandoned
		// word already in the box.
		com.mopicmp.npcstudio.client.map.Naming.cancel();
		WorkspaceCamera.release();
		// And the view goes back to the player. Looking through the scene's camera is
		// a thing you do inside the workspace, and the only way out of it is a button
		// in a panel — so leaving it on while the panels go away would be a player
		// stuck seeing through a camera with nothing on screen to switch off.
		com.mopicmp.npcstudio.client.scene.SceneCameras.through(false);
		dock.closed();

		// Written on the way out rather than on every change: a layout is being
		// fiddled with constantly while it is being arranged, and a file rewritten
		// on every drag of a splitter is a file being written a hundred times a
		// second.
		var config = com.mopicmp.npcstudio.client.NpcStudioConfig.get();
		config.workspace = DockLayout.of(dock);
		config.panelWidth = PanelWidths.all();
		config.save();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
