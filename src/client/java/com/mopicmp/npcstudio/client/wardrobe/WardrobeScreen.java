package com.mopicmp.npcstudio.client.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.editor.IconButton;
import com.mopicmp.npcstudio.client.editor.PreviewFigure;
import com.mopicmp.npcstudio.client.skin.SkinImport;
import com.mopicmp.npcstudio.net.WardrobePayloads;

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

	private static final int MARGIN = 16;
	private static final int HEADER = 62;
	private static final int FOOTER = 30;
	private static final int GAP = 6;
	private static final int LABEL = 20;
	private static final int CELL = 72;

	/** How much of the right-hand column the editing panel takes. */
	private static final int DETAILS = 96;

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
			nameBox = box(200, "название");
			categoryBox = box(100, "категория");
			groupBox = box(100, "подкатегория");
		}

		int toolbar = MARGIN + 18;
		addRenderableWidget(IconButton.labelled(MARGIN, toolbar, 190, 18,
			() -> "▾ " + (category == null ? "все" : category), ACCENT,
			Component.literal("категория"), () -> open(Overlay.SHELVES, MARGIN, toolbar + 22)));
		search.setPosition(MARGIN + 196, toolbar);
		search.setWidth(Math.max(80, previewLeft() - MARGIN - 200));
		addRenderableWidget(search);

		layOutDetails();

		int bottom = height - FOOTER + 6;
		int at = MARGIN;

		// Wearing is the point, and it is now the only way to do it: clicking a
		// costume holds it up against the character, and this is what commits.
		if (entityId >= 0) {
			addRenderableWidget(new FlatButton(at, bottom, 116, 20,
				Component.literal("надеть"), GOOD, this::wear));
			at += 124;
		}
		addRenderableWidget(new FlatButton(at, bottom, 104, 20,
			Component.literal("закрыть"), TEXT_DIM, () -> minecraft.setScreenAndShow(parent)));
		at += 112;

		// Adding is the reason somebody opens an empty wardrobe, so it gets words.
		// It was an icon with a tooltip, which was a mistake and a plain one: the
		// lesson from the NPC settings was that *small* operations on a picture
		// should be icons, not that everything should be. A library with no visible
		// way to put anything in it reads as a library that cannot be filled.
		addRenderableWidget(new FlatButton(at, bottom, 140, 20,
			Component.literal("+ добавить костюм"), GOOD, this::importCostume));
		at += 148;
		addRenderableWidget(new FlatButton(at, bottom, 76, 20,
			Component.literal("глаза"), ACCENT, this::markEyes));
		at += 84;
		int versions = at;
		addRenderableWidget(IconButton.of(versions, bottom, 20, IconButton.Shape.RESTART, ACCENT,
			Component.literal("вернуть прежнюю версию списка"),
			() -> open(Overlay.VERSIONS, versions, bottom - 4)));
		addRenderableWidget(IconButton.labelled(at + 24, bottom, 28, 20, () -> "×",
			WARN, Component.literal("убрать выбранные из списка — картинки останутся"),
			this::remove));
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
		minecraft.setScreenAndShow(new com.mopicmp.npcstudio.client.editor.EyeMarkScreen(
			this, picture, Costumes.texture(fingerprint), opening,
			marked -> Costumes.markEyes(id, marked)));
	}

	private EditBox box(int width, String hint) {
		EditBox made = new EditBox(font, 0, 0, width, 18, Component.literal(hint));
		made.setSuggestion(hint);
		made.setMaxLength(48);
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
		int left = previewLeft() + 8;
		int wide = width - MARGIN - previewLeft() - 16;
		int half = (wide - 4) / 2;
		int top = height - FOOTER - DETAILS + 14;

		nameBox.setPosition(left, top);
		nameBox.setWidth(wide);
		addRenderableWidget(nameBox);

		categoryBox.setPosition(left, top + 22);
		categoryBox.setWidth(half);
		addRenderableWidget(categoryBox);

		groupBox.setPosition(left + half + 4, top + 22);
		groupBox.setWidth(wide - half - 4);
		addRenderableWidget(groupBox);

		addRenderableWidget(new FlatButton(left, top + 44, wide - 64, 20,
			Component.literal("применить"), ACCENT, this::apply));
		addRenderableWidget(IconButton.labelled(left + wide - 60, top + 44, 60, 20,
			() -> "копия", GOOD,
			Component.literal("положить те же костюмы ещё и сюда — места это не займёт"),
			this::copyHere));
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
	 */
	private void syncDetails() {
		String now = String.join(",", picked);
		if (now.equals(filledFrom)) return;
		filledFrom = now;

		WardrobePayloads.Costume one = only();
		nameBox.setValue(one == null ? "" : one.label());
		nameBox.setEditable(one != null);
		if (one != null) {
			categoryBox.setValue(one.category());
			groupBox.setValue(one.group());
		}
		nameBox.setSuggestion(one == null && !picked.isEmpty()
			? "выбрано: " + picked.size() : nameBox.getValue().isEmpty() ? "название" : "");
	}

	private int previewLeft() {
		return Math.max(width * 2 / 3, width - 300);
	}

	private int columns() {
		return Math.max(1, (previewLeft() - MARGIN * 2 + GAP) / (CELL + GAP));
	}

	private int rows() {
		return Math.max(1, (height - HEADER - FOOTER + GAP) / (CELL + LABEL + GAP));
	}

	private int tileAt(double mouseX, double mouseY) {
		int column = (int) ((mouseX - MARGIN) / (CELL + GAP));
		int row = (int) ((mouseY - HEADER) / (CELL + LABEL + GAP));
		if (mouseX < MARGIN || column < 0 || column >= columns()) return -1;
		if (mouseY < HEADER || row < 0 || row >= rows()) return -1;
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

	/**
	 * Writes the boxes back to whatever is picked.
	 *
	 * Two orders sent rather than one, because the server keeps renaming and
	 * refiling apart and there is no sense inventing a third verb that means
	 * "both". A rename only goes when a single costume is picked, so a careless
	 * click cannot give a hundred costumes one name.
	 */
	private void apply() {
		if (picked.isEmpty()) {
			notice = "сначала выбери костюм";
			return;
		}
		WardrobePayloads.Costume one = only();
		String name = nameBox.getValue().trim();
		if (one != null && !name.isEmpty() && !name.equals(one.label())) {
			Costumes.edit(WardrobePayloads.Edit.Verb.RENAME, List.of(one.id()),
				name, "", "", new byte[0]);
		}
		Costumes.edit(WardrobePayloads.Edit.Verb.REFILE, List.copyOf(picked), "",
			categoryBox.getValue().trim(), groupBox.getValue().trim(), new byte[0]);

		// A category is a label somebody wrote, so writing a new one here is how a
		// category gets made. Worth saying, because there is no "new category"
		// button to look for and its absence should read as simplicity.
		notice = shelfName().isEmpty() ? "убрано из категорий" : "теперь в «" + shelfName() + "»";
		filledFrom = "";
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
			String id = shown.get(tile).id();
			// Holding shift adds to what is picked; a plain click starts again. The
			// same two gestures every list of files has used for thirty years.
			boolean adding = (event.modifiers() & (GLFW_SHIFT | GLFW_CONTROL)) != 0;
			if (!adding) picked.clear();
			if (!picked.remove(id)) picked.add(id);
			tryOn();
			return true;
		}
		if (event.x() >= previewLeft() && event.y() < height - FOOTER - DETAILS) {
			dragging = true;
			dragFrom = event.x();
			dragFromY = event.y();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
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
		// The wheel means whatever it is pointing at: the shelf scrolls, the figure
		// comes closer. Two meanings for one gesture is fine when they cannot be
		// confused, and a grid and a portrait cannot.
		if (mouseX >= previewLeft() && mouseY < height - FOOTER - DETAILS) {
			zoom = net.minecraft.util.Mth.clamp(zoom * (dy > 0 ? 1.15f : 1f / 1.15f), 0.4f, 6f);
			return true;
		}
		int total = (shown.size() + columns() - 1) / columns();
		scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, total - rows()));
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
		boolean typing = search.isFocused() || nameBox.isFocused()
			|| categoryBox.isFocused() || groupBox.isFocused();
		if (typing) return super.keyPressed(event);

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
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public void removed() {
		// However the screen goes, the character stops wearing what it was only
		// being shown in. A costume left on would follow whatever holds this entity
		// id next, and ids are handed out per world.
		TryingOn.stop();
	}

	// ----------------------------------------------------------- the drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		syncDetails();

		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, MARGIN, MARGIN, TEXT);

		String count = shown.size() + " из " + Costumes.all().size();
		graphics.text(font, Component.literal(count),
			previewLeft() - font.width(count), MARGIN, TEXT_DIM);

		drawTiles(graphics, mouseX, mouseY);
		drawPreview(graphics, mouseX, mouseY);
		drawDetails(graphics);

		String said = notice.isEmpty() ? progress() : notice;
		if (!said.isEmpty()) {
			graphics.text(font, Component.literal(said), MARGIN, height - 12,
				said.equals("надето") ? GOOD : TEXT_DIM);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		drawOverlay(graphics, mouseX, mouseY);
	}

	private void drawTiles(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (shown.isEmpty()) {
			// An empty shelf says how to fill it. Somebody opening this for the first
			// time has nothing to click and nothing to look at, and a bare "no
			// costumes" leaves them to work out the rest on their own.
			graphics.text(font, Component.literal(Costumes.all().isEmpty()
				? "в этом мире пока нет костюмов" : "ничего не подходит"), MARGIN, HEADER, TEXT_DIM);
			if (Costumes.all().isEmpty()) {
				graphics.text(font, Component.literal("«+ добавить костюм» внизу — возьмёт PNG с компьютера"),
					MARGIN, HEADER + 14, TEXT_DIM);
			}
			return;
		}

		int hovered = tileAt(mouseX, mouseY);
		int columns = columns();
		for (int row = 0; row < rows(); row++) {
			for (int column = 0; column < columns; column++) {
				int index = (scroll + row) * columns + column;
				if (index >= shown.size()) return;

				WardrobePayloads.Costume costume = shown.get(index);
				int x = MARGIN + column * (CELL + GAP);
				int y = HEADER + row * (CELL + LABEL + GAP);
				boolean on = picked.contains(costume.id());

				graphics.fill(x, y, x + CELL, y + CELL,
					on ? TILE_ON : index == hovered ? TILE_HOVER : TILE);
				if (on) {
					graphics.fill(x, y, x + CELL, y + 2, ACCENT);
					graphics.fill(x, y + CELL - 2, x + CELL, y + CELL, ACCENT);
				}

				Identifier skin = Costumes.texture(costume.fingerprint());
				if (skin != null) {
					int figure = CELL - 8;
					PaperDoll.draw(graphics, skin,
						x + (CELL - PaperDoll.widthFor(figure)) / 2, y + 4, figure,
						Costumes.tall(costume.fingerprint()));
				}

				String label = trim(costume.label(), CELL - 4);
				graphics.text(font, Component.literal(label),
					x + CELL / 2 - font.width(label) / 2, y + CELL + 2, on ? ACCENT : TEXT_DIM);

				// The size, and whether a blink will work on it — both read from the
				// file's header before anything was downloaded. Nobody else can tell
				// you the second one at all.
				String badge = costume.width() > 64 ? String.valueOf(costume.width()) : "";
				if (!badge.isEmpty()) {
					graphics.text(font, Component.literal(badge), x + 4, y + 4, TEXT_DIM);
				}
				if (costume.eyes()) {
					graphics.fill(x + CELL - 8, y + 4, x + CELL - 4, y + 8, GOOD);
				}
			}
		}
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
		int left = previewLeft();
		int right = width - MARGIN;
		int top = HEADER;
		int bottom = height - FOOTER - DETAILS - 8;
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

		Identifier skin = Costumes.texture(costume.fingerprint());
		if (skin == null) return;
		int figure = Math.min(bottom - top - 24, (right - left) * 2);
		figure -= figure % PaperDoll.TALL;
		PaperDoll.draw(graphics, skin, (left + right) / 2 - PaperDoll.widthFor(figure) / 2,
			top + 12, figure, Costumes.tall(costume.fingerprint()));
		String label = trim(costume.label(), right - left - 8);
		graphics.text(font, Component.literal(label),
			(left + right) / 2 - font.width(label) / 2, bottom - 12, TEXT_DIM);
	}

	private void drawDetails(GuiGraphicsExtractor graphics) {
		int left = previewLeft();
		int top = height - FOOTER - DETAILS;
		graphics.fill(left, top, width - MARGIN, height - FOOTER - 4, PANEL);
		graphics.fill(left, top, width - MARGIN, top + 1, EDGE);

		String heading = picked.isEmpty() ? "ничего не выбрано"
			: picked.size() == 1 ? "костюм" : "костюмов: " + picked.size();
		graphics.text(font, Component.literal(heading), left + 8, top + 4,
			picked.isEmpty() ? TEXT_DIM : ACCENT);
		if (!copied.isEmpty()) {
			String buffer = "в буфере: " + copied.size();
			graphics.text(font, Component.literal(buffer),
				width - MARGIN - 8 - font.width(buffer), top + 4, GOOD);
		}
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
}
