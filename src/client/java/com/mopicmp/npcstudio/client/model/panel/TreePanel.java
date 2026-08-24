package com.mopicmp.npcstudio.client.model.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.model.Modelling;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.model.Bone;
import com.mopicmp.npcstudio.model.Cube;
import com.mopicmp.npcstudio.model.Model;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The model as a tree: folders, and the boxes inside them.
 *
 * <h2>Folders, not bones</h2>
 *
 * What the document calls a bone is what this calls a folder, and the rename is
 * the whole of the simplification. A bone is a thing you have to have an opinion
 * about — it is for animation, it is not needed by scenery, and being asked
 * about it while building a mast is being asked a question that has nothing to do
 * with the mast. A folder is a thing to put boxes in. Every box is in one whether
 * anybody thinks about it or not, which is exactly what was asked for: let it
 * always be there and always apply.
 *
 * Nothing under this changed. The same bone is still what an animation will turn.
 *
 * <h2>Why the tree is flattened every frame</h2>
 *
 * The document stores a parent on each bone and works the tree out from that,
 * which is right for editing and useless for drawing a list. So the list is
 * rebuilt from the tree each frame: forty rows of arithmetic against a document
 * that fits in a cache line. Keeping a second, flattened copy would be a second
 * thing to invalidate, and the thing it would save is nothing.
 */
public class TreePanel extends WorkspacePanel {

	private static final int ROW = 13;
	private static final int PAD = 6;
	private static final int INDENT = 10;
	private static final int TOOLBAR = 22;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int ROW_ON = 0xFF27313E;
	private static final int ROW_HOVER = 0xFF232A34;

	/** One line of the list: a bone, or a box belonging to one. */
	private record Row(String bone, int cube, int depth) { }

	private List<Row> rows = List.of();
	private int scroll;

	/**
	 * The folders that are shut, by name.
	 *
	 * Kept here rather than in the document, because whether a folder is open is
	 * about somebody looking at a list — it is not a property of the model and has
	 * no business being saved into the file or sent to anybody.
	 */
	private final java.util.Set<String> shut = new java.util.HashSet<>();

	/** How wide the little chevron's column is. */
	private static final int TWIST = 9;

	@Override
	public String id() {
		return "tree";
	}

	@Override
	public int minimumWidth() {
		return 140;
	}

	@Override
	protected void build() {
		IconTextButton.row(PAD, PAD - 2, width - PAD * 2, 18, List.of(
			new IconTextButton.Spec(Icon.SOLID, Component.translatable("npc_studio.model.add_cube"),
				ACCENT, Modelling::addCube),
			new IconTextButton.Spec(Icon.FOLDER, Component.translatable("npc_studio.model.add_bone"),
				ACCENT, Modelling::addBone),
			new IconTextButton.Spec(Icon.REMOVE, Component.translatable("npc_studio.model.remove"),
				0xFFEF5350, Modelling::removeSelected)), this::add);
	}

	/**
	 * Delete removes the row that is selected.
	 *
	 * The key was only on the world panel before, where it means the object
	 * standing there, and pressing it while looking at this list took the whole
	 * object away and left every row of it exactly where it was. Both keys are
	 * right; what was missing is that this list is the one about parts.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == KEY_DELETE) {
			Modelling.removeSelected();
			return true;
		}
		return super.keyPressed(event);
	}

	private static final int KEY_DELETE = 261;

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		Model model = Modelling.model();
		if (model == null) {
			graphics.text(font, Component.translatable("npc_studio.model.nothing_open"),
				PAD, TOOLBAR, TEXT_DIM);
			return;
		}

		rows = flatten(model);
		int hovered = rowAt(mouseX, mouseY);

		for (int i = 0; i < rows.size(); i++) {
			int top = TOOLBAR + i * ROW - scroll;
			if (top + ROW < TOOLBAR || top > height) continue;
			Row row = rows.get(i);

			boolean active = row.bone().equals(Modelling.selectedBone())
				&& row.cube() == Modelling.selectedCube();
			boolean on = active || Modelling.isSelected(row.bone(), row.cube());
			if (on || i == hovered) {
				graphics.fill(0, top, width, top + ROW, on ? ROW_ON : ROW_HOVER);
			}
			// The active one keeps a mark of its own. It is the row the handles are on
			// and the row the numbers on the right belong to, and with five rows lit
			// there would otherwise be no way to tell which one that is.
			if (active && Modelling.selectedCount() > 1) {
				graphics.fill(0, top, 2, top + ROW, ACCENT);
			}
			// Where a drag would put things. Drawn on the folder rather than under the
			// cursor, because what matters is which folder, not which pixel.
			if (dragging && i == over && row.cube() < 0) {
				graphics.fill(0, top, width, top + 1, ACCENT);
				graphics.fill(0, top + ROW - 1, width, top + ROW, ACCENT);
			}

			int left = PAD + row.depth() * INDENT;
			// The chevron first, in a column of its own, so that clicking it can be
			// told from clicking the row it sits on.
			if (row.cube() < 0 && holdsAnything(model, row.bone())) {
				(shut.contains(row.bone()) ? Icon.CLOSED : Icon.EXPAND)
					.draw(graphics, left - 4, top + (ROW - Icon.SIZE) / 2, TEXT_DIM);
			}
			left += TWIST;

			Icon icon = row.cube() < 0 ? Icon.FOLDER : Icon.SOLID;
			icon.draw(graphics, left, top + (ROW - Icon.SIZE) / 2, on ? ACCENT : TEXT_DIM);
			graphics.text(font, Component.literal(label(model, row)),
				left + Icon.SIZE + 3, top + 3, on ? ACCENT : row.cube() < 0 ? TEXT : TEXT_DIM);
		}
	}

	/**
	 * What a row says it is.
	 *
	 * A box is numbered and then measured. The number is what makes two boxes of
	 * the same size two entries rather than one repeated — a list where every line
	 * reads "16×16×16" is a list that looks like it has one thing in it, which is
	 * how it looked.
	 */
	private String label(Model model, Row row) {
		if (row.cube() < 0) return row.bone();
		Bone bone = model.bone(row.bone());
		if (bone == null || row.cube() >= bone.cubes().size()) return "?";
		Cube cube = bone.cubes().get(row.cube());
		return "%d · %.0f×%.0f×%.0f".formatted(row.cube() + 1,
			cube.sizeX(), cube.sizeY(), cube.sizeZ());
	}

	/** The tree, depth first, as a list of lines. */
	private List<Row> flatten(Model model) {
		List<Row> made = new ArrayList<>();
		for (Bone bone : model.childrenOf("")) walk(model, bone, 0, made);
		return made;
	}

	private void walk(Model model, Bone bone, int depth, List<Row> into) {
		into.add(new Row(bone.name(), -1, depth));
		// A shut folder is one line. That is the whole point of it: a sphere is two
		// hundred boxes, and a list that cannot be shut is a list with two hundred
		// lines of sphere in the middle of it.
		if (shut.contains(bone.name())) return;

		for (int i = 0; i < bone.cubes().size(); i++) {
			into.add(new Row(bone.name(), i, depth + 1));
		}
		for (Bone child : model.childrenOf(bone.name())) walk(model, child, depth + 1, into);
	}

	/** Whether a folder has anything in it worth opening. */
	private boolean holdsAnything(Model model, String folder) {
		Bone bone = model.bone(folder);
		return bone != null && (!bone.cubes().isEmpty() || !model.childrenOf(folder).isEmpty());
	}

	private int rowAt(double mouseX, double mouseY) {
		if (!inside(mouseX, mouseY) || mouseY < TOOLBAR) return -1;
		int row = (int) ((mouseY - TOOLBAR + scroll) / ROW);
		return row >= 0 && row < rows.size() ? row : -1;
	}

	// ------------------------------------------------------------------ input

	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	/** Which row the mouse took hold of, and whether it has been dragged anywhere. */
	private int held = -1;
	private int over = -1;
	private boolean dragging;

	/**
	 * Clicking a row, the way every list with more than one thing in it works.
	 *
	 * Plain replaces the selection, control adds or takes away one, shift takes
	 * everything between. Shift and control are what Blockbench uses and what
	 * everything else uses; there is nothing to invent here and a great deal to get
	 * wrong by inventing.
	 *
	 * A plain click on a row that is already selected does <em>not</em> collapse the
	 * selection to it. That is what makes dragging several things possible: taking
	 * hold of one of five to move all five must not first throw four of them away.
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int row = rowAt(event.x(), event.y());
		if (row < 0) return super.mouseClicked(event, doubleClick);
		Row picked = rows.get(row);

		// The chevron opens and shuts, and does nothing else. Its own column rather
		// than a modifier or a double click: opening a folder is a thing done to the
		// list, not to the model, and it should not also choose anything.
		int twistLeft = PAD + picked.depth() * INDENT - 4;
		if (picked.cube() < 0 && event.x() >= twistLeft && event.x() < twistLeft + TWIST + 4) {
			if (!shut.remove(picked.bone())) shut.add(picked.bone());
			return true;
		}

		if ((event.modifiers() & GLFW_CONTROL) != 0) {
			Modelling.alsoSelect(picked.bone(), picked.cube());
		} else if ((event.modifiers() & GLFW_SHIFT) != 0) {
			int from = activeRow();
			List<String> bones = new ArrayList<>();
			List<Integer> cubes = new ArrayList<>();
			for (Row one : rows) {
				bones.add(one.bone());
				cubes.add(one.cube());
			}
			Modelling.selectThrough(bones, cubes, from < 0 ? row : from, row);
		} else if (!Modelling.isSelected(picked.bone(), picked.cube())) {
			// A folder means the folder and everything in it. Choosing a folder and
			// having nothing happen — no handles, no material, no delete — is a row
			// that looks like it does nothing, which is what it looked like.
			if (picked.cube() < 0) Modelling.selectFolder(picked.bone());
			else Modelling.select(picked.bone(), picked.cube());
		}

		held = row;
		dragging = false;
		return true;
	}

	/** Where the active row is in the list, or -1 when it is not showing. */
	private int activeRow() {
		for (int at = 0; at < rows.size(); at++) {
			Row one = rows.get(at);
			if (one.bone().equals(Modelling.selectedBone()) && one.cube() == Modelling.selectedCube()) {
				return at;
			}
		}
		return -1;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (held < 0) return super.mouseDragged(event, dragX, dragY);
		// A few pixels of slack, so that clicking a row selects it and does not carry
		// it off because a hand moved.
		if (!dragging && Math.abs(dragY) + Math.abs(dragX) < 3) return true;
		dragging = true;
		over = rowAt(event.x(), event.y());
		return true;
	}

	/**
	 * Letting go over a folder puts everything selected into it.
	 *
	 * Only over a folder. A box is not somewhere things can be put, and a drop on
	 * one that quietly meant "into whatever folder that box is in" would be a rule
	 * nobody could see being applied.
	 */
	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean was = dragging;
		int target = over;
		held = -1;
		over = -1;
		dragging = false;

		if (was && target >= 0 && target < rows.size()) {
			Row onto = rows.get(target);
			if (onto.cube() < 0) {
				Modelling.moveInto(onto.bone());
				return true;
			}
		}
		return was || super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		int most = Math.max(0, rows.size() * ROW - height + TOOLBAR);
		scroll = Math.max(0, Math.min(most, scroll - (int) (amountY * ROW)));
		return true;
	}
}
