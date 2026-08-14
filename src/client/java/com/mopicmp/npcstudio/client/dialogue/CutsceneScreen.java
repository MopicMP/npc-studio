package com.mopicmp.npcstudio.client.dialogue;

import com.mopicmp.npcstudio.client.NpcStudioConfig;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.AdvancePayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * A staged scene: the camera has left, and so has the player's control of it.
 *
 * A {@code Screen} again, and again because that is what stops the player
 * moving. It draws almost nothing — no hotbar, no health, only the line — since
 * the hotbar belongs to someone playing, and for the length of a scene nobody
 * is.
 *
 * Escape ends the scene rather than suspending it. That is the one place it
 * differs from a question: a question waits for an answer and a scene simply
 * plays, so leaving early means the camera comes home and the conversation is
 * where it was.
 */
public class CutsceneScreen extends Screen {

	private static final int BAR_HEIGHT = 46;
	private static final int MARGIN = 40;
	private static final int PADDING = 12;
	private static final int ICON = 24;

	private static final int BAR = 0xD40C0F13;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF6E7A87;
	private static final int ACCENT = 0xFF4FC3F7;

	/**
	 * The instance on screen, not a flag.
	 *
	 * A flag was wrong in a way that only showed on the second line: the new
	 * screen's init ran before the old one's removed, so the old one switched the
	 * flag off again and the bar started drawing underneath — the same line twice.
	 * Comparing identities means a screen can only ever clear itself.
	 */
	private static Object open;

	public static boolean isOpen() {
		return open != null;
	}

	private final DialogueClientState state;

	public CutsceneScreen(DialogueClientState state) {
		super(Component.literal("Scene"));
		this.state = state;
	}

	@Override
	protected void init() {
		open = this;
		DialogueCamera.beginScene(state.npcId());
	}

	@Override
	public void removed() {
		if (open == this) open = null;
		// The camera flies home rather than cutting: a scene that ends by snapping
		// back undoes the impression the flight out just made.
		DialogueCamera.endScene();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!state.finishedTyping(NpcStudioConfig.get().typingSpeed)) {
			DialogueClientState.skipTyping();
			return true;
		}
		ClientPlayNetworking.send(new AdvancePayload(state.npcId()));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Bars top and bottom rather than a wash over everything. A scene is being
		// shown rather than played, and the shape says so before a word is read.
		int bar = Math.max(BAR_HEIGHT / 2, height / 10);
		graphics.fill(0, 0, width, bar, 0xFF000000);
		graphics.fill(0, height - bar, width, height, 0xFF000000);

		int dim = NpcStudioConfig.get().dimColour() ;
		if (dim != 0) graphics.fill(0, bar, width, height - bar, dim & 0x40FFFFFF);

		int top = height - bar - BAR_HEIGHT - 8;
		int left = MARGIN;
		int right = width - MARGIN;
		graphics.fill(left, top, right, top + BAR_HEIGHT, BAR);
		graphics.fill(left, top, left + 2, top + BAR_HEIGHT, ACCENT);

		int cursor = left + PADDING;
		Minecraft client = Minecraft.getInstance();
		Entity speaker = client.level == null ? null : client.level.getEntity(state.npcId());
		if (speaker instanceof NpcEntity npc) {
			DialogueIcon.draw(graphics, npc, cursor, top + PADDING, ICON);
			cursor += ICON + PADDING;
		}

		int textTop = top + PADDING;
		if (!state.speaker().isEmpty()) {
			graphics.text(font, Component.literal(state.speaker()), cursor, textTop, ACCENT);
			textTop += font.lineHeight + 4;
		}
		String visible = state.visibleText(NpcStudioConfig.get().typingSpeed);
		for (var row : font.split(Component.literal(visible), right - PADDING - cursor)) {
			graphics.text(font, row, cursor, textTop, TEXT);
			textTop += font.lineHeight + 2;
		}

		String hint = "click to go on  ·  esc to end the scene";
		graphics.text(font, Component.literal(hint),
			right - font.width(hint), height - bar + 6, TEXT_DIM);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
