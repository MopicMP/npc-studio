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

	@Override
	protected void init() {
		ShapeEditing.begin(entityId, shape);

		int x = width - PANEL - MARGIN;
		int y = MARGIN + 16;

		// Presets first, because most visits end here. Wrapped rather than in one
		// row: there are seven of them and the panel is not wide.
		int at = x;
		int wide = (PANEL - 8) / 3;
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

		slider(x, y, "плечи", () -> shape.shoulders(), v -> take(withShoulders(v)));
		y += ROW;
		slider(x, y, "грудь", () -> shape.chest(), v -> take(withChest(v)));
		y += ROW;
		slider(x, y, "живот", () -> shape.belly(), v -> take(withBelly(v)));
		y += ROW;
		slider(x, y, "бёдра", () -> shape.hips(), v -> take(withHips(v)));
		y += ROW;
		slider(x, y, "руки", () -> shape.arms(), v -> take(withArms(v)));
		y += ROW;
		slider(x, y, "ноги", () -> shape.legs(), v -> take(withLegs(v)));
		y += ROW;
		slider(x, y, "голова", () -> shape.head(), v -> take(withHead(v)));
		y += ROW + 6;

		// The two that are not scales, kept apart from the seven that are.
		addRenderableWidget(new FlatSlider(x, y, PANEL, 18, "округлость",
			0, 1, 0.02, GOOD, () -> shape.roundness(), v -> take(withRoundness(v.floatValue()))));
		y += ROW;
		addRenderableWidget(new FlatSlider(x, y, PANEL, 18, "осанка",
			-BodyShape.MAX_STOOP, BodyShape.MAX_STOOP, 0.01, GOOD,
			() -> shape.stoop(), v -> take(withStoop(v.floatValue()))));
		y += ROW + 10;

		addRenderableWidget(new FlatButton(x, y, PANEL, 20,
			Component.literal("готово"), GOOD, this::keep));
		y += 24;
		addRenderableWidget(new FlatButton(x, y, PANEL / 2 - 2, 20,
			Component.literal("как у костюма"), ACCENT, this::fromCostume));
		addRenderableWidget(new FlatButton(x + PANEL / 2 + 2, y, PANEL / 2 - 2, 20,
			Component.literal("в костюм"), ACCENT, this::toCostume));
		y += 24;
		addRenderableWidget(new FlatButton(x, y, PANEL, 20,
			Component.literal("отмена"), TEXT_DIM, this::onClose));
	}

	private void slider(int x, int y, String label, DoubleSupplier value, Consumer<Float> set) {
		addRenderableWidget(new FlatSlider(x, y, PANEL, 18, label,
			BodyShape.MIN_SCALE, BodyShape.MAX_SCALE, 0.01, ACCENT,
			value, picked -> set.accept(picked.floatValue())));
	}

	// The record has nine components and no wither, so these are the withers. Nine
	// one-line methods rather than a builder, because a builder for a value this
	// small is more machinery than the thing it builds.
	private BodyShape withShoulders(float v) {
		return new BodyShape(v, shape.hips(), shape.belly(), shape.arms(), shape.legs(),
			shape.chest(), shape.head(), shape.roundness(), shape.stoop());
	}

	private BodyShape withHips(float v) {
		return new BodyShape(shape.shoulders(), v, shape.belly(), shape.arms(), shape.legs(),
			shape.chest(), shape.head(), shape.roundness(), shape.stoop());
	}

	private BodyShape withBelly(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), v, shape.arms(), shape.legs(),
			shape.chest(), shape.head(), shape.roundness(), shape.stoop());
	}

	private BodyShape withArms(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), shape.belly(), v, shape.legs(),
			shape.chest(), shape.head(), shape.roundness(), shape.stoop());
	}

	private BodyShape withLegs(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), shape.belly(), shape.arms(), v,
			shape.chest(), shape.head(), shape.roundness(), shape.stoop());
	}

	private BodyShape withChest(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), shape.belly(), shape.arms(),
			shape.legs(), v, shape.head(), shape.roundness(), shape.stoop());
	}

	private BodyShape withHead(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), shape.belly(), shape.arms(),
			shape.legs(), shape.chest(), v, shape.roundness(), shape.stoop());
	}

	private BodyShape withRoundness(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), shape.belly(), shape.arms(),
			shape.legs(), shape.chest(), shape.head(), v, shape.stoop());
	}

	private BodyShape withStoop(float v) {
		return new BodyShape(shape.shoulders(), shape.hips(), shape.belly(), shape.arms(),
			shape.legs(), shape.chest(), shape.head(), shape.roundness(), v);
	}

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
		minecraft.setScreenAndShow(parent);
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
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public void onClose() {
		// Leaving without deciding puts the character back. The build was never
		// sent, so there is nothing to undo on the server — only the working copy
		// this screen was showing.
		if (!kept) shape = asFound;
		ShapeEditing.end();
		minecraft.setScreenAndShow(parent);
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
		if (event.x() < width - PANEL - MARGIN * 2) {
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

		int panelLeft = width - PANEL - MARGIN * 2;
		graphics.fill(panelLeft, 0, width, height, PANEL_FILL);
		graphics.fill(panelLeft, 0, panelLeft + 1, height, EDGE);

		drawFigure(graphics, panelLeft);

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
}
