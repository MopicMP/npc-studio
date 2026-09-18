package com.mopicmp.npcstudio.client.dialogue;

import java.util.List;

import com.mopicmp.npcstudio.client.NpcStudioConfig;
import com.mopicmp.npcstudio.client.text.Ink;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.AnswerPayload;
import com.mopicmp.npcstudio.net.ShowChoicePayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * The conversation with the screen to itself.
 *
 * Being a {@code Screen} is doing most of the work: movement, the inventory,
 * breaking blocks, using items — all of it stops, because that is what having a
 * screen open means. There is no input-blocking code here and there should not
 * be; writing one would be reimplementing something the game already does, and
 * getting it slightly wrong.
 *
 * A question and a line are laid out differently on purpose. A line is read and
 * moved past, so it sits along the bottom where a subtitle would, and looks
 * like the bar it just replaced. A question is a stop — it wants to be at the
 * top of the eye's travel with its answers under it, and no wider than it needs
 * to be, so that reading it and choosing happen in one place instead of across
 * the whole screen.
 */
public class DialogueScreen extends Screen {

	private static final int MARGIN = 24;
	private static final int PADDING = 10;

	/**
	 * Air under each row of the line.
	 *
	 * Two, which is what this screen has always put between rows — kept as a
	 * number handed to the layout rather than added afterwards, so the height the
	 * card is sized to and the height the text actually takes are the same sum.
	 */
	private static final int LEADING = 2;
	private static final int ICON = 28;
	private static final int LINE_PANEL = 64;
	private static final int OPTION_HEIGHT = 22;
	private static final int OPTION_GAP = 4;
	private static final int MIN_CARD = 170;

	private static final int PANEL = 0xE614181D;
	private static final int PANEL_EDGE = 0x33FFFFFF;
	private static final int OPTION = 0xE01A1F26;
	private static final int OPTION_HOVER = 0xF0222B36;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	/**
	 * Whether one of these is on screen.
	 *
	 * Tracked here because {@code Minecraft} no longer exposes the screen it is
	 * showing, and closing this one must not close somebody else's — a line that
	 * ends while the player happens to be in their inventory should leave the
	 * inventory alone. The bar also reads it, so the two never draw at once.
	 */
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
	private int cardWidth;

	public DialogueScreen(DialogueClientState state) {
		super(Component.literal("Dialogue"));
		this.state = state;
	}

	@Override
	protected void init() {
		open = this;
		DialogueCamera.lookAt(state.npcId());
		measure();
	}

	@Override
	public void removed() {
		if (open == this) open = null;
		DialogueCamera.release();
	}

	/**
	 * The card is as wide as its contents, within reason.
	 *
	 * A question stretched across the screen makes the eye travel further than
	 * the words do. A floor stops a two-word question from becoming a stamp, and
	 * a ceiling keeps a long one from running off the edge.
	 */
	private void measure() {
		int longest = Math.max(Ink.width(font, state.text()), font.width(state.speaker()));
		for (ShowChoicePayload.Option option : state.options()) {
			longest = Math.max(longest, Ink.width(font, option.label()));
		}
		// Two thirds rather than a half. A half looked generous until a question ran
		// past it, and then the card stopped growing while the words kept coming —
		// which is what "cut off" looks like.
		cardWidth = Math.clamp(longest + ICON + PADDING * 4, MIN_CARD, width * 2 / 3);
	}

	/**
	 * Escape puts the conversation down; it does not throw it away.
	 *
	 * <h2>Why closing the screen was not enough</h2>
	 *
	 * Because the bar draws whatever the server has on this player's screen, and
	 * closing a screen is something only the client can see. So escape took the card
	 * away and the bar picked the same question straight up — one press, two
	 * dialogues, the second appearing as the first went. Reported in those words.
	 *
	 * The server is told, takes the line off screen the ordinary way, and sends back
	 * the same closing packet a timeout would have sent. The bookmark is untouched:
	 * clicking again picks the same moment up.
	 */
	@Override
	public void onClose() {
		ClientPlayNetworking.send(new com.mopicmp.npcstudio.net.PutDownPayload());
		minecraft.setScreenAndShow(null);
	}

	/**
	 * A click on this screen: pick an answer, finish the typing, or go on.
	 *
	 * <h2>The third of those was missing</h2>
	 *
	 * A line shown full screen said "click to go on" along the bottom, and clicking
	 * never went on. The first click finished the typing and every click after it did
	 * the same thing again — so a full-screen line was a dead end, with the only way
	 * out being escape, which puts the conversation down rather than carrying it.
	 *
	 * The cutscene screen beside this one had the pair the right way round the whole
	 * time. That is what a shared {@link SpeakingOn#carryOn} is for now: the two are
	 * the same act and were written twice.
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int hovered = optionAt((int) event.x(), (int) event.y());
		if (hovered >= 0) {
			// Minus one when a place is asking, which the server now knows how to
			// answer — before, an answer to a doorway's question was looked up as an
			// entity, found nothing, and was dropped without a word.
			ClientPlayNetworking.send(new AnswerPayload(
				state.npcId(), state.options().get(hovered).index()));
			return true;
		}
		if (!state.options().isEmpty()) return super.mouseClicked(event, doubleClick);

		// The rest of the sentence first. Somebody clicking at a line still arriving
		// wants to read it now, not to skip it unread.
		if (!state.finishedTyping(NpcStudioConfig.get().typingSpeed)) {
			DialogueClientState.skipTyping();
			return true;
		}
		SpeakingOn.carryOn(state);
		return true;
	}

	private int optionsTop() {
		return MARGIN + questionHeight() + 6;
	}

	private int questionHeight() {
		// The finished line, not the part typed so far: a card that grew letter by
		// letter while the words arrived is the thing the typing effect must not do.
		int text = Ink.lay(font, state.text(), questionWidth(), LEADING).height();
		if (!state.speaker().isEmpty()) text += font.lineHeight + 6;
		// Never shorter than the face it holds, or the icon hangs out of the box.
		return Math.max(ICON, text) + PADDING * 2;
	}

	/** Room for the words: the card, less the face and the gaps around it. */
	private int questionWidth() {
		return cardWidth - ICON - PADDING * 3;
	}

	private int optionAt(int mouseX, int mouseY) {
		if (state.options().isEmpty()) return -1;
		int left = MARGIN;
		if (mouseX < left || mouseX > left + cardWidth) return -1;
		int index = (mouseY - optionsTop()) / (OPTION_HEIGHT + OPTION_GAP);
		return index >= 0 && index < state.options().size() ? index : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int dim = NpcStudioConfig.get().dimColour();
		if (dim != 0) graphics.fill(0, 0, width, height, dim);

		if (state.options().isEmpty()) drawLine(graphics);
		else drawQuestion(graphics, mouseX, mouseY);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	/** A spoken line: along the bottom, laid out like the bar it replaced. */
	private void drawLine(GuiGraphicsExtractor graphics) {
		int top = height - MARGIN - LINE_PANEL;
		int left = MARGIN;
		int right = width - MARGIN;

		graphics.fill(left, top, right, top + LINE_PANEL, PANEL);
		graphics.fill(left, top, right, top + 1, PANEL_EDGE);
		graphics.fill(left, top + LINE_PANEL - 1, right, top + LINE_PANEL, PANEL_EDGE);

		int cursor = left + PADDING;
		icon(graphics, cursor, top + PADDING);
		cursor += ICON + PADDING;

		int textTop = top + PADDING;
		if (!state.speaker().isEmpty()) {
			Component name = Component.literal(state.speaker());
			graphics.text(font, name, cursor, textTop, ACCENT);
			graphics.fill(cursor, textTop + font.lineHeight + 2,
				cursor + font.width(name), textTop + font.lineHeight + 3, ACCENT);
			textTop += font.lineHeight + 8;
		}
		Ink.draw(graphics, font, visible(), cursor, textTop,
			right - PADDING - cursor, LEADING, TEXT);

		String hint = "click to go on  ·  esc to step away";
		graphics.text(font, Component.literal(hint),
			right - PADDING - font.width(hint), top + LINE_PANEL - font.lineHeight - 4, TEXT_DIM);
	}

	/**
	 * A question: top left, answers stacked under it, both only as wide as needed.
	 *
	 * <h2>Who still sees this</h2>
	 *
	 * Only a question asked during a cutscene. An ordinary one goes in the bar, and
	 * this card is no longer a staging anybody can choose — the reason is written out
	 * in full at {@link com.mopicmp.npcstudio.dialogue.Node.Choice}, and the short of
	 * it is that it appeared in the corner, escape swapped it for the bar version, and
	 * the corner one was never the one being asked for.
	 *
	 * It stays because a cutscene has taken the screen: there is no bar under it to
	 * put a question in, so this is the only layout that can hold one at all.
	 */
	private void drawQuestion(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = MARGIN;
		int top = MARGIN;
		int cardHeight = questionHeight();

		graphics.fill(left, top, left + cardWidth, top + cardHeight, PANEL);
		graphics.fill(left, top, left + cardWidth, top + 1, PANEL_EDGE);
		graphics.fill(left, top, left + 2, top + cardHeight, ACCENT);

		icon(graphics, left + PADDING, top + PADDING);

		int textLeft = left + PADDING + ICON + PADDING;
		int textTop = top + PADDING;

		// Who is asking. It was missing entirely, so a question arrived from
		// nobody while every ordinary line named its speaker.
		if (!state.speaker().isEmpty()) {
			Component name = Component.literal(state.speaker());
			graphics.text(font, name, textLeft, textTop, ACCENT);
			graphics.fill(textLeft, textTop + font.lineHeight + 1,
				textLeft + font.width(name), textTop + font.lineHeight + 2, ACCENT);
			textTop += font.lineHeight + 6;
		}
		Ink.draw(graphics, font, visible(), textLeft, textTop, questionWidth(), LEADING, TEXT);

		List<ShowChoicePayload.Option> options = state.options();
		int at = optionsTop();
		int hovered = optionAt(mouseX, mouseY);

		for (int i = 0; i < options.size(); i++) {
			ShowChoicePayload.Option option = options.get(i);
			int y = at + i * (OPTION_HEIGHT + OPTION_GAP);
			boolean over = i == hovered;
			int colour = colourOf(option.colour());

			graphics.fill(left, y, left + cardWidth, y + OPTION_HEIGHT, over ? OPTION_HOVER : OPTION);
			graphics.fill(left, y, left + (over ? 3 : 2), y + OPTION_HEIGHT, colour);
			if (over) {
				graphics.fill(left, y, left + cardWidth, y + 1, colour);
				graphics.fill(left, y + OPTION_HEIGHT - 1, left + cardWidth, y + OPTION_HEIGHT, colour);
			}
			// Kept to one row rather than allowed to run past the edge: an answer whose
			// end is off-screen cannot be read, and a card that stretched to fit the
			// longest one would undo the point of sizing it to the question.
			var laid = Ink.lay(font, option.label(), cardWidth - PADDING * 2 - 8);
			Ink.draw(graphics, font, laid, left + PADDING + 4,
				y + (OPTION_HEIGHT - Math.min(OPTION_HEIGHT, laid.height())) / 2,
				over ? colour : TEXT);
		}
	}

	private com.mopicmp.npcstudio.dialogue.text.Words visible() {
		return state.visibleText(NpcStudioConfig.get().typingSpeed);
	}

	/**
	 * The speaker's face, always on the left.
	 *
	 * It sits on the left in the bar too. Moving it across when a conversation
	 * steps up to the full screen made the change of layout read as a change of
	 * speaker — the one thing it must never look like.
	 */
	private void icon(GuiGraphicsExtractor graphics, int x, int y) {
		Minecraft client = Minecraft.getInstance();
		Entity speaker = client.level == null ? null : client.level.getEntity(state.npcId());
		graphics.fill(x - 1, y - 1, x + ICON + 1, y + ICON + 1, PANEL_EDGE);
		if (speaker instanceof NpcEntity npc) DialogueIcon.draw(graphics, npc, x, y, ICON);
	}

	private static int colourOf(String name) {
		// One vocabulary, shared with the bar, the answers and the pickers that offer
		// it. Kept here as a call rather than a copy: a colour offered in the editor
		// and unknown to the drawing is a setting that silently does nothing, which is
		// exactly how this arrived as a bug report.
		return com.mopicmp.npcstudio.dialogue.text.Tint.of(name);
	}

	/**
	 * The world carries on behind.
	 *
	 * Pausing would stop the NPC mid-gesture and freeze whatever else was
	 * happening, which is wrong for a conversation and impossible on a server
	 * anyway — so single player behaves the same way as multiplayer.
	 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
