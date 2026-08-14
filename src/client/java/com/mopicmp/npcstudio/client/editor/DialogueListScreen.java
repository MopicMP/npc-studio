package com.mopicmp.npcstudio.client.editor;

import java.util.List;

import com.mopicmp.npcstudio.net.EditorPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Which conversation to work on.
 *
 * Until now the only way in was to know the name and type it, which meant the
 * editor could not be reached by anyone who had not just made something — and
 * a name typed from memory is a name typed wrong.
 *
 * Names carrying a namespace come from a datapack; the rest were made here.
 * Both open the same way, and a datapack one edited in-game becomes the world's
 * own copy on save, leaving the file it came from untouched.
 */
public class DialogueListScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ROW = 22;
	private static final int WIDTH = 260;

	private final List<String> names;
	private int scroll;

	public DialogueListScreen(List<String> names) {
		super(Component.literal("Dialogues"));
		this.names = names;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH / 2;
		int top = 52;
		int visible = Math.max(1, (height - top - 70) / ROW);

		for (int i = 0; i < visible && scroll + i < names.size(); i++) {
			String name = names.get(scroll + i);
			addRenderableWidget(new FlatButton(left, top + i * ROW, WIDTH, 20,
				Component.literal(name), 0xFF4FC3F7,
				() -> ClientPlayNetworking.send(new EditorPayloads.Open(name))));
		}

		int bottom = height - 40;
		addRenderableWidget(new FlatButton(left, bottom, 124, 20,
			Component.literal("+ new dialogue"), 0xFF66BB6A,
			() -> ClientPlayNetworking.send(new EditorPayloads.Open(""))));
		addRenderableWidget(new FlatButton(left + 132, bottom, 60, 20,
			Component.literal("up"), 0xFF8A99A6, () -> scrollBy(-1)));
		addRenderableWidget(new FlatButton(left + 196, bottom, 64, 20,
			Component.literal("down"), 0xFF8A99A6, () -> scrollBy(1)));
	}

	private void scrollBy(int by) {
		int visible = Math.max(1, (height - 52 - 70) / ROW);
		scroll = Math.clamp(scroll + by, 0, Math.max(0, names.size() - visible));
		clearWidgets();
		init();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, width / 2 - font.width(title) / 2, 22, TEXT);
		if (names.isEmpty()) {
			String empty = "nothing here yet — start one";
			graphics.text(font, Component.literal(empty), width / 2 - font.width(empty) / 2, 60, TEXT_DIM);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
