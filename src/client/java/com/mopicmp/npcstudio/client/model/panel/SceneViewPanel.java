package com.mopicmp.npcstudio.client.model.panel;

import com.mopicmp.npcstudio.client.model.Gizmo;
import com.mopicmp.npcstudio.client.model.ModelPicking;
import com.mopicmp.npcstudio.client.model.ModelStore;
import com.mopicmp.npcstudio.client.model.Modelling;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.WorkspaceCamera;
import com.mopicmp.npcstudio.client.workspace.panel.ViewportPanel;
import com.mopicmp.npcstudio.entity.ModelObject;
import com.mopicmp.npcstudio.model.Cube;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The world, while a model is being built.
 *
 * <h2>Why it is not just the workspace's viewport</h2>
 *
 * Because that one is about characters. It selects whatever it is pointed at
 * and the workspace's panels follow — which in this mode meant the modelling
 * window quietly took hold of an NPC, framed the camera on it and showed its
 * settings elsewhere. Nothing here has anything to say about a character.
 *
 * So this one picks placed models and nothing else, and clicking one opens it
 * for editing: an object in the world and the document behind it are the same
 * thing seen from two sides, and that is the shortest way to say so.
 *
 * <h2>The one strip of buttons on the work</h2>
 *
 * The plain viewport keeps nothing clickable over the world, on the grounds that
 * things to click belong in panels where they are not in the way. Three tool
 * buttons are the exception, and they earn it: which tool is armed changes what
 * every drag in this panel does, so it is the one piece of state that has to be
 * visible from inside the work rather than found beside it. They are also how
 * anybody learns the keys, which is the other half of the job.
 */
public class SceneViewPanel extends ViewportPanel {

	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int TOOL_FILL = 0x99101418;
	private static final int TOOL_ON = 0xCC1B3A4A;

	/** One tool button, and the gap between them. */
	private static final int CELL = 20;
	private static final int EDGE = 4;

	private static final int GLFW_LEFT = 0;
	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	/**
	 * The tool keys, and why they are numbers.
	 *
	 * G, R and S would be the letters every modelling program agrees on, and they
	 * are not available: the camera flies on the letter keys the whole time this
	 * panel has the keyboard, so S would both fly backwards and arm the resize tool.
	 * Between a familiar letter and a camera you have to hold a mouse button to
	 * steer, the camera wins — it is used every few seconds and the tool is switched
	 * every few minutes.
	 */
	private static final int KEY_1 = 49;
	private static final int KEY_2 = 50;
	private static final int KEY_3 = 51;
	private static final int KEY_DELETE = 261;

	/**
	 * How coarsely a drag lands, in model pixels, and how fine control makes it.
	 *
	 * A whole pixel by default because every number in a Minecraft model is
	 * sixteenths of a block and boxes that are meant to meet have to actually meet;
	 * a drag that lands on 3.0417 is a seam. Control is there for the times that
	 * rule is wrong, and it is a modifier rather than a setting because it is
	 * wanted for one drag at a time.
	 */
	private static final float STEP = 1f;
	private static final float FINE = 0.25f;
	private static final float FINEST = 0.0625f;

	/** The same three for angles. */
	private static final float TURN = 5f;
	private static final float TURN_FINE = 1f;
	private static final float TURN_FINEST = 0.25f;

	@Override
	public String id() {
		return "scene_view";
	}

	/** Not while a handle is being pulled. */
	@Override
	protected boolean flyable() {
		return Gizmo.grip() == null;
	}

	/**
	 * The modifiers as they are now, rather than as they were when the button went down.
	 *
	 * A mouse event carries the modifiers of the press it came from, and a drag is a
	 * long series of events all carrying the same ones. Reading them from there meant
	 * the step could only be chosen before starting — reach for shift halfway through
	 * and nothing happened, so the drag had to be let go of and begun again. The
	 * keyboard is asked directly, exactly as flying asks it, and for the same reason:
	 * a screen is open and the game's bindings are all released.
	 */
	private static int modifiersNow() {
		var window = net.minecraft.client.Minecraft.getInstance().getWindow();
		int held = 0;
		if (down(window, 340) || down(window, 344)) held |= GLFW_SHIFT;
		if (down(window, 341) || down(window, 345)) held |= GLFW_CONTROL;
		return held;
	}

	private static boolean down(com.mojang.blaze3d.platform.Window window, int key) {
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, key);
	}

	/**
	 * Only placed models answer here.
	 *
	 * A character standing in the way of a mast should not become the thing the
	 * window is about, and it used to.
	 */
	@Override
	protected boolean pickable(Entity entity) {
		return entity instanceof ModelObject;
	}

	@Override
	protected void chose(Entity picked) {
		if (!(picked instanceof ModelObject object)) return;
		if (ModelStore.get(object.model()) == null) return;
		Modelling.openModel(object.model());
	}

	// -------------------------------------------------------------- the tools

	private static final Gizmo.Tool[] TOOLS = {
		Gizmo.Tool.MOVE, Gizmo.Tool.ROTATE, Gizmo.Tool.RESIZE };

	private static Icon iconOf(Gizmo.Tool tool) {
		return switch (tool) {
			case MOVE -> Icon.TOOL_MOVE;
			case ROTATE -> Icon.TOOL_ROTATE;
			case RESIZE -> Icon.TOOL_SCALE;
		};
	}

	/** Which button is at a point, or -1. */
	private int toolAt(double x, double y) {
		if (y < EDGE || y >= EDGE + CELL) return -1;
		int index = (int) ((x - EDGE) / (CELL + 2));
		if (index < 0 || index >= TOOLS.length) return -1;
		int left = EDGE + index * (CELL + 2);
		return x >= left && x < left + CELL ? index : -1;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == GLFW_LEFT) {
			int tool = toolAt(event.x(), event.y());
			if (tool >= 0) {
				Gizmo.tool(TOOLS[tool]);
				return true;
			}
			// The mouse is in the panel's own coordinates and the handles are drawn on
			// the display, so the panel's corner has to be added back on.
			if (Modelling.cube() != null
				&& Gizmo.grab(originX() + event.x(), originY() + event.y())) {
				// One pull is one thing to undo. Without this the forty edits a drag
				// makes would be forty presses of undo to put back.
				Modelling.gesture(true);
				return true;
			}
			if (choose(event.x(), event.y(), doubleClick)) return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * Clicking a box in the world, which selects the box and not merely the object.
	 *
	 * Both, in fact: the model it belongs to is opened if it was not already, and
	 * the box under the mouse becomes the one the handles are on. That is the whole
	 * of what "choosing a model in the workspace" has to mean — a selection you can
	 * then do something to, rather than a name that lights up in a list.
	 */
	private boolean choose(double localX, double localY, boolean doubleClick) {
		Vec3[] line = ray(localX, localY);
		if (line == null) return false;

		ModelPicking.Hit hit = ModelPicking.pick(line[0], line[1]);
		if (hit == null) return false;
		if (ModelStore.get(hit.object().model()) == null) return false;

		if (!hit.object().model().equals(Modelling.name())) {
			Modelling.openModel(hit.object().model());
		}
		Gizmo.attach(hit.object());
		Modelling.select(hit.bone(), hit.index());
		if (doubleClick) WorkspaceCamera.frame(hit.object());
		return true;
	}

	/**
	 * The drag, measured from where the button went down rather than step by step.
	 *
	 * Every position is worked out from the box as it was at the press plus the
	 * whole pull, so nothing accumulates: a drag out and back leaves the box where
	 * it started, to the pixel, and the rounding happens once instead of on every
	 * mouse event. Adding up rounded steps is how a box ends up a third of a pixel
	 * off after a long pull.
	 */
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		Gizmo.Grip grip = Gizmo.grip();
		if (grip == null) return super.mouseDragged(event, dragX, dragY);

		double mouseX = originX() + event.x();
		double mouseY = originY() + event.y();
		boolean turning = grip.tool() == Gizmo.Tool.ROTATE;

		float step = step(modifiersNow(), turning);
		float by = turning
			? Math.round(Gizmo.turned(mouseX, mouseY) / step) * step
			: Math.round(grip.pulled(mouseX, mouseY) / step) * step;

		// Every marked box, each worked out from its own state at the press. One drag
		// of one handle is a drag of the selection: four walls are picked out together
		// and moved together, and doing the same drag four times because only the last
		// row clicked answers is what makes picking several pointless.
		java.util.List<Cube> changed = new java.util.ArrayList<>(grip.start().size());
		for (Cube start : grip.start()) {
			changed.add(switch (grip.tool()) {
				case MOVE -> moved(grip, start, by);
				case RESIZE -> resized(grip, start, by);
				case ROTATE -> spun(grip, start, by);
			});
		}
		Modelling.replace(grip.where(), changed);
		return true;
	}

	/**
	 * How coarsely a drag lands, in model pixels or degrees.
	 *
	 * Three steps rather than two, because two were not enough: a whole pixel is
	 * right for laying out a hull and useless for closing a seam, and a quarter is
	 * right for closing a seam and useless for a hair's breadth. Shift is finer and
	 * control is finer still — held together, control wins, because a hand that is
	 * holding both meant the smaller one.
	 */
	private static float step(int modifiers, boolean turning) {
		if ((modifiers & GLFW_CONTROL) != 0) return turning ? TURN_FINEST : FINEST;
		if ((modifiers & GLFW_SHIFT) != 0) return turning ? TURN_FINE : FINE;
		return turning ? TURN : STEP;
	}

	/** The box shifted bodily along the arm. */
	private static Cube moved(Gizmo.Grip grip, Cube start, float by) {
		float along = by * grip.sign();
		return start.movedTo(
			start.fromX() + (grip.axis() == Gizmo.Axis.X ? along : 0),
			start.fromY() + (grip.axis() == Gizmo.Axis.Y ? along : 0),
			start.fromZ() + (grip.axis() == Gizmo.Axis.Z ? along : 0));
	}

	/**
	 * The selection stretched along the arm, from the side the arm does not point at.
	 *
	 * <h2>Why a scale and not a nudge each</h2>
	 *
	 * Because a group has a size of its own. Pushing the same face of every box out
	 * by the same amount makes two hundred bigger bricks, not a bigger cone: the
	 * gaps between them stay exactly as they were, so the thing grows lumpy instead
	 * of growing. What a person dragging the end of a cone means is that the cone
	 * becomes that long — every box moves out from the fixed end in proportion, and
	 * gets longer in proportion.
	 *
	 * <h2>Why one box behaves the same as before</h2>
	 *
	 * Because it is the same arithmetic. One box's far face pushed out by two is one
	 * box scaled about its near face by a factor that puts the far face two further
	 * out — the general form written for many boxes reduces to exactly the old one
	 * when there is one, so there is no second path to keep in step.
	 */
	private static Cube resized(Gizmo.Grip grip, Cube start, float by) {
		float span = grip.span();
		float x0 = start.fromX();
		float y0 = start.fromY();
		float z0 = start.fromZ();
		float x1 = start.toX();
		float y1 = start.toY();
		float z1 = start.toZ();

		// A selection with no thickness along this axis has no proportion to keep, so
		// it is pushed rather than scaled. Dividing by it would send everything to
		// infinity, which is a long way to go for a flat plate.
		if (span < 0.001f) {
			boolean far = grip.sign() > 0;
			switch (grip.axis()) {
				case X -> { if (far) x1 += by; else x0 -= by; }
				case Y -> { if (far) y1 += by; else y0 -= by; }
				case Z -> { if (far) z1 += by; else z0 -= by; }
			}
			return start.corners(x0, y0, z0, x1, y1, z1);
		}

		float anchor = grip.anchor();
		float factor = (span + by) / span;
		switch (grip.axis()) {
			case X -> {
				x0 = anchor + (x0 - anchor) * factor;
				x1 = anchor + (x1 - anchor) * factor;
			}
			case Y -> {
				y0 = anchor + (y0 - anchor) * factor;
				y1 = anchor + (y1 - anchor) * factor;
			}
			case Z -> {
				z0 = anchor + (z0 - anchor) * factor;
				z1 = anchor + (z1 - anchor) * factor;
			}
		}
		return start.corners(x0, y0, z0, x1, y1, z1);
	}

	/** The box turned about the ring's axis, from the angle it started at. */
	private static Cube spun(Gizmo.Grip grip, Cube start, float by) {
		return start.turned(
			start.rotX() + (grip.axis() == Gizmo.Axis.X ? by : 0),
			start.rotY() + (grip.axis() == Gizmo.Axis.Y ? by : 0),
			start.rotZ() + (grip.axis() == Gizmo.Axis.Z ? by : 0));
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean was = Gizmo.grip() != null;
		Gizmo.release();
		Modelling.gesture(false);
		return was || super.mouseReleased(event);
	}

	/**
	 * The keys: one per tool, and delete.
	 *
	 * Delete removes the selected box, here as in the list of parts. It used to take
	 * the whole object out of the world, on the argument that this panel is the
	 * world — a clean argument and the wrong one in practice: what is selected here
	 * is a box, the handles are on that box, and a key that takes away the thing the
	 * handles are on plus everything around it is a key that deletes too much. When
	 * the last box goes the object goes with it, which is the part of the old
	 * behaviour worth keeping.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		// Control and the same three digits mirror along that axis. Beside the tool
		// keys because it is the same three axes, and on control because it acts on
		// what is already there rather than choosing how to act.
		if ((event.modifiers() & GLFW_CONTROL) != 0) {
			switch (event.key()) {
				case KEY_1 -> { Modelling.mirror(0); return true; }
				case KEY_2 -> { Modelling.mirror(1); return true; }
				case KEY_3 -> { Modelling.mirror(2); return true; }
				default -> { }
			}
			return super.keyPressed(event);
		}

		switch (event.key()) {
			case KEY_1 -> { Gizmo.tool(Gizmo.Tool.MOVE); return true; }
			case KEY_2 -> { Gizmo.tool(Gizmo.Tool.ROTATE); return true; }
			case KEY_3 -> { Gizmo.tool(Gizmo.Tool.RESIZE); return true; }
			case KEY_DELETE -> { return remove(); }
			default -> { return super.keyPressed(event); }
		}
	}

	private boolean remove() {
		if (!Modelling.open()) return false;
		Modelling.removeSelected();
		return true;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Explicitly, because this panel replaces the viewport's own drawing rather
		// than adding to it, and flying is done in there. Left out, the letter keys
		// did nothing at all in this window — which is exactly what was reported, and
		// it applied to the right-button flying too, not only to the new free WASD.
		steer();

		// Which handle the mouse is on, so the renderer can light it. Worked out here
		// because this is the only place that is told where the mouse is; the world
		// is drawn before any of this, so the highlight follows a frame behind, which
		// is not something an eye can see.
		Gizmo.hover(inside(mouseX, mouseY) && toolAt(mouseX, mouseY) < 0
			? Gizmo.grabbed(originX() + mouseX, originY() + mouseY)
			: null);

		tools(graphics, mouseX, mouseY);

		String what = Modelling.open()
			? Modelling.name() + " — " + Modelling.model().cubeCount()
			: Component.translatable("npc_studio.model.nothing_open").getString();
		graphics.text(font, Component.literal(what), 6, height - 22,
			Modelling.open() ? ACCENT : TEXT_DIM);

		// Said on screen rather than left to be discovered. Keys that are not written
		// anywhere are keys nobody has.
		graphics.text(font, Component.translatable("npc_studio.model.handles"),
			6, height - 12, TEXT_DIM);
	}

	/**
	 * The handles, over everything this panel has drawn and over the world behind it.
	 *
	 * Here rather than in {@link #draw}, because what is drawn here is on top of the
	 * panel's own widgets as well — a gizmo behind the tool strip would be a gizmo
	 * you cannot use in the top-left corner of the view.
	 */
	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		com.mopicmp.npcstudio.client.model.Handles.draw(graphics, originX(), originY());
	}

	private void tools(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int over = toolAt(mouseX, mouseY);
		for (int index = 0; index < TOOLS.length; index++) {
			Gizmo.Tool tool = TOOLS[index];
			boolean on = Gizmo.tool() == tool;
			int left = EDGE + index * (CELL + 2);

			graphics.fill(left, EDGE, left + CELL, EDGE + CELL, on ? TOOL_ON : TOOL_FILL);
			iconOf(tool).draw(graphics, left + (CELL - Icon.SIZE) / 2, EDGE + (CELL - Icon.SIZE) / 2,
				on ? ACCENT : index == over ? 0xFFFFFFFF : TEXT_DIM);
		}

		// The name of whichever one is under the mouse, beside the strip rather than
		// floating over the world: a tooltip that follows the cursor is a tooltip that
		// covers the thing being pointed at.
		if (over >= 0) {
			graphics.text(font, Component.translatable("npc_studio.tool." + name(TOOLS[over])),
				EDGE + TOOLS.length * (CELL + 2) + 4, EDGE + (CELL - 8) / 2, 0xFFFFFFFF);
		}
	}

	private static String name(Gizmo.Tool tool) {
		return switch (tool) {
			case MOVE -> "move";
			case ROTATE -> "rotate";
			case RESIZE -> "resize";
		};
	}
}
