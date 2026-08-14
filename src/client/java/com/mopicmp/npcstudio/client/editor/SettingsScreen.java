package com.mopicmp.npcstudio.client.editor;

import com.mopicmp.npcstudio.client.NpcStudioConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The handful of settings that belong to whoever is looking at the screen.
 *
 * They were in a file and nowhere else, which is the same as not existing:
 * nobody edits a config file to try a value, they try it and then decide. Both
 * of these are about how a conversation feels, and feel is something you have
 * to see.
 *
 * Reached from the editor rather than from the game's own options, because the
 * person who cares is the one building conversations — and it saves inventing
 * a place in a menu that belongs to somebody else.
 */
public class SettingsScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int WIDTH = 240;

	private final Screen parent;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH / 2;
		int y = 60;

		NpcStudioConfig config = NpcStudioConfig.get();

		// Steps rather than a slider: five values are enough to choose between,
		// and a slider would invite fiddling with a number nobody can perceive to
		// the nearest percent.
		addRenderableWidget(new FlatButton(left, y, WIDTH, 20,
			Component.literal("darkening behind: " + describeDim(config.dialogueDim)), 0xFF4FC3F7, () -> {
				config.dialogueDim = switch (config.dialogueDim) {
					case 0 -> 20;
					case 20 -> 40;
					case 40 -> 60;
					case 60 -> 80;
					default -> 0;
				};
				config.save();
				rebuild();
			}));
		y += 42;

		addRenderableWidget(new FlatButton(left, y, WIDTH, 20,
			Component.literal("typing speed: " + config.typingSpeed + " a second"), 0xFF66BB6A, () -> {
				config.typingSpeed = switch (config.typingSpeed) {
					case 25 -> 45;
					case 45 -> 70;
					case 70 -> 120;
					case 120 -> 400;
					default -> 25;
				};
				config.save();
				rebuild();
			}));
		y += 52;

		addRenderableWidget(new FlatButton(left, y, WIDTH, 20,
			Component.literal("back"), 0xFF8A99A6, () -> minecraft.setScreenAndShow(parent)));
	}

	private static String describeDim(int value) {
		return value == 0 ? "off" : value + "%";
	}

	private void rebuild() {
		clearWidgets();
		init();
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, width / 2 - font.width(title) / 2, 28, TEXT);

		int left = width / 2 - WIDTH / 2;
		graphics.text(font, Component.literal("how much the world dims during a full-screen scene"),
			left, 48, TEXT_DIM);
		graphics.text(font, Component.literal("how fast a line writes itself out"),
			left, 130, TEXT_DIM);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
