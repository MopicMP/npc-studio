package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.AnimationPickerScreen;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;
import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The animation library, as a panel.
 *
 * <h2>What "apply" means here</h2>
 *
 * On a screen of its own this was always opened with a question attached —
 * "choose the walking animation for this character" — and it answered that one
 * question and closed. As a panel it is usually just open, with nobody having
 * asked anything, and its buttons did nothing at all: apply had no destination,
 * cancel had nothing to go back to, credits had nowhere to open.
 *
 * So the destination is chosen here, in the strip along the top, and apply
 * always means something. A request from the character panel or from the graph
 * still overrides it — that is somebody asking a specific question, and the
 * answer belongs to them.
 *
 * <h2>Where the preview went</h2>
 *
 * Into the scene. The figure that used to stand beside the grid was a second,
 * smaller copy of a character already standing in the viewport, so highlighting
 * an animation now plays it on the real one. It is played on this client only
 * and put back when the panel is finished with, because looking at a gesture is
 * not the same as giving somebody one.
 */
public class AnimationPanel extends ScreenPanel {

	private static final int STRIP = 18;
	private static final int ROW = 18;

	private static final int BAR = 0xFF12161C;
	private static final int PANEL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int HOVER = 0xFF232A34;

	/** The request being answered, so a new one is noticed. */
	private Workspace.Pick answering;

	/** Which of the character's states the choice lands in, when nobody has asked. */
	private int slot;
	private boolean open;

	/** Said when an answer arrives for a character nobody is looking at any more. */
	private String refused = "";

	/** What is being shown on the real character, and what it was doing before. */
	private String previewing = "";
	private int previewOn = -1;
	private String wasDoing = "";

	@Override
	public String id() {
		return "animation";
	}

	@Override
	public int minimumWidth() {
		return 340;
	}

	@Override
	public int minimumHeight() {
		return 190;
	}

	@Override
	protected int insetTop() {
		return STRIP;
	}

	@Override
	protected Screen make() {
		Workspace.Pick request = Workspace.animationPick();
		answering = request;
		String current = request == null ? "" : request.current();
		int who = Workspace.selected();
		return new AnimationPickerScreen(null, current,
			() -> AnimationPickerScreen.lookFor(who), this::apply);
	}

	/**
	 * What the "use this" button now does.
	 *
	 * Whoever asked gets the answer; with nobody asking it goes into the state
	 * named in the strip. Either way the live preview stops, because the choice
	 * has been made and the character should go back to standing about.
	 */
	private void apply(String picked) {
		if (answering != null) {
			// Whoever asked decides whether the answer still means anything — they are
			// the ones who know what it was for. A refusal is said out loud rather than
			// swallowed: a button that does nothing teaches nobody why.
			if (answering.about() >= 0 && answering.about() != Workspace.selected()) {
				refused = Component.translatable("npc_studio.animation.moved_on").getString();
				return;
			}
			answering.chose().accept(picked);
			Workspace.answered();
			answering = null;
		} else {
			int which = slot;
			WorkspaceScreen.reveal("character");
			WorkspaceScreen.deliver(CharacterPanel.class, panel -> panel.setAnimation(which, picked));
		}
		stopPreview();
	}

	// ---------------------------------------------------------------- preview

	@Override
	public void tick() {
		super.tick();
		if (Workspace.animationPick() != answering) {
			// A new question clears the last refusal: it was about the old one, and a
			// warning left standing over a fresh question is a warning about nothing.
			refused = "";
			remake();
			return;
		}
		if (!(inner() instanceof AnimationPickerScreen picker)) return;

		String want = picker.selected();
		if (want == null) want = "";
		// Shown on whoever the question is about, not on whoever happens to be selected.
		// The two are the same nearly always, and when they are not it is because the
		// selection has moved — and a preview playing on one character while the answer
		// is destined for another is the fault this pair was reported as.
		int on = answering != null && answering.about() >= 0
			? answering.about() : Workspace.selected();
		if (want.equals(previewing) && previewOn == on) return;

		stopPreview();
		NpcEntity npc = minecraft.level == null || on < 0 ? null
			: minecraft.level.getEntity(on) instanceof NpcEntity found ? found : null;
		if (npc == null || want.isEmpty()) return;

		// Remembered before it is replaced, so leaving puts the character back the
		// way it was found rather than the way it was last looked at.
		wasDoing = npc.gesture();
		previewOn = npc.getId();
		previewing = want;
		npc.playGesture(want, 0);
	}

	private void stopPreview() {
		if (previewOn < 0) return;
		if (minecraft.level != null
			&& minecraft.level.getEntity(previewOn) instanceof NpcEntity npc) {
			npc.playGesture(wasDoing, 0);
		}
		previewOn = -1;
		previewing = "";
		wasDoing = "";
	}

	@Override
	public void closed() {
		stopPreview();
		super.closed();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, STRIP, BAR);
		graphics.fill(0, STRIP - 1, width, STRIP, EDGE);

		boolean hovered = mouseY < STRIP && answering == null;
		Icon.PLAY.draw(graphics, 4, (STRIP - Icon.SIZE) / 2, hovered ? ACCENT : TEXT_DIM);
		graphics.text(font, heading(), 4 + Icon.SIZE + 4, (STRIP - 8) / 2,
			hovered ? ACCENT : TEXT);
		if (answering == null) {
			graphics.fill(width - 10, 8, width - 4, 9, hovered ? ACCENT : TEXT_DIM);
			graphics.fill(width - 9, 9, width - 5, 10, hovered ? ACCENT : TEXT_DIM);
		}
		// Why the button just did nothing. Along the bottom, out of the way of the
		// grid: it is an answer to something somebody pressed a moment ago, not a
		// state of the panel.
		if (!refused.isEmpty()) {
			graphics.text(font, Component.literal(refused), 4, height - 10, 0xFFE0A34A);
		}

		super.draw(graphics, mouseX, mouseY, delta);
	}

	/**
	 * What the strip says.
	 *
	 * When somebody has asked a question it says so and stops offering a choice,
	 * because the destination is not ours to change — the character panel asked
	 * for its walking animation and getting its idle one back would be a bug
	 * wearing a dropdown.
	 */
	private Component heading() {
		if (answering != null) return Component.translatable("npc_studio.animation.asked");
		return Component.translatable("npc_studio.animation.into",
			Component.translatable("npc_studio.motion." + state(slot)));
	}

	private static String state(int which) {
		return NpcEntity.Motion.values()[which].name().toLowerCase(java.util.Locale.ROOT);
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (!open) return;
		List<NpcEntity.Motion> states = List.of(NpcEntity.Motion.values());
		int bottom = STRIP + states.size() * ROW;
		graphics.fill(0, STRIP, Math.min(width, 160), bottom, PANEL);
		graphics.fill(0, bottom, Math.min(width, 160), bottom + 1, EDGE);
		for (int i = 0; i < states.size(); i++) {
			int top = STRIP + i * ROW;
			boolean hovered = mouseY >= top && mouseY < top + ROW && mouseX >= 0 && mouseX < 160;
			if (hovered) graphics.fill(0, top, Math.min(width, 160), top + ROW, HOVER);
			graphics.text(font, Component.translatable("npc_studio.motion." + state(i)),
				8, top + (ROW - 8) / 2, i == slot ? ACCENT : TEXT_DIM);
		}
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (open) {
			int row = (int) ((event.y() - STRIP) / ROW);
			if (event.y() >= STRIP && event.x() < 160
				&& row >= 0 && row < NpcEntity.Motion.values().length) {
				slot = row;
			}
			open = false;
			return true;
		}
		if (event.y() < STRIP) {
			if (answering == null) open = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
