package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.ItemPickerScreen;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * What there is to put in a scene.
 *
 * The grid is the item picker, which already knew how to find one thing among
 * several thousand. What is new is the question above it: picking something has
 * to mean something, and it can mean more than one thing — put it in a hand,
 * put it in the world, hang it off a bone. So the destination is chosen first
 * and the grid answers to it.
 *
 * A dropdown rather than a row of buttons, and it will keep being one: the list
 * of things a chosen object can become is going to grow with the mod, and a row
 * that grows runs out of panel.
 */
public class AssetsPanel extends ScreenPanel {

	private static final int STRIP = 18;
	private static final int ROW = 18;

	private static final int BAR = 0xFF12161C;
	private static final int PANEL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int HOVER = 0xFF232A34;

	/** Where a chosen thing goes. The ones that are not built yet are not offered. */
	private static final List<String> WHERE = List.of("main_hand", "off_hand");

	private int destination;
	private boolean open;

	@Override
	public String id() {
		return "assets";
	}

	@Override
	public int minimumWidth() {
		return 150;
	}

	@Override
	protected int insetTop() {
		return STRIP;
	}

	@Override
	protected Screen make() {
		return new ItemPickerScreen(null, this::give);
	}

	/**
	 * Hands the chosen item to the character panel rather than to the server.
	 *
	 * The character panel owns what a character is wearing and holding — it is
	 * the thing that knows all the other fields, and a change has to travel with
	 * them. Sending an item on its own would be a second way to edit a character,
	 * and the two would disagree about which of them was right.
	 */
	private void give(String item) {
		int slot = destination;
		WorkspaceScreen.reveal("character");
		WorkspaceScreen.deliver(CharacterPanel.class, panel -> panel.hold(slot, item));
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, STRIP, BAR);
		graphics.fill(0, STRIP - 1, width, STRIP, EDGE);

		boolean hovered = mouseY < STRIP;
		icon(destination).draw(graphics, 4, (STRIP - Icon.SIZE) / 2, hovered ? ACCENT : TEXT_DIM);
		graphics.text(font, label(destination), 4 + Icon.SIZE + 4, (STRIP - 8) / 2,
			hovered ? ACCENT : TEXT);
		// The mark that says there is more behind this word.
		graphics.fill(width - 10, 6, width - 4, 7, hovered ? ACCENT : TEXT_DIM);
		graphics.fill(width - 9, 7, width - 5, 8, hovered ? ACCENT : TEXT_DIM);

		super.draw(graphics, mouseX, mouseY, delta);
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (!open) return;
		int bottom = STRIP + WHERE.size() * ROW;
		graphics.fill(0, STRIP, width, bottom, PANEL);
		graphics.fill(0, bottom, width, bottom + 1, EDGE);
		for (int i = 0; i < WHERE.size(); i++) {
			int top = STRIP + i * ROW;
			boolean hovered = mouseY >= top && mouseY < top + ROW && mouseX >= 0 && mouseX < width;
			if (hovered) graphics.fill(0, top, width, top + ROW, HOVER);
			icon(i).draw(graphics, 4, top + (ROW - Icon.SIZE) / 2,
				i == destination ? ACCENT : TEXT_DIM);
			graphics.text(font, label(i), 4 + Icon.SIZE + 4, top + (ROW - 8) / 2,
				i == destination ? ACCENT : TEXT_DIM);
		}
	}

	private Icon icon(int index) {
		return index == 0 ? Icon.MAIN_HAND : Icon.OFF_HAND;
	}

	private Component label(int index) {
		return Component.translatable("npc_studio.assets." + WHERE.get(index));
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (open) {
			int row = (int) ((event.y() - STRIP) / ROW);
			if (event.y() >= STRIP && row >= 0 && row < WHERE.size()) destination = row;
			open = false;
			return true;
		}
		if (event.y() < STRIP) {
			open = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
