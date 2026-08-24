package com.mopicmp.npcstudio.client.workspace.panel;

import com.mopicmp.npcstudio.client.editor.BodyShapeScreen;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.entity.BodyShape;
import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.gui.screens.Screen;

/**
 * The build of whoever is selected.
 *
 * The shape is read off the character rather than remembered here. There is one
 * copy of it, on the entity, and a panel holding a second one is a panel that
 * disagrees with the world the first time anything else changes it.
 */
public class BodyPanel extends ScreenPanel {

	private int about = -1;

	@Override
	public String id() {
		return "body";
	}

	@Override
	public int minimumWidth() {
		return 240;
	}

	@Override
	public int minimumHeight() {
		return 170;
	}

	/**
	 * Eleven sliders, seven presets and four buttons do not fit a side column.
	 *
	 * So the panel says how tall its contents really are and lets the adapter
	 * scroll them. Squeezing them into whatever height the column has would mean
	 * either overlapping rows or a "done" button below the bottom edge.
	 */
	@Override
	protected int contentHeight() {
		return inner() instanceof BodyShapeScreen body ? body.contentHeight() : 0;
	}

	@Override
	protected Screen make() {
		about = Workspace.selected();
		NpcEntity npc = Workspace.selectedNpc();
		return new BodyShapeScreen(null, about, npc == null ? BodyShape.DEFAULT : npc.bodyShape());
	}

	@Override
	public void tick() {
		super.tick();
		if (Workspace.selected() != about) remake();
	}
}
