package com.mopicmp.npcstudio.client.model.panel;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mopicmp.npcstudio.client.editor.ItemPickerScreen;
import com.mopicmp.npcstudio.client.model.Modelling;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.model.Bone;
import com.mopicmp.npcstudio.model.Cube;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The numbers of whatever is selected.
 *
 * <h2>Typed, not dragged — for now</h2>
 *
 * A gizmo is the right way to move a box and it is not the only way that has to
 * exist. Numbers typed by hand are how a mast ends up exactly eight pixels from
 * the middle rather than nearly eight, and no amount of dragging gets there.
 * Blockbench has both for the same reason. The gizmo is the next piece of work;
 * this one has to exist either way.
 *
 * <h2>Why a bad number is ignored rather than announced</h2>
 *
 * These boxes are typed into constantly, and a half-typed number is not a
 * mistake — "1" on the way to "16" spends a moment being a perfectly good
 * number that is not what anybody meant, and "-" on the way to "-4" is not a
 * number at all. Refusing to parse and leaving the value alone is silent and
 * right; a complaint on every keystroke would be a complaint about typing.
 */
public class NumbersPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int ROW = 16;
	private static final int LABEL = 44;

	private static final int TEXT_DIM = 0xFF8A99A6;

	/** What was selected when the fields were built, so a change is noticed. */
	private String forBone = "";
	private int forCube = -2;

	@Override
	public String id() {
		return "numbers";
	}

	@Override
	public int minimumWidth() {
		return 170;
	}

	@Override
	public void tick() {
		if (forBone.equals(Modelling.selectedBone()) && forCube == Modelling.selectedCube()) return;
		forBone = Modelling.selectedBone();
		forCube = Modelling.selectedCube();
		rebuild();
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void build() {
		forBone = Modelling.selectedBone();
		forCube = Modelling.selectedCube();

		Cube cube = Modelling.cube();
		if (cube != null) {
			buildCube(cube);
			return;
		}
		Bone bone = Modelling.bone();
		if (bone != null) buildBone(bone);
	}

	private void buildCube(Cube cube) {
		int y = PAD + 10;
		y = triple(y, () -> Modelling.cube().fromX(), () -> Modelling.cube().fromY(),
			() -> Modelling.cube().fromZ(),
			(x) -> at(x, Modelling.cube().fromY(), Modelling.cube().fromZ()),
			(v) -> at(Modelling.cube().fromX(), v, Modelling.cube().fromZ()),
			(z) -> at(Modelling.cube().fromX(), Modelling.cube().fromY(), z));

		y = triple(y, () -> Modelling.cube().sizeX(), () -> Modelling.cube().sizeY(),
			() -> Modelling.cube().sizeZ(),
			(x) -> size(x, Modelling.cube().sizeY(), Modelling.cube().sizeZ()),
			(v) -> size(Modelling.cube().sizeX(), v, Modelling.cube().sizeZ()),
			(z) -> size(Modelling.cube().sizeX(), Modelling.cube().sizeY(), z));

		y = triple(y, () -> Modelling.cube().pivotX(), () -> Modelling.cube().pivotY(),
			() -> Modelling.cube().pivotZ(),
			(x) -> Modelling.changeCube(Modelling.cube().pivotedAt(x,
				Modelling.cube().pivotY(), Modelling.cube().pivotZ())),
			(v) -> Modelling.changeCube(Modelling.cube().pivotedAt(Modelling.cube().pivotX(),
				v, Modelling.cube().pivotZ())),
			(z) -> Modelling.changeCube(Modelling.cube().pivotedAt(Modelling.cube().pivotX(),
				Modelling.cube().pivotY(), z)));

		y = triple(y, () -> Modelling.cube().rotX(), () -> Modelling.cube().rotY(),
			() -> Modelling.cube().rotZ(),
			(x) -> Modelling.changeCube(Modelling.cube().turned(x,
				Modelling.cube().rotY(), Modelling.cube().rotZ())),
			(v) -> Modelling.changeCube(Modelling.cube().turned(Modelling.cube().rotX(),
				v, Modelling.cube().rotZ())),
			(z) -> Modelling.changeCube(Modelling.cube().turned(Modelling.cube().rotX(),
				Modelling.cube().rotY(), z)));

		// Two numbers rather than three: a texture has no depth.
		int across = (width - PAD * 2 - LABEL - 4) / 2;
		number(PAD + LABEL, y, across, () -> Modelling.cube().u(),
			(u) -> Modelling.changeCube(Modelling.cube().textured(Math.round(u), Modelling.cube().v())));
		number(PAD + LABEL + across + 4, y, across, () -> Modelling.cube().v(),
			(v) -> Modelling.changeCube(Modelling.cube().textured(Modelling.cube().u(), Math.round(v))));
		y += ROW + 4;

		// What the box is made of. The grid that picks it is the one that already
		// picks an item for a character's hand — a block is an item, the search is
		// the same search, and a second grid of a thousand things would be a second
		// grid to keep in step.
		blockAt = y;
		IconTextButton.row(PAD, y, width - PAD * 2, 18, java.util.List.of(
			new IconTextButton.Spec(Icon.ASSETS, Component.translatable("npc_studio.model.block"),
				ACCENT, this::chooseBlock),
			new IconTextButton.Spec(Icon.CANCEL, Component.translatable("npc_studio.model.bare"),
				TEXT_DIM, () -> Modelling.wear(""))), this::add);
	}

	/** Where the material row ended up, so its name can be drawn above it. */
	private int blockAt;

	private static final int ACCENT = 0xFF4FC3F7;

	private void chooseBlock() {
		// On top of the modelling screen rather than inside it, and that is the one
		// place a screen is still the right shape: picking from a thousand things
		// is the whole of what somebody is doing while it is open.
		minecraft.setScreenAndShow(new ItemPickerScreen(
			com.mopicmp.npcstudio.client.model.ModellingScreen.current(), Modelling::wear));
	}

	private void at(float x, float y, float z) {
		Modelling.changeCube(Modelling.cube().movedTo(x, y, z));
	}

	private void size(float x, float y, float z) {
		Modelling.changeCube(Modelling.cube().sized(x, y, z));
	}

	private void buildBone(Bone bone) {
		int y = PAD + 10;
		y = triple(y, () -> Modelling.bone().pivotX(), () -> Modelling.bone().pivotY(),
			() -> Modelling.bone().pivotZ(),
			(x) -> Modelling.changeBone(Modelling.bone().pivotedAt(x,
				Modelling.bone().pivotY(), Modelling.bone().pivotZ())),
			(v) -> Modelling.changeBone(Modelling.bone().pivotedAt(Modelling.bone().pivotX(),
				v, Modelling.bone().pivotZ())),
			(z) -> Modelling.changeBone(Modelling.bone().pivotedAt(Modelling.bone().pivotX(),
				Modelling.bone().pivotY(), z)));

		triple(y, () -> Modelling.bone().rotX(), () -> Modelling.bone().rotY(),
			() -> Modelling.bone().rotZ(),
			(x) -> Modelling.changeBone(Modelling.bone().turned(x,
				Modelling.bone().rotY(), Modelling.bone().rotZ())),
			(v) -> Modelling.changeBone(Modelling.bone().turned(Modelling.bone().rotX(),
				v, Modelling.bone().rotZ())),
			(z) -> Modelling.changeBone(Modelling.bone().turned(Modelling.bone().rotX(),
				Modelling.bone().rotY(), z)));
	}

	/** Three boxes on one line: what every one of these settings is. */
	private int triple(int y, Supplier<Number> x, Supplier<Number> yy, Supplier<Number> z,
			Consumer<Float> setX, Consumer<Float> setY, Consumer<Float> setZ) {
		int across = (width - PAD * 2 - LABEL - 8) / 3;
		if (across < 20) return y + ROW;
		number(PAD + LABEL, y, across, x, setX);
		number(PAD + LABEL + across + 4, y, across, yy, setY);
		number(PAD + LABEL + (across + 4) * 2, y, across, z, setZ);
		return y + ROW;
	}

	private void number(int x, int y, int across, Supplier<Number> value, Consumer<Float> set) {
		EditBox box = new EditBox(font, x, y, across, ROW - 4, Component.literal(""));
		box.setValue(show(value.get().floatValue()));
		box.setResponder(text -> {
			try {
				set.accept(Float.parseFloat(text.trim()));
			} catch (NumberFormatException halfTyped) {
				// Left alone on purpose. See the note at the top of the class.
			}
		});
		add(box);
	}

	private static String show(float value) {
		return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (!Modelling.open()) {
			graphics.text(font, Component.translatable("npc_studio.model.nothing_open"),
				PAD, PAD, TEXT_DIM);
			return;
		}
		boolean box = Modelling.cube() != null;
		String[] labels = box
			? new String[] { "npc_studio.model.at", "npc_studio.model.size",
				"npc_studio.model.pivot", "npc_studio.model.rotation", "npc_studio.model.uv" }
			: new String[] { "npc_studio.model.pivot", "npc_studio.model.rotation" };

		int y = PAD + 10;
		for (String key : labels) {
			graphics.text(font, Component.translatable(key), PAD, y + 2, TEXT_DIM);
			y += ROW;
		}
		if (!box) return;

		String worn = Modelling.cube().textured()
			? Modelling.cube().block()
			: Component.translatable("npc_studio.model.bare").getString();
		graphics.text(font, Component.literal(worn), PAD, blockAt - 11,
			Modelling.cube().textured() ? ACCENT : TEXT_DIM);
	}
}
