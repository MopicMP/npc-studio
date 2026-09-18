package com.mopicmp.npcstudio.client.dialogue;

import com.mopicmp.npcstudio.client.text.Ink;
import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * The line of dialogue drawn over the hotbar.
 *
 * Deliberately not a screen: the player keeps hold of the game, can still walk
 * and look around, and the world stays in front of them. That is what separates
 * a line spoken in passing from a scene that takes over.
 *
 * Everything is drawn from rectangles and text rather than from an image. The
 * default look only has to be clean and dark, since the ornate frames are the
 * map maker's business — which means no artwork to ship, and a theme that can
 * be described entirely by numbers when the theme system arrives.
 */
public final class DialogueHud {

	/**
	 * Where things sit and how big they are.
	 *
	 * The height is no longer among them. It was thirty-four pixels and one row of
	 * text by construction, and both were decisions about one scene written down as
	 * facts about dialogue — see {@link com.mopicmp.npcstudio.dialogue.Manner}, which
	 * is where they went and why. What is left here is padding and colour: the things
	 * that are about this bar being a bar rather than about any conversation.
	 */
	private static final int MARGIN = 10;
	private static final int PADDING = 8;
	private static final int ABOVE_HOTBAR = 24;
	private static final int ICON = 20;
	private static final int OPTION_HEIGHT = 18;
	private static final int OPTION_GAP = 2;
	private static final int OPTION_WIDTH = 260;

	private static final int BACKGROUND = 0xCC101014;
	private static final int OPTION_BACKGROUND = 0xE0161A20;
	private static final int OPTION_HOVERED = 0xF0222833;
	private static final int BORDER = 0x33FFFFFF;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;

	/**
	 * How fast the player asked lines to type themselves out.
	 *
	 * The player's preference and nothing else. Whether the document overrules it is
	 * decided in one place, inside the state — a map maker wanting a solemn line
	 * slower says so in the document, and a player who reads slowly should not have
	 * that taken off them by every author who never thought about it.
	 */
	public static float charsPerSecond() {
		return com.mopicmp.npcstudio.client.NpcStudioConfig.get().typingSpeed;
	}

	/**
	 * How tall the bar is, for a document that asked for this many rows of text.
	 *
	 * Rows rather than pixels because that is what an author means and what survives
	 * being looked at on somebody else's screen. The padding above and below is the
	 * same whatever the height, which is what keeps a three-line bar looking like the
	 * one-line bar grown rather than like a different thing.
	 */
	private static int heightFor(Font font, int lines) {
		return font.lineHeight * lines + PADDING * 2 + 2;
	}

	/**
	 * The same, made tall enough for a name with a head under it.
	 *
	 * Only in the stacked layout, and only when it would otherwise be too short: two
	 * rows of ordinary text is already taller than a name and a twenty-pixel head, so
	 * this changes nothing in the ordinary case and stops the head hanging out of the
	 * bottom of a bar in the awkward one.
	 */
	private static int heightIn(Font font, DialogueClientState state, int rows) {
		int plain = heightFor(font, rows);
		if (!stacked(state) || !hasFace(state)) return plain;
		return Math.max(plain, PADDING * 2 + font.lineHeight + 4 + ICON);
	}

	/**
	 * How wide the bar is, and where its left edge falls.
	 *
	 * Centred on the screen rather than pinned to the left margin. It was pinned
	 * because it was always the full width, where the two are the same thing — and
	 * the moment a document could ask for half a screen, a bar hard against the left
	 * edge is a caption in the corner rather than a line somebody is saying.
	 */
	private static int askedWidth(int screen, DialogueClientState state) {
		return Math.max(80, (int) ((screen - MARGIN * 2) * state.showing().width()));
	}

	/** As wide as a bar may ever be: the screen, less the margin either side. */
	private static int mostWidth(int screen) {
		return Math.max(80, screen - MARGIN * 2);
	}

	/**
	 * How wide the bar actually is, once the line in it has had its say.
	 *
	 * <h2>Which way it grows, and why that is the whole question</h2>
	 *
	 * Sideways. A line too long for the rows it was given widens the bar until it
	 * fits, and only grows taller if the screen runs out first. It grew downwards
	 * before, and that was the wrong axis: the number of rows is the shape the author
	 * chose and the thing they are looking at while writing, so a bar that answers a
	 * long sentence by becoming three rows tall has changed the one thing they set.
	 * Width is the free dimension — there is screen either side of a half-width bar
	 * doing nothing at all.
	 *
	 * <h2>Why it is searched for rather than worked out</h2>
	 *
	 * Because where a line wraps depends on where its spaces are, and no arithmetic on
	 * the total width answers that. Halving the gap each time settles in about ten
	 * layouts of one short line, which is nothing, and the answer is exact — a guess
	 * with a fudge factor in it would be a bar a few pixels short of fitting, which is
	 * exactly the case that looks like a bug.
	 */
	private static int widthFor(Font font, int screen, DialogueClientState state) {
		int asked = askedWidth(screen, state);
		int most = mostWidth(screen);
		if (asked >= most) return most;
		int lines = state.showing().lines();
		if (rowsAt(font, state, asked, screen, asked) <= lines) return asked;
		// It does not fit as asked. If it will not fit at any width, stay as asked and
		// let the height give way instead — see rowsFor. Losing words is never an option.
		if (rowsAt(font, state, most, screen, most) > lines) return most;

		int narrow = asked;
		int wide = most;
		while (wide - narrow > 2) {
			int middle = (narrow + wide) / 2;
			if (rowsAt(font, state, middle, screen, middle) <= lines) wide = middle;
			else narrow = middle;
		}
		return wide;
	}

	private static int leftFor(Font font, int screen, DialogueClientState state) {
		return (screen - widthFor(font, screen, state)) / 2;
	}

	/** How many rows the whole line takes if the bar were this wide. */
	private static int rowsAt(Font font, DialogueClientState state, int barWide,
			int screen, int ignored) {
		int left = (screen - barWide) / 2;
		int room = Math.max(16, left + barWide - PADDING - textLeftIn(font, state, left));
		return Ink.lay(font, state.text(), room).rows().size();
	}

	/**
	 * How many rows the whole line needs, whatever the document asked for.
	 *
	 * <h2>Why the whole line and not the part of it that is showing</h2>
	 *
	 * Because a bar that grows as the sentence arrives is a bar that jumps under the
	 * reader — and it jumps at exactly the moment they are reading it. Reported as
	 * that: the growing has to have happened before the first word appears.
	 *
	 * So it is measured against the finished text, which the client is holding all
	 * along — {@code state.text()} exists for precisely this, and the note on it says
	 * so. Only what is <em>drawn</em> is cut down to what has been typed.
	 *
	 * <h2>And why it grows at all</h2>
	 *
	 * Because the number of rows is a request, not a promise. An author asking for two
	 * and writing four sentences has not asked for the last two to be invisible; they
	 * have asked for a bar that is usually two rows tall. Cutting the line off would
	 * lose words silently, which is the one outcome nothing here should ever produce.
	 */
	private static int rowsFor(Font font, DialogueClientState state, int room) {
		int asked = state.showing().lines();
		if (room <= 0) return asked;
		int needed = Ink.lay(font, state.text(), room).rows().size();
		// Height is the last resort, not the first. By the time this is asked the bar
		// has already been widened as far as the screen allows, so a line still needing
		// more rows than it was given is one that genuinely will not fit on the shape it
		// was asked for — and then the rows have to give, because the alternative is
		// words nobody ever sees.
		return Math.max(asked, Math.min(needed, MOST_ROWS));
	}

	/**
	 * As tall as the bar may grow, however long the line is.
	 *
	 * A bound because the line comes from a document somebody else wrote, and a
	 * paragraph pasted into one would otherwise be a bar covering the whole screen
	 * with the world behind it gone. Twelve rows is more than twice what anybody
	 * should put in a subtitle, which is the right place for a limit: it never
	 * catches honest writing and it always catches a mistake.
	 */
	private static final int MOST_ROWS = 12;

	/**
	 * Whether the name and the head are stacked in a column rather than laid in a row.
	 *
	 * <h2>Why this is asked of the setting and not of the finished line</h2>
	 *
	 * Because the row count is not known yet and cannot be: how many rows a line takes
	 * depends on how much room it has, which depends on how wide this column is, which
	 * is the thing being decided. Asked the other way round it is not circular at all —
	 * a bar the author has asked to be three lines tall is a tall bar whether or not
	 * this particular line fills it, and the head belongs under the name in a tall bar
	 * because there is somewhere for it to go.
	 *
	 * It is also the question as it was put: if the size chosen is more than one line.
	 */
	private static boolean stacked(DialogueClientState state) {
		return state.showing().lines() > 1;
	}

	/** Whether any head is drawn at all, of whoever's it would be. */
	private static boolean hasFace(DialogueClientState state) {
		return switch (state.face()) {
			case SPEAKER -> state.npcId() >= 0;
			// The player is always there to draw. That is the whole difference between
			// this and the character, who may be nobody at all in a conversation a
			// doorway began.
			case PLAYER -> Minecraft.getInstance().player != null;
			case NONE -> false;
		};
	}

	/** Where the text starts across, once the icon and the name have had their room. */
	private static int textLeftIn(Font font, DialogueClientState state, int left) {
		int name = state.speaker().isEmpty()
			? 0 : font.width(Component.literal(state.speaker()));
		if (stacked(state)) {
			// One column holding both, as wide as the wider of them. The text starts
			// after it, and it starts in the same place on every row — which is what
			// makes a stack of rows read as a paragraph rather than as a list.
			return left + PADDING + Math.max(hasFace(state) ? ICON : 0, name) + PADDING;
		}
		int cursor = left + PADDING;
		if (hasFace(state)) cursor += ICON + PADDING;
		if (name > 0) cursor += name + PADDING;
		return cursor;
	}

	/**
	 * How tall the bar is right now, for whoever needs to know where its top edge is.
	 *
	 * Worked out in one place because two things ask — the drawing and the hit test
	 * for the answers — and when they disagree the answers are clickable somewhere
	 * other than where they are drawn.
	 */
	public static int heightOf(Font font, DialogueClientState state, int screen) {
		int wide = widthFor(font, screen, state);
		int left = (screen - wide) / 2;
		int room = Math.max(16, left + wide - PADDING - textLeftIn(font, state, left));
		return heightIn(font, state, rowsFor(font, state, room));
	}

	private DialogueHud() { }

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		// Before everything, and before the early returns below it. A portrait is not
		// part of the bar: it is raised by a verb, it outlives the line that raised it,
		// and it has to stand there through a full-screen card and through a moment
		// with nothing being said at all. Tied to the bar it would blink out between
		// sentences, which is the one thing the whole design of it exists to avoid.
		Portraits.draw(graphics);
		// And the account of what the last step decided, if somebody asked for it. Before
		// the early returns for the same reason the portrait is: it is about the
		// conversation, not about the bar, and it has to be readable when there is no bar.
		Watching.draw(graphics);
		// And what the player is carrying. Before the early returns with the portrait
		// and the account, and for the same reason: it is about the player rather than
		// about the bar, and it has to be readable when there is no bar at all — which
		// is nearly always, since a stamina is watched while walking about.
		Gauges.draw(graphics);

		DialogueClientState state = DialogueClientState.current();
		if (state == null) return;
		// The full-screen mode shows the same line in its own layout, and drawing
		// both put the question on screen twice.
		if (DialogueScreen.isOpen() || CutsceneScreen.isOpen()) return;

		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		var showing = state.showing();
		// A share of the screen rather than a number of pixels: the same picture at
		// any interface scale and on any monitor, which a pixel count is not.
		// Wide enough for what is actually being said, which may be wider than the
		// document asked for. Worked out from the finished line before a letter of it
		// is drawn, so the bar is already the size it will end up being.
		int width = widthFor(font, graphics.guiWidth(), state);
		int left = (graphics.guiWidth() - width) / 2;
		int textLeft = textLeftIn(font, state, left);
		int room = Math.max(16, left + width - PADDING - textLeft);
		int rows = rowsFor(font, state, room);
		int height = heightIn(font, state, rows);
		int top = graphics.guiHeight() - ABOVE_HOTBAR - height;

		graphics.fill(left, top, left + width, top + height, BACKGROUND);
		// A single hairline rather than a frame: at this size a thicker border
		// reads as a box around the text instead of a surface under it.
		graphics.fill(left, top, left + width, top + 1, BORDER);
		graphics.fill(left, top + height - 1, left + width, top + height, BORDER);

		// A single row is centred against the bar; several start at the top. Text that
		// grows downwards from where it began is what reading looks like, whereas text
		// staying centred while it wraps slides up the screen as the sentence arrives.
		int textTop = rows > 1
			? top + PADDING
			: top + (height - font.lineHeight) / 2;

		boolean column = stacked(state);
		int nameLeft = left + PADDING
			+ (!column && hasFace(state) ? ICON + PADDING : 0);
		int nameTop = column ? top + PADDING : textTop;

		if (!state.speaker().isEmpty()) {
			Component name = Component.literal(state.speaker());
			// The document's colour for this name, or this line's own, both settled on
			// the server. Falling back to the accent keeps every line written before
			// anybody could colour a name looking exactly as it did.
			int tint = state.nameColour().isEmpty() ? ACCENT : colourOf(state.nameColour());
			graphics.text(font, name, nameLeft, nameTop, tint);
			// A short rule under the name. The one flourish worth keeping by default:
			// it separates who is speaking from what they said without a second colour.
			graphics.fill(nameLeft, nameTop + font.lineHeight + 1,
				nameLeft + font.width(name), nameTop + font.lineHeight + 2, ACCENT);
		}

		// The head last, because where it goes depends on where the name ended up:
		// beside it on a one-line bar, under it on a taller one — which is where there
		// is room for it and where it was asked to be.
		if (hasFace(state)) {
			int faceLeft = left + PADDING;
			int faceTop = column
				? nameTop + (state.speaker().isEmpty() ? 0 : font.lineHeight + 4)
				: top + (height - ICON) / 2;
			drawFace(graphics, client, state, faceLeft, faceTop);
		}

		// Centred in the room it has rather than started at the left of it. What is
		// centred is the block, not each row inside it: a wrapped paragraph whose rows
		// are each centred is a poem, and this is somebody talking.
		//
		// Measured on the finished line, not on the part that has been typed, or the
		// text would crawl leftwards across the bar as it arrived.
		int blockWide = Math.min(room, Ink.lay(font, state.text(), room).width());
		int textAt = textLeft + Math.max(0, (room - blockWide) / 2);
		Ink.draw(graphics, font, state.visibleText(charsPerSecond()), textAt, textTop,
			room, 0, TEXT);

		drawOptions(graphics, font, state, left, top);
		drawHolding(graphics, font, left, width, top);
		drawCarryOn(graphics, font, state, left, width, top + height);
	}

	/**
	 * How to move on, when there is no way anybody would guess it.
	 *
	 * <h2>Why only this one case gets a prompt</h2>
	 *
	 * Because it is the only one that has to be taught. A character says a line and
	 * you click her: that is the gesture, and a prompt under every line she ever says
	 * would be an instruction repeated forever for something learned in one second.
	 *
	 * A place saying a line is different in kind. There is nothing on the screen to
	 * point at, so there is nothing to try — somebody would stand there reading a
	 * sentence that will not move, and the only thing they can do is wait for it to
	 * time out. That is not a gesture waiting to be discovered; it is a dead end with
	 * no sign on it.
	 *
	 * Under the bar rather than over it, because over it is where being held still is
	 * said, and two dim lines above a bar arguing for the same strip of screen is how
	 * one of them ends up drawn on top of the other.
	 */
	private static void drawCarryOn(GuiGraphicsExtractor graphics, Font font,
			DialogueClientState state, int left, int width, int barBottom) {
		if (state.npcId() >= 0 || !state.options().isEmpty()) return;
		Component said = Component.translatable("npc_studio.dialogue.carry_on");
		int at = left + (width - font.width(said)) / 2;
		graphics.text(font, said, at, barBottom + 3, TEXT_DIM);
	}

	/**
	 * The head beside the line, of whoever the line said it belongs to.
	 *
	 * <h2>Why the player's own head is a case and not a character</h2>
	 *
	 * Because there is no NPC to point at. Every line so far named the character
	 * being talked to, and a line the player speaks names nobody in the world except
	 * the person reading it — so the entity id in the packet cannot carry it and a
	 * separate answer had to exist. That answer is the line's, not the runtime's:
	 * whether the player is speaking is something an author writes down.
	 */
	private static void drawFace(GuiGraphicsExtractor graphics, Minecraft client,
			DialogueClientState state, int x, int y) {
		if (state.face() == com.mopicmp.npcstudio.dialogue.Node.Line.Face.PLAYER) {
			if (client.player == null) return;
			DialogueIcon.draw(graphics, client.player, x, y, ICON);
			return;
		}
		Entity speaker = client.level == null ? null : client.level.getEntity(state.npcId());
		if (speaker instanceof NpcEntity npc) DialogueIcon.draw(graphics, npc, x, y, ICON);
	}

	/**
	 * That the player is being kept still, and how to stop being kept still.
	 *
	 * <h2>Why this is not optional</h2>
	 *
	 * Because a game that has taken the movement keys and says nothing about it is
	 * indistinguishable from a game that has frozen, and about two seconds is all
	 * anybody gives it before reaching for the task manager. Reported from the other
	 * end of the same fault: the character walked off, the keys stayed taken, and
	 * there was nothing on the screen to say what was happening or what to do.
	 *
	 * The bar fills as sneak is held, so the way out shows its own progress. A way out
	 * that gives no sign it is working is one people let go of half way through and
	 * conclude does not work.
	 */
	private static void drawHolding(GuiGraphicsExtractor graphics, Font font,
			int left, int width, int barTop) {
		if (!com.mopicmp.npcstudio.client.dialogue.Holding.sayingSo()) return;

		Component said = Component.translatable("npc_studio.dialogue.held");
		int y = barTop - font.lineHeight - 4;
		int at = left + (width - font.width(said)) / 2;
		graphics.text(font, said, at, y, TEXT_DIM);

		float through = com.mopicmp.npcstudio.client.dialogue.Holding.breakingOut();
		if (through <= 0) return;
		int rule = font.width(said);
		graphics.fill(at, y + font.lineHeight, at + rule, y + font.lineHeight + 1, 0x40FFFFFF);
		graphics.fill(at, y + font.lineHeight, at + (int) (rule * through),
			y + font.lineHeight + 1, ACCENT);
	}

	/**
	 * The answers, stacked above the bar.
	 *
	 * Above rather than below, because below is the hotbar. They are narrow
	 * rather than full width: a line of dialogue runs the width of the screen
	 * because it is prose, but an answer is a thing to be picked, and something
	 * pickable should look like it has edges.
	 */
	private static void drawOptions(GuiGraphicsExtractor graphics, Font font,
			DialogueClientState state, int left, int barTop) {
		var options = state.options();
		if (options.isEmpty()) return;

		int hovered = hoveredOption(state);
		int bottom = barTop - 4;
		int top = bottom - (OPTION_HEIGHT + OPTION_GAP) * options.size();

		for (int i = 0; i < options.size(); i++) {
			var option = options.get(i);
			int y = top + (OPTION_HEIGHT + OPTION_GAP) * i;
			boolean isHovered = i == hovered;
			int colour = colourOf(option.colour());

			graphics.fill(left, y, left + OPTION_WIDTH, y + OPTION_HEIGHT,
				isHovered ? OPTION_HOVERED : OPTION_BACKGROUND);
			// A bar down the left edge in the option's own colour, thicker when the
			// mouse is over it — the same signal twice, so it survives being read
			// quickly or by someone who cannot separate those colours.
			graphics.fill(left, y, left + (isHovered ? 3 : 2), y + OPTION_HEIGHT, colour);
			if (isHovered) {
				graphics.fill(left, y, left + OPTION_WIDTH, y + 1, colour);
				graphics.fill(left, y + OPTION_HEIGHT - 1, left + OPTION_WIDTH, y + OPTION_HEIGHT, colour);
			}

			int textY = y + (OPTION_HEIGHT - font.lineHeight) / 2;
			// The key that answers this, which is its place on the screen — not its
			// place in the document. Those differ the moment a condition hides an
			// answer, and the number printed was the document's: a question showing
			// three answers could number them 0, 2 and 5, and none of the three was a
			// key anybody could press.
			//
			// Past the ninth there is no key. The number is left off rather than being
			// printed as a lie, and the ninth is further than any question anybody
			// writes — see Answering.MOST, which is the row of keys and nothing else.
			if (i < com.mopicmp.npcstudio.client.dialogue.Answering.MOST) {
				graphics.text(font, Component.literal(String.valueOf(i + 1)),
					left + PADDING, textY, TEXT_DIM);
			}
			Ink.draw(graphics, font, option.label(),
				left + PADDING + 12, textY, 0, 0, isHovered ? colour : TEXT);
		}

		// Said whenever the mouse is not over an answer, which is nearly always: this
		// is the bar, so there is no cursor unless the player has opened chat. It used
		// to say to press T and click, which was the only way there was and reads as an
		// apology. Now it names the gesture that works while walking, and clicking
		// through chat still works for anybody who liked it.
		if (hovered < 0) {
			graphics.text(font, Component.translatable("npc_studio.dialogue.answer_keys"),
				left + PADDING, top - font.lineHeight - 3, TEXT_DIM);
		}
	}

	/**
	 * Which answer the mouse is over, or -1.
	 *
	 * Only while the chat screen is open, because that is the only time the game
	 * gives the player a cursor without taking the world away. It is a stopgap —
	 * the full-screen mode will have a proper pointer of its own — but it means
	 * answers can be clicked today instead of typed as numbers.
	 */
	public static int hoveredOption(DialogueClientState state) {
		Minecraft client = Minecraft.getInstance();
		if (!DialogueClientState.isChatOpen()) return -1;

		double scale = client.getWindow().getGuiScale();
		int mouseX = (int) (client.mouseHandler.xpos() / scale);
		int mouseY = (int) (client.mouseHandler.ypos() / scale);

		int screen = client.getWindow().getGuiScaledWidth();
		int barTop = client.getWindow().getGuiScaledHeight() - ABOVE_HOTBAR
			- heightOf(client.font, state, screen);
		int left = leftFor(client.font, screen, state);
		int count = state.options().size();
		int top = barTop - 4 - (OPTION_HEIGHT + OPTION_GAP) * count;

		if (mouseX < left || mouseX > left + OPTION_WIDTH) return -1;
		int index = (mouseY - top) / (OPTION_HEIGHT + OPTION_GAP);
		return index >= 0 && index < count ? index : -1;
	}

	/**
	 * A colour name from the dialogue turned into something to draw with.
	 *
	 * Only the handful a writer actually reaches for. An unknown name falls back
	 * to the accent rather than failing, because a mistyped colour should not
	 * take the option away — the player still has to be able to answer.
	 */
	private static int colourOf(String name) {
		// One vocabulary, shared with the bar, the answers and the pickers that offer
		// it. Kept here as a call rather than a copy: a colour offered in the editor
		// and unknown to the drawing is a setting that silently does nothing, which is
		// exactly how this arrived as a bug report.
		return com.mopicmp.npcstudio.dialogue.text.Tint.of(name);
	}
}
