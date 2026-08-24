package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.scene.Shaders;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Which shader pack the scene is being looked at through.
 *
 * <h2>Why this is a panel and not a trip to the settings</h2>
 *
 * Because judging a shot under the lighting it will be filmed under is something
 * you do a dozen times while framing it, and the way to do it now is to leave the
 * workspace, open the video settings, open the shader list, choose, come back,
 * and find the camera where you left it if you are lucky. A list beside the
 * viewport turns that into one click.
 *
 * <h2>The line at the bottom</h2>
 *
 * It says what was found rather than what was expected, and it is there because
 * the switching goes through Iris's own API by reflection — see
 * {@link Shaders} for why it could not be checked against anything here. If a
 * class or a method is not where it was expected, this line names it, which is
 * the difference between a feature that quietly does nothing and one that can be
 * fixed from a screenshot.
 */
public class ShadersPanel extends WorkspacePanel {

	private static final int ROW = 13;
	private static final int FOOT = 12;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int WARN = 0xFFE57373;
	private static final int ROW_ON = 0xFF27313E;
	private static final int ROW_HOVER = 0xFF232A34;

	private int scroll;

	@Override
	public String id() {
		return "shaders";
	}

	@Override
	public int minimumWidth() {
		return 180;
	}

	@Override
	public int minimumHeight() {
		return 90;
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (!Shaders.installed()) {
			// Not an error and not a failure of ours. Said as a fact, with the one
			// thing that would change it.
			graphics.text(font, Component.translatable("npc_studio.shaders.none"), 6, 6, TEXT_DIM);
			graphics.text(font, Component.translatable("npc_studio.shaders.install"),
				6, 18, TEXT_DIM);
			return;
		}

		List<String> packs = Shaders.packs();
		String on = Shaders.current();
		int room = Math.max(0, height - FOOT);
		scroll = Mth.clamp(scroll, 0, Math.max(0, packs.size() * ROW - room));
		int hovered = rowAt(mouseX, mouseY, packs.size());

		for (int i = 0; i < packs.size(); i++) {
			int top = i * ROW - scroll;
			if (top + ROW < 0 || top > room) continue;

			String pack = packs.get(i);
			boolean chosen = pack.equals(on);
			if (chosen || i == hovered) {
				graphics.fill(0, top, width, top + ROW, chosen ? ROW_ON : ROW_HOVER);
			}
			// The empty name is "no shaders", which is a choice rather than the
			// absence of one and reads better said than left blank.
			String said = pack.isEmpty()
				? Component.translatable("npc_studio.shaders.off").getString() : pack;
			graphics.text(font, Component.literal(trim(said, width - 12)), 6, top + 3,
				chosen ? ACCENT : TEXT);
		}

		String detail = Shaders.detail();
		if (!detail.isEmpty()) {
			graphics.text(font, Component.literal(trim(detail, width - 8)), 4, height - 10,
				Shaders.state() == Shaders.State.READY ? TEXT_DIM : WARN);
		}
	}

	private String trim(String said, int room) {
		return font.width(said) <= room ? said
			: font.plainSubstrByWidth(said, room - font.width("…")) + "…";
	}

	private int rowAt(double mouseX, double mouseY, int rows) {
		if (!inside(mouseX, mouseY)) return -1;
		if (mouseY > height - FOOT) return -1;
		int row = (int) ((mouseY + scroll) / ROW);
		return row >= 0 && row < rows ? row : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		List<String> packs = Shaders.packs();
		int row = rowAt(event.x(), event.y(), packs.size());
		if (row < 0) return false;
		Shaders.use(packs.get(row));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (!inside(mouseX, mouseY)) return false;
		scroll -= (int) (amountY * ROW);
		return true;
	}
}
