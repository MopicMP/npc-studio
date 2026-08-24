package com.mopicmp.npcstudio.client.editor;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Who made the animations.
 *
 * Reachable from the picker rather than buried in a menu nobody opens, because
 * the moment somebody is choosing an animation is the moment they might wonder
 * where it came from — and it is the only moment they will.
 *
 * The links cannot be clicked. Opening a browser from inside a game is a thing
 * players are right to be wary of, and a line of text that can be read and
 * typed is enough for a credit.
 */
public class CreditsScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	private static final List<String> LINES = List.of(
		"§bSPEmotes",
		"Все анимации персонажей — работа SPEmotes.",
		"Файлы включены в мод как есть, без изменений.",
		"",
		"Сайт:    https://spemotes.com/",
		"Boosty:  https://boosty.to/spemotes",
		"",
		"Пак распространяется свободно для некоммерческого",
		"использования. В мод вошло только то, что лежит",
		"в открытом доступе — платных эксклюзивов здесь нет.",
		"Если пак вам пригодился, поддержите автора.",
		"",
		"§7Права на сторонние материалы принадлежат их авторам.",
		"§7Если вы автор и возражаете против присутствия своей",
		"§7работы здесь — напишите, и она будет удалена.",
		"",
		"§bФормат",
		"Анимации в формате EmoteCraft. Мод читает его же,",
		"так что свои эмоции можно добавить ресурспаком.");

	private final Screen parent;

	public CreditsScreen(Screen parent) {
		super(Component.literal("Благодарности"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(new FlatButton(width / 2 - 52, height - 34, 104, 20,
			Component.literal("back"), ACCENT, () -> leave()));
	}

	@Override
	public void onClose() {
		leave();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);

		int left = Math.max(24, width / 2 - 180);
		graphics.text(font, title, left, 24, TEXT);

		int y = 48;
		for (String line : LINES) {
			if (!line.isEmpty()) {
				graphics.text(font, Component.literal(line), left, y,
					line.startsWith("§") ? TEXT : TEXT_DIM);
			}
			y += font.lineHeight + 3;
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
	/** Nowhere to go back to means staying put; see the other editors. */
	private void leave() {
		if (parent != null) minecraft.setScreenAndShow(parent);
	}

}
