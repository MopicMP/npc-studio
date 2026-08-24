package com.mopicmp.npcstudio.client.workspace.panel;

import com.mopicmp.npcstudio.client.editor.CreditsScreen;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;

import net.minecraft.client.gui.screens.Screen;

/**
 * Who made the animations.
 *
 * A panel because the button that opens it is in a panel, and a button that
 * opens nothing is worse than no button. It was doing nothing: the credits used
 * to replace the whole display, which inside the workspace would have thrown
 * the workspace away, so it had been guarded into silence.
 */
public class CreditsPanel extends ScreenPanel {

	@Override
	public String id() {
		return "credits";
	}

	@Override
	protected Screen make() {
		return new CreditsScreen(null);
	}
}
