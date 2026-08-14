package com.mopicmp.npcstudio.client.editor;

import java.util.List;
import java.util.function.Consumer;

import com.mopicmp.npcstudio.client.NpcStudioConfig;
import com.mopicmp.npcstudio.client.emote.EmoteImport;
import com.mopicmp.npcstudio.client.emote.EmoteLibrary;
import com.mopicmp.npcstudio.client.entity.AnimationCatalogue;
import com.mopicmp.npcstudio.client.entity.ClientNpcEntity;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.entity.NpcStudioEntities;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Choosing an animation by watching it.
 *
 * A name in a text box is no way to pick a gesture — "shrug" and "think" both
 * read as plausible until you see which one the character actually does. So
 * there is a grid of stills to find something by, and the one under the
 * highlight plays full size beside it.
 *
 * Stills in the grid rather than seven hundred moving figures, and that is the
 * whole reason the layout is shaped this way. A hundred animated characters is
 * a hundred models a frame and unreadable besides; a hundred stills is a
 * hundred textures the game was already going to draw. The movement lives in
 * one place, where it can be watched properly, slowed down and turned around.
 */
public class AnimationPickerScreen extends Screen {

	private static final int MARGIN = 16;
	private static final int HEADER = 62;
	private static final int FOOTER = 30;
	private static final int GAP = 6;
	private static final int LABEL = 10;

	private static final int CANVAS = 0xFF101318;
	private static final int PANEL = 0xFF161A20;
	private static final int CELL = 0xFF1B2028;
	private static final int CELL_HOVER = 0xFF232A34;
	private static final int CELL_ON = 0xFF27313E;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	/** Sizes the grid can step through, so the control is a nudge rather than a slider. */
	private static final int[] SIZES = { 40, 56, 72, 96 };

	/** Playback rates, as fractions of real time. */
	private static final float[] SPEEDS = { 0.25f, 0.5f, 1f, 1.5f, 2f };

	/**
	 * The first cell, always: "no animation".
	 *
	 * This used to be a button called "none" sitting next to "back", and nobody
	 * could tell which of the two did what — one cleared the animation and the
	 * other cancelled, and the labels said neither. As a cell it explains itself:
	 * it is a choice among choices, picked and confirmed like any other.
	 */
	private static final AnimationCatalogue.Entry NOTHING =
		new AnimationCatalogue.Entry("", "— no animation —", "");

	private final Screen parent;
	private final String chosen;
	private final Consumer<String> onPick;
	/**
	 * Where to get the character's look from, asked again each time this opens.
	 *
	 * A value rather than a supplier was the first version, and it went stale:
	 * step into the character's settings, change its sword, come back, and the
	 * preview was still holding the old one until the whole screen was reopened.
	 */
	private final java.util.function.Supplier<Look> lookUp;
	private Look appearance;

	/** The first chip, meaning no filter at all. */
	private static final String ALL = "все";

	private EditBox search;
	private List<AnimationCatalogue.Entry> shown = withNothing(AnimationCatalogue.all());
	private List<MenuRow> menuRows;
	private String category;
	private boolean menuOpen;
	private int menuScroll;
	private boolean started;

	/** A line of feedback under the footer — what an import did, or is doing. */
	private String notice = "";

	/** Which headings have been opened to show what is filed under them. */
	private final java.util.Set<String> expanded = new java.util.HashSet<>();
	/**
	 * The animation that is chosen, by name.
	 *
	 * Held as an identity rather than as a position in the grid, and that is the
	 * whole of the fix. It used to be a row number, so changing the filter or
	 * typing in the search box left the number pointing at whatever had moved
	 * into that slot — pick the twelfth of one category and you came back holding
	 * the twelfth of another.
	 */
	private String selected = "";

	/** Where the chosen animation sits in what is currently listed, or -1. */
	private int highlighted;
	private int scroll;

	private int sizeStep = NpcStudioConfig.get().pickerIconSize;
	private int speedStep = NpcStudioConfig.get().pickerSpeed;
	private int split = NpcStudioConfig.get().pickerSplit;
	private boolean paused;

	/** True while the divider between the grid and the preview is being dragged. */
	private boolean resizing;

	/** A figure that exists only to be looked at; it is never added to the world. */
	private ClientNpcEntity preview;
	private String playing = "";
	private float age;
	private long lastFrame;

	/**
	 * How the preview is being looked at, which is the viewer's business and not
	 * the emote's. Degrees, and unbounded: dragging keeps turning the character
	 * past profile and all the way round.
	 */
	private float spin;

	/** How far above or below the figure the eye sits, in degrees. */
	private float pitch = PreviewFigure.RESTING_PITCH;
	private float zoom = 1f;
	private boolean dragging;
	private double dragFrom;
	private double dragFromY;

	public AnimationPickerScreen(Screen parent, String chosen,
			java.util.function.Supplier<Look> lookUp, Consumer<String> onPick) {
		super(Component.literal("Animations"));
		this.parent = parent;
		this.chosen = chosen;
		this.lookUp = lookUp;
		this.appearance = lookUp.get();
		this.onPick = onPick;
	}

	private static List<AnimationCatalogue.Entry> withNothing(List<AnimationCatalogue.Entry> entries) {
		return java.util.stream.Stream.concat(
			java.util.stream.Stream.of(NOTHING), entries.stream()).toList();
	}

	@Override
	protected void init() {
		// Asked again on every opening, so a change made in the character's own
		// settings is showing by the time you are back here.
		appearance = lookUp.get();
		if (preview == null && minecraft.level != null) {
			preview = new ClientNpcEntity(NpcStudioEntities.NPC, minecraft.level);
			// An entity that never entered the world has no id, and asking one for
			// its id throws rather than returning nothing. The renderer does ask —
			// it seeds the held-item model from it — so the preview froze the whole
			// game the first time it was drawn. Any number will do; this one is
			// never in a world to collide with anything.
			preview.setId(-1);
			// Wearing the skin of the NPC this dialogue belongs to. A gesture is
			// being judged on a character, and judging it on a stranger is the same
			// mistake as picking it by name.
			if (appearance != null) dressPreviewSkin();
		}
		// Only ever on the way in. This runs again on every rebuild — a window
		// resize is enough — and it used to drag the highlight back to whatever the
		// dialogue already had, so a chosen animation quietly turned back into the
		// old one while the screen was open.
		if (!started) {
			started = true;
			for (int i = 0; i < shown.size(); i++) {
				if (shown.get(i).id().equals(chosen)) selected = chosen;
			}
		}
		// Where the choice sits in the grid, worked out rather than left at zero.
		// Nobody did this on the way in, so `highlighted` began life pointing at
		// cell zero — which is "no animation" — whatever was actually chosen. Every
		// other cell was unaffected, because clicking one moved the highlight to it
		// first; the only cell that behaved differently was the one the highlight
		// was already wrongly on. Which is exactly the cell that misbehaved.
		relocate();

		// Built once and kept. Rebuilding it from the responder — which is what the
		// first version did — threw away the character being typed and the focus
		// along with it, which is why the search box appeared to be dead.
		if (search == null) {
			search = new EditBox(font, 0, 0, 180, 18, Component.literal("search"));
			search.setSuggestion("search");
			search.setResponder(text -> {
				search.setSuggestion(text.isEmpty() ? "search" : "");
				shown = withNothing(AnimationCatalogue.search(text, category));
				relocate();
				scroll = 0;
			});
		}
		// The toolbar: a filter that opens a menu, then the search box. Both on one
		// line, above the grid and never inside it.
		int toolbar = MARGIN + 18;
		addRenderableWidget(IconButton.labelled(MARGIN, toolbar, MENU_WIDTH, 18,
			() -> "▾ " + categoryLabel(), ACCENT, Component.literal("category"), () -> {
				menuOpen = !menuOpen;
				menuScroll = 0;
			}));

		int searchLeft = MARGIN + MENU_WIDTH + 6;
		// Re-dressed every opening rather than only when the figure is built. Coming
		// back from the character's own settings with a new skin used to show the
		// old one until the whole screen was closed and opened again.
		dressPreviewSkin();
		dressPreview();

		search.setPosition(searchLeft, toolbar);
		search.setWidth(Math.max(80, previewLeft() - MARGIN - searchLeft - 4));
		addRenderableWidget(search);

		int bottom = height - FOOTER + 6;
		// The button says what it will do, by name, asked afresh every frame. A
		// button labelled only "use this" cannot be wrong out loud: if what it is
		// holding is not what was clicked, the only way to find out is to press it
		// and go and look. This one shows its hand first.
		addRenderableWidget(IconButton.labelled(MARGIN, bottom, 148, 20,
			() -> "use this: " + shortName(selected), 0xFF66BB6A,
			Component.literal("применить выбранную анимацию"), this::confirm));
		addRenderableWidget(new FlatButton(MARGIN + 156, bottom, 90, 20,
			Component.literal("cancel"), 0xFF8A99A6, () -> minecraft.setScreenAndShow(parent)));
		// Here rather than in a settings menu: choosing an animation is the one
		// moment somebody might wonder who made it.
		addRenderableWidget(new FlatButton(MARGIN + 252, bottom, 90, 20,
			Component.literal("credits"), 0xFF8A99A6,
			() -> minecraft.setScreenAndShow(new CreditsScreen(this))));

		// The preview's controls tuck into its bottom corner at the size of a
		// fingernail. They belong to the picture rather than to the decision, and
		// three full-width buttons spelling out "pause" was more furniture than
		// the picture they surrounded.
		//
		// None of them rebuild anything. Each draws its own state every frame, so
		// pressing one no longer clears the widget list that the press is being
		// dispatched through — which is why they did nothing at all before.
		int size = 18;
		int right = width - MARGIN - size;
		int row = height - FOOTER - size - 6;
		NpcStudioConfig config = NpcStudioConfig.get();

		addRenderableWidget(IconButton.of(right, row, size,
			IconButton.Shape.RESTART, ACCENT, Component.literal("start again"), () -> age = 0));
		addRenderableWidget(IconButton.labelled(right - 32, row, 28, size, this::speedLabel,
			ACCENT, Component.literal("playback speed"), () -> {
				speedStep = (speedStep + 1) % SPEEDS.length;
				remember();
			}));
		addRenderableWidget(new IconButton(right - 54, row, size,
			() -> paused ? IconButton.Shape.PLAY : IconButton.Shape.PAUSE, () -> "",
			ACCENT, Component.literal("pause"), () -> paused = !paused));

		// Switches rather than buttons, because both of these are states rather
		// than actions. What they do is in the tooltip: spelled out on the line
		// they would be more words than picture.
		addRenderableWidget(new ToggleSwitch(right - 90, row + 3, 28, 14,
			Component.literal("Show what the character is holding"), ACCENT,
			() -> NpcStudioConfig.get().showHeldItems, () -> {
				NpcStudioConfig live = NpcStudioConfig.get();
				live.showHeldItems = !live.showHeldItems;
				live.save();
				dressPreview();
			}));
		addRenderableWidget(new ToggleSwitch(right - 122, row + 3, 28, 14,
			Component.literal("Hide limbs an emote has thrown far from the body — "
				+ "some hide a body they do not want by flinging it out of sight"),
			ACCENT, () -> NpcStudioConfig.get().hideDistantParts, () -> {
				NpcStudioConfig live = NpcStudioConfig.get();
				live.hideDistantParts = !live.hideDistantParts;
				live.save();
			}));

		// The character's own settings, as a face rather than a word: it is the
		// only control here that leaves for somewhere else, and it belongs in the
		// corner rather than in the row of things that change this picture.
		addRenderableWidget(IconButton.of(width - MARGIN - 20, MARGIN - 4, 20,
			IconButton.Shape.FACE, TEXT, Component.literal("this NPC's settings"),
			() -> NpcSettingsButton.open(this)));

		addRenderableWidget(new FlatButton(MARGIN + 336, bottom, 104, 20,
			Component.literal("import…"), ACCENT, this::importEmote));

		// In the footer beside the other buttons, not floating over the grid. It
		// used to sit inside the grid area, where a small thumbnail size grew the
		// grid out under it and left it unclickable.
		addRenderableWidget(IconButton.of(MARGIN + 448, bottom, 20,
			IconButton.Shape.GRID, 0xFF8A99A6, Component.literal("size of the thumbnails"), () -> {
				sizeStep = (sizeStep + 1) % SIZES.length;
				scroll = 0;
				remember();
			}));
	}

	// ------------------------------------------------------- the categories

	/**
	 * One line of the menu: a heading, or something filed under one.
	 *
	 * Flattened into a single list with a depth on each rather than kept as a
	 * tree, because a menu is drawn and clicked as a list — the nesting only has
	 * to survive as far as an indent.
	 */
	private record MenuRow(String id, String label, int depth, boolean expandable) { }

	private static final int MENU_ROW = 13;
	private static final int MENU_WIDTH = 190;

	/**
	 * The menu as it stands, with closed headings holding their children back.
	 *
	 * Rebuilt on each opening and closing rather than kept, because the list is
	 * nine rows long most of the time and the alternative is a second copy of the
	 * same information that can disagree with the first.
	 */
	private List<MenuRow> menu() {
		List<MenuRow> rows = new java.util.ArrayList<>();
		rows.add(new MenuRow(null, ALL, 0, false));
		for (var group : AnimationCatalogue.categories()) {
			boolean open = expanded.contains(group.name());
			boolean hasChildren = !group.children().isEmpty();
			rows.add(new MenuRow(group.name(), EmoteLibrary.strip(group.name()), 0, hasChildren));
			if (open) {
				for (String child : group.children()) {
					rows.add(new MenuRow(child, EmoteLibrary.strip(child), 1, false));
				}
			}
		}
		rows.add(new MenuRow(AnimationCatalogue.UNLABELLED, "без категории", 0, false));
		return rows;
	}

	private String categoryLabel() {
		if (category == null) return ALL;
		return menu().stream().filter(row -> category.equals(row.id()))
			.map(MenuRow::label).findFirst().orElse(ALL);
	}

	private int menuLeft() {
		return MARGIN;
	}

	private int menuTop() {
		return MARGIN + 40;
	}

	/**
	 * How tall the menu is allowed to be before it scrolls.
	 *
	 * Capped against the screen rather than the list, so a pack with sixty
	 * categories does not produce a menu that runs off the bottom edge.
	 */
	private int menuRowsVisible() {
		return Math.min(menu().size(), Math.max(4, (height - menuTop() - FOOTER - 10) / MENU_ROW));
	}

	/**
	 * Answers a click in the menu.
	 *
	 * @return whether the menu should stay open, which it does when a heading was
	 *         merely unfolded — that is a step towards choosing rather than the
	 *         choice itself, and closing on it would mean reopening to see what
	 *         appeared
	 */
	private boolean chooseFromMenu(double mouseX, double mouseY) {
		int left = menuLeft();
		List<MenuRow> rows = menu();
		if (mouseX < left || mouseX > left + MENU_WIDTH) return false;
		int row = (int) (mouseY - menuTop()) / MENU_ROW + menuScroll;
		if (row < menuScroll || row >= rows.size()) return false;

		MenuRow clicked = rows.get(row);
		// The arrow at the left edge folds; the rest of the row chooses. Two
		// meanings on one line, told apart by where the pointer is, because a
		// heading is both a place to go and a lid to lift.
		if (clicked.expandable() && mouseX < left + 14) {
			if (!expanded.remove(clicked.id())) expanded.add(clicked.id());
			return true;
		}
		category = clicked.id();
		refilter();
		return false;
	}

	private void refilter() {
		shown = withNothing(AnimationCatalogue.search(search == null ? "" : search.getValue(), category));
		relocate();
		scroll = 0;
	}

	/**
	 * Finds the chosen animation in the list as it now stands.
	 *
	 * Minus one when it has been filtered out, which is a real state rather than
	 * a failure: the choice still stands and "use this" still returns it, there
	 * is simply nothing to draw a box around at the moment.
	 */
	private void relocate() {
		highlighted = -1;
		for (int i = 0; i < shown.size(); i++) {
			if (shown.get(i).id().equals(selected)) highlighted = i;
		}
	}

	/**
	 * The open menu, drawn over everything else.
	 *
	 * Last, and that is the point. A row of filters along the top was tried first
	 * and the grid drew straight over it — nine categories of which one was
	 * visible, at the far right where the grid happened to end. A menu that opens
	 * over the content cannot be painted on by it, and it costs no permanent
	 * space at all, which a row of nine chips does.
	 */
	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!menuOpen) return;
		int left = menuLeft();
		int top = menuTop();
		int visible = menuRowsVisible();
		int bottom = top + visible * MENU_ROW + 2;

		graphics.fill(left - 1, top - 1, left + MENU_WIDTH + 1, bottom + 1, EDGE);
		graphics.fill(left, top, left + MENU_WIDTH, bottom, PANEL);

		List<MenuRow> rows = menu();
		for (int i = 0; i < visible; i++) {
			int index = menuScroll + i;
			if (index >= rows.size()) break;
			MenuRow row = rows.get(index);
			int y = top + 1 + i * MENU_ROW;
			boolean on = java.util.Objects.equals(row.id(), category);
			boolean hovered = mouseX >= left && mouseX <= left + MENU_WIDTH
				&& mouseY >= y && mouseY < y + MENU_ROW;

			if (on || hovered) {
				graphics.fill(left + 1, y, left + MENU_WIDTH - 1, y + MENU_ROW,
					on ? CELL_ON : CELL_HOVER);
			}
			if (row.expandable()) {
				String arrow = expanded.contains(row.id()) ? "▾" : "▸";
				graphics.text(font, Component.literal(arrow), left + 5, y + 3, TEXT_DIM);
			}
			// A child is indented and dimmed rather than bulleted: the indent says
			// it belongs to the line above, and one signal is enough.
			graphics.text(font, Component.literal(row.label()),
				left + 15 + row.depth() * 10, y + 3,
				on ? ACCENT : row.depth() > 0 ? TEXT_DIM : TEXT);
		}
	}

	private String speedLabel() {
		float speed = SPEEDS[speedStep];
		return speed == Math.floor(speed) ? (int) speed + "x" : speed + "x";
	}

	/**
	 * Picks a file and brings it in.
	 *
	 * The chooser runs off the render thread because it is the operating
	 * system's own window and it blocks until answered — waiting for it on the
	 * render thread would freeze the game behind it. The result comes back to
	 * the render thread to touch the screen, since nothing else may.
	 */
	private void importEmote() {
		notice = "choosing a file…";
		Thread chooser = new Thread(() -> {
			EmoteImport.Result result = EmoteImport.choose();
			minecraft.execute(() -> {
				if (result.ok()) {
					EmoteImport.refresh();
					shown = withNothing(AnimationCatalogue.search(
						search == null ? "" : search.getValue(), category));
					relocate();
				}
				notice = result.message();
			});
		}, "npc-studio-emote-import");
		chooser.setDaemon(true);
		chooser.start();
	}

	/**
	 * Gives the preview what the character is holding, or takes it away.
	 *
	 * Half the pack swings, blocks or points with something, and an empty hand
	 * makes those unreadable — but an item is also the thing most likely to be in
	 * front of the pose somebody is trying to see, which is why it can be turned
	 * off.
	 */
	private void dressPreview() {
		if (preview == null) return;
		boolean show = appearance != null && NpcStudioConfig.get().showHeldItems;
		preview.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
			show ? appearance.mainHand() : net.minecraft.world.item.ItemStack.EMPTY);
		preview.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
			show ? appearance.offHand() : net.minecraft.world.item.ItemStack.EMPTY);
	}

	/** What the button should say it is holding, short enough to fit on it. */
	private String shortName(String id) {
		if (id == null || id.isEmpty()) return "— none —";
		AnimationCatalogue.Entry entry = AnimationCatalogue.byId(id);
		String name = entry == null ? id
			: com.mopicmp.npcstudio.client.emote.EmoteLibrary.strip(entry.name());
		return name.length() <= 16 ? name : name.substring(0, 15) + "…";
	}

	/**
	 * Puts the character's real skin on the figure, costume and all.
	 *
	 * The profile alone was not enough: it names a player, and a character wearing
	 * an uploaded picture is not wearing a player. The picture is already on this
	 * client — it was sent when the character came into view — so it is a lookup
	 * rather than a fetch, and giving it back to the preview makes it recompute
	 * the same fingerprint and find the same texture.
	 */
	private void dressPreviewSkin() {
		if (preview == null || appearance == null) return;
		preview.setProfile(appearance.profile());

		String mark = appearance.skinMark();
		if (mark.isEmpty()) {
			preview.setCustomSkin(null);
			return;
		}
		byte[] picture = com.mopicmp.npcstudio.client.skin.CustomSkins.pixelsFor(mark);
		// Nothing to put on if it has not arrived; the profile stands in until it
		// does, which is the same thing that happens to the character itself.
		if (picture != null) preview.setCustomSkin(picture);
	}

	private void confirm() {
		onPick.accept(selected);
		minecraft.setScreenAndShow(parent);
	}

	// ------------------------------------------------------------ the grid

	private int cell() {
		return SIZES[sizeStep];
	}

	private int step() {
		return cell() + GAP;
	}

	/**
	 * The divider, snapped to sit just past the last column.
	 *
	 * The columns almost never divide the space exactly, and the remainder has to
	 * go somewhere. Left on the right it became a band of empty canvas between the
	 * thumbnails and the preview — worse with big thumbnails, where nearly a whole
	 * column could be left over. Splitting it between both edges was the next
	 * attempt and it was worse again: the same emptiness, now in two places.
	 *
	 * So the grid keeps what it uses and the preview takes the rest. The divider
	 * still moves where it is dragged, it just comes to rest on a column edge.
	 */
	private int previewLeft() {
		int wanted = Math.clamp(width * split / 100, width / 2, width - 200);
		int room = wanted - MARGIN * 2;
		int columns = Math.max(1, (room + GAP) / step());
		return MARGIN * 2 + columns * step() - GAP;
	}

	private int gridWidth() {
		return previewLeft() - MARGIN * 2;
	}

	private int columns() {
		return Math.max(1, (gridWidth() + GAP) / step());
	}

	/** Hard against the left margin — the divider takes up the slack instead. */
	private int gridLeft() {
		return MARGIN;
	}

	private int rows() {
		return Math.max(1, (height - HEADER - FOOTER + GAP) / (step() + LABEL));
	}

	private int maxScroll() {
		int total = (shown.size() + columns() - 1) / columns();
		return Math.max(0, total - rows());
	}

	/** Which cell is under the pointer, or -1. */
	private int cellAt(double mouseX, double mouseY) {
		int column = (int) ((mouseX - gridLeft()) / step());
		int row = (int) ((mouseY - HEADER) / (step() + LABEL));
		if (mouseX < gridLeft() || column < 0 || column >= columns()) return -1;
		if (mouseY < HEADER || row < 0 || row >= rows()) return -1;
		int index = (scroll + row) * columns() + column;
		return index < shown.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The widgets get first refusal, always. Claiming the preview area for
		// turning the figure before this ran is what stopped pause, speed and
		// restart working: they sit in that area, and the click was answered
		// before it ever reached them.
		if (super.mouseClicked(event, doubleClick)) return true;

		if (menuOpen) {
			menuOpen = chooseFromMenu(event.x(), event.y());
			return true;
		}
		int cell = cellAt(event.x(), event.y());
		if (cell >= 0) {
			// A second click on the cell already chosen takes it, so the obvious
			// gesture works for someone who does not look for the button.
			if (cell == highlighted && doubleClick) confirm();
			else select(cell);
			return true;
		}
		// A narrow strip on the divider resizes; anything further into the preview
		// turns the figure.
		if (Math.abs(event.x() - previewLeft()) <= 4) {
			resizing = true;
			return true;
		}
		if (event.x() >= previewLeft()) {
			// A double click puts the figure back facing forward. Now that it turns
			// the whole way round, finding the front again by eye is guesswork.
			if (doubleClick) {
				spin = 0f;
				zoom = 1f;
				pitch = PreviewFigure.RESTING_PITCH;
				return true;
			}
			dragging = true;
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		// Written on release rather than on every pixel of the drag: this ends up
		// on disk, and a file rewritten sixty times a second to follow a mouse is
		// not a settings file.
		if (resizing || dragging) remember();
		dragging = false;
		resizing = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (resizing) {
			split = Math.clamp((int) (event.x() * 100 / width), 40, 85);
			rebuildWidgets();
			return true;
		}
		if (dragging) {
			// Sideways turns and up-and-down moves. Both are dragging because both
			// are "take hold of the figure and shift it", and a tall pose that runs
			// off the top of the panel needs pulling down more often than it needs
			// explaining.
			spin += (float) (event.x() - dragFrom) * 1.4f;
			// Stopped short of straight up and straight down: past the poles the
			// figure turns upside down and the drag reverses, which feels broken
			// even though it is only geometry.
			pitch = Mth.clamp(pitch + (float) (event.y() - dragFromY) * 0.7f, -85f, 85f);
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	/** Keeps how this was left, so the screen does not ask the same question twice. */
	private void remember() {
		NpcStudioConfig config = NpcStudioConfig.get();
		config.pickerIconSize = sizeStep;
		config.pickerSpeed = speedStep;
		config.pickerSplit = split;
		config.save();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (menuOpen) {
			menuScroll = Math.clamp(menuScroll - (int) Math.signum(dy),
				0, Math.max(0, menu().size() - menuRowsVisible()));
			return true;
		}
		// The wheel means two different things depending on which half it is over,
		// and both are the obvious one: move through the list, or move closer.
		if (mouseX >= previewLeft()) {
			zoom = Mth.clamp(zoom * (dy > 0 ? 1.12f : 0.89f), 0.4f, 4f);
			return true;
		}
		scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int key = event.key();
		int columns = columns();
		int moved = switch (key) {
			case 263 -> highlighted - 1;
			case 262 -> highlighted + 1;
			case 265 -> highlighted - columns;
			case 264 -> highlighted + columns;
			default -> highlighted;
		};
		// Left and right belong to the search box while it is being typed in;
		// stepping through cells with them would make the text uneditable.
		boolean typing = search != null && search.isFocused();
		if (typing && (key == 262 || key == 263)) return super.keyPressed(event);

		if (moved != highlighted && moved >= 0 && moved < shown.size()) {
			select(moved);
			return true;
		}
		if (key == 257 || key == 335) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	private void select(int index) {
		highlighted = index;
		selected = shown.get(index).id();
		int row = index / columns();
		if (row < scroll) scroll = row;
		if (row >= scroll + rows()) scroll = row - rows() + 1;
		scroll = Math.clamp(scroll, 0, maxScroll());
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}

	// --------------------------------------------------------- the drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, MARGIN, MARGIN, TEXT);

		String count = shown.size() - 1 + " animations";
		graphics.text(font, Component.literal(count),
			previewLeft() - font.width(count), MARGIN, TEXT_DIM);

		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice), MARGIN, height - 12, TEXT_DIM);
		}

		drawGrid(graphics, mouseX, mouseY);
		drawPreview(graphics, mouseX, mouseY);

		// The divider, drawn because a handle nobody can see is a handle nobody
		// finds. It brightens under the mouse to say that it moves.
		int divider = previewLeft() - 4;
		boolean onDivider = resizing || Math.abs(mouseX - previewLeft()) <= 4;
		graphics.fill(divider, HEADER, divider + 2, height - FOOTER, onDivider ? ACCENT : EDGE);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// After the widgets, so an open menu covers them too — a filter list that
		// buttons poke through is worse than no filter list.
		drawMenu(graphics, mouseX, mouseY);
	}

	private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (shown.isEmpty()) {
			graphics.text(font, Component.literal("nothing matches that"), MARGIN, HEADER, TEXT_DIM);
			return;
		}

		int hovered = cellAt(mouseX, mouseY);
		int columns = columns();
		int size = cell();

		for (int row = 0; row < rows(); row++) {
			for (int column = 0; column < columns; column++) {
				int index = (scroll + row) * columns + column;
				if (index >= shown.size()) return;

				int x = gridLeft() + column * step();
				int y = HEADER + row * (step() + LABEL);
				boolean on = index == highlighted;
				AnimationCatalogue.Entry entry = shown.get(index);

				graphics.fill(x, y, x + size, y + size,
					on ? CELL_ON : index == hovered ? CELL_HOVER : CELL);
				if (on) {
					graphics.fill(x, y, x + size, y + 2, ACCENT);
					graphics.fill(x, y + size - 2, x + size, y + size, ACCENT);
				}

				Identifier icon = entry.icon();
				if (icon != null) {
					graphics.blit(RenderPipelines.GUI_TEXTURED, icon,
						x + 2, y + 2, 0f, 0f, size - 4, size - 4, 256, 256, 256, 256);
				} else {
					// The built-in gestures never had a file, so they never had a
					// still either. Their initial stands in rather than an empty box.
					String initial = entry.name().isEmpty() ? "–" : entry.name().substring(0, 1);
					graphics.text(font, Component.literal(initial),
						x + size / 2 - font.width(initial) / 2, y + size / 2 - font.lineHeight / 2, TEXT_DIM);
				}

				String label = trim(EmoteLibrary.strip(entry.name()), size);
				graphics.text(font, Component.literal(label),
					x + size / 2 - font.width(label) / 2, y + size + 1, on ? ACCENT : TEXT_DIM);
			}
		}
	}

	/** Cuts a label to the width it has, with an ellipsis so the cut is visible. */
	private String trim(String text, int room) {
		if (font.width(text) <= room) return text;
		String cut = font.plainSubstrByWidth(text, room - font.width("…"));
		return cut + "…";
	}

	/**
	 * The highlighted animation, playing.
	 *
	 * The clock runs on real time rather than on frames. Winding it a tick per
	 * frame — which is what the first version did — meant the same emote played
	 * three times faster on a fast machine than on a slow one, and there is no
	 * way to judge a gesture whose speed depends on the hardware.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = previewLeft();
		int right = width - MARGIN;
		int top = HEADER;
		int bottom = height - FOOTER - 28;
		if (right - left < 80 || preview == null || shown.isEmpty()) return;

		graphics.fill(left, top, right, bottom, PANEL);
		graphics.fill(left, top, right, top + 1, EDGE);

		// Looked up by name rather than by row, so the preview keeps playing what
		// was chosen even once a filter has hidden it from the grid.
		AnimationCatalogue.Entry entry = highlighted >= 0 ? shown.get(highlighted)
			: selected.isEmpty() ? NOTHING : AnimationCatalogue.byId(selected);
		if (entry == null) entry = NOTHING;
		if (entry.id().isEmpty()) {
			String nothing = "the character will just stand there";
			graphics.text(font, Component.literal(nothing),
				(left + right) / 2 - font.width(nothing) / 2, (top + bottom) / 2, TEXT_DIM);
			playing = "";
			return;
		}

		long now = net.minecraft.util.Util.getMillis();
		float elapsed = lastFrame == 0 ? 0 : (now - lastFrame) / 50f;
		lastFrame = now;
		if (!entry.id().equals(playing)) {
			playing = entry.id();
			age = 0;
		} else if (!paused) {
			age += elapsed * SPEEDS[speedStep];
		}

		// An emote that loops is left to loop itself, and that is a correction
		// rather than a nicety. Its own loop returns to a point partway in, past
		// whatever the character does to get into position; restarting it from
		// zero here — which is what this used to do — put that preparation back
		// into every cycle. A dance came out as hands to the shoulders, two steps,
		// hands to the shoulders again.
		//
		// Only an emote that ends is restarted, because a preview showing a
		// motionless figure after one pass is no way to judge a gesture.
		com.mopicmp.npcstudio.client.emote.EmoteLibrary.emote(entry.id())
			.ifPresent(emote -> {
				if (!emote.loopsVisibly() && age > emote.length()) age = 0;
			});
		// No length in the preview: it drives the playhead itself, and a gesture
		// that expired would be cleared out from under it.
		if (!entry.id().equals(preview.gesture())) preview.playGesture(entry.id(), 0);
		preview.previewAge(age);

		// A float, so a nudge of the wheel is a nudge rather than a step: an int
		// here made zooming jump between whole pixels.
		float scale = Math.min((bottom - top) / 2.6f, 62) * zoom;
		try {
			PreviewFigure.draw(graphics, left, top, right, bottom, scale, 0.0625f, spin, pitch, preview);
		} catch (RuntimeException failed) {
			// A preview is a convenience. If drawing one goes wrong it must cost the
			// preview and nothing else — the first version of this threw every frame
			// from inside the render loop and left the game unclosable, which is the
			// worst thing a picker can do.
			preview = null;
			com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
				"Could not draw the animation preview: {}", failed.toString());
			return;
		}

		int textTop = bottom - 30;
		String name = EmoteLibrary.strip(entry.name());
		graphics.text(font, Component.literal(trim(name, right - left - 16)),
			(left + right) / 2 - font.width(trim(name, right - left - 16)) / 2, textTop, TEXT);
		if (!entry.author().isEmpty()) {
			String by = "by " + entry.author();
			graphics.text(font, Component.literal(by),
				(left + right) / 2 - font.width(by) / 2, textTop + 11, TEXT_DIM);
		}
		String hint = "drag to turn and move  ·  wheel to zoom  ·  double-click to reset";
		graphics.text(font, Component.literal(hint),
			(left + right) / 2 - font.width(hint) / 2, bottom - 8, TEXT_DIM);
	}

	/**
	 * Everything about a character that a preview needs to look like it.
	 *
	 * The skin was here first and was not enough: an NPC with a sword in its hand
	 * was previewed empty-handed, and half the animations in the pack are swings,
	 * blocks and stabs that mean nothing without the thing being swung.
	 */
	/**
	 * @param skinMark the fingerprint of an uploaded skin or a costume, empty when
	 *                 the character simply wears whoever its profile names
	 */
	public record Look(ResolvableProfile profile, String skinMark,
			net.minecraft.world.item.ItemStack mainHand,
			net.minecraft.world.item.ItemStack offHand) { }

	/**
	 * The look of one particular character.
	 *
	 * Used wherever the character is actually known — from its own settings, say
	 * — because searching for it by the name of its dialogue is guesswork that
	 * fails in two ways: it finds the wrong one when several share a dialogue,
	 * and it finds nothing at all when the dialogue field is empty, at which
	 * point the preview quietly puts the player's own skin on instead. That is
	 * where "the skin turned into mine" came from.
	 */
	public static Look lookFor(int entityId) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.level != null && client.level.getEntity(entityId) instanceof NpcEntity npc) {
			return lookOf(npc);
		}
		return null;
	}

	private static Look lookOf(NpcEntity npc) {
		// The fingerprint as well as the profile. A character wearing a costume or
		// an uploaded picture keeps whatever name its profile last held, and that
		// name is not what it looks like — which is why the figure in here kept
		// turning up in a skin nobody had chosen.
		return new Look(npc.getProfile(), npc.skinMark(),
			npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).copy(),
			npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).copy());
	}

	/** The look of the NPC this dialogue belongs to, if one is nearby. */
	public static Look lookFor(String dialogueId) {
		NpcEntity npc = nearestFor(dialogueId);
		if (npc != null) return lookOf(npc);
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.player == null) return null;
		return new Look(ResolvableProfile.createResolved(client.player.getGameProfile()), "",
			net.minecraft.world.item.ItemStack.EMPTY, net.minecraft.world.item.ItemStack.EMPTY);
	}

	/** The nearest NPC that actually uses this dialogue. */
	private static NpcEntity nearestFor(String dialogueId) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null || client.player == null) return null;

		NpcEntity best = null;
		double nearest = Double.MAX_VALUE;
		for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof NpcEntity npc)) continue;
			if (!npc.dialogueId().equals(dialogueId)) continue;
			double distance = npc.distanceToSqr(client.player);
			if (distance < nearest) {
				nearest = distance;
				best = npc;
			}
		}
		// Null when there is none, and the caller falls back to the player's own
		// skin: an author looking at themselves knows at a glance that this is a
		// stand-in, where a default skin just looks like the NPC is wrong.
		return best;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
