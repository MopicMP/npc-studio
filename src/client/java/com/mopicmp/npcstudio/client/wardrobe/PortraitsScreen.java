package com.mopicmp.npcstudio.client.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.skin.SkinImport;
import com.mopicmp.npcstudio.net.WardrobePayloads;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The world's portraits: what is on the shelf, and what can be done with it.
 *
 * <h2>Why this is not the wardrobe screen</h2>
 *
 * Because most of the wardrobe screen is about dressing somebody. It holds a paper
 * doll, a figure to try a costume on, a body build to write back, and a face whose
 * eyes have to be marked — none of which a portrait has. Parameterising thirteen
 * hundred lines to share the fifth of them that is a grid of pictures would have made
 * both screens harder to read to save writing a grid once.
 *
 * What is shared is everything underneath: the shelf, the upload, the picture cache,
 * the request guard. That is the part where a second copy would be dangerous rather
 * than merely tedious.
 *
 * <h2>Folders</h2>
 *
 * The shelf files a picture under a name, and this shows those names down the left as
 * folders. They are not folders on disk — see {@code Portraits}, where that trade is
 * argued — and the difference shows in one place only: a folder exists while
 * something is in it. Making an empty one and filling it later would need a record of
 * folders beside the record of pictures, which is a second list to keep in step for
 * something a rename already does.
 */
public class PortraitsScreen extends Screen {

	private static final int MARGIN = 12;
	private static final int SIDEBAR = 120;
	private static final int CELL = 96;
	private static final int GAP = 8;
	private static final int ROW = 20;

	private static final int PANEL = 0xE0161A20;
	private static final int EDGE = 0xFF3A424D;
	private static final int PICKED = 0xFFBA68C8;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;

	private final Screen parent;

	/** Which folder is being looked at. Empty is the shelf's own top level. */
	private String folder = "";

	/** What is selected, by id. Several, because moving and deleting are batch acts. */
	private final Set<String> picked = new LinkedHashSet<>();

	private EditBox naming;
	private int scroll;

	public PortraitsScreen(Screen parent) {
		super(Component.translatable("npc_studio.portraits.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		PortraitShelf.refresh();
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		int left = MARGIN;
		int y = MARGIN + ROW;

		// The folders, and the shelf's top level above them. Always present, so that a
		// shelf with everything filed still has somewhere to put the next thing.
		addRenderableWidget(new FlatButton(left, y, SIDEBAR - 8, 18,
			Component.translatable("npc_studio.portraits.all"),
			folder.isEmpty() ? PICKED : EDGE, () -> {
				folder = "";
				picked.clear();
				rebuild();
			}));
		y += ROW;
		for (String each : PortraitShelf.folders()) {
			addRenderableWidget(new FlatButton(left, y, SIDEBAR - 8, 18,
				Component.literal(each), each.equals(folder) ? PICKED : EDGE, () -> {
					folder = each;
					picked.clear();
					rebuild();
				}));
			y += ROW;
		}

		// The name a new folder gets, and the name a rename gives. One box, because
		// both are "type a word and press the button beside it" and two boxes would be
		// two places to look for the word you just typed.
		naming = new EditBox(font, left, height - MARGIN - 44, SIDEBAR - 8, 18,
			Component.translatable("npc_studio.portraits.name"));
		naming.setMaxLength(48);
		naming.setHint(Component.translatable("npc_studio.portraits.name"));
		addRenderableWidget(naming);

		addRenderableWidget(new FlatButton(left, height - MARGIN - 22, SIDEBAR - 8, 18,
			Component.translatable("npc_studio.portraits.add"), 0xFF66BB6A, this::bringIn));
		// The way through to assembling a figure out of these. Here rather than anywhere
		// else because this is where somebody is when they notice that what they have is
		// four hundred pieces of a person rather than four hundred portraits.
		addRenderableWidget(new FlatButton(left, height - MARGIN - 44, SIDEBAR - 8, 18,
			Component.translatable("npc_studio.portraits.figures"), 0xFFBA68C8,
			() -> {
				if (minecraft != null) {
					minecraft.setScreenAndShow(
						new com.mopicmp.npcstudio.client.puppet.LayoutScreen(this, null));
				}
			}));

		int right = width - MARGIN;
		int wide = 108;
		int at = right - wide;
		addRenderableWidget(new FlatButton(at, height - MARGIN - 22, wide, 18,
			Component.translatable("npc_studio.portraits.done"), EDGE, this::onClose));
		at -= wide + 4;
		addRenderableWidget(new FlatButton(at, height - MARGIN - 22, wide, 18,
			Component.translatable("npc_studio.portraits.drop"), 0xFFEF5350, () -> {
				if (picked.isEmpty()) return;
				PortraitShelf.remove(List.copyOf(picked));
				picked.clear();
			}));
		at -= wide + 4;
		addRenderableWidget(new FlatButton(at, height - MARGIN - 22, wide, 18,
			Component.translatable("npc_studio.portraits.refile"), EDGE, () -> {
				if (picked.isEmpty()) return;
				// The typed word, or the folder being looked at when nothing is typed.
				// Moving into a folder you are standing in is the useless case, so an
				// empty box means "out to the top level" only when that is where you are.
				PortraitShelf.refile(List.copyOf(picked), naming.getValue().trim());
				picked.clear();
			}));
		at -= wide + 4;
		addRenderableWidget(new FlatButton(at, height - MARGIN - 22, wide, 18,
			Component.translatable("npc_studio.portraits.rename"), EDGE, () -> {
				if (picked.size() != 1 || naming.getValue().isBlank()) return;
				PortraitShelf.rename(picked.iterator().next(), naming.getValue().trim());
			}));
		at -= wide + 4;
		addRenderableWidget(new FlatButton(at, height - MARGIN - 22, wide, 18,
			Component.translatable("npc_studio.portraits.drop_folder"), 0xFFEF5350, () -> {
				if (folder.isEmpty()) return;
				PortraitShelf.dropFolder(folder);
				folder = "";
				picked.clear();
				rebuild();
			}));
	}

	/**
	 * Brings pictures in from disk, several at a time.
	 *
	 * Through the same chooser skins use, which already takes a folder of files or a
	 * zip and names each picture after its file. Thirty portraits is one trip rather
	 * than thirty, and thirty pictures called "portrait" would be a shelf nobody can
	 * find anything on.
	 */
	private void bringIn() {
		notice = Component.translatable("npc_studio.portraits.choosing").getString();
		// On a thread of its own, and that is not tidiness: the file chooser blocks
		// until somebody answers it, and blocking here is blocking the frame — the
		// game would stop drawing until the dialog was dismissed, which reads as a
		// crash. Everything that touches the game goes back through execute().
		String into = folder;
		Thread chooser = new Thread(() -> {
			SkinImport.Many many = SkinImport.chooseMany();
			minecraft.execute(() -> {
				int sent = 0;
				int refused = 0;
				for (SkinImport.Skin each : many.skins()) {
					if (Costumes.tooBig(each.pixels())) {
						// Said here rather than found out by being disconnected. The
						// picture is fine; it is the way to the server that is too narrow.
						refused++;
						continue;
					}
					PortraitShelf.add(each.label(), into, each.pixels());
					sent++;
				}
				notice = many.skins().isEmpty() ? many.message()
					: Component.translatable("npc_studio.portraits.brought", sent, refused)
						.getString();
			});
		}, "npc-studio-portrait-import");
		chooser.setDaemon(true);
		chooser.start();
	}

	/** What the last trip to the file chooser came to, or empty. */
	private String notice = "";

	/** What is on this shelf and in this folder, in the order the shelf lists it. */
	private List<WardrobePayloads.Costume> showing() {
		List<WardrobePayloads.Costume> here = new ArrayList<>();
		for (WardrobePayloads.Costume each : PortraitShelf.all()) {
			if (each.category().equals(folder)) here.add(each);
		}
		return here;
	}

	private int columns() {
		return Math.max(1, (width - MARGIN * 2 - SIDEBAR) / (CELL + GAP));
	}

	private int cellsTop() {
		return MARGIN + ROW;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int hit = cellAt((int) event.x(), (int) event.y());
		if (hit >= 0) {
			List<WardrobePayloads.Costume> here = showing();
			if (hit < here.size()) {
				String id = here.get(hit).id();
				// Clicking a chosen one lets it go, so a mis-click costs one click.
				if (!picked.remove(id)) picked.add(id);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int cellAt(int mouseX, int mouseY) {
		int left = MARGIN + SIDEBAR;
		int top = cellsTop() - scroll;
		if (mouseX < left || mouseY < top) return -1;
		int column = (mouseX - left) / (CELL + GAP);
		if (column >= columns()) return -1;
		int row = (mouseY - top) / (CELL + GAP);
		if (row < 0) return -1;
		return row * columns() + column;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double byX, double byY) {
		int rows = (showing().size() + columns() - 1) / columns();
		int tall = rows * (CELL + GAP);
		int room = height - cellsTop() - MARGIN - 30;
		scroll = Math.clamp(scroll - (int) (byY * 24), 0, Math.max(0, tall - room));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		Font font = this.font;

		graphics.text(font, title, MARGIN, MARGIN - 2, TEXT);
		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice),
				MARGIN + font.width(title) + 12, MARGIN - 2, TEXT_DIM);
		}

		List<WardrobePayloads.Costume> here = showing();
		if (here.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.portraits.empty"),
				MARGIN + SIDEBAR, cellsTop() + 4, TEXT_DIM);
			return;
		}

		int left = MARGIN + SIDEBAR;
		int top = cellsTop() - scroll;
		int columns = columns();
		for (int i = 0; i < here.size(); i++) {
			WardrobePayloads.Costume each = here.get(i);
			int x = left + (i % columns) * (CELL + GAP);
			int y = top + (i / columns) * (CELL + GAP);
			if (y + CELL < cellsTop() || y > height) continue;

			boolean chosen = picked.contains(each.id());
			graphics.fill(x, y, x + CELL, y + CELL, PANEL);
			graphics.fill(x, y, x + CELL, y + 1, chosen ? PICKED : EDGE);
			graphics.fill(x, y + CELL - 1, x + CELL, y + CELL, chosen ? PICKED : EDGE);
			graphics.fill(x, y, x + 1, y + CELL, chosen ? PICKED : EDGE);
			graphics.fill(x + CELL - 1, y, x + CELL, y + CELL, chosen ? PICKED : EDGE);

			drawPicture(graphics, each.fingerprint(), x + 2, y + 2, CELL - 4, CELL - 14);
			graphics.text(font, Component.literal(shorten(each.label())),
				x + 4, y + CELL - 11, chosen ? PICKED : TEXT_DIM);
		}
	}

	/**
	 * The picture inside a cell, fitted rather than stretched.
	 *
	 * A portrait is taller than it is wide and a cell is square, so fitting is the
	 * only honest choice: stretching would show every drawing at proportions nobody
	 * drew, and the cell is what somebody picks from.
	 */
	private void drawPicture(GuiGraphicsExtractor graphics, String fingerprint,
			int x, int y, int wide, int high) {
		Identifier texture = Costumes.texture(fingerprint);
		if (texture == null) return;
		int[] size = Costumes.sizeOf(fingerprint);
		if (size == null) return;

		float scale = Math.min(wide / (float) size[0], high / (float) size[1]);
		int drawWide = Math.max(1, Math.round(size[0] * scale));
		int drawHigh = Math.max(1, Math.round(size[1] * scale));
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
			x + (wide - drawWide) / 2, y + (high - drawHigh) / 2,
			0f, 0f, drawWide, drawHigh, size[0], size[1], size[0], size[1]);
	}

	private String shorten(String label) {
		return label.length() <= 14 ? label : label.substring(0, 13) + "…";
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.setScreenAndShow(parent);
	}
}
