package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.DialogueNames;
import com.mopicmp.npcstudio.client.editor.EditorState;
import com.mopicmp.npcstudio.client.editor.GraphEditorScreen;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;
import com.mopicmp.npcstudio.net.EditorPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The dialogue as boxes and wires, at the bottom of the workspace.
 *
 * <h2>Getting into one</h2>
 *
 * The panel used to say "no dialogue open" and stop there, which was true and
 * useless: the way in had been a screen of its own, and that screen no longer
 * opens. So the empty state is the way in. It lists what the world has, offers
 * a new one, and asks the server for the list if nobody has yet — a panel whose
 * empty state is a dead end is a panel nobody can start from.
 *
 * Under it sits the timeline, and the two are one arrangement rather than two
 * tabs of the same slot. What a node does and when it happens are the same
 * question asked twice.
 */
public class GraphPanel extends ScreenPanel {

	private static final int ROW = 16;
	private static final int PAD = 8;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int ROW_HOVER = 0xFF232A34;

	/** The dialogue being edited, as it arrived from the server. */
	private static EditorState waiting;

	/** Whichever state this panel is currently showing. */
	private EditorState showing;

	/** True while the list of dialogues is up, over whatever else is here. */
	private boolean choosing;
	private int scroll;

	/** Hands a dialogue to the panel and puts the panel where it can be seen. */
	public static void accept(EditorState state) {
		waiting = state;
		WorkspaceScreen.reveal("graph");
		WorkspaceScreen.deliver(GraphPanel.class, GraphPanel::take);
	}

	/** The answer to "which dialogue" arrived, or somebody asked to change it. */
	public static void choose() {
		WorkspaceScreen.reveal("graph");
		WorkspaceScreen.deliver(GraphPanel.class, panel -> {
			panel.choosing = true;
			panel.scroll = 0;
			panel.asked = true;
		});
	}

	private void take() {
		choosing = false;
		remake();
	}

	@Override
	public String id() {
		return "graph";
	}

	@Override
	public int minimumWidth() {
		return 240;
	}

	@Override
	public int minimumHeight() {
		return 100;
	}

	@Override
	protected Screen make() {
		showing = waiting;
		return showing == null ? null : new GraphEditorScreen(showing);
	}

	/** Whether this panel has already asked the server what dialogues there are. */
	private boolean asked;

	@Override
	public void opened() {
		ask();
	}

	@Override
	public void tick() {
		super.tick();
		// Also from the tick, and that is the fix rather than belt and braces:
		// opened() fires when somebody switches to a tab, and the dialogue panel is
		// showing from the moment the workspace opens — so nobody ever switched to
		// it, nobody ever asked, and the list of dialogues was empty for ever.
		ask();
	}

	private void ask() {
		if (asked || showing != null) return;
		asked = true;
		ClientPlayNetworking.send(new EditorPayloads.Browse());
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (showing != null && !choosing) {
			super.draw(graphics, mouseX, mouseY, delta);
			return;
		}
		drawChooser(graphics, mouseX, mouseY);
	}

	private void drawChooser(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> names = DialogueNames.known();
		graphics.text(font, Component.translatable("npc_studio.graph.choose"), PAD, PAD, TEXT_DIM);

		int top = PAD + 14;
		int hovered = rowAt(mouseX, mouseY, names.size() + 1);

		// The new one first, because an empty world has nothing else to click and
		// the whole reason somebody is looking at this list is to get to work.
		drawRow(graphics, top, Icon.ADD, Component.translatable("npc_studio.graph.new"),
			hovered == 0, ACCENT);

		for (int i = 0; i < names.size(); i++) {
			int y = top + (i + 1) * ROW - scroll;
			if (y + ROW < top || y > height) continue;
			drawRow(graphics, y, Icon.DIALOGUE, Component.literal(names.get(i)),
				hovered == i + 1, TEXT);
		}
	}

	private void drawRow(GuiGraphicsExtractor graphics, int top, Icon icon, Component label,
			boolean hovered, int ink) {
		if (hovered) graphics.fill(0, top, width, top + ROW, ROW_HOVER);
		icon.draw(graphics, PAD, top + (ROW - Icon.SIZE) / 2, hovered ? ACCENT : TEXT_DIM);
		graphics.text(font, label, PAD + Icon.SIZE + 5, top + (ROW - 8) / 2, hovered ? ACCENT : ink);
	}

	private int rowAt(double mouseX, double mouseY, int rows) {
		if (!inside(mouseX, mouseY)) return -1;
		int top = PAD + 14;
		int row = (int) ((mouseY - top + scroll) / ROW);
		// The new-dialogue row does not scroll with the rest; it is always first.
		if (mouseY >= top && mouseY < top + ROW) return 0;
		return row > 0 && row < rows ? row : -1;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (showing != null && !choosing) return super.mouseClicked(event, doubleClick);

		List<String> names = DialogueNames.known();
		int row = rowAt(event.x(), event.y(), names.size() + 1);
		if (row < 0) return false;
		// An empty name is how the server is asked for a fresh one; that is the
		// same request the list screen made before this panel existed.
		ClientPlayNetworking.send(new EditorPayloads.Open(row == 0 ? "" : names.get(row - 1)));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (showing != null && !choosing) return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
		int rows = DialogueNames.known().size() + 1;
		scroll = Math.max(0, Math.min(Math.max(0, rows * ROW - height + PAD * 3),
			scroll - (int) (amountY * ROW)));
		return true;
	}
}
