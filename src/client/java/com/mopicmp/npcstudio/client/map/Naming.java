package com.mopicmp.npcstudio.client.map;

import com.mopicmp.npcstudio.map.Spot;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Typing the name of a place that has just been pointed at.
 *
 * <h2>Why there is a box at all, when the rule is to do things in the world</h2>
 *
 * Because a name cannot be pointed at. Everything else about placing a point is a
 * world act — where it goes is decided by looking at where it should go — and
 * this is the one part of it that is a word, so it is the one part that needs
 * letters. Putting it in a panel instead would mean placing a point took a trip
 * to the side of the screen and back, and the point of naming it there and then
 * is that you are looking at the thing you are naming.
 *
 * <h2>Why not a text widget</h2>
 *
 * The panel's widgets are thrown away and rebuilt whenever the panel is resized,
 * and this is open across exactly the moments somebody might drag a panel edge.
 * A field that loses half a typed word to a resize is worse than no field. What
 * this needs is a line of text, a caret and two keys, and that is all it is.
 */
public final class Naming {

	private Naming() { }

	private static final int BOX = 0xEE12161C;
	private static final int EDGE = 0xFFFFB74D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int WRONG = 0xFFE57373;

	private static final int WIDE = 180;
	private static final int TALL = 34;

	private static boolean open;
	private static BlockPos where;
	private static String typed = "";

	/** Opens the box for a place at this block, with a name to start from. */
	public static void start(BlockPos at, String suggested) {
		open = true;
		where = at;
		typed = suggested == null ? "" : suggested;
	}

	public static boolean isOpen() {
		return open;
	}

	public static void cancel() {
		open = false;
		where = null;
		typed = "";
	}

	private static final int KEY_ESCAPE = 256;
	private static final int KEY_ENTER = 257;
	private static final int KEY_BACKSPACE = 259;
	private static final int KEY_NUMPAD_ENTER = 335;

	public static boolean keyPressed(int key) {
		if (!open) return false;
		if (key == KEY_ESCAPE) {
			cancel();
			return true;
		}
		if (key == KEY_BACKSPACE) {
			if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
			return true;
		}
		if (key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
			String name = Spot.tidy(typed);
			// A refusal leaves the box open with what was typed still in it. Closing on
			// a bad name would throw away the work and the point together, and the
			// person would have to go and find the same block again.
			if (name == null) return true;
			BlockPos at = where;
			cancel();
			Spots.put(name, at);
			return true;
		}
		return false;
	}

	/**
	 * One letter, as a code point rather than a char.
	 *
	 * Which matters for the names this is for: a name in Russian is inside the basic
	 * plane and survives being narrowed, and a name with an emoji in it is not — the
	 * char would be half a surrogate pair and the field would fill with rubbish.
	 *
	 * The length is counted the way {@link Spot#tidy} counts it, which is the way the
	 * packet counts it. Counting code points here would let somebody type a name the
	 * box accepts and the send refuses, which is a field that stops working with no
	 * explanation at some length nobody can see.
	 */
	public static boolean charTyped(int letter) {
		if (!open) return false;
		if (letter < ' ' || letter == 127) return false;
		if (typed.length() + Character.charCount(letter) > Spot.LONGEST) return true;
		typed += new String(Character.toChars(letter));
		return true;
	}

	/**
	 * Drawn in the middle of the viewport, where the eye already is.
	 *
	 * Not at the cursor: the cursor is on the block being named, and a box over it
	 * would cover the one thing worth looking at while choosing a name for it.
	 */
	public static void draw(GuiGraphicsExtractor graphics, Font font, int width, int height) {
		if (!open) return;

		int left = (width - WIDE) / 2;
		int top = height / 3;
		graphics.fill(left - 1, top - 1, left + WIDE + 1, top + TALL + 1, EDGE);
		graphics.fill(left, top, left + WIDE, top + TALL, BOX);

		graphics.text(font, Component.translatable("npc_studio.spot.name_it"),
			left + 6, top + 5, TEXT_DIM);

		String name = Spot.tidy(typed);
		// The caret is a bar rather than a blink. A blinking one has to be driven by
		// the clock and there is nothing else in this box that needs a clock.
		graphics.text(font, Component.literal(typed + "_"), left + 6, top + 19,
			typed.isEmpty() || name != null ? TEXT : WRONG);

		// What the line under the box says, and it must be what pressing the key will
		// actually do. It said "Enter to place" whatever was typed, including while
		// Enter was being refused — so backspacing a name away left an instruction that
		// was simply untrue, with nothing anywhere saying why nothing happened.
		Component how;
		if (typed.isBlank()) {
			how = Component.translatable("npc_studio.spot.needs_name");
		} else if (name == null) {
			how = Component.translatable("npc_studio.spot.bad_name");
		} else if (Spots.has(name)) {
			how = Component.translatable("npc_studio.spot.moves");
		} else {
			how = Component.translatable("npc_studio.spot.keys");
		}
		graphics.text(font, how, left, top + TALL + 4, TEXT_DIM);
	}
}
