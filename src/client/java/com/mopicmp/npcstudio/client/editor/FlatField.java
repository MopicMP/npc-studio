package com.mopicmp.npcstudio.client.editor;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A line of text in the editor's own style.
 *
 * <h2>Why not the game's own box</h2>
 *
 * The same reason {@link FlatButton} is not the game's own button, and it shows
 * worse here because there are more of them. The vanilla field is a sunken slot
 * with a hard white border and a blinking underscore; on a dark canvas of wires
 * it reads as a piece of another program that has been pasted in. Every other
 * control in this editor is a filled rectangle with a hairline edge and an accent
 * that appears when it is yours, and the field was the one thing still shouting.
 *
 * It also brought behaviour nobody here wants: a border that stays lit whether or
 * not the field is focused, and a suggestion drawn in the same slot as the text
 * so that a placeholder and a real value look alike at a glance.
 *
 * <h2>What it is</h2>
 *
 * Deliberately small. One line, no wrapping, no formatting, no history: a caret,
 * a selection, and the keys that move them. Everything a name, a number of ticks
 * or an item id needs, and nothing a document editor needs — that already exists
 * next door as {@code server/TextEditor} and is a different animal.
 *
 * <h2>The scroll, which is the part that is easy to get wrong</h2>
 *
 * A field narrower than its text has to show the part the caret is in, and the
 * window has to move with the caret rather than being worked out once. Written
 * the obvious way — always showing the start — a long item id becomes a field you
 * can type into and cannot read what you typed.
 */
public class FlatField extends AbstractWidget {

	private static final int FILL = 0xFF14181E;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int GHOST = 0xFF5A6674;
	private static final int PICKED = 0x804FC3F7;

	private static final int PAD = 4;

	private final int accent;
	private final Consumer<String> onChange;

	/** What is typed here. Never null; an empty field is an empty string. */
	private String text = "";

	/** Where the caret is, and where a selection started; equal means no selection. */
	private int caret;
	private int anchor;

	/** The first character actually drawn, which moves so that the caret stays in view. */
	private int from;

	private int most = 512;

	/** Shown in place of the text when there is none, so an empty row still says what it is for. */
	private final Component ghost;

	public FlatField(int x, int y, int width, int height, Component ghost, int accent,
			Consumer<String> onChange) {
		super(x, y, width, height, ghost);
		this.ghost = ghost;
		this.accent = accent;
		this.onChange = onChange;
	}

	public void setMaxLength(int length) {
		most = Math.max(1, length);
	}

	public String getValue() {
		return text;
	}

	/**
	 * Puts a value in without telling anybody.
	 *
	 * Silent on purpose. This is called while the panel is being built, from the
	 * document — so announcing it would write the document back to itself on every
	 * rebuild, and any edit that rebuilds the panel would fight the one after it.
	 */
	public void setValue(String value) {
		text = value == null ? "" : value.length() > most ? value.substring(0, most) : value;
		caret = Math.min(caret, text.length());
		anchor = Math.min(anchor, text.length());
	}

	// ------------------------------------------------------------------ typing

	private int inner() {
		return getWidth() - PAD * 2;
	}

	private Font font() {
		return Minecraft.getInstance().font;
	}

	private void say() {
		onChange.accept(text);
	}

	private boolean picked() {
		return caret != anchor;
	}

	private int pickedFrom() {
		return Math.min(caret, anchor);
	}

	private int pickedTo() {
		return Math.max(caret, anchor);
	}

	private void put(String what) {
		String head = text.substring(0, pickedFrom());
		String tail = text.substring(pickedTo());
		int room = most - head.length() - tail.length();
		if (room <= 0) return;
		String fits = what.length() > room ? what.substring(0, room) : what;
		text = head + fits + tail;
		caret = head.length() + fits.length();
		anchor = caret;
		say();
	}

	private void erase(int by) {
		if (picked()) {
			put("");
			return;
		}
		int at = caret + by;
		if (at < 0 || at > text.length() || by == 0) return;
		int one = Math.min(caret, at);
		int other = Math.max(caret, at);
		text = text.substring(0, one) + text.substring(other);
		caret = one;
		anchor = caret;
		say();
	}

	private void moveTo(int at, boolean keeping) {
		caret = Math.clamp(at, 0, text.length());
		if (!keeping) anchor = caret;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!isFocused()) return false;
		int letter = event.codepoint();
		// Control characters are not text. Space is, and the delete key arrives here
		// as 127 on some layouts, which would otherwise be typed as a glyph nobody
		// can see and nobody can remove.
		if (letter < ' ' || letter == 127) return false;
		put(new String(Character.toChars(letter)));
		return true;
	}

	private static final int KEY_BACKSPACE = 259;
	private static final int KEY_DELETE = 261;
	private static final int KEY_LEFT = 263;
	private static final int KEY_RIGHT = 262;
	private static final int KEY_HOME = 268;
	private static final int KEY_END = 269;
	private static final int KEY_A = 65;
	private static final int KEY_C = 67;
	private static final int KEY_V = 86;
	private static final int KEY_X = 88;
	private static final int MOD_SHIFT = 0x0001;
	private static final int MOD_CONTROL = 0x0002;

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!isFocused()) return false;
		boolean keeping = (event.modifiers() & MOD_SHIFT) != 0;
		boolean control = (event.modifiers() & MOD_CONTROL) != 0;
		Minecraft client = Minecraft.getInstance();

		if (control) {
			switch (event.key()) {
				case KEY_A -> {
					anchor = 0;
					caret = text.length();
					return true;
				}
				case KEY_C -> {
					if (picked()) client.keyboardHandler.setClipboard(
						text.substring(pickedFrom(), pickedTo()));
					return true;
				}
				case KEY_X -> {
					if (picked()) {
						client.keyboardHandler.setClipboard(text.substring(pickedFrom(), pickedTo()));
						put("");
					}
					return true;
				}
				case KEY_V -> {
					// One line, so a pasted newline is dropped rather than drawn as a
					// square nobody typed and nothing can remove.
					put(client.keyboardHandler.getClipboard().replaceAll("[\\r\\n]", " "));
					return true;
				}
				default -> { }
			}
		}

		switch (event.key()) {
			case KEY_BACKSPACE -> {
				erase(-1);
				return true;
			}
			case KEY_DELETE -> {
				erase(1);
				return true;
			}
			case KEY_LEFT -> {
				moveTo(caret - 1, keeping);
				return true;
			}
			case KEY_RIGHT -> {
				moveTo(caret + 1, keeping);
				return true;
			}
			case KEY_HOME -> {
				moveTo(0, keeping);
				return true;
			}
			case KEY_END -> {
				moveTo(text.length(), keeping);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setFocused(true);
		if (doubleClick) {
			anchor = 0;
			caret = text.length();
			return;
		}
		moveTo(at(event.x()), false);
	}

	/** Which character a point along the field lands on. */
	private int at(double mouseX) {
		int into = (int) Math.round(mouseX - getX() - PAD);
		String showing = text.substring(Math.min(from, text.length()));
		return from + font().plainSubstrByWidth(showing, Math.max(0, into)).length();
	}

	// ----------------------------------------------------------------- drawing

	/**
	 * Moves the window so the caret is inside it.
	 *
	 * Done at draw time rather than on every edit, because every way the caret can
	 * move would otherwise have to remember to do it — and the one that forgets is
	 * the one where somebody is typing off the right-hand edge into a field that
	 * has stopped showing what they are typing.
	 */
	private void follow() {
		Font font = font();
		int room = inner();
		from = Math.clamp(from, 0, text.length());
		if (caret < from) from = caret;
		// Widen the window back towards the caret until the caret fits in it.
		while (from < caret && font.width(text.substring(from, caret)) > room) from++;
		// And if there is spare room at the end, give it back to the start, so a
		// field that was scrolled and then emptied does not stay scrolled.
		while (from > 0 && font.width(text.substring(from - 1)) <= room) from--;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float delta) {
		int left = getX();
		int top = getY();
		int right = left + getWidth();
		int bottom = top + getHeight();
		boolean mine = isFocused();

		graphics.fill(left, top, right, bottom, EDGE);
		graphics.fill(left + 1, top + 1, right - 1, bottom - 1, FILL);
		// The accent underneath while it is yours, which is the same language the
		// buttons speak on hover — one editor, one way of saying "this one".
		if (mine) graphics.fill(left + 1, bottom - 2, right - 1, bottom - 1, accent);

		Font font = font();
		int baseline = top + (getHeight() - font.lineHeight) / 2 + 1;

		if (text.isEmpty() && !mine) {
			graphics.text(font, ghost, left + PAD, baseline, GHOST);
			return;
		}

		follow();
		String showing = font.plainSubstrByWidth(text.substring(from), inner());

		if (picked()) {
			int one = Math.clamp(pickedFrom() - from, 0, showing.length());
			int other = Math.clamp(pickedTo() - from, 0, showing.length());
			if (other > one) {
				int startX = left + PAD + font.width(showing.substring(0, one));
				int endX = left + PAD + font.width(showing.substring(0, other));
				graphics.fill(startX, top + 2, endX, bottom - 2, PICKED);
			}
		}

		graphics.text(font, Component.literal(showing), left + PAD, baseline, TEXT);

		if (!mine) return;
		// A bar rather than a blinking underscore. It sits between letters, which is
		// where a caret actually is, and it does not flash — a row of six fields with
		// one of them winking is a row that keeps pulling the eye back to it.
		int caretX = left + PAD
			+ font.width(showing.substring(0, Math.clamp(caret - from, 0, showing.length())));
		graphics.fill(caretX, top + 3, caretX + 1, bottom - 3, accent);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, ghost);
	}
}
