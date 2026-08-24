package com.mopicmp.npcstudio.client.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.mopicmp.npcstudio.client.editor.IconButton;
import com.mopicmp.npcstudio.client.editor.PreviewFigure;
import com.mopicmp.npcstudio.client.skin.SkinImport;
import com.mopicmp.npcstudio.net.WardrobePayloads;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The world's costumes, to look through and to dress somebody in.
 *
 * Built to the same shape as the animation picker on purpose: a grid on the
 * left with a search box and a category menu, a large figure on the right that
 * can be turned. Not because one screen must look like another, but because
 * somebody who has used one already knows this one, and because the layout
 * earned its keep there.
 *
 * The costumes are the world's rather than one character's, so this is a
 * library and behaves like one — several can be picked at once, moved between
 * shelves together, copied onto a second shelf, or thrown out together.
 * Everything destructive is recoverable: the pictures are never deleted and the
 * list is versioned, which is what makes a mass action safe enough to offer at
 * all.
 */
public class WardrobeScreen extends Screen {

	/**
	 * The measurements, and why there are two of nearly everything.
	 *
	 * This was written to fill a display, where sixteen pixels of margin reads as
	 * room to breathe. The same sixteen inside a panel three hundred wide is a
	 * tenth of the screen spent on nothing — and with the header, the details
	 * block and the footer all at their full-screen heights there were two pixels
	 * left over for the costumes themselves. The grid drew its one forced row
	 * straight through the editing panel, and that overlap is what "crooked"
	 * looked like from the outside.
	 *
	 * So the fixed numbers are a floor and a ceiling now, and what sits between
	 * them is worked out from the room there is.
	 */
	private static final int MARGIN = 16;
	private static final int SNUG = 6;
	private static final int GAP = 6;

	/**
	 * How much room a name takes under a tile.
	 *
	 * Ten, which is one line, and it used to be twenty. The extra ten were for a
	 * second line that never existed — the name is trimmed to the tile's width —
	 * so every row of the shelf carried half a line of nothing and a screen held
	 * one row fewer than it could.
	 */
	private static final int LABEL = 10;

	/**
	 * A tile is taller than it is wide, because a person is.
	 *
	 * The costumes were square when they were flat cut-outs and the figure had to
	 * be shrunk to fit the narrow way. A drawn figure standing at three-quarters
	 * needs its height, and a card shaped like the thing on it wastes no room —
	 * five to four is about what a turned figure occupies.
	 */
	private static final int CELL = 62;
	private static final int CELL_SNUG = 48;
	private static final int TALLER = 5;
	private static final int WIDER = 4;

	/** The editing panel: a heading, three boxes, and the copy button. */
	private static final int DETAILS = 80;

	/**
	 * Shift and control, as the window system reports them.
	 *
	 * Read off the click itself rather than asked of the keyboard: the state at
	 * the moment of the press is what the person meant, and a key let go in the
	 * meantime should not change what their click did.
	 */
	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	private static final int CANVAS = 0xFF101318;
	private static final int PANEL = 0xFF161A20;
	private static final int TILE = 0xFF1B2028;
	private static final int TILE_HOVER = 0xFF232A34;
	private static final int TILE_ON = 0xFF27313E;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;
	private static final int WARN = 0xFFE57373;

	/** Which list is hanging open over the rest of the screen, if any. */
	private enum Overlay { NONE, SHELVES, VERSIONS }

	private final Screen parent;

	/** The character being dressed, or -1 when this was opened just to tidy up. */
	private final int entityId;

	private EditBox search;
	private EditBox nameBox;
	private EditBox categoryBox;
	private EditBox groupBox;

	private List<WardrobePayloads.Costume> shown = List.of();
	private String category;
	private Overlay overlay = Overlay.NONE;
	private int overlayX = MARGIN;
	private int overlayY = MARGIN + 40;

	/**
	 * What is picked out, by identity.
	 *
	 * A set of names rather than of row numbers, and for the reason the animation
	 * picker taught: a number points at whatever has moved into that slot when
	 * the filter changes, so a selection made in one category came back holding
	 * something from another.
	 */
	private final Set<String> picked = new LinkedHashSet<>();

	/**
	 * What copy remembered.
	 *
	 * Kept apart from the selection so that copying, then clicking somewhere else
	 * to choose where it should land, does not throw away what was copied — which
	 * is exactly the sequence anybody performs.
	 */
	private final List<String> copied = new ArrayList<>();

	/** The selection the boxes were last filled from, so they refill when it moves. */
	private String filledFrom = "";

	private int scroll;
	private float zoom = 1f;
	private float spin;
	private float pitch = PreviewFigure.RESTING_PITCH;
	private boolean dragging;
	private double dragFrom;
	private double dragFromY;
	private String notice = "";

	public WardrobeScreen(Screen parent, int entityId) {
		super(Component.literal("Костюмы"));
		this.parent = parent;
		this.entityId = entityId;
	}

	@Override
	protected void init() {
		Costumes.refresh();
		refilter();

		if (search == null) {
			search = box(200, "поиск");
			search.setResponder(text -> {
				search.setSuggestion(text.isEmpty() ? "поиск" : "");
				refilter();
				scroll = 0;
			});
			nameBox = hinted(box(200, "название"), "название");
			categoryBox = hinted(box(100, "категория"), "категория");
			groupBox = hinted(box(100, "подкатегория"), "подкатегория");
		}

		int toolbar = toolbarTop();
		// Shared out rather than fixed at a hundred and ninety: at that width the
		// search box in a panel had eighty pixels and the category name was cut
		// after two words, which is the wrong way round — the shelf you are on is
		// one word and what you are looking for is typed a letter at a time.
		int shelves = Math.min(190, (gridRight() - margin()) * 55 / 100);
		addRenderableWidget(IconButton.labelled(margin(), toolbar, shelves, 18,
			() -> "▾ " + (category == null ? "все" : category), ACCENT,
			Component.literal("категория"), () -> open(Overlay.SHELVES, margin(), toolbar + 22)));
		search.setPosition(margin() + shelves + 6, toolbar);
		search.setWidth(Math.max(60, gridRight() - margin() - shelves - 6));
		addRenderableWidget(search);

		layOutDetails();

		int bottom = footerTop();

		// Laid out by the room there is. Seven controls at fixed offsets came to
		// something over five hundred pixels, and in a panel the last four of them
		// were off the end — including the one that removes a costume.
		java.util.List<IconTextButton.Spec> row = new java.util.ArrayList<>();
		if (entityId >= 0) {
			// Wearing is the point, and it is now the only way to do it: clicking a
			// costume holds it up against the character, and this is what commits.
			row.add(new IconTextButton.Spec(Icon.CHECK,
				Component.translatable("npc_studio.wardrobe.wear"), GOOD, this::wear));
		}
		row.add(new IconTextButton.Spec(Icon.CANCEL,
			Component.translatable("npc_studio.wardrobe.close"), TEXT_DIM, () -> leave()));
		// Adding is the reason somebody opens an empty wardrobe. A library with no
		// visible way to put anything in it reads as a library that cannot be filled.
		row.add(new IconTextButton.Spec(Icon.ADD,
			Component.translatable("npc_studio.wardrobe.add"), GOOD, this::importCostume));
		row.add(new IconTextButton.Spec(Icon.EYES,
			Component.translatable("npc_studio.wardrobe.eyes"), ACCENT, this::markEyes));
		row.add(new IconTextButton.Spec(Icon.DRAFTS,
			Component.translatable("npc_studio.wardrobe.versions"), ACCENT,
			() -> open(Overlay.VERSIONS, margin(), bottom - 4)));
		row.add(new IconTextButton.Spec(Icon.REMOVE,
			Component.translatable("npc_studio.wardrobe.remove"), WARN, this::remove));

		IconTextButton.row(margin(), bottom, width - margin() * 2, 20, row, this::addRenderableWidget);

		// A face being marked out survives the panel being resized: it is laid out
		// again rather than thrown away, exactly as this screen is.
		if (detour != null) detour.init(width, height);
	}

	/**
	 * Opens the face of the chosen costume to be marked out by hand.
	 *
	 * It opens on whatever is known: the answer given a moment ago if there is
	 * one, otherwise the reading. That is the whole point of having a reading —
	 * the work becomes a correction rather than marking sixty-four pixels from
	 * scratch.
	 */
	private void markEyes() {
		if (picked.isEmpty()) {
			notice = "сначала выбери костюм";
			return;
		}
		String id = picked.iterator().next();
		var found = Costumes.all().stream().filter(c -> c.id().equals(id)).findFirst();
		if (found.isEmpty()) return;

		String fingerprint = found.get().fingerprint();
		var picture = Costumes.facePicture(fingerprint);
		if (picture == null) {
			// The list arrives whole and the pictures do not, so this is a wait
			// rather than a failure. Asking for it is what makes the next press work.
			Costumes.texture(fingerprint);
			notice = "картинка ещё едет, нажми ещё раз";
			return;
		}

		// The reading is still eight by eight, so a guess is offered in eighths and
		// grown to the face's own size on the way in. That is the honest thing: it
		// says where the eyes roughly are and leaves the person to say where they
		// exactly are, which is the whole reason for opening on a guess at all.
		var known = Costumes.markedEyes(id);
		var opening = known != null ? known
			: com.mopicmp.npcstudio.entity.FaceMask.of(
				com.mopicmp.npcstudio.skin.FaceLook.read(picture.eighths()));

		if (!showsFigure()) {
			// Inside the workspace this button did nothing at all — it checked
			// whether it was embedded and returned, because opening a screen there
			// means replacing the whole workspace with it. Which is true, and is a
			// reason not to call setScreen; it was never a reason for the marking to
			// be unreachable. So the editor is put up inside this screen instead: the
			// wardrobe hands over everything it is given until the face is finished,
			// and the panel around it is none the wiser.
			// Anything half typed goes now: the boxes are about to stop being drawn,
			// and a box nobody can see is a box nobody will think to leave.
			keep();
			wasEditing = false;
			setFocused(null);

			var editor = new com.mopicmp.npcstudio.client.editor.EyeMarkScreen(
				null, picture, Costumes.texture(fingerprint), opening,
				marked -> Costumes.markEyes(id, marked));
			editor.backTo(() -> detour = null);
			editor.init(width, height);
			detour = editor;
			return;
		}
		minecraft.setScreenAndShow(new com.mopicmp.npcstudio.client.editor.EyeMarkScreen(
			this, picture, Costumes.texture(fingerprint), opening,
			marked -> Costumes.markEyes(id, marked)));
	}

	/**
	 * A screen being shown in this one's place, or null.
	 *
	 * Only ever the eye editor so far. Held rather than pushed onto the display
	 * because this screen may itself be a panel, and a panel's way back is not the
	 * display's.
	 */
	private Screen detour;

	private EditBox box(int width, String hint) {
		EditBox made = new EditBox(font, 0, 0, width, 18, Component.literal(hint));
		made.setSuggestion(hint);
		made.setMaxLength(48);
		return made;
	}

	/**
	 * Makes the grey hint go away when there is something written over it.
	 *
	 * A suggestion is drawn after whatever the box holds rather than behind it, so
	 * a hint set once and never cleared appears as part of the text the moment
	 * anybody types — "корабльподкатегория" in the box they are trying to read.
	 * The search box always knew this; the three under the picture were set once
	 * at build time and never told again.
	 */
	private EditBox hinted(EditBox made, String hint) {
		made.setResponder(text -> made.setSuggestion(text.isEmpty() ? hint : ""));
		return made;
	}

	/**
	 * The editing panel, under the preview.
	 *
	 * Three boxes and two buttons rather than a dialog per operation. Renaming a
	 * costume and moving it to another shelf are the same gesture as far as
	 * anybody is concerned — look at the thing, change what is written about it —
	 * and splitting them across two windows would only be faithful to how they
	 * happen to be stored.
	 */
	private void layOutDetails() {
		int left = detailsLeft();
		int wide = detailsWide();
		int half = (wide - 4) / 2;
		int top = detailsTop() + 14;

		nameBox.setPosition(left, top);
		nameBox.setWidth(wide);
		addRenderableWidget(nameBox);

		categoryBox.setPosition(left, top + 22);
		categoryBox.setWidth(half);
		addRenderableWidget(categoryBox);

		groupBox.setPosition(left + half + 4, top + 22);
		groupBox.setWidth(wide - half - 4);
		addRenderableWidget(groupBox);

		// "Применить" used to sit here. What it applied now applies itself the
		// moment the caret leaves the box, so the button was a step between having
		// typed something and having it be true — and a step that can be forgotten
		// is a step that will be. Copying stays a button because it is not a saved
		// edit at all: it makes a second entry, which is a thing you ask for.
		addRenderableWidget(IconButton.labelled(left + wide - 60, top + 44, 60, 18,
			() -> "копия", GOOD,
			Component.literal("положить те же костюмы ещё и сюда — места это не займёт"),
			this::copyHere));
	}

	/**
	 * Whether the caret is in one of the three boxes under the picture.
	 *
	 * The search box deliberately does not count: it changes nothing and saving it
	 * would mean nothing.
	 */
	private boolean editingDetails() {
		return nameBox.isFocused() || categoryBox.isFocused() || groupBox.isFocused();
	}

	/** Whether each box had the caret last frame, so that losing it can be seen. */
	private boolean wasEditing;

	/**
	 * What was last written down, so that nothing is written down twice.
	 *
	 * Compared against rather than "has this box been touched", because the
	 * library answers back: a rename comes home as a new list a moment later, and
	 * a box refilled from that list must not read as a fresh edit of the same
	 * name.
	 */
	private String savedName = "";
	private String savedCategory = "";
	private String savedGroup = "";

	/**
	 * Writes the boxes back, if they say anything new.
	 *
	 * Two orders rather than one, because the server keeps renaming and refiling
	 * apart and there is no sense inventing a third verb meaning "both". A rename
	 * only goes when a single costume is picked, so a careless click cannot give a
	 * hundred costumes one name; refiling goes to all of them, which is the entire
	 * point of picking a hundred.
	 */
	private void keep() {
		if (picked.isEmpty()) return;

		WardrobePayloads.Costume one = only();
		String name = nameBox.getValue().trim();
		if (one != null && !name.isEmpty() && !name.equals(savedName)) {
			Costumes.edit(WardrobePayloads.Edit.Verb.RENAME, List.of(one.id()),
				name, "", "", new byte[0]);
			savedName = name;
			notice = "переименовано";
		}

		String head = categoryBox.getValue().trim();
		String tail = groupBox.getValue().trim();
		if (!head.equals(savedCategory) || !tail.equals(savedGroup)) {
			Costumes.edit(WardrobePayloads.Edit.Verb.REFILE, List.copyOf(picked), "",
				head, tail, new byte[0]);
			savedCategory = head;
			savedGroup = tail;
			// A category is a label somebody wrote, so writing a new one here is how
			// a category gets made. Worth saying, because there is no "new category"
			// button to look for and its absence should read as simplicity.
			notice = shelfName().isEmpty() ? "убрано из категорий"
				: "теперь в «" + shelfName() + "»";
		}
	}

	/** Saves when the caret leaves the boxes, which is when the person is finished. */
	private void keepIfLeft() {
		boolean now = editingDetails();
		if (wasEditing && !now) keep();
		wasEditing = now;
	}

	private void open(Overlay which, int x, int y) {
		overlay = overlay == which ? Overlay.NONE : which;
		overlayX = x;
		overlayY = y;
	}

	// ------------------------------------------------------------- the list

	private void refilter() {
		String needle = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		List<WardrobePayloads.Costume> found = new ArrayList<>();
		for (WardrobePayloads.Costume costume : Costumes.all()) {
			if (category != null && !shelf(costume).equals(category)) continue;
			if (!needle.isEmpty() && !costume.label().toLowerCase(Locale.ROOT).contains(needle)) continue;
			found.add(costume);
		}
		shown = List.copyOf(found);
	}

	private static String shelf(WardrobePayloads.Costume costume) {
		if (costume.category().isBlank()) return "без категории";
		return costume.group().isBlank() ? costume.category()
			: costume.category() + " · " + costume.group();
	}

	private List<String> shelves() {
		List<String> found = new ArrayList<>();
		found.add(null);
		for (WardrobePayloads.Costume costume : Costumes.all()) {
			String shelf = shelf(costume);
			if (!found.contains(shelf)) found.add(shelf);
		}
		return found;
	}

	private WardrobePayloads.Costume only() {
		if (picked.size() != 1) return null;
		String id = picked.iterator().next();
		return Costumes.all().stream().filter(costume -> costume.id().equals(id))
			.findFirst().orElse(null);
	}

	/**
	 * Fills the boxes from whatever is picked, when that changes.
	 *
	 * Only when it changes, or every frame would wipe out what is being typed.
	 * With several picked the name is left alone and greyed: fifty costumes
	 * called the same thing is not something anybody meant to ask for, while
	 * moving fifty to one shelf is precisely the point of picking fifty.
	 *
	 * With several picked the shelf boxes are emptied rather than left showing
	 * whichever costume was looked at last. That mattered little when a button had
	 * to be pressed; now that leaving a box files what is in it, a stale value
	 * sitting there is a hundred costumes about to be moved somewhere nobody
	 * asked for. Empty means "say where these go", and saying nothing moves
	 * nothing.
	 */
	private void syncDetails() {
		String now = String.join(",", picked);
		if (now.equals(filledFrom)) return;
		filledFrom = now;

		WardrobePayloads.Costume one = only();
		nameBox.setValue(one == null ? "" : one.label());
		nameBox.setEditable(one != null);
		categoryBox.setValue(one == null ? "" : one.category());
		groupBox.setValue(one == null ? "" : one.group());

		savedName = nameBox.getValue();
		savedCategory = categoryBox.getValue();
		savedGroup = groupBox.getValue();

		nameBox.setSuggestion(one == null && !picked.isEmpty()
			? "выбрано: " + picked.size() : nameBox.getValue().isEmpty() ? "название" : "");
	}

	/** The same bargain as the animation library: the scene shows the character. */
	private boolean showsFigure() {
		return !com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded();
	}

	// --------------------------------------------------------- the measurements

	private int margin() {
		return showsFigure() ? MARGIN : SNUG;
	}

	private int toolbarTop() {
		return margin() + 18;
	}

	/** Where the costumes start: under the title and the toolbar. */
	private int header() {
		return toolbarTop() + 24;
	}

	private int footer() {
		return showsFigure() ? 30 : 26;
	}

	private int footerTop() {
		return height - footer() + (showsFigure() ? 6 : 4);
	}

	private int cell() {
		return showsFigure() ? CELL : CELL_SNUG;
	}

	/** How tall a tile's picture is. The name sits under it. */
	private int cellTall() {
		return cell() * TALLER / WIDER;
	}

	/** One row of the shelf, picture and name together. */
	private int step() {
		return cellTall() + LABEL + GAP;
	}

	private int previewLeft() {
		if (!showsFigure()) return width - margin();
		return Math.max(width * 2 / 3, width - 300);
	}

	/**
	 * Where the details block sits, which is not the same place in both layouts.
	 *
	 * Under the figure when there is a figure, across the bottom when there is
	 * not — and this used to be worked out twice, once by the widgets and once by
	 * whatever drew the panel behind them. The two answers agreed only in the
	 * full-screen case: in a panel the widgets were laid across the bottom while
	 * their background was drawn in a strip one margin wide at the right-hand
	 * edge, taking the heading off the screen with it.
	 */
	private int detailsLeft() {
		return showsFigure() ? previewLeft() + 8 : margin();
	}

	private int detailsWide() {
		return showsFigure() ? width - margin() - previewLeft() - 16 : width - margin() * 2;
	}

	private int detailsTop() {
		return height - footer() - DETAILS;
	}

	/** The right-hand edge of the grid: short of the figure, or of the window. */
	private int gridRight() {
		return showsFigure() ? previewLeft() - 8 : width - margin();
	}

	/**
	 * The bottom of the grid.
	 *
	 * With a figure the details block is off to the right and the grid runs the
	 * whole way down beside it. Without one the block is underneath, and the grid
	 * has to stop above it rather than through it.
	 */
	private int gridBottom() {
		return showsFigure() ? height - footer() - 4 : detailsTop() - 4;
	}

	private int columns() {
		return Math.max(1, (gridRight() - margin() + GAP) / (cell() + GAP));
	}

	/**
	 * How many rows are drawn, counting the one that only half fits.
	 *
	 * <h2>The empty band under the shelf</h2>
	 *
	 * This used to count whole rows only, and whatever was left over — up to one
	 * row less a pixel, which on a tall panel is most of a costume — was a band of
	 * nothing between the last row and the editing block. It read as a mistake in
	 * the layout, and it was one: a grid that scrolls has no business stopping
	 * short of its own edge, because the thing under the cut is exactly what
	 * scrolling is for reaching.
	 *
	 * So the row that half fits is drawn and clipped. The shelf now runs to the
	 * bottom of the space it has, which is also the honest picture: there is more
	 * below, and it looks like there is more below.
	 */
	private int rows() {
		int room = gridBottom() - header() + GAP;
		return Math.max(1, (room + step() - 1) / step());
	}

	/** How many rows fit whole, which is what a page of scrolling is worth. */
	private int wholeRows() {
		return Math.max(1, (gridBottom() - header() + GAP) / step());
	}

	private int tileAt(double mouseX, double mouseY) {
		int column = (int) ((mouseX - margin()) / (cell() + GAP));
		int row = (int) ((mouseY - header()) / step());
		if (mouseX < margin() || column < 0 || column >= columns()) return -1;
		if (mouseY < header() || mouseY >= gridBottom() || row < 0 || row >= rows()) return -1;
		int index = (scroll + row) * columns() + column;
		return index < shown.size() ? index : -1;
	}

	// ---------------------------------------------------------- the actions

	/**
	 * Puts whatever is picked on the character, for this client's eyes only.
	 *
	 * The figure is the largest thing on this screen and it used to be the one
	 * thing that never answered a click. Nothing is sent: it is a mirror, not a
	 * decision.
	 */
	private void tryOn() {
		if (entityId < 0) return;
		WardrobePayloads.Costume one = only();
		TryingOn.show(entityId, one == null ? "" : one.fingerprint());
	}

	/**
	 * Puts the picked costume on for real, and stays to show whether it worked.
	 *
	 * <h2>One action, and it does not run away</h2>
	 *
	 * There were two before, and between them they managed to be confusing in both
	 * directions. Clicking a costume showed it on the figure, which reads as having
	 * put it on; then "надеть" closed the window, which took the fitting away at
	 * the same moment the real thing was supposed to arrive. Whether it had worked
	 * was impossible to tell, and that is a screen actively hiding its own failure.
	 *
	 * So: the window stays. The fitting is dropped the instant the order is sent,
	 * so what stands there afterwards is the character itself and nothing borrowed.
	 * If the costume is on, it worked. If the character snaps back to what it was,
	 * it did not, and the reason is written underneath.
	 */
	private void wear() {
		if (entityId < 0 || picked.isEmpty()) {
			notice = "сначала выбери костюм";
			return;
		}
		// The first one picked, because wearing several at once is not a thing a
		// character can do and pretending otherwise would need explaining.
		Costumes.wear(entityId, picked.iterator().next());
		TryingOn.stop();
		waitingSince = System.currentTimeMillis();
		notice = "";
	}

	/**
	 * When the order went out, so the screen can say if nothing came back.
	 *
	 * A skin travels as a picture and arrives a moment later, so "nothing has
	 * happened yet" and "nothing is going to happen" look identical for the first
	 * few frames. Waiting a second before saying so tells them apart.
	 */
	private long waitingSince;

	private String progress() {
		if (waitingSince == 0) return "";
		long waited = System.currentTimeMillis() - waitingSince;
		if (minecraft.level != null
				&& minecraft.level.getEntity(entityId)
					instanceof com.mopicmp.npcstudio.entity.NpcEntity npc
				&& !npc.skinMark().isEmpty()) {
			waitingSince = 0;
			return "надето";
		}
		if (waited < 1500) return "надеваю…";
		return "сервер не ответил — смотри сообщение над панелью предметов";
	}

	private String shelfName() {
		String head = categoryBox.getValue().trim();
		String tail = groupBox.getValue().trim();
		if (head.isEmpty()) return "";
		return tail.isEmpty() ? head : head + " · " + tail;
	}

	private void copyHere() {
		List<String> source = copied.isEmpty() ? List.copyOf(picked) : List.copyOf(copied);
		if (source.isEmpty()) {
			notice = "сначала выбери костюм";
			return;
		}
		Costumes.edit(WardrobePayloads.Edit.Verb.COPY, source, "",
			categoryBox.getValue().trim(), groupBox.getValue().trim(), new byte[0]);
		// Said out loud because it is the surprising part: the same skin on five
		// shelves is still one file, so nobody needs to ration copies.
		notice = source.size() + " → «" + (shelfName().isEmpty() ? "без категории" : shelfName())
			+ "», картинки не дублируются";
	}

	private void remove() {
		if (picked.isEmpty()) {
			notice = "нечего убирать";
			return;
		}
		Costumes.edit(WardrobePayloads.Edit.Verb.REMOVE, List.copyOf(picked), "", "", "", new byte[0]);
		// Said plainly, because "delete" reads as final and this one is not: the
		// pictures stay on disk and the list can be put back.
		notice = "убрано из списка — картинки на месте, список можно вернуть";
		picked.clear();
	}

	private void restore(String version) {
		Costumes.edit(WardrobePayloads.Edit.Verb.RESTORE, List.of(), version, "", "", new byte[0]);
		notice = "вернул версию от " + version;
		picked.clear();
	}

	private void dropShelf(String shelf) {
		if (shelf == null) return;
		String[] parts = shelf.split(" · ", 2);
		Costumes.edit(WardrobePayloads.Edit.Verb.DROP_CATEGORY, List.of(), "",
			parts[0], parts.length > 1 ? parts[1] : "", new byte[0]);
		if (Objects.equals(category, shelf)) category = null;
		// The distinction that stops this reading as a delete button.
		notice = "категория убрана, костюмы остались";
	}

	/**
	 * Adds however many skins were chosen, from files or from an archive.
	 *
	 * Sent one at a time even when many were chosen, because each costume is its
	 * own entry in the library and the wire has room for one skin at a time, not
	 * for thirty-six. Named from the file: a list of thirty-six costumes all
	 * called "new costume" is a list nobody can use.
	 */
	private void importCostume() {
		notice = "выбираю файлы…";
		Thread chooser = new Thread(() -> {
			SkinImport.Many many = SkinImport.chooseMany();
			minecraft.execute(() -> {
				int sent = 0;
				int tooBig = 0;
				for (SkinImport.Skin skin : many.skins()) {
					if (Costumes.tooBig(skin.pixels())) {
						// Said here rather than found out by being disconnected. The
						// picture is perfectly good; it is the way to the server that is
						// too narrow.
						tooBig++;
						continue;
					}
					Costumes.add(skin.label(), category == null ? "" : category, "",
						skin.pixels());
					sent++;
				}
				String said = many.message();
				if (tooBig > 0) {
					said = said + ", слишком больших для отправки: " + tooBig;
				}
				if (sent > 0 && many.skins().size() > 1) {
					said = "добавлено: " + sent + (tooBig > 0 ? ", пропущено: " + tooBig : "");
				}
				notice = said;
			});
		}, "npc-studio-costume-import");
		chooser.setDaemon(true);
		chooser.start();
	}

	// ------------------------------------------------------------- the mouse

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (detour != null) return detour.mouseClicked(event, doubleClick);
		// Overlays first and always. The lesson from the dialogue drop-down that
		// opened the animation picker: a list drawn over a button is still drawn
		// over it as far as the person clicking is concerned.
		if (overlay != Overlay.NONE) {
			if (overlay == Overlay.SHELVES) chooseShelf(event.x(), event.y());
			else chooseVersion(event.x(), event.y());
			overlay = Overlay.NONE;
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) return true;

		int tile = tileAt(event.x(), event.y());
		if (tile >= 0) {
			// Whatever was being typed belongs to what is picked now, not to what is
			// about to be. Saved before the selection moves, or a half-typed shelf
			// would land on the next costume clicked.
			keep();
			wasEditing = false;
			String id = shown.get(tile).id();
			// Holding shift adds to what is picked; a plain click starts again. The
			// same two gestures every list of files has used for thirty years.
			boolean adding = (event.modifiers() & (GLFW_SHIFT | GLFW_CONTROL)) != 0;
			if (!adding) picked.clear();
			if (!picked.remove(id)) picked.add(id);
			tryOn();
			return true;
		}
		if (showsFigure() && event.x() >= previewLeft() && event.y() < detailsTop()) {
			dragging = true;
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (detour != null) return detour.mouseReleased(event);
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (detour != null) {
			detour.mouseMoved(mouseX, mouseY);
			return;
		}
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (detour != null) return detour.charTyped(event);
		return super.charTyped(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (detour != null) return detour.keyReleased(event);
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (detour != null) return detour.mouseDragged(event, dragX, dragY);
		if (dragging) {
			spin += (float) (event.x() - dragFrom) * 1.4f;
			pitch = net.minecraft.util.Mth.clamp(
				pitch + (float) (event.y() - dragFromY) * 0.7f, -85f, 85f);
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (detour != null) return detour.mouseScrolled(mouseX, mouseY, dx, dy);
		// The wheel means whatever it is pointing at: the shelf scrolls, the figure
		// comes closer. Two meanings for one gesture is fine when they cannot be
		// confused, and a grid and a portrait cannot.
		if (showsFigure() && mouseX >= previewLeft() && mouseY < detailsTop()) {
			zoom = net.minecraft.util.Mth.clamp(zoom * (dy > 0 ? 1.15f : 1f / 1.15f), 0.4f, 6f);
			return true;
		}
		int total = (shown.size() + columns() - 1) / columns();
		// Against the rows that fit whole, not the one that is half showing —
		// otherwise the last costume can only ever be reached in halves.
		scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, total - wholeRows()));
		return true;
	}

	/**
	 * The shortcuts a list of things is expected to have.
	 *
	 * Not decoration: copy and paste are how somebody with forty costumes builds
	 * a second shelf out of the first, and doing that by pressing a button once
	 * per costume is the sort of thing people give up on halfway through.
	 *
	 * Nothing fires while a box has the caret, or typing "костюм" into the search
	 * would delete the selection at the "с".
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (detour != null) {
			// Escape leaves the face rather than the workspace. Without this it goes
			// to whatever is holding this screen, and in a panel that is everything.
			if (event.key() == 256) {
				detour = null;
				return true;
			}
			return detour.keyPressed(event);
		}

		boolean typing = search.isFocused() || editingDetails();
		if (typing) {
			// Enter means "that's it" — the same thing as clicking away, said with
			// the hand that is already on the keyboard.
			if (event.key() == 257 || event.key() == 335) {
				keep();
				wasEditing = false;
				setFocused(null);
				return true;
			}
			return super.keyPressed(event);
		}

		boolean control = (event.modifiers() & GLFW_CONTROL) != 0;
		switch (event.key()) {
			case 67 -> {                                      // C
				if (!control) break;
				copied.clear();
				copied.addAll(picked);
				notice = copied.isEmpty() ? "нечего копировать" : "скопировано: " + copied.size();
				return true;
			}
			case 86 -> {                                      // V
				if (!control) break;
				if (copied.isEmpty()) {
					notice = "буфер пуст";
					return true;
				}
				copyHere();
				return true;
			}
			case 65 -> {                                      // A
				if (!control) break;
				picked.clear();
				for (WardrobePayloads.Costume costume : shown) picked.add(costume.id());
				notice = "выбрано: " + picked.size();
				return true;
			}
			case 261 -> {                                     // Delete
				remove();
				return true;
			}
			default -> { }
		}
		return super.keyPressed(event);
	}

	private void chooseShelf(double mouseX, double mouseY) {
		List<String> shelves = shelves();
		int row = (int) (mouseY - overlayY) / 13;
		if (mouseX < overlayX || mouseX > overlayX + 190) return;
		if (row < 0 || row >= shelves.size()) return;

		// The last twelve pixels of a row are its own × — this is where a category
		// is thrown away, next to the thing being thrown away rather than in a
		// menu somewhere else.
		if (mouseX > overlayX + 178 && shelves.get(row) != null) {
			dropShelf(shelves.get(row));
			return;
		}
		category = shelves.get(row);
		refilter();
		scroll = 0;
	}

	private void chooseVersion(double mouseX, double mouseY) {
		List<String> versions = Costumes.versions();
		if (versions.isEmpty()) {
			notice = "прежних версий пока нет";
			return;
		}
		int rows = Math.min(versions.size(), 12);
		int top = overlayY - rows * 13 - 2;
		int row = (int) (mouseY - top) / 13;
		if (mouseX < overlayX || mouseX > overlayX + 150) return;
		if (row < 0 || row >= rows) return;
		restore(versions.get(row));
	}

	@Override
	public void onClose() {
		leave();
	}

	@Override
	public void removed() {
		// Whatever was typed and not yet left goes now. Closing a window is the one
		// way of finishing with a box that never produces a moment where the caret
		// leaves it, and losing an edit at exactly that moment is what makes people
		// distrust a screen that saves by itself.
		keep();

		// However the screen goes, the character stops wearing what it was only
		// being shown in. A costume left on would follow whatever holds this entity
		// id next, and ids are handed out per world.
		TryingOn.stop();
	}

	// ----------------------------------------------------------- the drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (detour != null) {
			detour.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		freshen();
		keepIfLeft();
		syncDetails();

		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, margin(), margin(), TEXT);

		String count = shown.size() + " из " + Costumes.all().size();
		graphics.text(font, Component.literal(count),
			gridRight() - font.width(count), margin(), TEXT_DIM);

		drawTiles(graphics, mouseX, mouseY);
		drawPreview(graphics, mouseX, mouseY);
		drawDetails(graphics);

		String said = notice.isEmpty() ? progress() : notice;
		if (!said.isEmpty()) {
			graphics.text(font, Component.literal(said), margin(), height - 12,
				said.equals("надето") ? GOOD : TEXT_DIM);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		drawOverlay(graphics, mouseX, mouseY);
	}

	/** Which version of the library the grid was built from. */
	private int builtFrom = -1;

	/**
	 * Notices that the library has changed underneath, and rebuilds the grid.
	 *
	 * This is why a skin uploaded a moment ago was not there until the screen had
	 * been closed and opened again. The server does send the new list — it sends
	 * it the instant the last piece of the picture lands — and it arrived, and was
	 * accepted, and sat in a field nobody looked at. Filtering happened once, when
	 * the screen opened.
	 *
	 * The boxes are refilled with it too, unless somebody is typing in them: a
	 * list arriving while a name is half written must not take the other half away.
	 */
	private void freshen() {
		if (Costumes.generation() == builtFrom) return;
		builtFrom = Costumes.generation();
		refilter();
		if (!editingDetails()) filledFrom = "";
	}

	private void drawTiles(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (shown.isEmpty()) {
			// An empty shelf says how to fill it. Somebody opening this for the first
			// time has nothing to click and nothing to look at, and a bare "no
			// costumes" leaves them to work out the rest on their own.
			graphics.text(font, Component.literal(Costumes.all().isEmpty()
				? "в этом мире пока нет костюмов" : "ничего не подходит"), margin(), header(), TEXT_DIM);
			if (Costumes.all().isEmpty()) {
				graphics.text(font, Component.literal("«+ добавить костюм» внизу — возьмёт PNG с компьютера"),
					margin(), header() + 14, TEXT_DIM);
			}
			return;
		}

		int hovered = tileAt(mouseX, mouseY);
		int columns = columns();
		int cell = cell();
		int tall = cellTall();
		// The last row is allowed to be cut off by the edge of the shelf rather
		// than left out of it, so nothing is drawn over the editing block below.
		graphics.enableScissor(margin(), header(), gridRight(), gridBottom());
		// Labelled, and that is not stylistic. Running out of costumes used to
		// return straight out of here, which walked away leaving the scissor on —
		// and a scissor left on clips everything drawn afterwards, in every panel.
		shelf:
		for (int row = 0; row < rows(); row++) {
			for (int column = 0; column < columns; column++) {
				int index = (scroll + row) * columns + column;
				if (index >= shown.size()) break shelf;

				WardrobePayloads.Costume costume = shown.get(index);
				int x = margin() + column * (cell + GAP);
				int y = header() + row * step();
				boolean on = picked.contains(costume.id());

				graphics.fill(x, y, x + cell, y + tall,
					on ? TILE_ON : index == hovered ? TILE_HOVER : TILE);
				if (on) {
					graphics.fill(x, y, x + cell, y + 2, ACCENT);
					graphics.fill(x, y + tall - 2, x + cell, y + tall, ACCENT);
				}

				// Drawn inside the tile rather than filling it, so that a turned
				// shoulder does not touch the next costume along.
				drawCostume(graphics, costume.fingerprint(),
					x + 3, y + 3, x + cell - 3, y + tall - 3);

				String label = trim(costume.label(), cell - 4);
				graphics.text(font, Component.literal(label),
					x + cell / 2 - font.width(label) / 2, y + tall + 1, on ? ACCENT : TEXT_DIM);

				// The size, and whether a blink will work on it — both read from the
				// file's header before anything was downloaded. Nobody else can tell
				// you the second one at all.
				String badge = costume.width() > 64 ? String.valueOf(costume.width()) : "";
				if (!badge.isEmpty()) {
					graphics.text(font, Component.literal(badge), x + 3, y + 3, TEXT_DIM);
				}
				if (costume.eyes()) {
					graphics.fill(x + cell - 7, y + 3, x + cell - 3, y + 7, GOOD);
				}
			}
		}
		graphics.disableScissor();
	}

	/**
	 * One costume, as a figure — or flat, when it cannot be a figure.
	 *
	 * <h2>The skins that have to stay flat</h2>
	 *
	 * A skin from before 1.8 is half as tall: it has no left arm and no left leg of
	 * its own, and none of the outer layers below the head. The player model has
	 * boxes for all of those and would read them from rows the picture does not
	 * have, which comes out as a figure wearing somebody's cheek on its shin. That
	 * is worse than a cut-out, so those keep the cut-out — which was written
	 * knowing about the old layout and draws them correctly.
	 *
	 * Converting them on the way in is the better answer and a bigger one: it means
	 * rewriting somebody's file, and the wardrobe's whole bargain is that the
	 * picture on disk is the picture they gave it.
	 */
	private void drawCostume(GuiGraphicsExtractor graphics, String fingerprint,
			int left, int top, int right, int bottom) {
		Identifier skin = Costumes.texture(fingerprint);
		if (skin == null) return;

		if (Costumes.tall(fingerprint) < 64) {
			int tall = bottom - top;
			tall -= tall % PaperDoll.TALL;
			if (tall <= 0) return;
			PaperDoll.draw(graphics, skin,
				(left + right) / 2 - PaperDoll.widthFor(tall) / 2, top, tall, 32);
			return;
		}
		SkinFigure.draw(graphics, skin, false, left, top, right, bottom);
	}

	private String trim(String label, int room) {
		return font.width(label) <= room ? label
			: font.plainSubstrByWidth(label, room - font.width("…")) + "…";
	}

	/**
	 * The right-hand column's picture.
	 *
	 * A character to dress if there is one, and otherwise the costume itself,
	 * drawn large. The second case is not a consolation prize — the wardrobe is
	 * opened to tidy up at least as often as to dress somebody, and a column that
	 * said "персонаж не виден" the whole time would be a third of the screen
	 * spent on an apology.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!showsFigure()) return;
		int left = previewLeft();
		int right = width - margin();
		int top = header();
		int bottom = detailsTop() - 8;
		graphics.fill(left, top, right, bottom, PANEL);
		graphics.fill(left, top, right, top + 1, EDGE);

		if (minecraft.level != null && entityId >= 0
				&& minecraft.level.getEntity(entityId)
					instanceof net.minecraft.world.entity.LivingEntity npc) {
			try {
				PreviewFigure.draw(graphics, left, top, right, bottom - 8,
					(bottom - top) / 4f * zoom, 0.0625f, spin, pitch, npc);
				return;
			} catch (RuntimeException failed) {
				com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
					"Could not draw the wardrobe preview: {}", failed.toString());
			}
		}

		// Whatever the mouse is over, or failing that whatever is picked — so
		// running the cursor along a shelf shows each costume full size.
		int hovered = tileAt(mouseX, mouseY);
		WardrobePayloads.Costume costume = hovered >= 0 ? shown.get(hovered) : only();
		if (costume == null && !picked.isEmpty()) {
			costume = Costumes.all().stream()
				.filter(one -> one.id().equals(picked.iterator().next())).findFirst().orElse(null);
		}
		if (costume == null) return;

		if (Costumes.texture(costume.fingerprint()) == null) return;
		// The same figure as on the shelf, drawn large. It used to be the flat
		// cut-out here too, which made the biggest picture on the screen the least
		// informative one.
		int figure = Math.min(bottom - top - 28, (right - left) * 2);
		int half = SkinFigure.widthFor(figure) / 2;
		int middle = (left + right) / 2;
		drawCostume(graphics, costume.fingerprint(),
			middle - half, top + 12, middle + half, top + 12 + figure);
		String label = trim(costume.label(), right - left - 8);
		graphics.text(font, Component.literal(label),
			(left + right) / 2 - font.width(label) / 2, bottom - 12, TEXT_DIM);
	}

	private void drawDetails(GuiGraphicsExtractor graphics) {
		int left = detailsLeft() - 8;
		int right = detailsLeft() + detailsWide() + 8;
		int top = detailsTop();
		graphics.fill(left, top, right, height - footer() - 4, PANEL);
		graphics.fill(left, top, right, top + 1, EDGE);

		String heading = picked.isEmpty() ? "ничего не выбрано"
			: picked.size() == 1 ? "костюм" : "костюмов: " + picked.size();
		graphics.text(font, Component.literal(heading), left + 8, top + 4,
			picked.isEmpty() ? TEXT_DIM : ACCENT);

		// Said once, quietly, and only where the typing happens: with the button
		// gone there is nothing on the screen to tell somebody their name went
		// anywhere, and "did that save" is a question a person should never have to
		// hold on to.
		String aside = copied.isEmpty() ? "сохраняется само" : "в буфере: " + copied.size();
		graphics.text(font, Component.literal(aside),
			right - 8 - font.width(aside), top + 4, copied.isEmpty() ? TEXT_DIM : GOOD);
	}

	private void drawOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		switch (overlay) {
			case SHELVES -> drawShelves(graphics, mouseX, mouseY);
			case VERSIONS -> drawVersions(graphics, mouseX, mouseY);
			case NONE -> { }
		}
	}

	private void drawShelves(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> shelves = shelves();
		int left = overlayX;
		int top = overlayY;
		int bottom = top + shelves.size() * 13 + 2;

		graphics.fill(left - 1, top - 1, left + 191, bottom + 1, EDGE);
		graphics.fill(left, top, left + 190, bottom, PANEL);
		for (int i = 0; i < shelves.size(); i++) {
			String shelf = shelves.get(i);
			int y = top + 1 + i * 13;
			boolean on = Objects.equals(shelf, category);
			boolean hovered = mouseX >= left && mouseX <= left + 190 && mouseY >= y && mouseY < y + 13;
			if (on || hovered) {
				graphics.fill(left + 1, y, left + 189, y + 13, on ? TILE_ON : TILE_HOVER);
			}
			graphics.text(font, Component.literal(shelf == null ? "все" : trim(shelf, 168)),
				left + 6, y + 3, on ? ACCENT : TEXT);
			// Offered only where it means something: "all" is not a category and
			// cannot be thrown away.
			if (hovered && shelf != null) {
				graphics.text(font, Component.literal("×"), left + 180, y + 3, WARN);
			}
		}
	}

	/**
	 * The versions, newest first, with dates people can read.
	 *
	 * A list rather than a single "undo", because the version worth going back to
	 * is rarely the last one — somebody notices the damage a few changes later,
	 * and being offered only the state just after the damage is being offered
	 * nothing.
	 */
	private void drawVersions(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> versions = Costumes.versions();
		int rows = Math.min(versions.size(), 12);
		if (rows == 0) return;

		int left = overlayX;
		int bottom = overlayY;
		int top = bottom - rows * 13 - 2;

		graphics.fill(left - 1, top - 1, left + 151, bottom + 1, EDGE);
		graphics.fill(left, top, left + 150, bottom, PANEL);
		for (int i = 0; i < rows; i++) {
			int y = top + 1 + i * 13;
			boolean hovered = mouseX >= left && mouseX <= left + 150 && mouseY >= y && mouseY < y + 13;
			if (hovered) graphics.fill(left + 1, y, left + 149, y + 13, TILE_HOVER);
			// The stamp is stored down to the millisecond so that two changes in one
			// second cannot share a filename; nobody needs to read that part.
			String when = versions.get(i);
			int tail = when.lastIndexOf('-');
			graphics.text(font, Component.literal(tail > 10 ? when.substring(0, tail) : when),
				left + 6, y + 3, i == 0 ? TEXT : TEXT_DIM);
		}
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
	 * staying, which is what a panel does when you have finished with it.
	 */
	private void leave() {
		if (parent != null) minecraft.setScreenAndShow(parent);
	}

}
