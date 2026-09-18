package com.mopicmp.npcstudio.client.model.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.model.Modelling;
import com.mopicmp.npcstudio.client.model.ModelStore;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.WorkspaceCamera;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.entity.ModelObject;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * What has actually been put in the world.
 *
 * <h2>Why this had to exist</h2>
 *
 * Because an object could be placed and then not found. A model is made once,
 * put down once and looked for repeatedly, and a white plate on a grey hillside
 * is not something the eye picks out — "I cannot find any objects" was the first
 * thing said about the first version, and it was a fair thing to say.
 *
 * So: a list, nearest first, with how far off each one is. Clicking takes the
 * camera to it and opens its model. That is the shortest possible answer to
 * "where did it go", and it also happens to be the way to get back to something
 * built yesterday.
 */
public class PlacedPanel extends WorkspacePanel {

	private static final int ROW = 14;
	private static final int PAD = 6;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int ROW_ON = 0xFF27313E;
	private static final int ROW_HOVER = 0xFF232A34;

	private List<ModelObject> objects = List.of();

	@Override
	public String id() {
		return "placed";
	}

	@Override
	public int minimumWidth() {
		return 140;
	}

	@Override
	public void tick() {
		// Asked every tick rather than cached: objects are placed and removed while
		// this is open, and a list that needs refreshing by hand is a list that is
		// wrong exactly when somebody is looking for something.
		objects = Modelling.placed();
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (objects.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.model.none_placed"),
				PAD, PAD, TEXT_DIM);
			return;
		}

		int hovered = rowAt(mouseX, mouseY);
		for (int i = 0; i < objects.size(); i++) {
			int top = PAD + i * ROW;
			if (top + ROW > height) break;
			ModelObject object = objects.get(i);

			boolean on = object.model().equals(Modelling.name());
			if (on || i == hovered) graphics.fill(0, top, width, top + ROW, on ? ROW_ON : ROW_HOVER);
			Icon.SCENE.draw(graphics, PAD, top + (ROW - Icon.SIZE) / 2, on ? ACCENT : TEXT_DIM);

			// The name, and how far away it is. Distance is what turns a list into
			// directions — so when the row is too narrow for both, the distance is the
			// half that survives and the name is the half that gets cut.
			//
			// It used to be neither: the name was drawn at full length and the distance
			// over the top of it. Model names are long by habit — they are file names —
			// so in a panel a hundred and forty wide that was most rows.
			String away = "%.0f".formatted(
				minecraft.player == null ? 0 : minecraft.player.distanceTo(object));
			int end = width - PAD - font.width(away);
			int nameLeft = PAD + Icon.SIZE + 3;
			graphics.text(font, Component.literal(shortened(object.model(), end - nameLeft - 4)),
				nameLeft, top + 3, on ? ACCENT : TEXT);
			graphics.text(font, Component.literal(away), end, top + 3, TEXT_DIM);
		}
	}

	private int rowAt(double mouseX, double mouseY) {
		if (!inside(mouseX, mouseY)) return -1;
		int row = (int) ((mouseY - PAD) / ROW);
		return row >= 0 && row < objects.size() ? row : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int row = rowAt(event.x(), event.y());
		if (row < 0) return false;
		ModelObject object = objects.get(row);
		// The camera first, because the question this answers is "where is it".
		WorkspaceCamera.frame(object);
		if (ModelStore.get(object.model()) != null) Modelling.openModel(object.model());
		return true;
	}
}
