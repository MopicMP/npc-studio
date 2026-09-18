package com.mopicmp.npcstudio.client.server;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * A text editor for configuration files.
 *
 * <b>Why not the game's own multi-line box.</b> It was tried first, and a config
 * is exactly the kind of text it is not for. It wraps: a long comment becomes
 * three ragged lines, and LuckPerms' banner of box-drawing characters turns into
 * rubble. It draws every character in one colour, so a hundred lines of
 * documentation and the six settings buried in them look identical. It has no
 * line numbers, which is what an error message gives you. And it scrolls one line
 * per notch of the wheel, through a file of three hundred.
 *
 * <p>So this one:
 *
 * <ul>
 * <li><b>does not wrap.</b> A line too long to fit runs off the right edge and is
 * reached by scrolling sideways — because in this kind of file the indentation is
 * the structure, and a wrapped line is a lie about it;</li>
 * <li><b>is drawn in the uniform font</b>, whose latin glyphs are all one width.
 * Columns line up, and so does anything anybody drew with characters;</li>
 * <li><b>colours what a line is</b>: a comment, a key, a value. Not for
 * decoration — it is what makes six settings findable in three hundred lines;</li>
 * <li><b>counts the lines</b>, since that is the language every error message
 * about these files speaks.</li>
 * </ul>
 *
 * <p>What it deliberately keeps: the file's own line endings. It records whether
 * the text arrived with CRLF and gives it back the same way — a "helpful" editor
 * that normalises them rewrites every line of a file somebody asked it to change
 * one line of.
 */
public final class TextEditor extends AbstractWidget {

	/** Latin glyphs in this one are all eight pixels wide, so columns line up. */
	private static final net.minecraft.network.chat.FontDescription UNIFORM =
		new net.minecraft.network.chat.FontDescription.Resource(
			Identifier.parse("minecraft:uniform"));

	private static final int ROW = 11;
	private static final int PAD = 4;

	private static final int TEXT = 0xFFD8DEE9;
	private static final int COMMENT = 0xFF6B7A8C;
	private static final int KEY = 0xFF7FC7EE;
	private static final int STRING = 0xFFC3E88D;
	private static final int NUMBER = 0xFFF6C177;
	private static final int GUTTER = 0xFF5A6674;
	private static final int GUTTER_ON = 0xFF9AA7B4;
	private static final int CARET = 0xFF4FC3F7;
	private static final int PICKED = 0x664FC3F7;
	private static final int LINE_UNDER = 0x14FFFFFF;

	/** What the words in a line mean, which decides how it is coloured. */
	public enum Kind {
		YAML,
		PROPERTIES,
		PLAIN
	}

	private final Font font;
	private final Kind kind;
	private final Consumer<String> onChange;

	private final List<String> lines = new ArrayList<>();
	private String ending = "\n";
	private boolean endsWithNewline = true;

	private int caretLine;
	private int caretColumn;
	private int anchorLine;
	private int anchorColumn;
	private int topLine;
	private int leftPixels;
	private long caretSince;

	public TextEditor(Font font, int x, int y, int width, int height, Kind kind,
			Consumer<String> onChange) {
		super(x, y, width, height, Component.empty());
		this.font = font;
		this.kind = kind;
		this.onChange = onChange;
		lines.add("");
	}

	// ------------------------------------------------------------------ the text

	public void setValue(String text) {
		// The endings the file came with, kept for when it goes back.
		ending = text.contains("\r\n") ? "\r\n" : "\n";
		endsWithNewline = text.endsWith("\n");
		String body = endsWithNewline
			? text.substring(0, text.length() - ending.length()) : text;
		lines.clear();
		for (String line : body.split("\r\n|\n|\r", -1)) lines.add(line);
		if (lines.isEmpty()) lines.add("");
		caretLine = 0;
		caretColumn = 0;
		anchorLine = 0;
		anchorColumn = 0;
		topLine = 0;
		leftPixels = 0;
	}

	public String getValue() {
		return String.join(ending, lines) + (endsWithNewline ? ending : "");
	}

	public int lineCount() {
		return lines.size();
	}

	/** Which line the caret is on, counted the way a person counts. */
	public int caretAt() {
		return caretLine + 1;
	}

	private void changed() {
		if (onChange != null) onChange.accept(getValue());
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
			int mouseX, int mouseY, float delta) {
		int gutter = gutterWidth();
		int textLeft = getX() + gutter + PAD;
		int right = getX() + width;
		int bottom = getY() + height;

		graphics.fill(getX(), getY(), right, bottom, 0xFF0B0E12);
		graphics.fill(getX(), getY(), getX() + gutter, bottom, 0xFF0E131A);
		graphics.fill(getX() + gutter, getY(), getX() + gutter + 1, bottom, 0xFF1B2028);

		int rows = rows();
		graphics.enableScissor(getX(), getY(), right, bottom);
		for (int row = 0; row < rows; row++) {
			int at = topLine + row;
			if (at >= lines.size()) break;
			int y = getY() + PAD + row * ROW;

			// The line the caret is on, marked faintly. In a file where every line
			// looks like every other, this is how a person keeps their place.
			if (at == caretLine && isFocused()) {
				graphics.fill(getX() + gutter + 1, y - 2, right, y + ROW - 2, LINE_UNDER);
			}
			String number = String.valueOf(at + 1);
			graphics.text(font, styled(number, at == caretLine ? GUTTER_ON : GUTTER),
				getX() + gutter - PAD - widthOf(number), y, -1);

			drawSelection(graphics, at, textLeft, y, right);
			drawLine(graphics, lines.get(at), textLeft - leftPixels, y);
		}

		if (isFocused()) drawCaret(graphics, textLeft, gutter);
		graphics.disableScissor();

		drawBars(graphics, gutter);
	}

	private void drawCaret(GuiGraphicsExtractor graphics, int textLeft, int gutter) {
		// Blinking, because a caret that does not blink is a character in the text.
		if ((net.minecraft.util.Util.getMillis() - caretSince) % 1000 > 500) return;
		int row = caretLine - topLine;
		if (row < 0 || row >= rows()) return;
		int x = textLeft - leftPixels + widthOf(lines.get(caretLine).substring(0, caretColumn));
		if (x < getX() + gutter) return;
		int y = getY() + PAD + row * ROW;
		graphics.fill(x, y - 1, x + 1, y + ROW - 2, CARET);
	}

	private void drawSelection(GuiGraphicsExtractor graphics, int at, int textLeft, int y,
			int right) {
		if (!hasPick()) return;
		int[] from = firstOfPick();
		int[] to = lastOfPick();
		if (at < from[0] || at > to[0]) return;
		String line = lines.get(at);
		int start = at == from[0] ? from[1] : 0;
		int end = at == to[0] ? to[1] : line.length();
		int x1 = textLeft - leftPixels + widthOf(line.substring(0, start));
		int x2 = textLeft - leftPixels + widthOf(line.substring(0, end));
		// A line picked whole shows it to its end, or a chosen empty line would be
		// invisible.
		if (at < to[0]) x2 += widthOf(" ");
		graphics.fill(Math.max(textLeft, x1), y - 1, Math.min(right, Math.max(x1 + 1, x2)),
			y + ROW - 2, PICKED);
	}

	/**
	 * One line, in the colours of what it says.
	 *
	 * Read a line at a time and nothing carried between lines: a block of text
	 * written over several lines will be coloured as though each were its own, and
	 * that is the right trade. This is a hint about where the settings are, not a
	 * parser — and a parser is what the fields in the other half of this tab are.
	 */
	private void drawLine(GuiGraphicsExtractor graphics, String line, int x, int y) {
		if (line.isEmpty()) return;
		String bare = line.strip();
		if (bare.startsWith("#") || bare.startsWith("!")) {
			graphics.text(font, styled(line, COMMENT), x, y, -1);
			return;
		}
		int mark = kind == Kind.PROPERTIES ? separator(line, "=:") : separator(line, ":");
		if (kind == Kind.PLAIN || mark < 0) {
			graphics.text(font, styled(line, TEXT), x, y, -1);
			return;
		}
		String key = line.substring(0, mark + 1);
		String value = line.substring(mark + 1);
		graphics.text(font, styled(key, KEY), x, y, -1);
		int after = x + widthOf(key);
		// A comment after a value is still a comment.
		int hash = value.indexOf(" #");
		String rest = hash < 0 ? value : value.substring(0, hash);
		graphics.text(font, styled(rest, colourOf(rest)), after, y, -1);
		if (hash >= 0) {
			graphics.text(font, styled(value.substring(hash), COMMENT),
				after + widthOf(rest), y, -1);
		}
	}

	private static int colourOf(String value) {
		String bare = value.strip();
		if (bare.isEmpty()) return TEXT;
		if (bare.startsWith("'") || bare.startsWith("\"")) return STRING;
		if (bare.equalsIgnoreCase("true") || bare.equalsIgnoreCase("false")) return NUMBER;
		try {
			Double.parseDouble(bare);
			return NUMBER;
		} catch (NumberFormatException notANumber) {
			return TEXT;
		}
	}

	/** The first separator outside quotes, or -1. */
	private static int separator(String line, String marks) {
		char quote = 0;
		for (int at = 0; at < line.length(); at++) {
			char c = line.charAt(at);
			if (quote != 0) {
				if (c == quote) quote = 0;
			} else if (c == '\'' || c == '"') {
				quote = c;
			} else if (marks.indexOf(c) >= 0) {
				return at;
			}
		}
		return -1;
	}

	private void drawBars(GuiGraphicsExtractor graphics, int gutter) {
		int rows = rows();
		if (lines.size() > rows) {
			int span = height - 2;
			int thumb = Math.max(18, span * rows / lines.size());
			int most = lines.size() - rows;
			int at = getY() + 1 + (span - thumb) * Math.clamp(topLine, 0, most) / Math.max(1, most);
			graphics.fill(getX() + width - 3, getY(), getX() + width, getY() + height, 0xFF0E1116);
			graphics.fill(getX() + width - 3, at, getX() + width, at + thumb, 0xFF3A424D);
		}
		int widest = widestVisible();
		int room = width - gutter - PAD * 2;
		if (widest > room) {
			int span = width - gutter - 6;
			int thumb = Math.max(20, span * room / widest);
			int most = widest - room;
			int at = getX() + gutter + 2
				+ (span - thumb) * Math.clamp(leftPixels, 0, most) / Math.max(1, most);
			graphics.fill(at, getY() + height - 3, at + thumb, getY() + height, 0xFF3A424D);
		}
	}

	private int widestVisible() {
		int widest = 0;
		for (int row = 0; row < rows() && topLine + row < lines.size(); row++) {
			widest = Math.max(widest, widthOf(lines.get(topLine + row)));
		}
		return widest;
	}

	private Component styled(String text, int colour) {
		return Component.literal(text).setStyle(Style.EMPTY.withFont(UNIFORM).withColor(colour));
	}

	private int widthOf(String text) {
		return font.width(styled(text, TEXT));
	}

	private int gutterWidth() {
		return widthOf(String.valueOf(Math.max(99, lines.size()))) + PAD * 2;
	}

	private int rows() {
		return Math.max(1, (height - PAD * 2 + 2) / ROW);
	}

	// ------------------------------------------------------------------ the mouse

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setFocused(true);
		placeCaret(event.x(), event.y(), false);
		if (doubleClick) pickWord();
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		placeCaret(event.x(), event.y(), true);
	}

	private void placeCaret(double mouseX, double mouseY, boolean keepAnchor) {
		int row = (int) ((mouseY - getY() - PAD + 2) / ROW);
		caretLine = Math.clamp(topLine + row, 0, lines.size() - 1);
		String line = lines.get(caretLine);
		int x = (int) (mouseX - getX() - gutterWidth() - PAD + leftPixels);
		int column = 0;
		while (column < line.length() && widthOf(line.substring(0, column + 1)) <= x) column++;
		caretColumn = column;
		if (!keepAnchor) dropAnchor();
		showCaret();
	}

	private void pickWord() {
		String line = lines.get(caretLine);
		int from = caretColumn;
		int to = caretColumn;
		while (from > 0 && wordly(line.charAt(from - 1))) from--;
		while (to < line.length() && wordly(line.charAt(to))) to++;
		anchorLine = caretLine;
		anchorColumn = from;
		caretColumn = to;
	}

	private static boolean wordly(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		// Three lines a notch. One line a notch is what the game's own box does and
		// what made a three-hundred-line file feel like a well.
		if (dx != 0) {
			leftPixels = Math.max(0, leftPixels - (int) (dx * 24));
		} else {
			scrollBy((int) -Math.signum(dy) * 3);
		}
		return true;
	}

	private void scrollBy(int lines) {
		topLine = Math.clamp(topLine + lines, 0, Math.max(0, this.lines.size() - rows()));
	}

	// ------------------------------------------------------------------ the keys

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean shift = event.hasShiftDown();
		boolean control = event.hasControlDown();
		caretSince = net.minecraft.util.Util.getMillis();

		if (control) {
			switch (event.key()) {
				case org.lwjgl.glfw.GLFW.GLFW_KEY_A -> {
					anchorLine = 0;
					anchorColumn = 0;
					caretLine = lines.size() - 1;
					caretColumn = lines.get(caretLine).length();
					return true;
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_C -> {
					copy();
					return true;
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_X -> {
					copy();
					deletePick();
					return true;
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_V -> {
					paste();
					return true;
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
					caretLine = 0;
					caretColumn = 0;
					if (!shift) dropAnchor();
					showCaret();
					return true;
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> {
					caretLine = lines.size() - 1;
					caretColumn = lines.get(caretLine).length();
					if (!shift) dropAnchor();
					showCaret();
					return true;
				}
				default -> {
					// Anything else with control held is not ours: Ctrl+S belongs to
					// the screen, which writes the file.
					return false;
				}
			}
		}

		switch (event.key()) {
			case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> {
				if (caretColumn > 0) {
					caretColumn--;
				} else if (caretLine > 0) {
					caretLine--;
					caretColumn = lines.get(caretLine).length();
				}
				if (!shift) dropAnchor();
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> {
				if (caretColumn < lines.get(caretLine).length()) {
					caretColumn++;
				} else if (caretLine < lines.size() - 1) {
					caretLine++;
					caretColumn = 0;
				}
				if (!shift) dropAnchor();
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> moveLine(-1, shift);
			case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> moveLine(1, shift);
			case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> moveLine(-rows(), shift);
			case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> moveLine(rows(), shift);
			case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
				// To the first thing on the line before the start of it, which is
				// what a person means in an indented file.
				String line = lines.get(caretLine);
				int first = 0;
				while (first < line.length() && line.charAt(first) == ' ') first++;
				caretColumn = caretColumn == first ? 0 : first;
				if (!shift) dropAnchor();
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> {
				caretColumn = lines.get(caretLine).length();
				if (!shift) dropAnchor();
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
				if (hasPick()) {
					deletePick();
				} else if (caretColumn > 0) {
					String line = lines.get(caretLine);
					lines.set(caretLine, line.substring(0, caretColumn - 1)
						+ line.substring(caretColumn));
					caretColumn--;
					dropAnchor();
					changed();
				} else if (caretLine > 0) {
					String line = lines.remove(caretLine);
					caretLine--;
					caretColumn = lines.get(caretLine).length();
					lines.set(caretLine, lines.get(caretLine) + line);
					dropAnchor();
					changed();
				}
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> {
				if (hasPick()) {
					deletePick();
				} else {
					String line = lines.get(caretLine);
					if (caretColumn < line.length()) {
						lines.set(caretLine, line.substring(0, caretColumn)
							+ line.substring(caretColumn + 1));
						changed();
					} else if (caretLine < lines.size() - 1) {
						lines.set(caretLine, line + lines.remove(caretLine + 1));
						changed();
					}
				}
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
				if (hasPick()) deletePick();
				String line = lines.get(caretLine);
				String before = line.substring(0, caretColumn);
				String after = line.substring(caretColumn);
				// The new line starts where the old one did. In a file where
				// indentation is the structure, starting at the margin is wrong
				// every time.
				int indent = 0;
				while (indent < before.length() && before.charAt(indent) == ' ') indent++;
				String spaces = " ".repeat(indent);
				lines.set(caretLine, before);
				lines.add(caretLine + 1, spaces + after);
				caretLine++;
				caretColumn = indent;
				dropAnchor();
				changed();
				showCaret();
			}
			case org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> {
				insert("  ");
				showCaret();
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int codepoint = event.codepoint();
		if (codepoint < 32 && codepoint != 9) return false;
		caretSince = net.minecraft.util.Util.getMillis();
		insert(new String(Character.toChars(codepoint)));
		showCaret();
		return true;
	}

	private void moveLine(int by, boolean keepAnchor) {
		caretLine = Math.clamp(caretLine + by, 0, lines.size() - 1);
		caretColumn = Math.min(caretColumn, lines.get(caretLine).length());
		if (!keepAnchor) dropAnchor();
		showCaret();
	}

	// ------------------------------------------------------------------ editing

	private void insert(String text) {
		if (hasPick()) deletePick();
		String[] parts = text.split("\r\n|\n|\r", -1);
		String line = lines.get(caretLine);
		String before = line.substring(0, caretColumn);
		String after = line.substring(caretColumn);
		if (parts.length == 1) {
			lines.set(caretLine, before + parts[0] + after);
			caretColumn += parts[0].length();
		} else {
			lines.set(caretLine, before + parts[0]);
			for (int at = 1; at < parts.length; at++) {
				lines.add(caretLine + at, parts[at]);
			}
			caretLine += parts.length - 1;
			caretColumn = parts[parts.length - 1].length();
			lines.set(caretLine, lines.get(caretLine) + after);
		}
		dropAnchor();
		changed();
	}

	private void copy() {
		String text = hasPick() ? pickedText() : lines.get(caretLine);
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
	}

	private void paste() {
		String text = Minecraft.getInstance().keyboardHandler.getClipboard();
		if (text != null && !text.isEmpty()) insert(text);
		showCaret();
	}

	private boolean hasPick() {
		return anchorLine != caretLine || anchorColumn != caretColumn;
	}

	private void dropAnchor() {
		anchorLine = caretLine;
		anchorColumn = caretColumn;
	}

	private int[] firstOfPick() {
		return anchorLine < caretLine || anchorLine == caretLine && anchorColumn < caretColumn
			? new int[] {anchorLine, anchorColumn} : new int[] {caretLine, caretColumn};
	}

	private int[] lastOfPick() {
		return anchorLine < caretLine || anchorLine == caretLine && anchorColumn < caretColumn
			? new int[] {caretLine, caretColumn} : new int[] {anchorLine, anchorColumn};
	}

	private String pickedText() {
		int[] from = firstOfPick();
		int[] to = lastOfPick();
		if (from[0] == to[0]) return lines.get(from[0]).substring(from[1], to[1]);
		StringBuilder out = new StringBuilder(lines.get(from[0]).substring(from[1]));
		for (int at = from[0] + 1; at < to[0]; at++) out.append(ending).append(lines.get(at));
		out.append(ending).append(lines.get(to[0]), 0, to[1]);
		return out.toString();
	}

	private void deletePick() {
		if (!hasPick()) return;
		int[] from = firstOfPick();
		int[] to = lastOfPick();
		String head = lines.get(from[0]).substring(0, from[1]);
		String tail = lines.get(to[0]).substring(to[1]);
		for (int at = to[0]; at > from[0]; at--) lines.remove(at);
		lines.set(from[0], head + tail);
		caretLine = from[0];
		caretColumn = from[1];
		dropAnchor();
		changed();
	}

	/** Bring the caret into view, sideways as well as up and down. */
	private void showCaret() {
		caretSince = net.minecraft.util.Util.getMillis();
		int rows = rows();
		if (caretLine < topLine) topLine = caretLine;
		if (caretLine >= topLine + rows) topLine = caretLine - rows + 1;
		topLine = Math.clamp(topLine, 0, Math.max(0, lines.size() - rows));

		int at = widthOf(lines.get(caretLine).substring(0, caretColumn));
		int room = width - gutterWidth() - PAD * 2;
		if (at < leftPixels) leftPixels = Math.max(0, at - 20);
		if (at > leftPixels + room) leftPixels = at - room + 20;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
			Component.literal("Configuration file"));
	}
}
