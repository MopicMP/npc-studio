package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.DialogueNames;
import com.mopicmp.npcstudio.client.editor.EditorState;
import com.mopicmp.npcstudio.client.editor.GraphEditorScreen;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.ScreenPanel;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;
import com.mopicmp.npcstudio.net.EditorPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The dialogue as boxes and wires, at the bottom of the workspace.
 *
 * <h2>Getting into one</h2>
 *
 * The panel used to say "no dialogue open" and stop there, which was true and
 * useless: the way in had been a screen of its own, and that screen no longer
 * opens. So the empty state is the way in. It lists what the world has, offers
 * a new one, and asks the server for the list if nobody has yet — a panel whose
 * empty state is a dead end is a panel nobody can start from.
 *
 * Under it sits the timeline, and the two are one arrangement rather than two
 * tabs of the same slot. What a node does and when it happens are the same
 * question asked twice.
 */
public class GraphPanel extends ScreenPanel {

	private static final int ROW = 16;
	private static final int PAD = 8;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int ROW_HOVER = 0xFF232A34;

	/** The dialogue being edited, as it arrived from the server. */
	private static EditorState waiting;

	/** Whichever state this panel is currently showing. */
	private EditorState showing;

	/** True while the list of dialogues is up, over whatever else is here. */
	private boolean choosing;
	private int scroll;

	/** Hands a dialogue to the panel and puts the panel where it can be seen. */
	public static void accept(EditorState state) {
		waiting = state;
		WorkspaceScreen.reveal("graph");
		WorkspaceScreen.deliver(GraphPanel.class, GraphPanel::take);
	}

	/** The answer to "which dialogue" arrived, or somebody asked to change it. */
	public static void choose() {
		WorkspaceScreen.reveal("graph");
		WorkspaceScreen.deliver(GraphPanel.class, panel -> {
			panel.choosing = true;
			panel.scroll = 0;
			panel.asked = true;
		});
	}

	private void take() {
		choosing = false;
		remake();
	}

	@Override
	public String id() {
		return "graph";
	}

	/**
	 * A conversation is a shape, and a shape needs both directions.
	 *
	 * The same reason as the bench: what a branching dialogue tells you is which
	 * answer leads where, and that is read off the distance between nodes. Squeezed
	 * into a column it is a list of boxes with the one useful thing — the layout —
	 * taken out of it.
	 */
	@Override
	public boolean wholeWindow() {
		return true;
	}

	@Override
	public int minimumWidth() {
		return 240;
	}

	@Override
	public int minimumHeight() {
		return 100;
	}

	/**
	 * The text window closes before this panel does.
	 *
	 * Escape means "get me out of this" starting from the inside, and the innermost
	 * thing here is a window drawn over the graph. Without this, escape over an open
	 * text window would close the panel and take the half-written line with it.
	 */
	@Override
	public boolean escape() {
		Screen inner = inner();
		return inner instanceof com.mopicmp.npcstudio.client.editor.GraphEditorScreen graph
			&& graph.escape();
	}

	@Override
	protected Screen make() {
		showing = waiting;
		return showing == null ? null : new GraphEditorScreen(showing);
	}

	/** Whether this panel has already asked the server what dialogues there are. */
	private boolean asked;

	@Override
	public void opened() {
		ask();
	}

	@Override
	public void tick() {
		super.tick();
		// Also from the tick, and that is the fix rather than belt and braces:
		// opened() fires when somebody switches to a tab, and the dialogue panel is
		// showing from the moment the workspace opens — so nobody ever switched to
		// it, nobody ever asked, and the list of dialogues was empty for ever.
		ask();
	}

	private void ask() {
		if (asked || showing != null) return;
		asked = true;
		// Out loud: this panel is on the screen with nothing in it, and the list is
		// what it is going to show. The other asker — the character panel's dropdown —
		// asks quietly, which is the whole of the difference.
		ClientPlayNetworking.send(new EditorPayloads.Browse(true));
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (showing != null && !choosing) {
			super.draw(graphics, mouseX, mouseY, delta);
			return;
		}
		drawChooser(graphics, mouseX, mouseY);
	}

	/**
	 * The four sorts of document, as four lists.
	 *
	 * <h2>Why tabs and not a field inside the document</h2>
	 *
	 * Because that is what it was, and it did not work. What a document is for had been
	 * a setting three clicks inside the editor, so every document in this list looked
	 * identical and the three sorts added after conversations were, from here,
	 * invisible — the report was "besides the dialogue graph I did not see anything
	 * else", and it was accurate.
	 *
	 * A sort is not a property of a document the way its width is. It is where the
	 * document lives, and where a thing lives is a place you go, not a field you set.
	 *
	 * <h2>What stays one thing</h2>
	 *
	 * The language. These are four lists and four starting shapes, not four editors:
	 * the same nodes, conditions, variables and verbs, opened at the page each sort
	 * actually begins on. A conversation opens on a line; the other three are carried
	 * by nobody and open on a way in.
	 */
	private static final List<String> KINDS = List.of("scene", "player", "thing", "location");

	private static final List<Icon> TAB_ICONS =
		List.of(Icon.CHARACTER, Icon.PLAYER_ON, Icon.MAIN_HAND, Icon.ENVIRONMENT);

	private static final List<String> TAB_KEYS = List.of(
		"npc_studio.graph.tab.npc", "npc_studio.graph.tab.player",
		"npc_studio.graph.tab.thing", "npc_studio.graph.tab.location");

	private static final int TAB_H = 18;

	/** Which tab is up. Kept across openings, because it is where somebody was working. */
	private static int tab;

	private int listTop() {
		return PAD + 12 + TAB_H + 4;
	}

	private void drawChooser(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> names = DialogueNames.ofKind(KINDS.get(tab));
		graphics.text(font, Component.translatable("npc_studio.graph.choose"), PAD, PAD, TEXT_DIM);

		drawTabs(graphics, mouseX, mouseY);

		int top = listTop();
		int hovered = rowAt(mouseX, mouseY, names.size() + 1);

		// The new one first, because an empty world has nothing else to click and
		// the whole reason somebody is looking at this list is to get to work.
		drawRow(graphics, top, Icon.ADD, Component.translatable("npc_studio.graph.new"),
			hovered == 0, ACCENT);

		// The rows wear the tab's own icon rather than one icon for everything, so that
		// a name read here says what it is without the tab having to be looked at again
		// — the same name turns up on a character's panel and in a call.
		Icon icon = TAB_ICONS.get(tab);
		for (int i = 0; i < names.size(); i++) {
			int y = top + (i + 1) * ROW - scroll;
			if (y + ROW < top || y > height) continue;
			drawRow(graphics, y, icon, Component.literal(names.get(i)),
				hovered == i + 1, TEXT);
		}

		// An empty tab says so. Left blank it reads as a list that has not loaded, and
		// three of the four tabs are empty on every map that exists today.
		if (names.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.graph.tab.none"),
				PAD + Icon.SIZE + 5, top + ROW + 5, TEXT_DIM);
		}
	}

	private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int top = PAD + 12;
		int across = Math.max(1, (width - PAD * 2) / KINDS.size());
		int over = tabAt(mouseX, mouseY);
		for (int i = 0; i < KINDS.size(); i++) {
			int left = PAD + i * across;
			boolean on = i == tab;
			if (on || i == over) {
				graphics.fill(left, top, left + across - 2, top + TAB_H, ROW_HOVER);
			}
			// The chosen one is underlined rather than merely tinted, because a tint is
			// the same signal hovering uses and the two are told apart by nobody.
			if (on) graphics.fill(left, top + TAB_H - 1, left + across - 2, top + TAB_H, ACCENT);

			int ink = on ? ACCENT : (i == over ? TEXT : TEXT_DIM);
			Component label = Component.translatable(TAB_KEYS.get(i));
			int wide = Icon.SIZE + 4 + font.width(label);
			// Both when they fit, the icon alone when they do not. A clipped word is
			// worse than no word: it reads as a different word.
			if (wide <= across - 8) {
				int at = left + (across - 2 - wide) / 2;
				TAB_ICONS.get(i).draw(graphics, at, top + (TAB_H - Icon.SIZE) / 2, ink);
				graphics.text(font, label, at + Icon.SIZE + 4, top + (TAB_H - 8) / 2, ink);
			} else {
				TAB_ICONS.get(i).draw(graphics, left + (across - 2 - Icon.SIZE) / 2,
					top + (TAB_H - Icon.SIZE) / 2, ink);
			}
		}
	}

	private int tabAt(double mouseX, double mouseY) {
		if (!inside(mouseX, mouseY)) return -1;
		int top = PAD + 12;
		if (mouseY < top || mouseY >= top + TAB_H) return -1;
		int across = Math.max(1, (width - PAD * 2) / KINDS.size());
		int which = (int) ((mouseX - PAD) / across);
		return which >= 0 && which < KINDS.size() ? which : -1;
	}

	private void drawRow(GuiGraphicsExtractor graphics, int top, Icon icon, Component label,
			boolean hovered, int ink) {
		if (hovered) graphics.fill(0, top, width, top + ROW, ROW_HOVER);
		icon.draw(graphics, PAD, top + (ROW - Icon.SIZE) / 2, hovered ? ACCENT : TEXT_DIM);
		graphics.text(font, label, PAD + Icon.SIZE + 5, top + (ROW - 8) / 2, hovered ? ACCENT : ink);
	}

	private int rowAt(double mouseX, double mouseY, int rows) {
		if (!inside(mouseX, mouseY)) return -1;
		int top = listTop();
		if (mouseY < top) return -1;
		int row = (int) ((mouseY - top + scroll) / ROW);
		// The new-dialogue row does not scroll with the rest; it is always first.
		if (mouseY >= top && mouseY < top + ROW) return 0;
		return row > 0 && row < rows ? row : -1;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (showing != null && !choosing) return super.mouseClicked(event, doubleClick);

		int picked = tabAt(event.x(), event.y());
		if (picked >= 0) {
			if (picked != tab) scroll = 0;
			tab = picked;
			return true;
		}

		List<String> names = DialogueNames.ofKind(KINDS.get(tab));
		int row = rowAt(event.x(), event.y(), names.size() + 1);
		if (row < 0) return false;
		// An empty name is how the server is asked for a fresh one; that is the
		// same request the list screen made before this panel existed. The tab goes
		// with it, because the tab somebody pressed "new" in is the only thing that
		// says which of the four they meant — and a new document appearing in a tab
		// other than the one they were looking at reads as the button not working.
		ClientPlayNetworking.send(row == 0
			? new EditorPayloads.Open("", KINDS.get(tab))
			: new EditorPayloads.Open(names.get(row - 1), KINDS.get(tab)));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (showing != null && !choosing) return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
		int rows = DialogueNames.ofKind(KINDS.get(tab)).size() + 1;
		scroll = Math.max(0, Math.min(Math.max(0, rows * ROW - height + listTop() + PAD),
			scroll - (int) (amountY * ROW)));
		return true;
	}
}
