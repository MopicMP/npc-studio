package com.mopicmp.npcstudio.client.model;

import com.mopicmp.npcstudio.client.model.panel.ModelsPanel;
import com.mopicmp.npcstudio.client.model.panel.PlacedPanel;
import com.mopicmp.npcstudio.client.model.panel.NumbersPanel;
import com.mopicmp.npcstudio.client.model.panel.TreePanel;
import com.mopicmp.npcstudio.client.workspace.Dock;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.WorkspaceCamera;
import com.mopicmp.npcstudio.client.model.panel.SceneViewPanel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The modelling mode: its own key, its own panels.
 *
 * <h2>Why it is not a tab of the workspace</h2>
 *
 * Because it was asked for that way, and the reason holds: the two are
 * different jobs done at different times. Building a mast has nothing to say
 * about a character's walking animation, and a panel set that carries both is a
 * panel set where half of everything is always beside the point. Axiom draws the
 * same line — its editor is a mode you enter, not a window you open.
 *
 * What they do share is the dock. Splitting, dragging, folding and floating are
 * the same problem in both, and a second implementation of it would be a second
 * set of the same bugs.
 */
public class ModellingScreen extends Screen {

	private static final int TOOLBAR = 18;

	private static final int BAR = 0xFF12161C;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	/** Kept past the life of the screen, exactly as the workspace keeps its own. */
	private static Dock dock;

	private static ModellingScreen open;

	public static void show(Minecraft client) {
		if (dock == null) dock = new Dock(defaultLayout());
		ModellingScreen screen = new ModellingScreen();
		open = screen;
		client.setScreenAndShow(screen);
	}

	public static boolean showing() {
		return open != null;
	}

	/**
	 * The screen on display, for a picker that has to come back to it.
	 *
	 * The game does not hand out what it is showing in this version, and the one
	 * thing that reliably knows is the screen itself.
	 */
	public static ModellingScreen current() {
		return open;
	}

	/**
	 * Models on the left, the tree beside them, the view in the middle, the
	 * numbers on the right.
	 *
	 * Left to right in the order the work happens: pick a model, pick a part of
	 * it, look at it, change its numbers.
	 */
	private static Dock.Node defaultLayout() {
		return new Dock.Split(false, 0.16f,
			new Dock.Leaf(new ModelsPanel(), new PlacedPanel(), new TreePanel()),
			new Dock.Split(false, 0.74f,
				new Dock.Leaf(new SceneViewPanel()),
				new Dock.Leaf(new NumbersPanel())));
	}

	private ModellingScreen() {
		super(Component.translatable("npc_studio.model.title"));
	}

	/**
	 * The Add menu, which is the whole of how anything is made.
	 *
	 * Blender's arrangement, because it is the one everybody already knows and
	 * because the alternative was tried: a name to type, a create button, a place
	 * button and an add button in a fourth panel, in an order nobody guessed. One
	 * menu, one line each, and every line ends with something visible.
	 */
	private record AddItem(String key, Icon icon, Runnable act) { }

	/**
	 * What can be made. Only that.
	 *
	 * A box, three shapes built out of boxes, and a folder to put them in. Mirroring
	 * was here for one round and read as out of place, which it was: this is the menu
	 * of things that did not exist a moment ago, and turning a thing round is not one
	 * of them. It is on control and the axis keys, beside the other keys that act on
	 * what is already there.
	 *
	 * What the document calls a bone is what this calls a folder: a bone is a thing
	 * you have to have an opinion about, and being asked about animation while
	 * building a mast is being asked a question that has nothing to do with the mast.
	 */
	private static final String OAK = "minecraft:oak_planks";

	private static final java.util.List<AddItem> ADD = java.util.List.of(
		new AddItem("npc_studio.model.add_cube", Icon.SOLID, Modelling::addCube),
		new AddItem("npc_studio.model.add_ring", Icon.TOOL_ROTATE, () ->
			Modelling.addShape("ring", com.mopicmp.npcstudio.model.Primitives.ring(8, 2, OAK))),
		new AddItem("npc_studio.model.add_sphere", Icon.TOOL_SCALE, () ->
			Modelling.addShape("sphere", com.mopicmp.npcstudio.model.Primitives.sphere(8, OAK))),
		new AddItem("npc_studio.model.add_cone", Icon.TOOL_MOVE, () ->
			Modelling.addShape("cone", com.mopicmp.npcstudio.model.Primitives.cone(8, 16, OAK))),
		new AddItem("npc_studio.model.add_bone", Icon.FOLDER, Modelling::addBone));

	private boolean addOpen;

	private static final int MENU_ROW = 18;
	private static final int MENU_WIDTH = 132;

	@Override
	protected void init() {
		// Here rather than only at show(), because opening the block picker on top
		// of this screen ends it and coming back starts it again — and the second
		// time nobody calls show(). Without this the screen would be on display
		// while claiming not to be.
		open = this;
		if (!WorkspaceCamera.active()) WorkspaceCamera.take(false);
		Modelling.lightPlaced(true);
	}

	/** Nothing: the world behind is the point, the same as in the workspace. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		dock.layout(0, TOOLBAR, width, height - TOOLBAR);
		drawToolbar(graphics);
		dock.render(graphics, mouseX, mouseY, delta);

		if (addOpen) {
			graphics.nextStratum();
			drawAddMenu(graphics, mouseX, mouseY);
		}
	}

	private void drawToolbar(GuiGraphicsExtractor graphics) {
		graphics.fill(0, 0, width, TOOLBAR, BAR);
		graphics.fill(0, TOOLBAR - 1, width, TOOLBAR, EDGE);
		graphics.text(font, title, 6, 5, TEXT);

		int at = addLeft();
		boolean hovered = addOpen;
		Component add = Component.translatable("npc_studio.model.add");
		if (hovered) graphics.fill(at, 0, at + addWidth(), TOOLBAR - 1, 0xFF232A34);
		Icon.ADD.draw(graphics, at + 3, (TOOLBAR - Icon.SIZE) / 2, hovered ? ACCENT : TEXT);
		graphics.text(font, add, at + 3 + Icon.SIZE + 3, 5, hovered ? ACCENT : TEXT);

		// What is open. No star and no save button: it saves itself, and a mark
		// meaning "you might lose this" is a mark about a thing that cannot happen.
		String what = Modelling.open()
			? Modelling.name()
			: Component.translatable("npc_studio.model.nothing_open").getString();
		graphics.text(font, Component.literal(what), at + addWidth() + 10, 5, TEXT_DIM);

		drawSettings(graphics);
	}

	// ------------------------------------------------------- the right-hand end

	/**
	 * Two settings, at the far end of the bar.
	 *
	 * Here rather than in a panel because neither belongs to a model: how big the
	 * handles are drawn is about this pair of eyes and this screen, and whether
	 * objects are solid is about the world being built in. A setting that follows
	 * the document into a file is a setting that has to be right for everybody who
	 * opens it, and these two never will be.
	 */
	private static final int SETTING = 14;

	private int bodyLeft() {
		return width - SETTING - 6;
	}

	private int solidLeft() {
		return bodyLeft() - SETTING - 4;
	}

	private int axesLeft() {
		return solidLeft() - SETTING - 4;
	}

	private int sizeLeft() {
		return axesLeft() - 4 - (SETTING * 2 + 26);
	}

	private void drawSettings(GuiGraphicsExtractor graphics) {
		int left = sizeLeft();
		int middle = (TOOLBAR - SETTING) / 2;

		// The body, which is hidden by default here. The camera is detached to fly
		// about, a detached camera is a third-person one, and the game duly draws the
		// person building the thing standing in the middle of it. The button exists
		// because "where am I actually standing" is a real question with no other
		// answer once the body is gone.
		boolean shown = com.mopicmp.npcstudio.client.workspace.Workspace.showPlayer();
		int body = bodyLeft();
		graphics.fill(body, middle, body + SETTING, middle + SETTING,
			shown ? 0xFF1B3A4A : 0xFF232A34);
		(shown ? Icon.PLAYER_ON : Icon.PLAYER_OFF)
			.draw(graphics, body - 1, (TOOLBAR - Icon.SIZE) / 2, shown ? ACCENT : TEXT_DIM);

		Icon.HANDLES.draw(graphics, left, (TOOLBAR - Icon.SIZE) / 2, TEXT_DIM);
		graphics.text(font, Component.literal("−"), left + SETTING + 4, 5, TEXT);
		graphics.text(font, Component.literal("%.0f%%".formatted(Gizmo.sizing() * 100)),
			left + SETTING + 12, 5, TEXT_DIM);
		graphics.text(font, Component.literal("+"), left + SETTING + 38, 5, TEXT);

		// World directions or the box's own. Dimmed when the tool that is armed cannot
		// use it: a face of a turned box does not face along a world axis, and an
		// angle written as three numbers is turned in its own frame by definition.
		boolean square = Gizmo.world();
		boolean matters = Gizmo.worldMatters();
		int axes = axesLeft();
		graphics.fill(axes, middle, axes + SETTING, middle + SETTING,
			square && matters ? 0xFF1B3A4A : 0xFF232A34);
		Icon.FOCUS.draw(graphics, axes - 1, (TOOLBAR - Icon.SIZE) / 2,
			!matters ? 0xFF4A5560 : square ? ACCENT : TEXT_DIM);

		boolean solid = Modelling.solid();
		int at = solidLeft();
		graphics.fill(at, middle, at + SETTING, middle + SETTING, solid ? 0xFF1B3A4A : 0xFF232A34);
		Icon.SOLID.draw(graphics, at - 1, (TOOLBAR - Icon.SIZE) / 2, solid ? ACCENT : TEXT_DIM);
	}

	/** Returns true when the click belonged to one of the two settings. */
	private boolean clickSettings(double px, double py) {
		if (py >= TOOLBAR) return false;

		if (px >= bodyLeft() && px < bodyLeft() + SETTING) {
			com.mopicmp.npcstudio.client.workspace.Workspace.showPlayer(
				!com.mopicmp.npcstudio.client.workspace.Workspace.showPlayer());
			return true;
		}
		if (px >= axesLeft() && px < axesLeft() + SETTING) {
			Gizmo.world(!Gizmo.world());
			return true;
		}
		if (px >= solidLeft() && px < solidLeft() + SETTING) {
			Modelling.solid(!Modelling.solid());
			return true;
		}
		int left = sizeLeft();
		if (px >= left + SETTING + 2 && px < left + SETTING + 12) {
			Gizmo.sizing(Gizmo.sizing() - 0.25f);
			return true;
		}
		if (px >= left + SETTING + 34 && px < left + SETTING + 46) {
			Gizmo.sizing(Gizmo.sizing() + 0.25f);
			return true;
		}
		return false;
	}

	private int addLeft() {
		return 10 + font.width(title);
	}

	private int addWidth() {
		return Icon.SIZE + font.width(Component.translatable("npc_studio.model.add")) + 12;
	}

	private void drawAddMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = addLeft();
		int bottom = TOOLBAR + ADD.size() * MENU_ROW;
		graphics.fill(left - 1, TOOLBAR - 1, left + MENU_WIDTH + 1, bottom + 1, EDGE);
		graphics.fill(left, TOOLBAR, left + MENU_WIDTH, bottom, 0xFF161A20);
		for (int i = 0; i < ADD.size(); i++) {
			int top = TOOLBAR + i * MENU_ROW;
			boolean over = mouseX >= left && mouseX < left + MENU_WIDTH
				&& mouseY >= top && mouseY < top + MENU_ROW;
			if (over) graphics.fill(left, top, left + MENU_WIDTH, top + MENU_ROW, 0xFF232A34);
			ADD.get(i).icon().draw(graphics, left + 3, top + (MENU_ROW - Icon.SIZE) / 2,
				over ? ACCENT : TEXT_DIM);
			graphics.text(font, Component.translatable(ADD.get(i).key()),
				left + 6 + Icon.SIZE, top + (MENU_ROW - 8) / 2, over ? TEXT : TEXT_DIM);
		}
	}

	/** Returns true when the click was the menu's, open or closing. */
	private boolean clickAddMenu(double px, double py) {
		if (!addOpen) return false;
		int left = addLeft();
		int row = (int) ((py - TOOLBAR) / MENU_ROW);
		boolean inside = px >= left && px < left + MENU_WIDTH
			&& py >= TOOLBAR && row >= 0 && row < ADD.size();
		addOpen = false;
		if (inside) ADD.get(row).act().run();
		return true;
	}

	// ------------------------------------------------------------------ input

	private static final int KEY_ESCAPE = 256;
	private static final int KEY_C = 67;
	private static final int KEY_D = 68;
	private static final int KEY_S = 83;
	private static final int KEY_V = 86;
	private static final int KEY_Y = 89;
	private static final int KEY_Z = 90;
	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == KEY_ESCAPE && addOpen) {
			addOpen = false;
			return true;
		}
		if (event.key() == KEY_ESCAPE && dock.escape()) return true;

		// The keys every editor has, caught here rather than in a panel so that they
		// work wherever the keyboard happens to be pointing. Somebody who has just
		// dragged a box and wants it back does not first click on the right panel.
		if ((event.modifiers() & GLFW_CONTROL) != 0) {
			switch (event.key()) {
				// Control and S, because a model that took an hour should not depend
				// on somebody finding a button.
				case KEY_S -> { Modelling.save(); return true; }
				case KEY_Z -> {
					// Shift turns it round, as it does everywhere, and Y as well for
					// the half of the world that reaches for that one.
					if ((event.modifiers() & GLFW_SHIFT) != 0) Modelling.redo();
					else Modelling.undo();
					return true;
				}
				case KEY_Y -> { Modelling.redo(); return true; }
				case KEY_C -> { Modelling.copy(); return true; }
				case KEY_V -> { Modelling.paste(); return true; }
				case KEY_D -> { Modelling.duplicate(); return true; }
				default -> { }
			}
		}
		if (dock.keyPressed(event)) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickAddMenu(event.x(), event.y())) return true;
		if (clickSettings(event.x(), event.y())) return true;
		if (event.y() < TOOLBAR) {
			int left = addLeft();
			if (event.x() >= left && event.x() < left + addWidth()) addOpen = true;
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

	@Override
	public boolean keyReleased(KeyEvent event) {
		return dock.keyReleased(event) || super.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return dock.charTyped(event) || super.charTyped(event);
	}

	/** Ticks since the last edit, for a save that waits until the typing stops. */
	private int settling;

	@Override
	public void tick() {
		WorkspaceCamera.tick();
		dock.tick();

		// Saves itself, a second after the last change. Writing on every keystroke
		// would be a file written twenty times a second while somebody drags a
		// number; waiting for a button is how work gets lost.
		if (Modelling.dirty()) {
			settling++;
			if (settling > 20) {
				Modelling.save();
				// The same moment tells the server how big the thing has become. It
				// is the same question — "the editing has stopped, write it down" —
				// asked of the disk and of the world.
				Modelling.tellShape();
				settling = 0;
			}
		} else {
			settling = 0;
		}

		// And again on a slow beat, because not everything that changes the size of
		// a placed object goes through the document: putting one down is a round
		// trip to the server, so the entity whose size has to be set does not exist
		// yet at the moment the placing is asked for.
		if (++beat >= 20) {
			beat = 0;
			Modelling.tellShape();
		}
	}

	/** Ticks since the size of placed objects was last sent. */
	private int beat;

	@Override
	public void removed() {
		// Written on the way out as well, so closing never costs anything.
		Modelling.save();
		if (open == this) open = null;
		Modelling.lightPlaced(false);
		WorkspaceCamera.release();
		dock.closed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
