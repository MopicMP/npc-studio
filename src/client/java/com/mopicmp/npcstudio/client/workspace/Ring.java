package com.mopicmp.npcstudio.client.workspace;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The menu that opens where the cursor is, as directions rather than rows.
 *
 * <h2>Why a ring, and what it buys that a column does not</h2>
 *
 * Two things, and the second matters more than the first.
 *
 * The first is speed: a direction is remembered by the hand, so after a few days
 * "turn this" stops being something read and becomes somewhere the wrist goes.
 * A column is always read, however well known.
 *
 * The second is that <b>a ring cannot hold more than eight</b>, and that ceiling
 * is the point. The whole trouble this workspace was in came from things being
 * added because there was room: twelve panels in one column, twenty-odd entries
 * in one menu. A ring has nowhere to put a ninth, so "this subject is doing too
 * much" stops being an opinion somebody has to hold and becomes a fact of the
 * drawing. Anything past eight falls back to the column, and that fall is meant
 * to be noticed.
 *
 * <h2>Squares, not slices</h2>
 *
 * Drawn as eight small buttons around a circle rather than as a pie. Partly
 * because the drawing here has rectangles, text and outlines and no arcs; mostly
 * because at sixteen pixels an icon in a wedge is an icon with a wedge drawn over
 * it. The circle is what the arrangement says, not what is painted.
 *
 * <h2>Only the one under the hand is named</h2>
 *
 * Eight Russian labels around a circle overlap each other and the world behind
 * them. So the label appears for whichever direction the hand is nearest, in one
 * place under the ring where the eye already is. That was the choice made when
 * the shape was: icons to aim at, a word to confirm by.
 */
public final class Ring {

	/** How far out the buttons sit from the middle. */
	private static final int RADIUS = 34;

	private static final int BUTTON = 20;

	/**
	 * The middle, where nothing is chosen.
	 *
	 * A ring that acts on whatever the cursor happens to be nearest the instant it
	 * opens would act on something the moment it opened, because the cursor is in
	 * the middle. So the middle means no, and it is large enough to let go in.
	 */
	private static final int DEAD = 13;

	/**
	 * How far out a direction still counts.
	 *
	 * Beyond this a click is aimed at the world rather than at the ring. Generous,
	 * because the speed of the thing comes from flicking outwards rather than
	 * landing on a small target — the button is where the icon is drawn, not where
	 * the choice is made.
	 */
	private static final int REACH = 95;

	/** What the shape can hold. Past this the column takes over. */
	public static final int MOST = 8;

	/**
	 * How far under the ring the word sits.
	 *
	 * Bigger than it first looks it needs to be, and a drawing of the thing is what
	 * said so: with an even count there is always a button pointing straight down,
	 * and four pixels of gap put the label against it. Ten is clear of the button
	 * without the word drifting away from the ring it belongs to.
	 */
	private static final int LABEL_DROP = 10;

	private static final int FILL = 0xE8161A20;
	private static final int FILL_ON = 0xF22C3541;
	private static final int EDGE = 0xFF3A424D;
	private static final int EDGE_ON = 0xFF4FC3F7;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int LABEL_BACK = 0xE8101317;

	private List<Menu.Entry> entries = List.of();
	private int centreX;
	private int centreY;

	public boolean isOpen() {
		return !entries.isEmpty();
	}

	public void close() {
		entries = List.of();
	}

	/**
	 * Opens around a point, moved inward if the ring would not fit there.
	 *
	 * Moved rather than clipped: a direction drawn half outside the panel is a
	 * direction that cannot be aimed at, and the whole gesture is aiming.
	 */
	public void open(int px, int py, List<Menu.Entry> wanted,
			int boundsX, int boundsY, int boundsWidth, int boundsHeight) {
		if (wanted.isEmpty() || wanted.size() > MOST) return;
		entries = List.copyOf(wanted);

		int margin = RADIUS + BUTTON / 2 + 2;
		centreX = Math.clamp(px, boundsX + margin, boundsX + boundsWidth - margin);
		// More room below than above, because the label hangs there. Clamping by the
		// ring alone put the ring on screen and the word off it, which is the half
		// that says what the direction does.
		centreY = Math.clamp(py, boundsY + margin,
			boundsY + boundsHeight - margin - LABEL_DROP - 12);
	}

	/** Where the middle ended up, so a caller can tell whether it had to move. */
	public int centreX() {
		return centreX;
	}

	public int centreY() {
		return centreY;
	}

	/**
	 * Which direction the cursor is in, or -1 for none.
	 *
	 * By angle rather than by whether the cursor is on a button. That is what makes
	 * it quick: the hand throws outwards in a direction and stops wherever, instead
	 * of steering onto a twenty-pixel square.
	 */
	public int at(double mouseX, double mouseY) {
		if (!isOpen()) return -1;
		return directionAt(mouseX - centreX, mouseY - centreY, entries.size());
	}

	/**
	 * Which of {@code count} directions a displacement points at, or -1 for none.
	 *
	 * Taken out of the drawing on purpose. This is the arithmetic of the thing and
	 * it is the part that goes wrong quietly: an off-by-half-a-step puts every
	 * choice on the boundary between two icons, and the fault shows up as the ring
	 * "sometimes picking the wrong one" rather than as anything visible. The same
	 * class of mistake was already made once in this work, in the yaw comparison
	 * the undo rests on, and found by a test rather than by looking.
	 *
	 * Angles run from straight up, clockwise, matching the order the buttons are
	 * placed in. Half a step of slack each way means pointing at an icon is the
	 * middle of its wedge rather than the edge between two.
	 */
	static int directionAt(double dx, double dy, int count) {
		if (count <= 0) return -1;
		double away = Math.hypot(dx, dy);
		if (away < DEAD || away > REACH) return -1;

		double step = 360.0 / count;
		double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90 + step / 2;
		angle = ((angle % 360) + 360) % 360;
		int which = (int) (angle / step);
		return which >= 0 && which < count ? which : -1;
	}

	/** Takes the click. Anything that is not a direction closes it and does nothing. */
	public boolean click(double mouseX, double mouseY) {
		if (!isOpen()) return false;
		int which = at(mouseX, mouseY);
		List<Menu.Entry> was = entries;
		close();
		if (which >= 0) was.get(which).act().run();
		return true;
	}

	/**
	 * How much clear space there is between the lowest button and the word under it.
	 *
	 * <h2>Why this is a method rather than four numbers in the drawing</h2>
	 *
	 * Because it has already gone wrong once, and it went wrong invisibly: with an
	 * even number of directions one button always points straight down, and the label
	 * sat four pixels beneath it — close enough to read as part of the button rather
	 * than as the name of the choice. It was found by drawing the ring at its real
	 * size and looking, which is a check that only happens when somebody thinks to do
	 * it.
	 *
	 * The number changes with the count, because where the lowest button sits does.
	 * So the question is asked of the arithmetic instead, once, and the answer is
	 * pinned for every count the ring can hold.
	 */
	static int labelClearance(int count) {
		if (count <= 0) return 0;
		double step = 360.0 / count;
		double lowest = 0;
		for (int which = 0; which < count; which++) {
			lowest = Math.max(lowest, Math.sin(Math.toRadians(-90 + which * step)));
		}
		int buttonBottom = (int) Math.round(lowest * RADIUS) + BUTTON / 2;
		// Where name() puts the top of the backing plate, which is what the eye meets.
		int labelTop = RADIUS + BUTTON / 2 + LABEL_DROP - 3;
		return labelTop - buttonBottom;
	}

	private int[] seatOf(int which) {
		double step = 360.0 / entries.size();
		double angle = Math.toRadians(-90 + which * step);
		return new int[] {
			centreX + (int) Math.round(Math.cos(angle) * RADIUS),
			centreY + (int) Math.round(Math.sin(angle) * RADIUS) };
	}

	public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (!isOpen()) return;
		int on = at(mouseX, mouseY);

		for (int which = 0; which < entries.size(); which++) {
			int[] seat = seatOf(which);
			int left = seat[0] - BUTTON / 2;
			int top = seat[1] - BUTTON / 2;
			boolean lit = which == on;

			graphics.fill(left, top, left + BUTTON, top + BUTTON, lit ? FILL_ON : FILL);
			graphics.outline(left, top, BUTTON, BUTTON, lit ? EDGE_ON : EDGE);

			Menu.Entry entry = entries.get(which);
			if (entry.icon() != null) {
				entry.icon().draw(graphics, left + (BUTTON - Icon.SIZE) / 2,
					top + (BUTTON - Icon.SIZE) / 2, lit ? EDGE_ON : TEXT_DIM);
			}
		}

		if (on >= 0) name(graphics, font, entries.get(on).label());
	}

	/**
	 * The word for the direction under the hand, in one place under the ring.
	 *
	 * One place rather than beside its own icon, because a label that moves with
	 * the choice is a label the eye has to chase around a circle. Backed, because
	 * white text over a lit world is white text over a white wall often enough to
	 * matter.
	 */
	private void name(GuiGraphicsExtractor graphics, Font font, Component label) {
		int wide = font.width(label);
		int left = centreX - wide / 2;
		int top = centreY + RADIUS + BUTTON / 2 + LABEL_DROP;
		graphics.fill(left - 4, top - 3, left + wide + 4, top + 11, LABEL_BACK);
		graphics.text(font, label, left, top, TEXT);
	}
}
