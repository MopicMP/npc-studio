package com.mopicmp.npcstudio.client.editor;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Choosing an item by looking at it.
 *
 * Typing {@code minecraft:stone_sword} works and asks the wrong thing of a
 * person: it wants them to know a name they have never had to read, spelled
 * exactly, for an object they could simply point at. The same argument as the
 * animation picker, and the same answer — show the things.
 *
 * The grid is every item the game knows, drawn as itself. There are a thousand
 * or so, which is fewer than the emotes and the same problem, so it is solved
 * the same way: a search box and a scrolling grid of pictures.
 */
public class ItemPickerScreen extends Screen {

	private static final int MARGIN = 16;
	private static final int HEADER = 60;
	private static final int FOOTER = 30;
	private static final int CELL = 26;
	private static final int GAP = 2;

	private static final int CANVAS = 0xFF101318;
	private static final int SLOT = 0xFF1B2028;
	private static final int SLOT_HOVER = 0xFF27313E;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	private final Screen parent;
	private final Consumer<String> onPick;

	private EditBox search;
	private List<Item> shown = all();
	private int scroll;

	public ItemPickerScreen(Screen parent, Consumer<String> onPick) {
		super(Component.literal("Item"));
		this.parent = parent;
		this.onPick = onPick;
	}

	private static List<Item> all() {
		return BuiltInRegistries.ITEM.stream()
			.filter(item -> !new ItemStack(item).isEmpty())
			.toList();
	}

	private static List<Item> matching(String query) {
		String needle = query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) return all();
		return all().stream().filter(item -> {
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
			return id.contains(needle) || name.contains(needle);
		}).toList();
	}

	@Override
	protected void init() {
		if (search == null) {
			search = new EditBox(font, MARGIN, MARGIN + 20, 220, 18, Component.literal("search"));
			search.setSuggestion("search");
			search.setResponder(text -> {
				search.setSuggestion(text.isEmpty() ? "search" : "");
				shown = matching(text);
				scroll = 0;
			});
		}
		search.setPosition(MARGIN, MARGIN + 20);
		addRenderableWidget(search);

		int bottom = height - FOOTER + 6;
		// Whatever the player is holding, in one click. Most of the time the item
		// somebody wants on an NPC is the one already in their hand, and this saves
		// them finding it again in a grid of a thousand.
		addRenderableWidget(new FlatButton(MARGIN, bottom, 120, 20,
			Component.literal("from my hand"), ACCENT, () -> {
				ItemStack held = minecraft.player == null ? ItemStack.EMPTY
					: minecraft.player.getMainHandItem();
				take(held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
			}));
		addRenderableWidget(new FlatButton(MARGIN + 128, bottom, 100, 20,
			Component.literal("empty hand"), 0xFF8A99A6, () -> take("")));
		addRenderableWidget(new FlatButton(MARGIN + 236, bottom, 100, 20,
			Component.literal("cancel"), 0xFF8A99A6, () -> minecraft.setScreenAndShow(parent)));
	}

	private void take(String id) {
		onPick.accept(id);
		minecraft.setScreenAndShow(parent);
	}

	private int columns() {
		return Math.max(1, (width - MARGIN * 2 + GAP) / (CELL + GAP));
	}

	private int rows() {
		return Math.max(1, (height - HEADER - FOOTER + GAP) / (CELL + GAP));
	}

	private int slotAt(double mouseX, double mouseY) {
		int column = (int) ((mouseX - MARGIN) / (CELL + GAP));
		int row = (int) ((mouseY - HEADER) / (CELL + GAP));
		if (mouseX < MARGIN || column < 0 || column >= columns()) return -1;
		if (mouseY < HEADER || row < 0 || row >= rows()) return -1;
		int index = (scroll + row) * columns() + column;
		return index < shown.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		int slot = slotAt(event.x(), event.y());
		if (slot >= 0) {
			take(BuiltInRegistries.ITEM.getKey(shown.get(slot)).toString());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		int total = (shown.size() + columns() - 1) / columns();
		scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, total - rows()));
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.text(font, title, MARGIN, MARGIN, TEXT);

		int hovered = slotAt(mouseX, mouseY);
		int columns = columns();
		for (int row = 0; row < rows(); row++) {
			for (int column = 0; column < columns; column++) {
				int index = (scroll + row) * columns + column;
				if (index >= shown.size()) break;

				int x = MARGIN + column * (CELL + GAP);
				int y = HEADER + row * (CELL + GAP);
				graphics.fill(x, y, x + CELL, y + CELL, index == hovered ? SLOT_HOVER : SLOT);
				graphics.item(new ItemStack(shown.get(index)), x + 5, y + 5);
			}
		}

		if (hovered >= 0) {
			String name = new ItemStack(shown.get(hovered)).getHoverName().getString();
			graphics.text(font, Component.literal(name), MARGIN + 240, MARGIN + 25, TEXT_DIM);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
