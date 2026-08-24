package com.mopicmp.npcstudio.client.workspace.panel;

import com.mopicmp.npcstudio.client.wardrobe.WardrobeScreen;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;
import com.mopicmp.npcstudio.client.workspace.Workspace;

import net.minecraft.client.gui.screens.Screen;

/**
 * The world's costumes, as a panel.
 *
 * The library is the world's rather than one character's, so most of what is
 * here does not depend on who is selected. Dressing somebody does, and that is
 * why the panel is rebuilt when the selection moves: the wardrobe was written
 * knowing which character it was dressing, and being told a different one
 * afterwards is not something it was asked to survive.
 */
public class WardrobePanel extends ScreenPanel {

	private int dressing = -1;

	@Override
	public String id() {
		return "wardrobe";
	}

	/**
	 * What the wardrobe needs before it is worth showing at all.
	 *
	 * The height was a hundred and ninety, which was a guess, and the guess was
	 * wrong by exactly the amount that made the panel look broken: the header, the
	 * editing block and the row of buttons came to a hundred and fifty-four of it
	 * between them, and the grid drew its costumes straight through the boxes with
	 * what was left. Two hundred and forty is measured rather than guessed — it is
	 * the chrome plus one row of costumes, which is the least that is still a
	 * wardrobe.
	 */
	@Override
	public int minimumWidth() {
		return 300;
	}

	@Override
	public int minimumHeight() {
		return 240;
	}

	@Override
	protected Screen make() {
		dressing = Workspace.selected();
		return new WardrobeScreen(null, dressing);
	}

	@Override
	public void tick() {
		super.tick();
		if (Workspace.selected() != dressing) remake();
	}
}
