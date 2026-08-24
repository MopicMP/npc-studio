package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatSlider;
import com.mopicmp.npcstudio.client.scene.Captions;
import com.mopicmp.npcstudio.client.scene.Fonts;
import com.mopicmp.npcstudio.client.scene.Playing;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.scene.Cue;
import com.mopicmp.npcstudio.scene.Look;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A line on the screen, and everything about how it looks.
 *
 * <h2>What this replaces and why</h2>
 *
 * Two boxes wedged into the timeline's ruler strip — fourteen pixels tall, beside
 * seven buttons, with room for a line of text and a number and nothing else. It
 * was reported as inconvenient and crooked and it was both. A caption has a
 * typeface, a size, a colour, a place on the frame and a way of sitting at that
 * place, and none of those had anywhere to be said.
 *
 * The shape was the mistake rather than the size of it. The timeline holds
 * <em>when</em> — that is what a timeline is — and everything else about a thing
 * belongs in a panel, exactly as a bone's angles do. So the ruler keeps the mark
 * and the moment, and this holds the line.
 *
 * <h2>Why the typefaces are asked for and not listed</h2>
 *
 * Because the interesting ones are not the game's four. A resource pack may add
 * one, and a scene shot under a pack should be able to use it. See {@code Fonts}.
 *
 * <h2>Why the position is two sliders and not a drag on the screen</h2>
 *
 * Dragging the line about the frame is the better control and it is a larger one:
 * the frame it would be dragged on is the game's own interface, drawn under this
 * panel, so it needs a hit test in a place that has no mouse handling at all. Two
 * sliders in fractions of the frame say the same thing, work at any window size,
 * and can be typed at exactly. The drag goes on the list.
 */
public class CaptionPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int ROW = 16;
	private static final int LABEL = 11;
	private static final int BUTTON = 18;
	private static final int LIST_ROW = 12;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;
	private static final int WARN = 0xFFE57373;
	private static final int OFF = 0xFF55606B;
	private static final int PANEL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;

	/** The three colour sliders, in the colours they mix. The environment's own. */
	private static final int[] MIXES = { 0xFFFF5555, 0xFF55DD66, 0xFF5599FF };

	@Override
	public String id() {
		return "captions";
	}

	@Override
	public int minimumWidth() {
		return 210;
	}

	@Override
	public int minimumHeight() {
		// The list, the two buttons, the words, and six rows of settings each with a
		// heading over it rather than on it.
		return 290;
	}

	// -------------------------------------------------------------------- state

	/** Which line the widgets were built for, so they follow the choice. */
	private int builtFor = Captions.NONE;
	private boolean choosingFont;

	/** Where the list of lines ends and the editor begins. */
	private int editorTop;

	/**
	 * How many lines the list may show, and how it came to be a range.
	 *
	 * It was four, flatly, and four is a script of four lines. A scene with five in
	 * it showed four of them with no scrollbar and no way to reach the fifth — so a
	 * line sitting at six seconds was invisible, and typing six into the moment
	 * field was refused by a line nobody could see. That is most of "it will not go
	 * past five seconds".
	 *
	 * Now it takes whatever room the panel has after the editor, within reason, and
	 * the wheel moves it. Three at the smallest, because a list of two is not a list.
	 */
	private static final int LIST_LEAST = 3;
	private static final int LIST_MOST = 12;

	/** What the editor below needs, so the list can have the rest. */
	private static final int EDITOR = 230;

	/** The first line shown, when a hand has moved the list. */
	private int listScroll;

	private int listShows() {
		List<Cue> lines = Captions.lines();
		if (lines.isEmpty()) return 1;
		int room = (height - EDITOR - PAD) / LIST_ROW;
		return Math.clamp(Math.min(room, lines.size()), LIST_LEAST, LIST_MOST);
	}

	@Override
	public void tick() {
		if (builtFor != Captions.picked()) rebuild();
	}

	// ------------------------------------------------------------------ widgets

	@Override
	protected void build() {
		builtFor = Captions.picked();
		if (Playing.scene() == null) return;

		int across = width - PAD * 2;
		if (across < 80) return;

		int y = PAD + Math.min(listShows(), Math.max(1, Captions.lines().size())) * LIST_ROW + 6;
		y += IconTextButton.row(PAD, y, across, BUTTON, List.of(
			new IconTextButton.Spec(Icon.ADD,
				Component.translatable("npc_studio.caption.add"), ACCENT, Captions::addOrPick),
			new IconTextButton.Spec(Icon.REMOVE,
				Component.translatable("npc_studio.caption.remove"), WARN, Captions::remove),
			new IconTextButton.Spec(Icon.SCALE,
				Component.translatable("npc_studio.caption.stretch"), ACCENT, this::stretch)),
			this::add);
		y += 4;
		editorTop = y;

		Cue line = Captions.chosen();
		if (line == null) return;
		Look look = Captions.look();

		// The words. A proper text area rather than a single-line box, because a
		// title is two lines as often as it is one and joining them with a marker
		// somebody has to type would be a format inside a format.
		MultiLineEditBox words = new MultiLineEditBox.Builder()
			.setX(PAD).setY(y)
			.setShowBackground(true)
			.setShowDecorations(true)
			.setTextColor(TEXT)
			.build(font, across, 44, Component.translatable("npc_studio.caption.words"));
		words.setCharacterLimit(400);
		words.setValue(line.what());
		words.setValueListener(said -> {
			Cue now = Captions.chosen();
			if (now != null && !now.what().equals(said)) Captions.change(now.saying(said));
		});
		add(words);
		y += 48;

		// When and how long. The moment is editable here as well as by the cursor,
		// because "at four seconds exactly" is a thing you type and not a thing you
		// drag a playhead to.
		int half = (across - 4) / 2;
		timeTop = y;
		y += LABEL;
		number(PAD, y, half, line.at() / 20f, seconds -> {
			Captions.moveTo(Math.max(0, Math.round(seconds * 20)));
			// The line has a new moment and this panel is keyed to it. Followed
			// rather than rebuilt: a rebuild throws away the box being typed in, so
			// the digit that moved the line would also be the last one accepted.
			builtFor = Captions.picked();
		});
		number(PAD + half + 4, y, half, line.lasts() / 20f, seconds -> {
			Cue now = Captions.chosen();
			if (now != null) Captions.change(now.lasting(Math.max(1, Math.round(seconds * 20))));
		});
		y += ROW + 2;

		sizeTop = y;
		y += LABEL;
		add(new FlatSlider(PAD, y, across, ROW - 2, "",
			Look.SMALLEST, Look.LARGEST, 0.25, ACCENT,
			() -> Captions.look().size(),
			picked -> Captions.restyle(Captions.look().withSize(picked.floatValue()))));
		y += ROW + 2;

		placeTop = y;
		y += LABEL;
		add(new FlatSlider(PAD, y, half, ROW - 2, "X", -0.5, 1.5, 0.005, MIXES[0],
			() -> Captions.look().x(),
			picked -> Captions.restyle(Captions.look()
				.at(picked.floatValue(), Captions.look().y()))));
		add(new FlatSlider(PAD + half + 4, y, half, ROW - 2, "Y", -0.5, 1.5, 0.005, MIXES[1],
			() -> Captions.look().y(),
			picked -> Captions.restyle(Captions.look()
				.at(Captions.look().x(), picked.floatValue()))));
		y += ROW + 2;

		colourTop = y;
		y += LABEL;
		for (int i = 0; i < 3; i++) {
			int shift = 16 - 8 * i;
			add(new FlatSlider(PAD, y + i * (ROW - 2), across - 26, ROW - 4, "",
				0, 255, 1, MIXES[i],
				() -> (Captions.look().colour() >> shift) & 0xFF,
				picked -> {
					int was = Captions.look().colour();
					int part = (int) Math.clamp(Math.round(picked), 0, 255);
					Captions.restyle(Captions.look()
						.withColour((was & ~(0xFF << shift)) | (part << shift)));
				}));
		}
		y += (ROW - 2) * 3 + 4;

		// How it sits, whether it is heavy, and whether it has a shadow behind it.
		// One row, because they are one question — what kind of line is this — and
		// six separate rows would be six things to read.
		y += IconTextButton.row(PAD, y, across, BUTTON, List.of(
			aligned(Look.Align.LEFT, Icon.COLLAPSE),
			aligned(Look.Align.CENTRE, Icon.FOCUS),
			aligned(Look.Align.RIGHT, Icon.EXPAND),
			switched(Icon.CHARACTER, "bold", look.bold(),
				on -> Captions.restyle(Captions.look().withBold(on))),
			switched(Icon.ANIMATION, "italic", look.italic(),
				on -> Captions.restyle(Captions.look().withItalic(on))),
			switched(Icon.SOLID, "shadow", look.shadow(),
				on -> Captions.restyle(Captions.look().withShadow(on)))),
			this::add);
		y += 4;

		fontTop = y;
	}

	private int timeTop;
	private int sizeTop;
	private int placeTop;
	private int colourTop;
	private int fontTop;

	/**
	 * Makes the scene long enough to hold every line in it.
	 *
	 * The counterpart to fitting a scene to its music, and needed for the same
	 * reason: how long a film runs is worked out from what is in it far more often
	 * than it is chosen. A line typed at ten seconds under a five second scene is
	 * not a mistake to be refused — it is a scene that has not been lengthened yet.
	 */
	private void stretch() {
		var scene = Playing.scene();
		if (scene == null) return;
		int end = 0;
		for (Cue line : Captions.lines()) end = Math.max(end, line.ends());
		if (end <= 0 || end <= scene.length()) return;
		com.mopicmp.npcstudio.client.scene.Scenes.keep(Playing.openName(),
			scene.lengthened(end));
	}

	private IconTextButton.Spec aligned(Look.Align how, Icon icon) {
		boolean on = Captions.look().align() == how;
		return new IconTextButton.Spec(icon,
			Component.translatable("npc_studio.caption.align." + how.name().toLowerCase()),
			on ? GOOD : OFF, () -> {
				Captions.restyle(Captions.look().aligned(how));
				rebuild();
			});
	}

	private IconTextButton.Spec switched(Icon icon, String named, boolean on,
			java.util.function.Consumer<Boolean> set) {
		return new IconTextButton.Spec(icon,
			Component.translatable("npc_studio.caption." + named), on ? GOOD : OFF,
			() -> {
				set.accept(!on);
				rebuild();
			});
	}

	/**
	 * One typed number of seconds.
	 *
	 * Half-typed is ignored rather than announced, as everywhere else here: "1" on
	 * the way to "1.5" is a perfectly good number nobody meant.
	 */
	private void number(int x, int y, int across, float value,
			java.util.function.Consumer<Float> set) {
		EditBox box = new EditBox(font, x, y, across, ROW - 4, Component.literal(""));
		box.setValue(String.format(java.util.Locale.ROOT, "%.2f", value));
		box.setResponder(said -> {
			try {
				set.accept(Float.parseFloat(said.trim().replace(',', '.')));
			} catch (NumberFormatException halfTyped) {
				// Left alone on purpose.
			}
		});
		add(box);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (Playing.scene() == null) {
			graphics.text(font, Component.translatable("npc_studio.pose.no_scene"),
				PAD, PAD, TEXT_DIM);
			return;
		}
		drawList(graphics, mouseX, mouseY);

		if (Captions.chosen() == null) {
			graphics.text(font, Component.translatable("npc_studio.caption.none"),
				PAD, editorTop + 4, TEXT_DIM);
			return;
		}

		// Every heading sits above its row rather than on it. It used to sit on it:
		// the two time fields were drawn over by their own labels, which is most of
		// why setting a duration looked impossible rather than merely fiddly.
		graphics.text(font, Component.translatable("npc_studio.caption.at"),
			PAD, timeTop + 1, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.caption.lasts"),
			PAD + (width - PAD * 2 - 4) / 2 + 4, timeTop + 1, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.caption.size"),
			PAD, sizeTop + 1, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.caption.place"),
			PAD, placeTop + 1, TEXT_DIM);
		// A line past the end of the scene is never reached, never drawn and never
		// filmed — and until now nothing anywhere said so. It looked exactly like a
		// caption that does not work, which is what a five second scene with a line
		// at ten seconds in it is.
		Cue line = Captions.chosen();
		if (Captions.refused() != Captions.NONE) {
			// The one fact that explains a field which appears to ignore what is typed
			// into it. Two lines may not begin on the same tick — see Captions — and
			// until now that was enforced in silence.
			graphics.text(font, Component.translatable("npc_studio.caption.taken",
					String.format(java.util.Locale.ROOT, "%.2f", Captions.refused() / 20f)),
				PAD, timeTop + LABEL + ROW + 1, WARN);
		} else if (line != null && line.at() >= Playing.length()) {
			graphics.text(font, Component.translatable("npc_studio.caption.past"),
				PAD, timeTop + LABEL + ROW + 1, WARN);
		}
		graphics.text(font, Component.translatable("npc_studio.caption.colour"),
			PAD, colourTop + 1, TEXT_DIM);
		swatch(graphics, colourTop + LABEL, Captions.look().colour());

		// The typeface, as a row that opens a list. Last because it is the one
		// setting somebody chooses once and then leaves alone.
		//
		// With a count beside it, and the count is not decoration. Whether a font
		// does nothing because the drawing ignores it or because the list never
		// found any is two completely different faults that produce one sentence —
		// "the fonts do not work" — and the number tells them apart at a glance
		// without anybody opening a log.
		int found = Fonts.names().size() - 1;
		String named = Fonts.shown(Captions.look().font());
		boolean here = Fonts.loaded(Captions.look().font());
		boolean over = mouseY >= fontTop && mouseY < fontTop + 12 && mouseX < width - PAD;
		graphics.text(font, Component.literal("▾ " + named), PAD, fontTop + 2,
			!here ? WARN : over || choosingFont ? ACCENT : TEXT);
		String count = String.valueOf(found);
		graphics.text(font, Component.literal(count),
			width - PAD - font.width(count), fontTop + 2, found > 0 ? TEXT_DIM : WARN);
	}

	private void swatch(GuiGraphicsExtractor graphics, int top, int colour) {
		int left = width - PAD - 20;
		graphics.fill(left - 1, top - 1, left + 21, top + (ROW - 2) * 3 - 1, EDGE);
		graphics.fill(left, top, left + 20, top + (ROW - 2) * 3 - 2, 0xFF000000 | colour);
	}

	/**
	 * The lines there are, with the moment each starts.
	 *
	 * The time first and the words after it, because the column somebody reads down
	 * is the time — a caption panel is used by looking for "the one at four seconds"
	 * far more often than by looking for a phrase.
	 */
	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Cue> lines = Captions.lines();
		if (lines.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.caption.empty"),
				PAD, PAD + 2, TEXT_DIM);
			return;
		}
		int rows = Math.min(listShows(), lines.size());
		graphics.fill(PAD - 1, PAD - 1, width - PAD + 1, PAD + rows * LIST_ROW + 1, EDGE);
		graphics.fill(PAD, PAD, width - PAD, PAD + rows * LIST_ROW, PANEL);

		for (int i = 0; i < rows; i++) {
			Cue line = lines.get(i + scroll());
			int y = PAD + i * LIST_ROW;
			boolean on = line.at() == Captions.picked();
			boolean over = mouseX >= PAD && mouseX < width - PAD && mouseY >= y
				&& mouseY < y + LIST_ROW;
			if (on || over) {
				graphics.fill(PAD + 1, y, width - PAD - 1, y + LIST_ROW,
					on ? 0xFF27313E : 0xFF232A34);
			}
			String when = String.format(java.util.Locale.ROOT, "%.1fs", line.at() / 20f);
			graphics.text(font, Component.literal(when), PAD + 4, y + 2,
				on ? ACCENT : TEXT_DIM);
			String said = line.what().replace('\n', ' ');
			if (said.isEmpty()) said = Component.translatable("npc_studio.caption.blank").getString();
			graphics.text(font, Component.literal(
				trim(said, width - PAD * 2 - 42)), PAD + 38, y + 2, on ? TEXT : TEXT_DIM);
		}
	}

	/** Which line the list starts at, so the chosen one is always in it. */
	/**
	 * Which line the list starts at.
	 *
	 * Wherever a hand has put it, except that the chosen line is always in view: a
	 * list that has been scrolled away from what is being edited is a list showing
	 * one thing while the boxes below show another.
	 */
	private int scroll() {
		List<Cue> lines = Captions.lines();
		int shows = listShows();
		int most = Math.max(0, lines.size() - shows);
		int at = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).at() == Captions.picked()) at = i;
		}
		int from = Math.clamp(listScroll, 0, most);
		if (at < 0) return from;
		if (at < from) return at;
		if (at >= from + shows) return Math.clamp(at - shows + 1, 0, most);
		return from;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (!inside(mouseX, mouseY)) return false;
		List<Cue> lines = Captions.lines();
		int most = Math.max(0, lines.size() - listShows());
		listScroll = (int) Math.clamp(scroll() - Math.signum(amountY), 0, most);
		return true;
	}

	private String trim(String said, int room) {
		if (font.width(said) <= room) return said;
		return font.plainSubstrByWidth(said, Math.max(0, room - font.width("…"))) + "…";
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (choosingFont) drawFonts(graphics, mouseX, mouseY);
	}

	private void drawFonts(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> named = Fonts.names();
		int wide = width - PAD * 2;
		int top = Math.max(PAD, fontTop - named.size() * LIST_ROW);
		graphics.fill(PAD - 1, top - 1, PAD + wide + 1, top + named.size() * LIST_ROW + 1, EDGE);
		graphics.fill(PAD, top, PAD + wide, top + named.size() * LIST_ROW, PANEL);
		for (int i = 0; i < named.size(); i++) {
			int y = top + i * LIST_ROW;
			boolean over = mouseX >= PAD && mouseX < PAD + wide && mouseY >= y
				&& mouseY < y + LIST_ROW;
			boolean on = named.get(i).equals(Captions.look().font());
			if (over || on) {
				graphics.fill(PAD + 1, y, PAD + wide - 1, y + LIST_ROW,
					on ? 0xFF27313E : 0xFF232A34);
			}
			graphics.text(font, Component.literal(Fonts.shown(named.get(i))), PAD + 5, y + 2,
				on ? ACCENT : TEXT);
		}
	}

	// -------------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!inside(event.x(), event.y())) return false;

		if (choosingFont) {
			List<String> named = Fonts.names();
			int top = Math.max(PAD, fontTop - named.size() * LIST_ROW);
			int row = (int) ((event.y() - top) / LIST_ROW);
			if (event.x() >= PAD && event.x() < width - PAD && row >= 0 && row < named.size()) {
				Captions.restyle(Captions.look().withFont(named.get(row)));
			}
			choosingFont = false;
			return true;
		}

		List<Cue> lines = Captions.lines();
		int rows = Math.min(listShows(), lines.size());
		if (event.y() >= PAD && event.y() < PAD + rows * LIST_ROW && event.x() >= PAD) {
			int row = (int) ((event.y() - PAD) / LIST_ROW) + scroll();
			if (row >= 0 && row < lines.size()) {
				Captions.pick(lines.get(row).at());
				// And the cursor goes there, because what a line is for is the moment
				// it names; choosing one and then finding that moment by hand is two
				// actions for one intention. The timeline's own key list does the same.
				Playing.head(Playing.head().scrubbedTo(lines.get(row).at(), Playing.length()));
				rebuild();
			}
			return true;
		}

		if (Captions.chosen() != null && event.y() >= fontTop && event.y() < fontTop + 12) {
			// Asked again here rather than trusted from before: a resource pack may
			// have been turned on since the panel was opened, and opening the list is
			// exactly the moment somebody would expect the new typeface to appear.
			Fonts.forget();
			choosingFont = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
