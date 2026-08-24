package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.mopicmp.npcstudio.client.skin.FacePicture;
import com.mopicmp.npcstudio.entity.FaceMask;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Marking out where a face keeps its eyes, by hand, at the size it was drawn.
 *
 * <h2>Why this exists at all</h2>
 *
 * Reading a face off a skin will never be right every time — people draw visors,
 * masks, fringes and one eye winking. So there has to be a way to say what the
 * answer is, and the detector's job is to make saying it a correction rather than
 * a chore. Every mod that does this makes you do the whole job yourself: one has
 * you paint extra pixels into the skin file and re-upload it, another gives you a
 * selector and no guess to start from. Opening on a guess is the part worth
 * having.
 *
 * <h2>Why it is no longer eight by eight</h2>
 *
 * Because eight by eight was a decision about ordinary skins wearing the clothes
 * of a fact about faces. Measured over fifty HD skins, not one is a plain upscale
 * of a 64-wide skin, and there is no finer <em>fixed</em> grid that describes them
 * either — at sixteen cells across the eye comes out exactly on fifteen of the
 * fifty, at thirty-two on eighteen, at sixty-four on twenty-six. The only grid
 * that is right is the one the artist used.
 *
 * <h2>What the first version got wrong</h2>
 *
 * It was tried on a real HD face and three things were wrong with it, all of them
 * the same thing: a grid is not a drawing program.
 *
 * <ul>
 * <li><b>You could not see what you were about to do.</b> An HD eye is a dozen
 *     scattered colours and no tool said which cells it was going to take, so
 *     every fill was a guess followed by an undo. Now the cells a tool would
 *     change are lit up under the pointer before the button goes down — which is
 *     also the only honest way to show that the bucket and the wand are two
 *     different questions rather than two names for one.
 * <li><b>You could not see which layer anything was on.</b> There was a button
 *     that swapped the picture and nothing that let you compare. HD skins put a
 *     finer lash over a plain one and an iris over a sclera on the layer beneath,
 *     so which layer a pixel belongs to is half the answer, and the mask now
 *     carries it.
 * <li><b>The tools were down the side in words.</b> Which is a menu, and a menu
 *     costs the drawing the room it needed. They are pictures across the top now.
 * </ul>
 *
 * <h2>Mirroring</h2>
 *
 * On by default because faces are drawn symmetrically far more often than not —
 * measured at thirty-two of thirty-six real skins — so marking one eye usually
 * finishes both. It is a switch rather than a rule, because the other four exist.
 */
public class EyeMarkScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int BAR = 0xFF161A20;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GRID = 0x3A2A3038;
	private static final int GRID_EIGHTH = 0x66404A56;

	/**
	 * A colour for each kind of mark.
	 *
	 * Far apart on purpose, and never blended with each other. Two washes over one
	 * pixel make a third colour belonging to nothing, which is how the very first
	 * version of this managed to be unreadable at exactly the pixels that carried
	 * two answers.
	 */
	private static final int[] KIND_COLOUR = {
		0xFF4FC3F7, // eye
		0xFF81C784, // white
		0xFFFFB74D, // brow
		0xFFBA8CFF  // lash
	};

	private static final String[] KIND_NAME = { "зрачок", "белок", "бровь", "ресница" };
	private static final String[][] KIND_ICON = {
		Icons.EYE, Icons.WHITE, Icons.BROW, Icons.LASH
	};

	/** How the marking is being done. */
	private enum Tool { PENCIL, RECT, FILL, WAND, HAND }

	/** How far apart two colours may be and still count as the same drawing. */
	private static final int[] TOLERANCES = { 0, 6, 16, 32, 64, 110, 180 };

	/** How much of the window the furniture takes. */
	private static final int MARGIN = 8;
	private static final int TOP_TEXT = 6;
	private static final int BAR_TOP = 20;
	private static final int BUTTON = 20;
	private static final int FOOT = 30;

	/** The smallest cell a ring can be drawn in and still show the skin inside it. */
	private static final int RINGS_FROM = 10;

	private final Screen parent;

	/**
	 * What to do instead of going back, when there is nowhere on the display to go.
	 *
	 * Set by whoever is showing this inside itself rather than on the display. It
	 * is the same question the parent answers, asked by something that is not a
	 * screen you can be handed back to.
	 */
	private Runnable back;

	private final FacePicture picture;
	private final Identifier skin;
	private final FaceMask suggested;
	private final Consumer<FaceMask> done;

	private final int size;
	private long[][] masks;

	private FaceMask.Kind kind = FaceMask.Kind.EYE;
	private FaceMask.Layer editing = FaceMask.Layer.FACE;
	private Tool tool = Tool.PENCIL;

	/**
	 * Both start where the work actually starts.
	 *
	 * One layer at a time, because deciding which layer a pixel belongs to is the
	 * first question on an HD skin and a composite answers it last. And without the
	 * coverage outline, because on a face where the outer layer covers most of the
	 * head the outline is a net over everything and says nothing — it earns its
	 * keep when you go looking for an edge, not before.
	 */
	private boolean solo = true;
	private boolean showEdges;
	private boolean mirror = true;
	private boolean showMarks = true;
	private int nib = 1;
	private int tolerance = 2;

	/** How many screen pixels a cell of the face is, and where cell (0,0) sits. */
	private int zoom = 8;
	private int offX;
	private int offY;
	private boolean fitted;

	/** A box being dragged out, and whether it will mark or clear. */
	private boolean boxing;
	private boolean boxMarks;
	private int boxFromX;
	private int boxFromY;
	private int boxToX;
	private int boxToY;

	/** Whether the space bar is down, which turns any tool into the hand. */
	private boolean spacing;

	/**
	 * The cells the tool under the pointer would change, worked out once.
	 *
	 * The whole reason an HD face is markable at all. A flood fill over a quarter of
	 * a million cells is not something to do sixty times a second, and it is not
	 * something to do blind either — so it is done when the pointer moves to a new
	 * cell or a setting changes, and shown until then.
	 */
	private boolean[] preview;
	private int previewAt = -1;
	private int previewFor = -1;

	private final List<ToolButton> icons = new ArrayList<>();

	/**
	 * Where one group of buttons ends and the next begins.
	 *
	 * Drawn as a hairline rather than left to whitespace, because the grouping is
	 * carrying real meaning: that the bucket and the wand are two answers to one
	 * question is said by their sitting together, and a gap says it far more
	 * quietly than a line does.
	 */
	private final List<Integer> dividers = new ArrayList<>();
	private final List<Integer> dividerRow = new ArrayList<>();

	/**
	 * Where the next button goes, and how many rows the bar has come to.
	 *
	 * Twenty-one buttons is five hundred and fifty pixels, and at a large interface
	 * scale the whole window is four hundred and twenty-seven. So the bar wraps —
	 * and it wraps between groups rather than inside one, because a group split
	 * across two rows says the opposite of what the grouping is for.
	 */
	private int barX;
	private int barY;
	private int barRows = 1;

	/** Where the two numbers that belong between a pair of buttons are drawn. */
	private int brushLabel;
	private int brushRow;
	private int toleranceLabel;
	private int toleranceRow;

	/**
	 * What the face looked like before each of the last few strokes.
	 *
	 * Not a luxury once there is a fill: one click of "everything this colour" can
	 * change half the face, and without a way back the only remedy is to start
	 * again.
	 */
	private final Deque<long[][]> undo = new ArrayDeque<>();
	private static final int REMEMBERED = 32;

	/**
	 * @param picture the face at the size it was drawn, in both its layers
	 * @param skin    the whole skin, for drawing the face from
	 * @param opening what to start from — the detector's reading, or an earlier answer
	 * @param done    called with the finished mask, or not called if cancelled
	 */
	public EyeMarkScreen(Screen parent, FacePicture picture, Identifier skin,
			FaceMask opening, Consumer<FaceMask> done) {
		super(Component.literal("Глаза, брови и ресницы"));
		this.parent = parent;
		this.picture = picture;
		this.skin = skin;
		this.done = done;
		this.size = picture.size();

		// An answer made at another resolution is still an answer: it is grown or
		// shrunk to this face rather than thrown away, so that re-marking a skin
		// somebody redrew larger starts from what they already said.
		this.suggested = resize(opening == null ? FaceMask.NONE : opening, size);
		this.masks = suggested.masks();
	}

	// ------------------------------------------------------------------- the view

	private int viewLeft() {
		return MARGIN;
	}

	private int viewTop() {
		return canvasTop();
	}

	private int viewRight() {
		return width - MARGIN;
	}

	private int viewBottom() {
		return height - FOOT;
	}

	private void fit() {
		int across = Math.max(1, viewRight() - viewLeft());
		int down = Math.max(1, viewBottom() - viewTop());
		zoom = Math.max(1, Math.min(across, down) / size);
		offX = viewLeft() + (viewRight() - viewLeft() - size * zoom) / 2;
		offY = viewTop() + (viewBottom() - viewTop() - size * zoom) / 2;
		fitted = true;
	}

	/**
	 * Keeps the face from being pushed off the edge of the world.
	 *
	 * Panning with no limit is how a picture gets lost: one flick of the wheel at
	 * the wrong moment and the face is somewhere off to the left with nothing to
	 * say which way. A quarter of it always stays in view, so there is always
	 * something to drag back.
	 */
	private void hold() {
		int span = size * zoom;
		int keep = Math.min(span, Math.max(40, span / 4));
		offX = Math.clamp(offX, viewLeft() - span + keep, viewRight() - keep);
		offY = Math.clamp(offY, viewTop() - span + keep, viewBottom() - keep);
	}

	private int cellX(double mouseX) {
		return Math.floorDiv((int) mouseX - offX, zoom);
	}

	private int cellY(double mouseY) {
		return Math.floorDiv((int) mouseY - offY, zoom);
	}

	private boolean inView(double mouseX, double mouseY) {
		return mouseX >= viewLeft() && mouseX < viewRight()
			&& mouseY >= viewTop() && mouseY < viewBottom();
	}

	private boolean inside(int x, int y) {
		return x >= 0 && y >= 0 && x < size && y < size;
	}

	// ------------------------------------------------------------------- the parts

	@Override
	protected void init() {
		icons.clear();
		dividers.clear();
		dividerRow.clear();
		if (!fitted) fit();
		hold();

		barX = MARGIN;
		barY = BAR_TOP;
		barRows = 1;

		// What is being marked, first, because it is the question you answer most
		// often and the one the other buttons are all in service of.
		group(FaceMask.Kind.values().length, 0);
		for (FaceMask.Kind each : FaceMask.Kind.values()) {
			int at = each.ordinal();
			add(new ToolButton(barX, barY, BUTTON, KIND_ICON[at], KIND_COLOUR[at],
				KIND_NAME[at] + "   (" + (at + 1) + ")", () -> kind == each, () -> kind = each));
		}

		group(5, 0);
		add(new ToolButton(barX, barY, BUTTON, Icons.PENCIL, ACCENT,
			"карандаш — клетка за клеткой   (B)", () -> tool == Tool.PENCIL, () -> tool = Tool.PENCIL));
		add(new ToolButton(barX, barY, BUTTON, Icons.RECT, ACCENT,
			"прямоугольник — протянуть и отпустить   (R)", () -> tool == Tool.RECT, () -> tool = Tool.RECT));
		add(new ToolButton(barX, barY, BUTTON, Icons.FILL, ACCENT,
			"заливка — связное пятно того же цвета   (F)",
			() -> tool == Tool.FILL, () -> tool = Tool.FILL));
		add(new ToolButton(barX, barY, BUTTON, Icons.WAND, ACCENT,
			"по цвету — тот же цвет по всему лицу, даже вразброс   (G)",
			() -> tool == Tool.WAND, () -> tool = Tool.WAND));
		add(new ToolButton(barX, barY, BUTTON, Icons.HAND, ACCENT,
			"рука — двигать картинку   (H, пробел, средняя кнопка)",
			() -> tool == Tool.HAND, () -> tool = Tool.HAND));

		group(2, 16);
		add(new ToolButton(barX, barY, BUTTON, Icons.OUT, TEXT_DIM,
			"кисть меньше   ([)", () -> false, () -> nib = Math.max(1, nib - step())));
		brushLabel = barX + 5;
		brushRow = barY;
		barX += 16;
		add(new ToolButton(barX, barY, BUTTON, Icons.IN, TEXT_DIM,
			"кисть больше   (])", () -> false,
			() -> nib = Math.min(Math.max(1, size / 2), nib + step())));

		group(2, 22);
		add(new ToolButton(barX, barY, BUTTON, Icons.OUT, TEXT_DIM,
			"допуск меньше — заливка берёт только точный цвет   (−)", () -> false,
			() -> setTolerance(tolerance - 1)));
		toleranceLabel = barX + 5;
		toleranceRow = barY;
		barX += 22;
		add(new ToolButton(barX, barY, BUTTON, Icons.IN, TEXT_DIM,
			"допуск больше — заливка берёт и близкие оттенки   (=)", () -> false,
			() -> setTolerance(tolerance + 1)));

		// Which layer the marks land on. Two buttons rather than a cycle: this is
		// the thing that has to be answerable without pressing anything.
		group(4, 0);
		add(new ToolButton(barX, barY, BUTTON, Icons.LAYER_FACE, ACCENT,
			"метки ложатся на само лицо   (Q)", () -> editing == FaceMask.Layer.FACE,
			() -> setEditing(FaceMask.Layer.FACE)));
		add(new ToolButton(barX, barY, BUTTON, Icons.LAYER_OVER, 0xFFFFC46B,
			"метки ложатся на верхний слой   (W)", () -> editing == FaceMask.Layer.OVER,
			() -> setEditing(FaceMask.Layer.OVER)));
		add(new ToolButton(barX, barY, BUTTON, Icons.LAYER_BOTH, ACCENT,
			"показывать только рабочий слой   (O)", () -> solo, () -> {
				solo = !solo;
				preview = null;
				previewAt = -1;
			}));
		add(new ToolButton(barX, barY, BUTTON, Icons.EDGES, 0xFFFFC46B,
			"показать, где верхний слой закрывает лицо   (E)", () -> showEdges,
			() -> showEdges = !showEdges));

		group(4, 0);
		add(new ToolButton(barX, barY, BUTTON, Icons.MIRROR, ACCENT,
			"зеркало — вторая половина повторяет первую   (M)", () -> mirror,
			() -> mirror = !mirror));
		add(new ToolButton(barX, barY, BUTTON, Icons.MARKS, ACCENT,
			"показывать разметку   (V)", () -> showMarks, () -> showMarks = !showMarks));
		add(new ToolButton(barX, barY, BUTTON, Icons.UNDO, TEXT_DIM,
			"отменить   (Z)", () -> false, this::stepBack));
		add(new ToolButton(barX, barY, BUTTON, Icons.FIT, TEXT_DIM,
			"вписать лицо в окно   (0)", () -> false, this::fit));

		int bottom = height - FOOT + 5;
		addRenderableWidget(new FlatButton(MARGIN, bottom, 118, 18,
			Component.literal("как угадал мод"), TEXT_DIM, () -> {
				remember();
				masks = suggested.masks();
			}));
		addRenderableWidget(new FlatButton(MARGIN + 122, bottom, 62, 18,
			Component.literal("стереть"), TEXT_DIM, () -> {
				remember();
				masks = new long[FaceMask.PARTS][FaceMask.words(size)];
			}));
		addRenderableWidget(new FlatButton(width - MARGIN - 70, bottom, 70, 18,
			Component.literal("готово"), ACCENT, () -> {
				// Authored, because a person has now looked at it. That is what stops
				// a later, cleverer reading from overwriting their answer.
				done.accept(new FaceMask(size, masks, true));
				leave();
			}));
	}

	private int barBottom() {
		return BAR_TOP + barRows * (BUTTON + 4);
	}

	private int canvasTop() {
		return barBottom() + 4;
	}

	/**
	 * Starts a group: a divider before it, or a new row if it will not fit.
	 *
	 * @param buttons how many buttons are about to be added
	 * @param extra   room for anything drawn between them, like the brush size
	 */
	private void group(int buttons, int extra) {
		int needs = buttons * (BUTTON + 2) + extra;
		if (barX > MARGIN && barX + needs > width - MARGIN) {
			barRows++;
			barX = MARGIN;
			barY += BUTTON + 4;
			return;
		}
		if (barX > MARGIN) {
			dividers.add(barX + 3);
			dividerRow.add(barY);
			barX += 10;
		}
	}

	private void add(ToolButton button) {
		addRenderableWidget(button);
		icons.add(button);
		barX = button.getX() + BUTTON + 2;
	}

	private void setTolerance(int to) {
		tolerance = Math.clamp(to, 0, TOLERANCES.length - 1);
		previewAt = -1;
	}

	private void setEditing(FaceMask.Layer layer) {
		editing = layer;
		previewAt = -1;
	}

	/** How much a press of the size buttons is worth: coarse on a coarse face. */
	private int step() {
		return Math.max(1, size / 64);
	}

	@Override
	public void onClose() {
		leave();
	}

	// ------------------------------------------------------------------- the input

	/** Whether the pointer is dragging the picture rather than painting on it. */
	private boolean panning(int button) {
		return button == 2 || spacing || tool == Tool.HAND;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The buttons get first refusal, always. Claiming the click for the grid
		// before this ran would swallow every press that landed on a button.
		if (super.mouseClicked(event, doubleClick)) return true;
		if (!inView(event.x(), event.y())) return false;
		if (panning(event.button())) return true;

		int x = cellX(event.x());
		int y = cellY(event.y());
		if (!inside(x, y)) return false;

		boolean on = event.button() == 0;
		remember();
		switch (tool) {
			case PENCIL -> paint(x, y, on);
			case RECT -> {
				boxing = true;
				boxMarks = on;
				boxFromX = boxToX = x;
				boxFromY = boxToY = y;
			}
			case FILL, WAND -> {
				boolean[] taken = chosen(x, y);
				for (int cy = 0; cy < size; cy++) {
					for (int cx = 0; cx < size; cx++) {
						if (taken[cy * size + cx]) set(cx, cy, on);
					}
				}
			}
			case HAND -> { }
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (panning(event.button())) {
			offX += (int) dragX;
			offY += (int) dragY;
			hold();
			previewAt = -1;
			return true;
		}
		if (boxing) {
			boxToX = Math.clamp(cellX(event.x()), 0, size - 1);
			boxToY = Math.clamp(cellY(event.y()), 0, size - 1);
			return true;
		}
		if (tool != Tool.PENCIL || !inView(event.x(), event.y())) {
			return super.mouseDragged(event, dragX, dragY);
		}

		int x = cellX(event.x());
		int y = cellY(event.y());
		if (!inside(x, y)) return super.mouseDragged(event, dragX, dragY);
		paint(x, y, event.button() == 0);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (boxing) {
			for (int y = Math.min(boxFromY, boxToY); y <= Math.max(boxFromY, boxToY); y++) {
				for (int x = Math.min(boxFromX, boxToX); x <= Math.max(boxFromX, boxToX); x++) {
					set(x, y, boxMarks);
				}
			}
			boxing = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	/**
	 * The wheel moves closer, and it moves closer to whatever is under the pointer.
	 *
	 * Zooming about the middle of the window is the version that is easy to write
	 * and maddening to use: the thing you were looking at slides away every time.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (!inView(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, dx, dy);

		int was = zoom;
		zoom = Math.clamp(zoom + (int) Math.signum(dy) * Math.max(1, zoom / 4), 1, 48);
		if (zoom == was) return true;

		double atX = (mouseX - offX) / (double) was;
		double atY = (mouseY - offY) / (double) was;
		offX = (int) Math.round(mouseX - atX * zoom);
		offY = (int) Math.round(mouseY - atY * zoom);
		hold();
		previewAt = -1;
		return true;
	}

	/**
	 * A key for everything on the bar.
	 *
	 * Not a convenience. Marking a face is one hand on the mouse for minutes at a
	 * time, and every trip back up to the toolbar is a trip away from the pixel you
	 * were aiming at — so the tools that get swapped constantly, which is all of
	 * them, have to be reachable without moving. Each key is written on the button
	 * it belongs to, so nobody has to learn them from a page.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		int shove = Math.max(8, (viewRight() - viewLeft()) / 6);
		switch (key) {
			case 32 -> spacing = true;
			case 49, 50, 51, 52 -> kind = FaceMask.Kind.values()[key - 49];
			case 66 -> tool = Tool.PENCIL;
			case 82 -> tool = Tool.RECT;
			case 70 -> tool = Tool.FILL;
			case 71 -> tool = Tool.WAND;
			case 72 -> tool = Tool.HAND;
			case 81 -> setEditing(FaceMask.Layer.FACE);
			case 87 -> setEditing(FaceMask.Layer.OVER);
			case 79 -> {
				solo = !solo;
				preview = null;
			}
			case 69 -> showEdges = !showEdges;
			case 77 -> mirror = !mirror;
			case 86 -> showMarks = !showMarks;
			case 90 -> stepBack();
			case 48 -> fit();
			case 91 -> nib = Math.max(1, nib - step());
			case 93 -> nib = Math.min(Math.max(1, size / 2), nib + step());
			case 45, 333 -> setTolerance(tolerance - 1);
			case 61, 334 -> setTolerance(tolerance + 1);
			// The arrows move the picture, which is the one way of moving it that
			// works when both hands are already busy.
			case 263 -> offX += shove;
			case 262 -> offX -= shove;
			case 265 -> offY += shove;
			case 264 -> offY -= shove;
			default -> {
				return super.keyPressed(event);
			}
		}
		hold();
		previewAt = -1;
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.key() == 32) {
			spacing = false;
			return true;
		}
		return super.keyReleased(event);
	}

	// ------------------------------------------------------------------ the marking

	/** The nib, centred on the cell, and its mirror image if that is wanted. */
	private void paint(int x, int y, boolean on) {
		int from = -(nib - 1) / 2;
		int to = nib / 2;
		for (int dy = from; dy <= to; dy++) {
			for (int dx = from; dx <= to; dx++) set(x + dx, y + dy, on);
		}
	}

	private void set(int x, int y, boolean on) {
		mark(x, y, on);
		if (mirror) mark(size - 1 - x, y, on);
	}

	/**
	 * A mark lands on the layer that is chosen, always — never on the one the
	 * pixel happens to be visible on.
	 *
	 * Guessing was considered and is wrong here. On a composite view the topmost
	 * opaque layer is a reasonable guess and a guess is exactly what somebody
	 * marking a face is here to replace: a lash drawn on both layers would take
	 * whichever half the pointer happened to land on, and there would be no way to
	 * say "no, this one is the layer underneath". The two buttons say it instead.
	 */
	private void mark(int x, int y, boolean on) {
		if (!inside(x, y)) return;
		long[] mask = masks[FaceMask.part(kind, editing)];
		int bit = y * size + x;
		if (on) mask[bit >>> 6] |= 1L << (bit & 63);
		else mask[bit >>> 6] &= ~(1L << (bit & 63));
	}

	private boolean bit(long[] mask, int x, int y) {
		int at = y * size + x;
		return (mask[at >>> 6] & (1L << (at & 63))) != 0;
	}

	/** Which picture the fills read colours from. */
	private FacePicture.Layer looking() {
		if (!solo) return FacePicture.Layer.BOTH;
		return editing == FaceMask.Layer.OVER ? FacePicture.Layer.HAT : FacePicture.Layer.BASE;
	}

	/**
	 * The cells the tool under the pointer would take.
	 *
	 * <h2>Why the bucket and the wand are two buttons</h2>
	 *
	 * They answer different questions and an HD eye needs both. The bucket is "this
	 * patch": click an iris, get that iris. The wand is "this colour, anywhere":
	 * the same iris on the other side of a face drawn slightly off-centre, a lash
	 * broken into three pieces by a highlight, the four scattered cells of a
	 * catchlight. Neither can do the other's job.
	 *
	 * That the two looked identical in the first version was not a bug in either —
	 * it was that nothing showed what they were about to do, so on a patch that
	 * happened to be the only one of its colour they were the same. Now the answer
	 * is drawn before the click.
	 */
	private boolean[] chosen(int x, int y) {
		boolean[] taken = new boolean[size * size];
		if (!inside(x, y)) return taken;

		FacePicture.Layer look = looking();
		int seed = picture.at(look, x, y);
		int apart = TOLERANCES[tolerance];

		if (tool == Tool.WAND) {
			for (int cy = 0; cy < size; cy++) {
				for (int cx = 0; cx < size; cx++) {
					taken[cy * size + cx] = near(picture.at(look, cx, cy), seed, apart);
				}
			}
			return taken;
		}

		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[] { x, y });
		taken[y * size + x] = true;
		while (!queue.isEmpty()) {
			int[] at = queue.poll();
			for (int[] step : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
				int nx = at[0] + step[0];
				int ny = at[1] + step[1];
				if (!inside(nx, ny) || taken[ny * size + nx]) continue;
				if (!near(picture.at(look, nx, ny), seed, apart)) continue;
				taken[ny * size + nx] = true;
				queue.add(new int[] { nx, ny });
			}
		}
		return taken;
	}

	/**
	 * Whether two colours are the same drawing.
	 *
	 * Transparency counts as its own colour rather than as a difference in one: on
	 * the outer layer, "nothing here" is the commonest thing a pixel is, and a fill
	 * that leaked from a hole in a fringe into the whole of the empty sky around it
	 * would be worse than useless.
	 */
	private static boolean near(int colour, int seed, int apart) {
		int alpha = (colour >>> 24) & 0xFF;
		int seedAlpha = (seed >>> 24) & 0xFF;
		if ((alpha < 128) != (seedAlpha < 128)) return false;
		if (alpha < 128) return true;
		return Math.abs(((colour >> 16) & 0xFF) - ((seed >> 16) & 0xFF))
			+ Math.abs(((colour >> 8) & 0xFF) - ((seed >> 8) & 0xFF))
			+ Math.abs((colour & 0xFF) - (seed & 0xFF)) <= apart;
	}

	private void remember() {
		long[][] copy = new long[FaceMask.PARTS][];
		for (int i = 0; i < FaceMask.PARTS; i++) copy[i] = masks[i].clone();
		undo.push(copy);
		while (undo.size() > REMEMBERED) undo.removeLast();
	}

	private void stepBack() {
		long[][] was = undo.poll();
		if (was != null) masks = was;
	}

	/**
	 * A mask made at one size, said at another.
	 *
	 * Growing is exact — one cell becomes a square of cells and nothing is decided.
	 * Shrinking loses whatever was finer than the new grid, which is the honest
	 * outcome and the reason a mask carries the size it was made at rather than
	 * being normalised on the way in.
	 */
	private static FaceMask resize(FaceMask mask, int size) {
		if (mask.isNone()) return new FaceMask(size, null, false);
		if (mask.size() == size) return mask;

		long[][] out = new long[FaceMask.PARTS][FaceMask.words(size)];
		for (FaceMask.Kind kind : FaceMask.Kind.values()) {
			for (FaceMask.Layer layer : FaceMask.Layer.values()) {
				long[] to = out[FaceMask.part(kind, layer)];
				for (int y = 0; y < size; y++) {
					for (int x = 0; x < size; x++) {
						if (!mask.is(kind, layer, x * mask.size() / size, y * mask.size() / size)) {
							continue;
						}
						int bit = y * size + x;
						to[bit >>> 6] |= 1L << (bit & 63);
					}
				}
			}
		}
		return new FaceMask(size, out, mask.authored());
	}

	// ------------------------------------------------------------------ the drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.fill(0, BAR_TOP - 4, width, barBottom(), BAR);
		for (int i = 0; i < dividers.size(); i++) {
			int at = dividers.get(i);
			int row = dividerRow.get(i);
			graphics.fill(at, row + 2, at + 1, row + BUTTON - 2, 0xFF39424E);
		}
		graphics.fill(0, height - FOOT, width, height, BAR);

		freshenPreview(mouseX, mouseY);
		face(graphics);
		if (showMarks) marks(graphics);
		hover(graphics, mouseX, mouseY);
		if (boxing) outline(graphics, boxFromX, boxFromY, boxToX, boxToY, ACCENT);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
		words(graphics, mouseX, mouseY);
	}

	/**
	 * The one line of text, which says whatever is most worth saying right now.
	 *
	 * Resting on a button says what that button does; the rest of the time it says
	 * what is in your hand and where the pointer is. One place for words rather
	 * than a floating box that appears wherever the mouse happens to be.
	 */
	private void words(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		String said = null;
		for (ToolButton button : icons) {
			if (button.isHovered()) {
				said = button.says();
				break;
			}
		}
		if (said == null) {
			said = KIND_NAME[kind.ordinal()] + " · "
				+ (editing == FaceMask.Layer.OVER ? "верхний слой" : "лицо")
				+ (tool == Tool.PENCIL || tool == Tool.RECT ? " · кисть " + nib
					: tool == Tool.FILL || tool == Tool.WAND ? " · допуск " + TOLERANCES[tolerance]
					: "");
		}
		graphics.text(font, Component.literal(said), MARGIN, TOP_TEXT,
			KIND_COLOUR[kind.ordinal()]);

		StringBuilder right = new StringBuilder();
		right.append(size).append('×').append(size).append("  ").append(zoom).append('×');
		if (inView(mouseX, mouseY)) {
			int x = cellX(mouseX);
			int y = cellY(mouseY);
			if (inside(x, y)) right.append("   ").append(x).append(", ").append(y);
		}
		graphics.text(font, Component.literal(right.toString()),
			width - MARGIN - font.width(right.toString()), TOP_TEXT, TEXT_DIM);

		// The numbers that belong to a pair of buttons, drawn between them.
		graphics.text(font, Component.literal(String.valueOf(nib)),
			brushLabel, brushRow + 6, TEXT);
		graphics.text(font, Component.literal(String.valueOf(TOLERANCES[tolerance])),
			toleranceLabel, toleranceRow + 6, TEXT);

		String keys = "ЛКМ ставит · ПКМ убирает · колесо приближает · пробел или средняя двигает";
		graphics.text(font, Component.literal(keys),
			MARGIN + 196, height - FOOT + 10, TEXT_DIM);
	}

	/**
	 * The face itself, drawn from the skin rather than a cell at a time.
	 *
	 * Two draws whatever the resolution. Filling sixty-five thousand rectangles a
	 * frame is the obvious way to put a face on the screen and it is one that stops
	 * being a drawing program somewhere around a 1024-wide skin.
	 */
	private void face(GuiGraphicsExtractor graphics) {
		int span = size * zoom;
		graphics.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());
		graphics.fill(viewLeft(), viewTop(), viewRight(), viewBottom(), 0xFF0A0C0F);

		boolean overOnly = solo && editing == FaceMask.Layer.OVER;
		// A chequer under the picture, so a transparent outer layer reads as
		// transparent rather than as black paint.
		if (overOnly) {
			// Only the squares that are on the screen. Drawn over the whole face it
			// is sixty-five thousand rectangles on a 2048-wide skin, every frame,
			// which is exactly the stutter that showed up the moment this was
			// switched on with a large face open — and none of them were visible.
			int square = Math.max(4, zoom);
			int fromX = offX + Math.max(0, (viewLeft() - offX) / square) * square;
			int fromY = offY + Math.max(0, (viewTop() - offY) / square) * square;
			int toX = Math.min(offX + span, viewRight() + square);
			int toY = Math.min(offY + span, viewBottom() + square);
			for (int y = fromY; y < toY; y += square) {
				for (int x = fromX; x < toX; x += square) {
					boolean odd = ((x - offX) / square + (y - offY) / square) % 2 == 0;
					graphics.fill(x, y, Math.min(x + square, offX + span),
						Math.min(y + square, offY + span), odd ? 0xFF20262E : 0xFF171C22);
				}
			}
		}

		if (skin != null) {
			if (!overOnly) {
				graphics.blit(RenderPipelines.GUI_TEXTURED, skin, offX, offY,
					FacePicture.FACE_LEFT, FacePicture.FACE_TOP, span, span,
					FacePicture.SPAN, FacePicture.SPAN, 64, picture.tall());
			}
			if (!(solo && editing == FaceMask.Layer.FACE)) {
				graphics.blit(RenderPipelines.GUI_TEXTURED, skin, offX, offY,
					FacePicture.HAT_LEFT, FacePicture.HAT_TOP, span, span,
					FacePicture.SPAN, FacePicture.SPAN, 64, picture.tall());
			}
		}

		if (showEdges && !solo) coverage(graphics);
		grid(graphics, span);
		graphics.disableScissor();
	}

	/**
	 * Where the outer layer covers the face.
	 *
	 * The answer to "which layer am I looking at", asked without changing what is
	 * on the screen. A dotted edge round the covered region rather than a wash over
	 * it: a wash changes the colours you are trying to read, and the colours are the
	 * whole reason for looking.
	 */
	private void coverage(GuiGraphicsExtractor graphics) {
		int first = Math.max(0, (viewLeft() - offX) / zoom);
		int last = Math.min(size - 1, (viewRight() - offX) / zoom);
		int top = Math.max(0, (viewTop() - offY) / zoom);
		int bottom = Math.min(size - 1, (viewBottom() - offY) / zoom);
		int edge = 0xC0FFC46B;
		int thick = Math.max(1, zoom / 8);

		for (int y = top; y <= bottom; y++) {
			for (int x = first; x <= last; x++) {
				if (!covered(x, y)) continue;
				int at = offX + x * zoom;
				int to = offY + y * zoom;
				if (!covered(x, y - 1)) graphics.fill(at, to, at + zoom, to + thick, edge);
				if (!covered(x, y + 1)) graphics.fill(at, to + zoom - thick, at + zoom, to + zoom, edge);
				if (!covered(x - 1, y)) graphics.fill(at, to, at + thick, to + zoom, edge);
				if (!covered(x + 1, y)) graphics.fill(at + zoom - thick, to, at + zoom, to + zoom, edge);
			}
		}
	}

	private boolean covered(int x, int y) {
		return inside(x, y) && (picture.at(FacePicture.Layer.HAT, x, y) >>> 24) >= 128;
	}

	/**
	 * The grid, faint, and only where it can be seen without swallowing the picture.
	 *
	 * Every eighth line is stronger. On a face a hundred and twenty-eight cells
	 * across, a grid of identical hairlines is a texture rather than a ruler — the
	 * heavier lines are what let you say which pixel you are on without counting
	 * from the edge.
	 */
	private void grid(GuiGraphicsExtractor graphics, int span) {
		if (zoom >= 5) {
			for (int i = 0; i <= size; i++) {
				boolean eighth = i % 8 == 0;
				if (!eighth && zoom < 8) continue;
				int colour = eighth ? GRID_EIGHTH : GRID;
				graphics.fill(offX + i * zoom, offY, offX + i * zoom + 1, offY + span, colour);
				graphics.fill(offX, offY + i * zoom, offX + span, offY + i * zoom + 1, colour);
			}
		}
		// The middle of the face always, because that is what the mirror folds
		// about and where an eye stops being the left one.
		graphics.fill(offX + span / 2, offY, offX + span / 2 + 1, offY + span,
			mirror ? 0xB04FC3F7 : 0x50FFFFFF);
	}

	private void marks(GuiGraphicsExtractor graphics) {
		graphics.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());

		int first = Math.max(0, (viewLeft() - offX) / zoom);
		int last = Math.min(size - 1, (viewRight() - offX) / zoom);
		int top = Math.max(0, (viewTop() - offY) / zoom);
		int bottom = Math.min(size - 1, (viewBottom() - offY) / zoom);
		int thick = Math.max(1, zoom / 10);

		for (int y = top; y <= bottom; y++) {
			for (int x = first; x <= last; x++) {
				int at = offX + x * zoom;
				int to = offY + y * zoom;
				int ring = 0;
				int strongest = 0;
				boolean any = false;

				for (FaceMask.Kind each : FaceMask.Kind.values()) {
					for (FaceMask.Layer layer : FaceMask.Layer.values()) {
						if (!bit(masks[FaceMask.part(each, layer)], x, y)) continue;
						any = true;
						// A mark on the layer you are not working on is still shown, and
						// shown faintly. Hiding it is how you come to mark the same lash
						// twice; showing it as brightly is how you lose track of which
						// half of the head you are on.
						int alpha = layer == editing ? 0xFF000000 : 0x70000000;
						int colour = (KIND_COLOUR[each.ordinal()] & 0xFFFFFF) | alpha;
						if (layer == editing) strongest = colour;
						else if (strongest == 0) strongest = colour;

						if (zoom >= RINGS_FROM) {
							ring(graphics, at, to, ring * (thick + 1), thick, colour);
							ring++;
						}
					}
				}
				if (any && zoom < RINGS_FROM) {
					graphics.fill(at, to, at + zoom, to + zoom,
						(strongest & 0xFFFFFF) | 0xC0000000);
				}
			}
		}
		graphics.disableScissor();
	}

	/**
	 * What the tool under the pointer would do, shown before it is done.
	 *
	 * The single change that made an HD face markable. Everything else here is a
	 * convenience; this is the difference between choosing a fill and guessing one.
	 */
	private void freshenPreview(int mouseX, int mouseY) {
		if (!inView(mouseX, mouseY) || tool == Tool.HAND || boxing) {
			preview = null;
			return;
		}
		int x = cellX(mouseX);
		int y = cellY(mouseY);
		if (!inside(x, y)) {
			preview = null;
			return;
		}
		if (tool != Tool.FILL && tool != Tool.WAND) {
			preview = null;
			return;
		}

		int at = y * size + x;
		int forWhat = tolerance * 8 + tool.ordinal() * 2 + (solo ? 1 : 0) + editing.ordinal() * 64;
		if (at == previewAt && forWhat == previewFor && preview != null) return;
		previewAt = at;
		previewFor = forWhat;
		preview = chosen(x, y);
	}

	private void hover(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!inView(mouseX, mouseY)) return;
		int x = cellX(mouseX);
		int y = cellY(mouseY);
		if (!inside(x, y)) return;

		graphics.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());
		int tint = (KIND_COLOUR[kind.ordinal()] & 0xFFFFFF) | 0x60000000;

		if (preview != null) {
			for (int cy = 0; cy < size; cy++) {
				for (int cx = 0; cx < size; cx++) {
					if (!preview[cy * size + cx]) continue;
					int at = offX + cx * zoom;
					int to = offY + cy * zoom;
					if (at + zoom < viewLeft() || at > viewRight()) continue;
					if (to + zoom < viewTop() || to > viewBottom()) continue;
					graphics.fill(at, to, at + zoom, to + zoom, tint);
					if (mirror) {
						int mx = offX + (size - 1 - cx) * zoom;
						graphics.fill(mx, to, mx + zoom, to + zoom, tint);
					}
				}
			}
		} else if (tool == Tool.PENCIL || tool == Tool.RECT) {
			int from = -(nib - 1) / 2;
			int to = nib / 2;
			outline(graphics, x + from, y + from, x + to, y + to, tint | 0xFF000000);
			if (mirror) {
				outline(graphics, size - 1 - x - to, y + from, size - 1 - x - from, y + to,
					tint | 0x90000000);
			}
		}
		graphics.disableScissor();
	}

	private void outline(GuiGraphicsExtractor graphics, int fromX, int fromY,
			int toX, int toY, int colour) {
		int x0 = offX + Math.min(fromX, toX) * zoom;
		int y0 = offY + Math.min(fromY, toY) * zoom;
		int x1 = offX + (Math.max(fromX, toX) + 1) * zoom;
		int y1 = offY + (Math.max(fromY, toY) + 1) * zoom;
		int thick = Math.max(1, zoom / 8);
		graphics.fill(x0, y0, x1, y0 + thick, colour);
		graphics.fill(x0, y1 - thick, x1, y1, colour);
		graphics.fill(x0, y0, x0 + thick, y1, colour);
		graphics.fill(x1 - thick, y0, x1, y1, colour);
	}

	/** One square ring inside a cell, so several marks can share a pixel. */
	private void ring(GuiGraphicsExtractor graphics, int at, int to, int inset,
			int thick, int colour) {
		int from = at + inset;
		int top = to + inset;
		int span = zoom - inset * 2;
		if (span <= thick * 2) return;

		graphics.fill(from, top, from + span, top + thick, colour);
		graphics.fill(from, top + span - thick, from + span, top + span, colour);
		graphics.fill(from, top, from + thick, top + span, colour);
		graphics.fill(from + span - thick, top, from + span, top + span, colour);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * Goes back where it came from — unless there is nowhere to go back to.
	 *
	 * A null parent means this is living inside the workspace as a panel, and a
	 * panel has no "back": the thing behind it is the rest of the workspace, and
	 * handing the display to null would close all of it. So leaving becomes
	 * staying, which is what a panel does when you have finished with it — unless
	 * whoever put this up said what to do instead.
	 */
	private void leave() {
		if (back != null) {
			back.run();
			return;
		}
		if (parent != null) minecraft.setScreenAndShow(parent);
	}

	/** Says where "done" goes when this is living inside another screen. */
	public EyeMarkScreen backTo(Runnable where) {
		this.back = where;
		return this;
	}

}
