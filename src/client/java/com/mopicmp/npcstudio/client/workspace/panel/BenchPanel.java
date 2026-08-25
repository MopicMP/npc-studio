package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.net.BenchPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A bench for trying things on a character before there is a graph to order them.
 *
 * <h2>Why this exists, and why it is one panel</h2>
 *
 * Everything here used to be a command. Each was written with a comment saying
 * "scaffolding, this belongs in the character panel", and every one of them
 * stayed, because a thing spread across a command tree has no edge and nobody
 * ever takes it out. Seven of them had accumulated before anybody counted.
 *
 * So the rule now is a place rather than an intention. Anything temporary goes
 * here; when the panel is empty, the panel goes, and with it one entry in
 * {@link com.mopicmp.npcstudio.client.workspace.Panels}, one payload file and
 * one handler. Turning it off in the meantime is closing it.
 *
 * <h2>What may be in it</h2>
 *
 * An action a graph will eventually order and cannot yet, or a readout of
 * something invisible from outside. Not a setting: a setting is a fact about a
 * character and belongs on the character panel — which is where "watchful",
 * "endless ammo" and "which brain" have gone, and where the hands already were
 * before the commands duplicated them.
 */
public class BenchPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int ROW = 18;
	private static final int GAP = 4;
	private static final int LABEL = 10;

	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int TEXT = 0xFFECEFF1;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int WARM = 0xFFFF8A65;

	/**
	 * What came back, last thing first.
	 *
	 * Kept and drawn rather than sent to chat, because chat is where the readout
	 * used to go and it scrolled away behind whatever else the game was saying.
	 * Reading a character's ten senses out of a chat box while walking backwards
	 * is worse than not having them.
	 */
	private final List<String> said = new ArrayList<>();

	/** One group of buttons: what they are for, said once above them. */
	private record Group(String heading, List<Deed> deeds) { }

	private record Deed(Icon icon, String label, String action) { }

	/**
	 * Grouped rather than listed, because an undifferentiated column of buttons
	 * makes the reader work out for themselves which ones are safe to press.
	 */
	private static final List<Group> GROUPS = List.of(
		new Group("npc_studio.bench.looking", List.of(
			new Deed(Icon.EYES, "npc_studio.bench.senses", "senses"),
			new Deed(Icon.SEARCH, "npc_studio.bench.watch", "watch"),
			new Deed(Icon.BODY, "npc_studio.bench.brain", "brain"))),
		new Group("npc_studio.bench.doing", List.of(
			new Deed(Icon.MAIN_HAND, "npc_studio.bench.fire", "fire"))),
		new Group("npc_studio.bench.memory", List.of(
			new Deed(Icon.RESET, "npc_studio.bench.forget", "forget"))));

	@Override
	public String id() {
		return "bench";
	}

	@Override
	public int minimumWidth() {
		return 150;
	}

	@Override
	protected void build() {
		if (Workspace.selected() < 0) return;

		int across = width - PAD * 2;
		if (across < 60) return;

		int y = PAD;
		for (Group group : GROUPS) {
			y += LABEL;
			for (Deed deed : group.deeds()) {
				add(new IconTextButton(PAD, y, across, ROW, deed.icon(),
					Component.translatable(deed.label()),
					deed.action().equals("forget") ? WARM : ACCENT,
					() -> ask(deed.action())));
				y += ROW + GAP;
			}
			y += GAP;
		}
	}

	private void ask(String action) {
		int about = Workspace.selected();
		if (about < 0) return;
		said.clear();
		ClientPlayNetworking.send(new BenchPayloads.Ask(about, action));
	}

	/** Called from the packet: the server has answered. */
	public void accept(BenchPayloads.Told told) {
		said.clear();
		said.addAll(told.lines());
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (Workspace.selected() < 0) {
			graphics.text(font, Component.translatable("npc_studio.character.nothing"),
				PAD, PAD, TEXT_DIM);
			return;
		}

		int y = PAD;
		for (Group group : GROUPS) {
			graphics.text(font, Component.translatable(group.heading()), PAD, y, TEXT_DIM);
			y += LABEL + group.deeds().size() * (ROW + GAP) + GAP;
		}

		// Whatever the last thing pressed said, filling the rest of the panel. It
		// stops where the panel does rather than scrolling: a readout that needs
		// scrolling is a readout that should have been shorter.
		for (String line : said) {
			if (y > height - LABEL) break;
			graphics.text(font, Component.literal(line), PAD, y, TEXT);
			y += LABEL;
		}
	}
}
