package com.mopicmp.npcstudio.client.editor;

import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import com.mopicmp.npcstudio.client.entity.ShapeEditing;
import com.mopicmp.npcstudio.entity.BodyShape;
import com.mopicmp.npcstudio.net.NpcPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * How a character is built: nine sliders and a figure that answers at once.
 *
 * The figure is the whole screen, really. Nobody knows what "shoulders 1.25"
 * means and nobody should have to — they push the slider until the blacksmith
 * looks like a blacksmith. So the character changes as the hand moves, without
 * waiting for the server to agree, and the server is told when the work is done.
 *
 * Presets sit above the sliders rather than replacing them. A preset is a set of
 * slider positions and nothing else, so picking one is a place to start from
 * rather than a decision to live with — which is the difference between a
 * workshop and a menu of somebody else's guesses.
 */
public class BodyShapeScreen extends Screen {

	private static final int MARGIN = 16;
	private static final int ROW = 22;
	private static final int PANEL = 210;

	private static final int CANVAS = 0xFF101318;
	private static final int PANEL_FILL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;

	private final Screen parent;
	private final int entityId;

	/** What it looked like on opening, so that leaving without deciding is free. */
	private final BodyShape asFound;

	private BodyShape shape;
	private boolean kept;

	/**
	 * How close the figure is drawn, one being the size that fits the panel.
	 *
	 * Needed rather than nice: a belly is a couple of pixels of a character drawn
	 * whole, and nobody can judge two pixels. Turning the figure without being
	 * able to come closer to it is half a preview.
	 */
	private float zoom = 1f;

	private float spin = 0f;
	private float pitch = PreviewFigure.RESTING_PITCH;
	private boolean dragging;
	private double dragFrom;
	private double dragFromY;
	private String notice = "";

	public BodyShapeScreen(Screen parent, int entityId, BodyShape shape) {
		super(Component.literal("Телосложение"));
		this.parent = parent;
		this.entityId = entityId;
		this.asFound = shape == null ? BodyShape.DEFAULT : shape;
		this.shape = this.asFound;
	}

	/**
	 * Whether the figure is drawn beside the sliders.
	 *
	 * On a screen of its own it was most of the point — a belly is two pixels and
	 * nobody judges two pixels from a number. In the workspace the character is in
	 * the viewport, being drawn properly, and this figure was a dark rectangle
	 * taking two thirds of the panel to show nothing.
	 */
	private boolean showsFigure() {
		return !com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded();
	}

	private int panelWidth() {
		return showsFigure() ? PANEL : Math.max(120, width - MARGIN * 2);
	}

	private int panelLeft() {
		return showsFigure() ? width - PANEL - MARGIN : MARGIN;
	}

	/**
	 * How tall the controls come out, so a panel can decide whether to scroll.
	 *
	 * Counted the same way {@code init} lays them out rather than measured
	 * afterwards, because the panel has to know before anything is built.
	 */
	public int contentHeight() {
		int presets = (BodyShape.PRESETS.size() + 2) / 3;
		return MARGIN + 16 + presets * 20 + 30 + ROW * 11 + 16 + 20 + 24 + 24 + 20 + MARGIN;
	}

	@Override
	protected void init() {
		ShapeEditing.begin(entityId, shape);

		int x = panelLeft();
		int y = MARGIN + 16;

		// Presets first, because most visits end here. Wrapped rather than in one
		// row: there are seven of them and the panel is not wide.
		int at = x;
		int wide = (panelWidth() - 8) / 3;
		for (int i = 0; i < BodyShape.PRESETS.size(); i++) {
			BodyShape.Preset preset = BodyShape.PRESETS.get(i);
			if (i > 0 && i % 3 == 0) {
				y += 20;
				at = x;
			}
			addRenderableWidget(new FlatButton(at, y, wide, 18,
				Component.literal(preset.name()), 0xFFBA68C8, () -> take(preset.shape())));
			at += wide + 4;
		}
		y += 30;

		slider(x, y, "плечи", () -> shape.shoulders(), v -> take(with(SHOULDERS, v)));
		y += ROW;
		slider(x, y, "грудь", () -> shape.chest(), v -> take(with(CHEST, v)));
		y += ROW;
		slider(x, y, "живот", () -> shape.belly(), v -> take(with(BELLY, v)));
		y += ROW;
		slider(x, y, "бёдра", () -> shape.hips(), v -> take(with(HIPS, v)));
		y += ROW;
		slider(x, y, "руки", () -> shape.arms(), v -> take(with(ARMS, v)));
		y += ROW;
		slider(x, y, "ноги", () -> shape.legs(), v -> take(with(LEGS, v)));
		y += ROW;
		slider(x, y, "голова", () -> shape.head(), v -> take(with(HEAD, v)));
		y += ROW + 6;

		// The three that are not scales, kept apart from the seven that are. Relief
		// sits with them rather than with the girths: it does not make a character
		// bigger anywhere, it decides whether the limbs have knees and ankles at all.
		addRenderableWidget(new FlatSlider(x, y, panelWidth(), 18, "рост",
			BodyShape.MIN_HEIGHT, BodyShape.MAX_HEIGHT, 0.01, GOOD,
			() -> shape.height(), v -> take(with(HEIGHT, v.floatValue()))));
		y += ROW;
		addRenderableWidget(new FlatSlider(x, y, panelWidth(), 18, "перехват",
			0, BodyShape.MAX_TAPER, 0.05, GOOD,
			() -> shape.taper(), v -> take(with(TAPER, v.floatValue()))));
		y += ROW;
		addRenderableWidget(new FlatSlider(x, y, panelWidth(), 18, "мягкость",
			0, 1, 0.02, GOOD, () -> shape.softness(), v -> take(with(SOFTNESS, v.floatValue()))));
		y += ROW;
		addRenderableWidget(new FlatSlider(x, y, panelWidth(), 18, "осанка",
			-BodyShape.MAX_STOOP, BodyShape.MAX_STOOP, 0.01, GOOD,
			() -> shape.stoop(), v -> take(with(STOOP, v.floatValue()))));
		y += ROW + 10;

		addRenderableWidget(new FlatButton(x, y, panelWidth(), 20,
			Component.literal("готово"), GOOD, this::keep));
		y += 24;
		addRenderableWidget(new FlatButton(x, y, panelWidth() / 2 - 2, 20,
			Component.literal("как у костюма"), ACCENT, this::fromCostume));
		addRenderableWidget(new FlatButton(x + panelWidth() / 2 + 2, y, panelWidth() / 2 - 2, 20,
			Component.literal("в костюм"), ACCENT, this::toCostume));
		y += 24;
		addRenderableWidget(new FlatButton(x, y, panelWidth(), 20,
			Component.literal("отмена"), TEXT_DIM, this::onClose));
	}

	private void slider(int x, int y, String label, DoubleSupplier value, Consumer<Float> set) {
		addRenderableWidget(new FlatSlider(x, y, panelWidth(), 18, label,
			BodyShape.MIN_SCALE, BodyShape.MAX_SCALE, 0.01, ACCENT,
			value, picked -> set.accept(picked.floatValue())));
	}

	/**
	 * The record has no wither, so this is the wither: every value, one changed.
	 *
	 * One method rather than ten one-liners. Ten of them was already dull and became
	 * dangerous the moment a tenth value arrived — every one of them had to be
	 * remembered, and the one that was forgotten would not fail to compile. It would
	 * quietly reset somebody's relief the next time they touched a different slider.
	 */
	private BodyShape with(int which, float v) {
		return new BodyShape(
			which == SHOULDERS ? v : shape.shoulders(),
			which == HIPS ? v : shape.hips(),
			which == BELLY ? v : shape.belly(),
			which == ARMS ? v : shape.arms(),
			which == LEGS ? v : shape.legs(),
			which == CHEST ? v : shape.chest(),
			which == HEAD ? v : shape.head(),
			which == SOFTNESS ? v : shape.softness(),
			which == STOOP ? v : shape.stoop(),
			which == HEIGHT ? v : shape.height(),
			which == TAPER ? v : shape.taper());
	}

	private static final int SHOULDERS = 0, HIPS = 1, BELLY = 2, ARMS = 3, LEGS = 4;
	private static final int CHEST = 5, HEAD = 6, SOFTNESS = 7, STOOP = 8;
	private static final int HEIGHT = 9, TAPER = 10;

	// ---------------------------------------------------------- the actions

	/** Takes a new build and shows it at once. Nothing leaves this client yet. */
	private void take(BodyShape wanted) {
		shape = wanted;
		ShapeEditing.begin(entityId, shape);
	}

	private void send(NpcPayloads.Shape.Verb verb) {
		ClientPlayNetworking.send(new NpcPayloads.Shape(
			entityId, verb, shape.packed(), shape.packedPosture()));
	}

	private void keep() {
		kept = true;
		send(NpcPayloads.Shape.Verb.SET);
		leave();
	}

	/**
	 * Writes this build onto the costume, so everyone else wearing it gets it too.
	 *
	 * The costume is never named from here. The server remembers what it dressed
	 * this character in, and that is the one it writes to — a name off the wire
	 * would let a client rewrite any record it could guess the id of.
	 */
	private void toCostume() {
		kept = true;
		send(NpcPayloads.Shape.Verb.KEEP_ON_COSTUME);
		notice = "костюм запомнил — так будут выглядеть все в нём";
	}

	private void fromCostume() {
		kept = true;
		ClientPlayNetworking.send(new NpcPayloads.Shape(
			entityId, NpcPayloads.Shape.Verb.TAKE_FROM_COSTUME, 0, 0));
		// Let go locally as well, or the working copy would keep showing the build
		// that was just thrown away.
		ShapeEditing.end();
		leave();
	}

	@Override
	public void onClose() {
		// Leaving without deciding puts the character back. The build was never
		// sent, so there is nothing to undo on the server — only the working copy
		// this screen was showing.
		if (!kept) shape = asFound;
		ShapeEditing.end();
		leave();
	}

	@Override
	public void removed() {
		// Belt and braces for the ways a screen can go that are not onClose: the
		// world ending, another screen taking over. A working copy left behind
		// would follow whatever holds this entity id next.
		ShapeEditing.end();
	}

	// ------------------------------------------------------------- the mouse

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (showsFigure() && event.x() < panelLeft() - MARGIN) {
			dragging = true;
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		// Multiplied rather than stepped, so a notch of the wheel moves the same
		// visible amount whether the figure is small or large.
		zoom = net.minecraft.util.Mth.clamp(zoom * (dy > 0 ? 1.15f : 1f / 1.15f), 0.4f, 6f);
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			spin += (float) (event.x() - dragFrom) * 1.4f;
			pitch = net.minecraft.util.Mth.clamp(
				pitch + (float) (event.y() - dragFromY) * 0.7f, -85f, 85f);
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	// ----------------------------------------------------------- the drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, MARGIN, MARGIN, TEXT);

		int panelLeft = panelLeft() - MARGIN;
		graphics.fill(panelLeft, 0, width, height, PANEL_FILL);
		graphics.fill(panelLeft, 0, panelLeft + 1, height, EDGE);

		if (showsFigure()) drawFigure(graphics, panelLeft);

		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice), MARGIN, height - 12, GOOD);
		} else {
			String hint = "тяни — повернуть, колесо — приблизить";
			graphics.text(font, Component.literal(hint), MARGIN, height - 12, TEXT_DIM);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void drawFigure(GuiGraphicsExtractor graphics, int right) {
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(entityId)
				instanceof net.minecraft.world.entity.LivingEntity npc)) {
			String gone = "персонаж не виден";
			graphics.text(font, Component.literal(gone),
				right / 2 - font.width(gone) / 2, height / 2, TEXT_DIM);
			return;
		}
		int top = MARGIN + 20;
		int bottom = height - 30;
		try {
			PreviewFigure.draw(graphics, 0, top, right, bottom,
				(bottom - top) / 3.2f * zoom, 0.0625f, spin, pitch, npc);
		} catch (RuntimeException failed) {
			com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
				"Could not draw the body preview: {}", failed.toString());
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * Goes back where it came from — unless there is nowhere to go back to.
	 *
	 * A null parent means this is living inside the workspace as a panel, and a
	 * panel has no "back": the thing behind it is the rest of the workspace, and
	 * handing the display to null would close all of it. So leaving becomes
	 * staying, which is what a panel does when you have finished with it.
	 */
	private void leave() {
		if (parent != null) minecraft.setScreenAndShow(parent);
	}

}
