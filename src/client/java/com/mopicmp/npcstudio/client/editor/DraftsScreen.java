package com.mopicmp.npcstudio.client.editor;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Going back to how a dialogue was.
 *
 * The drafts are written by walking away rather than by asking, so this is the
 * only place they are ever seen. It is deliberately plain: a list of times,
 * newest first, and one button. Anyone opening it has lost something and wants
 * it back, not a file manager.
 */
public class DraftsScreen extends Screen {

	private static final int MARGIN = 24;
	private static final int ROW = 20;

	private static final int CANVAS = 0xFF101318;
	private static final int PANEL = 0xFF161A20;
	private static final int ROW_HOVER = 0xFF232A34;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	private final Screen parent;
	private final String dialogueId;
	private final Consumer<String> onRestore;
	private final List<DialogueDrafts.Draft> drafts;

	private int scroll;

	public DraftsScreen(Screen parent, String dialogueId, Consumer<String> onRestore) {
		super(Component.literal("Drafts of " + dialogueId));
		this.parent = parent;
		this.dialogueId = dialogueId;
		this.onRestore = onRestore;
		this.drafts = DialogueDrafts.of(dialogueId);
	}

	@Override
	protected void init() {
		addRenderableWidget(new FlatButton(MARGIN, height - MARGIN - 20, 104, 20,
			Component.literal("back"), 0xFF8A99A6, () -> minecraft.setScreenAndShow(parent)));
	}

	private int listTop() {
		return MARGIN + 30;
	}

	private int rows() {
		return Math.max(1, (height - listTop() - MARGIN - 30) / ROW);
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < MARGIN || mouseX > width - MARGIN) return -1;
		int row = (int) (mouseY - listTop()) / ROW + scroll;
		return row >= scroll && row < drafts.size() && row < scroll + rows() ? row : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		int row = rowAt(event.x(), event.y());
		if (row >= 0) {
			String json = DialogueDrafts.read(drafts.get(row));
			if (json != null) {
				onRestore.accept(json);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, drafts.size() - rows()));
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, MARGIN, MARGIN, TEXT);

		if (drafts.isEmpty()) {
			graphics.text(font, Component.literal(
				"Nothing kept yet. A draft is written whenever the editor is closed "
					+ "with changes that were never saved."),
				MARGIN, listTop(), TEXT_DIM);
			return;
		}

		int hovered = rowAt(mouseX, mouseY);
		for (int i = 0; i < rows() && scroll + i < drafts.size(); i++) {
			int index = scroll + i;
			int y = listTop() + i * ROW;
			graphics.fill(MARGIN, y, width - MARGIN, y + ROW - 2,
				index == hovered ? ROW_HOVER : PANEL);
			if (index == hovered) graphics.fill(MARGIN, y, MARGIN + 2, y + ROW - 2, ACCENT);

			graphics.text(font, Component.literal(drafts.get(index).when().replace('-', ':')),
				MARGIN + 8, y + 5, index == hovered ? ACCENT : TEXT);
			if (index == 0) {
				String newest = "most recent";
				graphics.text(font, Component.literal(newest),
					width - MARGIN - 8 - font.width(newest), y + 5, TEXT_DIM);
			}
		}

		String hint = "click one to load it into the editor";
		graphics.text(font, Component.literal(hint), MARGIN, height - MARGIN - 34, TEXT_DIM);
		graphics.fill(MARGIN, listTop() - 4, width - MARGIN, listTop() - 3, EDGE);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
