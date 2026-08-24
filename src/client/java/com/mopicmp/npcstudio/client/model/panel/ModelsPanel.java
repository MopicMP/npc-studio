package com.mopicmp.npcstudio.client.model.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.model.Modelling;
import com.mopicmp.npcstudio.client.model.ModelStore;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The models on disk.
 *
 * A folder beside the config, one file each, in Blockbench's own format. That
 * last part is worth more than it sounds: a model roughed out here can be opened
 * in Blockbench to be textured properly and brought back, with nothing exported
 * and nothing converted in either direction.
 */
public class ModelsPanel extends WorkspacePanel {

	private static final int ROW = 14;
	private static final int PAD = 6;
	private static final int TOOLBAR = 24;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int ROW_ON = 0xFF27313E;
	private static final int ROW_HOVER = 0xFF232A34;

	private List<String> names = List.of();
	private int scroll;

	@Override
	public String id() {
		return "models";
	}

	@Override
	public int minimumWidth() {
		return 140;
	}

	@Override
	protected void build() {
		int across = width - PAD * 2;
		if (across < 60) return;

		// One button. Making a model is Add > Cube in the toolbar, and saving
		// happens by itself — what is left is putting an already-made model into
		// the world a second time, which is a real thing to want and the only
		// thing here that was not covered.
		IconTextButton.row(PAD, PAD, across, 18, List.of(
			new IconTextButton.Spec(Icon.SCENE, Component.translatable("npc_studio.model.place"),
				ACCENT, Modelling::place)), this::add);
	}

	@Override
	public void opened() {
		names = ModelStore.names();
	}

	private int since;

	@Override
	public void tick() {
		// Re-listed regularly rather than once: models are made from the toolbar
		// while this panel is open, and a folder is edited from outside too — a
		// model dropped in from Blockbench should turn up without a restart.
		if (++since < 20 && !names.isEmpty()) return;
		since = 0;
		names = ModelStore.names();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (names.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.model.no_models"),
				PAD, TOOLBAR, TEXT_DIM);
			return;
		}

		int hovered = rowAt(mouseX, mouseY);
		for (int i = 0; i < names.size(); i++) {
			int top = TOOLBAR + i * ROW - scroll;
			if (top + ROW < TOOLBAR || top > height) continue;

			boolean on = names.get(i).equals(Modelling.name());
			if (on || i == hovered) graphics.fill(0, top, width, top + ROW, on ? ROW_ON : ROW_HOVER);
			Icon.ASSETS.draw(graphics, PAD, top + (ROW - Icon.SIZE) / 2, on ? ACCENT : TEXT_DIM);

			String label = names.get(i) + (on && Modelling.dirty() ? " *" : "");
			graphics.text(font, Component.literal(label),
				PAD + Icon.SIZE + 3, top + 3, on ? ACCENT : TEXT);
		}
	}

	private int rowAt(double mouseX, double mouseY) {
		if (!inside(mouseX, mouseY) || mouseY < TOOLBAR) return -1;
		int row = (int) ((mouseY - TOOLBAR + scroll) / ROW);
		return row >= 0 && row < names.size() ? row : -1;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int row = rowAt(event.x(), event.y());
		if (row < 0) return super.mouseClicked(event, doubleClick);
		Modelling.openModel(names.get(row));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		int most = Math.max(0, names.size() * ROW - height + TOOLBAR);
		scroll = Math.max(0, Math.min(most, scroll - (int) (amountY * ROW)));
		return true;
	}
}
