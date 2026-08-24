package com.mopicmp.npcstudio.client.workspace;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.workspace.panel.AnimationPanel;
import com.mopicmp.npcstudio.client.workspace.panel.AssetsPanel;
import com.mopicmp.npcstudio.client.workspace.panel.BodyPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CameraPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CaptionPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CharacterPanel;
import com.mopicmp.npcstudio.client.workspace.panel.EnvironmentPanel;
import com.mopicmp.npcstudio.client.workspace.panel.GraphPanel;
import com.mopicmp.npcstudio.client.workspace.panel.MusicPanel;
import com.mopicmp.npcstudio.client.workspace.panel.ScenePanel;
import com.mopicmp.npcstudio.client.workspace.panel.TimelinePanel;
import com.mopicmp.npcstudio.client.workspace.panel.ViewportPanel;
import com.mopicmp.npcstudio.client.workspace.panel.WardrobePanel;

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

	private List<Component> menu = List.of();
	private List<String> menuIds = List.of();
	private int menuX;
	private int menuY;

	public static void show(Minecraft client) {
		if (dock == null) {
			dock = DockLayout.read(com.mopicmp.npcstudio.client.NpcStudioConfig.get().workspace);
		}
		if (dock == null) dock = new Dock(defaultLayout());
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

	/** Brings a panel into view, making it if it is not in the layout at all. */
	public static void reveal(String id) {
		if (dock == null) return;
		if (dock.reveal(id)) return;
		WorkspacePanel made = Panels.make(id);
		if (made != null) dock.add(made, Panels.homeOf(id));
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

	/**
	 * Where the panels start out.
	 *
	 * Left column and right column run the full height; the viewport and the
	 * graph share the middle, one above the other. So the right-hand column
	 * stands beside the bottom strip rather than on top of it — which is how it
	 * was asked for, and it is also the arrangement that keeps a character's
	 * settings visible while its timing is being changed.
	 */
	private static Dock.Node defaultLayout() {
		Dock.Leaf left = new Dock.Leaf(new AssetsPanel(), new ScenePanel());
		Dock.Leaf right = new Dock.Leaf(new CharacterPanel(), new AnimationPanel(),
			new WardrobePanel(), new BodyPanel(), new CameraPanel(), new CaptionPanel(),
			new MusicPanel(), new EnvironmentPanel());

		// The graph over the timeline rather than beside it, and never as two tabs
		// of the same slot: what a node does and when it happens are one question,
		// and clicking between them would mean holding half the answer in the head.
		Dock.Node middle = new Dock.Split(true, 0.58f,
			new Dock.Leaf(new ViewportPanel()),
			new Dock.Split(true, 0.62f,
				new Dock.Leaf(new GraphPanel()),
				new Dock.Leaf(new TimelinePanel())));

		return new Dock.Split(false, 0.18f, left,
			new Dock.Split(false, 0.76f, middle, right));
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
		if (clean) return;

		dock.layout(0, TOOLBAR, width, height - TOOLBAR);
		drawToolbar(graphics, mouseX, mouseY);
		dock.render(graphics, mouseX, mouseY, delta);

		if (!menu.isEmpty()) {
			graphics.nextStratum();
			drawMenu(graphics, mouseX, mouseY);
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
		drawTool(graphics, bareLeft(), Icon.BARE, mouseX, mouseY, false);

		if (dock.maximized() != null) {
			Component hint = Component.translatable("npc_studio.workspace.maximized");
			graphics.text(font, hint, width - 6 - font.width(hint), 5, ACCENT);
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

	// ------------------------------------------------------------- the menu

	private static final int MENU_ROW = 18;
	private static final int MENU_WIDTH = 132;

	/**
	 * Every panel there is, with the ones already showing marked.
	 *
	 * A dropdown rather than a row of buttons: the list is going to keep growing
	 * as the mod does, and a row that grows is a row that eventually reaches the
	 * other side of the display.
	 */
	private void openPanelsMenu() {
		List<Component> labels = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for (String id : Panels.known()) {
			labels.add(Component.translatable("npc_studio.panel." + id));
			ids.add(id);
		}
		// Last, and separated by being last: the way out of any arrangement that
		// has gone wrong. Panels can be closed, dragged into each other and stacked
		// until nothing is where it was, and until this existed the only way back
		// was to work out what had been done and undo it by hand.
		labels.add(Component.translatable("npc_studio.workspace.reset"));
		ids.add(RESET);

		menu = labels;
		menuIds = ids;
		menuX = panelsLeft();
		menuY = TOOLBAR;
	}

	/** Not a panel id, and cannot collide with one: no panel is called this. */
	private static final String RESET = "";

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int bottom = menuY + menu.size() * MENU_ROW;
		graphics.fill(menuX - 1, menuY - 1, menuX + MENU_WIDTH + 1, bottom + 1, EDGE);
		graphics.fill(menuX, menuY, menuX + MENU_WIDTH, bottom, PANEL);
		for (int i = 0; i < menu.size(); i++) {
			int top = menuY + i * MENU_ROW;
			boolean hovered = mouseX >= menuX && mouseX < menuX + MENU_WIDTH
				&& mouseY >= top && mouseY < top + MENU_ROW;
			if (hovered) graphics.fill(menuX, top, menuX + MENU_WIDTH, top + MENU_ROW, HOVER);
			boolean showing = showing(menuIds.get(i));
			Icon icon = RESET.equals(menuIds.get(i)) ? Icon.RESET : Dock.iconOf(menuIds.get(i));
			icon.draw(graphics, menuX + 2, top + (MENU_ROW - Icon.SIZE) / 2,
				showing ? ACCENT : TEXT_DIM);
			graphics.text(font, menu.get(i), menuX + 4 + Icon.SIZE, top + (MENU_ROW - 8) / 2,
				hovered ? TEXT : TEXT_DIM);
		}
	}

	private boolean showing(String id) {
		for (WorkspacePanel panel : dock.panels()) {
			if (panel.id().equals(id)) return true;
		}
		return false;
	}

	private boolean clickMenu(double px, double py) {
		if (menu.isEmpty()) return false;
		int row = (int) ((py - menuY) / MENU_ROW);
		boolean inside = px >= menuX && px < menuX + MENU_WIDTH && row >= 0 && row < menu.size();
		List<String> ids = menuIds;
		menu = List.of();
		menuIds = List.of();
		if (!inside) return true;

		String id = ids.get(row);
		if (RESET.equals(id)) {
			dock.closed();
			dock = new Dock(defaultLayout());
			return true;
		}
		if (dock.reveal(id)) return true;
		WorkspacePanel made = Panels.make(id);
		if (made != null) dock.add(made, Panels.homeOf(id));
		return true;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickMenu(event.x(), event.y())) return true;

		if (event.y() < TOOLBAR) {
			if (event.x() >= panelsLeft() && event.x() < panelsLeft() + TOOL) openPanelsMenu();
			if (event.x() >= playerLeft() && event.x() < playerLeft() + TOOL) {
				Workspace.showPlayer(!Workspace.showPlayer());
			}
			if (event.x() >= bareLeft() && event.x() < bareLeft() + TOOL) clean = true;
			return true;
		}
		return dock.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return dock.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return dock.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		return dock.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		dock.mouseMoved(mouseX, mouseY);
	}

	private static final int GLFW_ESCAPE = 256;

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
			if (dock.escape()) return true;
			if (!menu.isEmpty()) {
				menu = List.of();
				menuIds = List.of();
				return true;
			}
		}
		if (dock.keyPressed(event)) return true;
		return super.keyPressed(event);
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
		config.save();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
