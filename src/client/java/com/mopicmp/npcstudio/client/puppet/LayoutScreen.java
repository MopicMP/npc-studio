package com.mopicmp.npcstudio.client.puppet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.wardrobe.Costumes;
import com.mopicmp.npcstudio.client.wardrobe.PortraitShelf;
import com.mopicmp.npcstudio.net.WardrobePayloads;
import com.mopicmp.npcstudio.puppet.Fitting;
import com.mopicmp.npcstudio.puppet.Puppet;
import com.mopicmp.npcstudio.puppet.Slots;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Putting the parts of a figure where they belong.
 *
 * <h2>Why this window has to exist at all</h2>
 *
 * Because a set of layer pictures arrives with no positions in it, and there is nothing
 * anywhere to derive them from. Measured on a real set of 4594: no {@code oFFs} chunk in
 * any of them, no coordinates in any filename, and 204 different canvases inside a single
 * pose — every layer trimmed to its own edges, and where it sat left behind in whatever
 * drew it. That is not one game being awkward; trimming is what exporting layers does.
 *
 * So a person says where things go. The whole design of this window is about how few
 * times they have to say it.
 *
 * <h2>The three things that make it short work</h2>
 *
 * <b>A slot, not a picture.</b> Eleven pairs of eyes go in one place, so the placing is
 * done once for the slot and every variant inherits it. The slots are proposed from the
 * names by {@link Slots} — a guess the person corrects, not a rule they have to know.
 *
 * <b>Mouse for the coarse, arrows for the fine.</b> Grab it, drop it roughly, then a
 * key per pixel. Nothing invented: it is how every drawing program already works, which
 * means there is nothing here to learn.
 *
 * <b>Variants settle themselves.</b> When a slot's variants were trimmed differently,
 * {@link Fitting} works out how far each has to shift to land where the placed one is.
 * That is the one part of this with an answer in the pictures themselves — see its note
 * for why placing <em>different</em> parts has no such answer and is never guessed at.
 *
 * <h2>What it is deliberately not</h2>
 *
 * A drawing program. No blend modes, no history, no editing of the pictures. Each of
 * those costs as much as everything here and none brings a figure any closer to
 * assembled.
 */
public class LayoutScreen extends Screen {

	private static final int MARGIN = 12;
	private static final int SIDEBAR = 150;
	private static final int RAIL = 96;
	private static final int ROW = 16;

	private static final int PANEL = 0xE0161A20;
	private static final int EDGE = 0xFF3A424D;
	private static final int PICKED = 0xFFBA68C8;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int CANVAS = 0xFF0E1116;

	private final Screen parent;

	/** The sheet being worked on, held here and sent a slot at a time as it changes. */
	private Puppet sheet;

	/** Which slot the mouse and the arrow keys are moving, by name. */
	private String picked = "";

	/**
	 * Which variant of each slot is being looked at.
	 *
	 * A view, not part of the sheet. Somebody flicking through eleven pairs of eyes to
	 * check the placement is not choosing what a scene shows — that is the act node's
	 * business — and writing it down here would put a preview into everybody's map.
	 */
	private final Map<String, String> showing = new LinkedHashMap<>();

	private float scale = 1f;
	private int panX;
	private int panY;
	private boolean moving;

	/** Open while a folder is being chosen, because that is a list and not a screen. */
	private List<String> folders;

	private EditBox naming;
	private String notice = "";

	public LayoutScreen(Screen parent, String sheetName) {
		super(Component.translatable("npc_studio.puppet.title"));
		this.parent = parent;
		Puppet known = sheetName == null ? null : PuppetSheets.named(sheetName);
		this.sheet = known == null
			? new Puppet(sheetName == null ? "" : sheetName, 1, 1, List.of()) : known;
		for (Puppet.Slot slot : this.sheet.slots()) {
			if (!slot.parts().isEmpty()) showing.put(slot.name(), slot.parts().get(0).label());
		}
	}

	@Override
	protected void init() {
		PortraitShelf.refresh();
		PuppetSheets.refresh();

		int left = MARGIN;
		naming = new EditBox(font, left, MARGIN + 14, SIDEBAR - 4, 16,
			Component.translatable("npc_studio.puppet.name"));
		naming.setMaxLength(48);
		naming.setValue(sheet.name());
		naming.setResponder(value -> sheet = new Puppet(value.trim(), sheet.wide(), sheet.high(),
			sheet.slots()));
		addRenderableWidget(naming);

		int bottom = height - MARGIN - 20;
		addRenderableWidget(new FlatButton(left, bottom - 44, SIDEBAR - 4, 18,
			Component.translatable("npc_studio.puppet.take_folder"), PICKED,
			() -> folders = PortraitShelf.folders()));
		addRenderableWidget(new FlatButton(left, bottom - 22, (SIDEBAR - 8) / 2, 18,
			Component.literal("▲"), 0xFF4FC3F7, () -> reorder(-1)));
		addRenderableWidget(new FlatButton(left + (SIDEBAR - 4) / 2, bottom - 22, (SIDEBAR - 8) / 2, 18,
			Component.literal("▼"), 0xFF4FC3F7, () -> reorder(1)));
		addRenderableWidget(new FlatButton(left, bottom, (SIDEBAR - 8) / 2, 18,
			Component.translatable("npc_studio.puppet.drop_slot"), 0xFFEF5350, this::dropPicked));
		addRenderableWidget(new FlatButton(left + (SIDEBAR - 4) / 2, bottom, (SIDEBAR - 8) / 2, 18,
			Component.translatable("npc_studio.puppet.done"), 0xFF66BB6A, this::onClose));

		fit();
	}

	// ------------------------------------------------------------------ the sheet

	/**
	 * Takes a folder off the portrait shelf and makes slots of what is in it.
	 *
	 * <h2>A folder rather than a picture at a time</h2>
	 *
	 * Because the sets people have are folders. Adding four thousand pictures one click
	 * at a time is not a slower way of doing this, it is a way of not doing it — and the
	 * folder is already the unit somebody imported by.
	 *
	 * The canvas is taken from the biggest picture in the folder, which is the body: a
	 * figure's base is the layer everything else sits inside. It is a guess and it is
	 * only ever made once, when the sheet is empty — a canvas that changed later would
	 * move every slot already placed, which is the one edit nobody would mean.
	 */
	private void take(String folder) {
		List<WardrobePayloads.Costume> inside = new ArrayList<>();
		for (WardrobePayloads.Costume each : PortraitShelf.all()) {
			if (each.category().equals(folder)) inside.add(each);
		}
		if (inside.isEmpty()) {
			notice = Component.translatable("npc_studio.puppet.folder_empty").getString();
			return;
		}

		int wide = sheet.wide();
		int high = sheet.high();
		if (sheet.slots().isEmpty()) {
			for (WardrobePayloads.Costume each : inside) {
				int[] size = Costumes.sizeOf(each.fingerprint());
				if (size == null) continue;
				if ((long) size[0] * size[1] > (long) wide * high) {
					wide = size[0];
					high = size[1];
				}
			}
		}

		List<String> labels = inside.stream().map(WardrobePayloads.Costume::label).toList();
		var grouped = Slots.group(labels);
		List<Puppet.Slot> slots = new ArrayList<>(sheet.slots());
		int added = 0;
		for (var group : grouped.entrySet()) {
			if (sheet.slot(group.getKey()) != null) continue;
			List<Puppet.Part> parts = new ArrayList<>();
			for (String label : group.getValue()) {
				for (WardrobePayloads.Costume each : inside) {
					if (each.label().equals(label)) {
						parts.add(new Puppet.Part(label, each.fingerprint()));
						break;
					}
				}
			}
			if (parts.isEmpty()) continue;
			slots.add(new Puppet.Slot(group.getKey(), 0, 0, parts));
			showing.put(group.getKey(), parts.get(0).label());
			added++;
		}

		sheet = new Puppet(sheet.name(), wide, high, slots);
		notice = Component.translatable("npc_studio.puppet.took", added, folder).getString();
		if (picked.isEmpty() && !slots.isEmpty()) picked = slots.get(0).name();
		fit();
		// Nothing is sent yet. A folder taken in is a pile of unplaced slots, and writing
		// a hundred of those into the world before anybody has looked at them would be
		// filling somebody's map with a decision they have not made.
	}

	private Puppet.Slot pickedSlot() {
		return sheet.slot(picked);
	}

	/**
	 * Moves the picked slot, and settles its variants against the one on screen.
	 *
	 * The settling happens here rather than when the slot was made, because it needs a
	 * placed variant to settle against — and until somebody has moved the slot there is
	 * nothing to be relative to.
	 */
	private void moveBy(int byX, int byY) {
		Puppet.Slot slot = pickedSlot();
		if (slot == null) return;
		sheet = sheet.with(slot.moved(slot.x() + byX, slot.y() + byY));
	}

	/**
	 * Writes the picked slot into the world and moves to the next one nobody has placed.
	 *
	 * Saving one slot at a time rather than everything at the end: it is what somebody is
	 * working on, it always fits in one packet, and closing the window has then never
	 * lost anything.
	 */
	private void keep() {
		Puppet.Slot slot = pickedSlot();
		if (slot == null || sheet.name().isEmpty()) {
			notice = Component.translatable("npc_studio.puppet.needs_name").getString();
			return;
		}
		sheet = sheet.with(settled(slot));
		PuppetSheets.put(sheet.name(), sheet.wide(), sheet.high(), sheet.slot(slot.name()));
		notice = Component.translatable("npc_studio.puppet.kept", slot.name()).getString();
		next();
	}

	/**
	 * Every variant of a slot nudged onto the one being looked at.
	 *
	 * Only the ones whose pictures have arrived; a variant still on its way is left at
	 * nothing, which is where it started and is honest about not having been measured.
	 */
	private Puppet.Slot settled(Puppet.Slot slot) {
		Puppet.Part against = slot.part(showing.getOrDefault(slot.name(), ""));
		if (against == null && !slot.parts().isEmpty()) against = slot.parts().get(0);
		if (against == null) return slot;

		boolean[] fixed = Masks.of(against.picture());
		int[] fixedSize = Costumes.sizeOf(against.picture());
		if (fixed == null || fixedSize == null) return slot;

		List<Puppet.Part> parts = new ArrayList<>();
		for (Puppet.Part part : slot.parts()) {
			if (part.picture().equals(against.picture())) {
				// The one being looked at is the reference, so it is by definition where
				// the slot says it is. Nudging it would move the very thing everything
				// else was measured against.
				parts.add(part.nudged(0, 0));
				continue;
			}
			boolean[] mask = Masks.of(part.picture());
			int[] size = Costumes.sizeOf(part.picture());
			if (mask == null || size == null || mask.length == 0) {
				parts.add(part);
				continue;
			}
			int[] by = Fitting.meet(fixed, fixedSize[0], fixedSize[1], mask, size[0], size[1]);
			parts.add(part.nudged(by[0], by[1]));
		}
		return new Puppet.Slot(slot.name(), slot.x(), slot.y(), parts);
	}

	/** The next slot that is still sitting at the corner nobody put it in. */
	private void next() {
		List<Puppet.Slot> slots = sheet.slots();
		int from = 0;
		for (int i = 0; i < slots.size(); i++) {
			if (slots.get(i).name().equals(picked)) from = i + 1;
		}
		for (int step = 0; step < slots.size(); step++) {
			Puppet.Slot slot = slots.get((from + step) % slots.size());
			if (slot.x() == 0 && slot.y() == 0) {
				picked = slot.name();
				return;
			}
		}
	}

	private void reorder(int by) {
		List<Puppet.Slot> slots = new ArrayList<>(sheet.slots());
		int at = -1;
		for (int i = 0; i < slots.size(); i++) {
			if (slots.get(i).name().equals(picked)) at = i;
		}
		if (at < 0) return;
		int to = at + by;
		if (to < 0 || to >= slots.size()) return;
		slots.add(to, slots.remove(at));
		sheet = new Puppet(sheet.name(), sheet.wide(), sheet.high(), slots);
		// The order is the sheet's, so it has to reach the world as the sheet — every
		// slot, not just the one that moved. Sent as two puts, which is what the wire
		// takes; a sheet of a hundred slots reorders about as often as never.
		if (!sheet.name().isEmpty()) {
			PuppetSheets.put(sheet.name(), sheet.wide(), sheet.high(), slots.get(at));
			PuppetSheets.put(sheet.name(), sheet.wide(), sheet.high(), slots.get(to));
		}
	}

	private void dropPicked() {
		Puppet.Slot slot = pickedSlot();
		if (slot == null) return;
		List<Puppet.Slot> slots = new ArrayList<>(sheet.slots());
		slots.removeIf(each -> each.name().equals(slot.name()));
		sheet = new Puppet(sheet.name(), sheet.wide(), sheet.high(), slots);
		showing.remove(slot.name());
		picked = slots.isEmpty() ? "" : slots.get(0).name();
		if (!sheet.name().isEmpty()) PuppetSheets.drop(sheet.name(), slot.name());
	}

	// ------------------------------------------------------------------ the view

	private int canvasLeft() {
		return MARGIN + SIDEBAR + 8;
	}

	private int canvasWide() {
		return width - canvasLeft() - RAIL - MARGIN * 2;
	}

	private int canvasTop() {
		return MARGIN + 20;
	}

	private int canvasHigh() {
		return height - canvasTop() - MARGIN;
	}

	/** The whole figure in view, which is where anybody wants to start. */
	private void fit() {
		scale = Math.min(canvasWide() / (float) sheet.wide(), canvasHigh() / (float) sheet.high());
		if (scale <= 0 || Float.isNaN(scale)) scale = 1f;
		panX = canvasLeft() + (int) ((canvasWide() - sheet.wide() * scale) / 2);
		panY = canvasTop() + (int) ((canvasHigh() - sheet.high() * scale) / 2);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double byX, double byY) {
		if (mouseX < canvasLeft()) return super.mouseScrolled(mouseX, mouseY, byX, byY);
		// Zoom about the pointer, so the thing under it stays under it. Not a luxury: a
		// figure six hundred pixels tall shown in a pane four hundred tall cannot show a
		// one-pixel nudge at all, and the nudge is what this window is for.
		float was = scale;
		scale = (float) Math.clamp(scale * (byY > 0 ? 1.1 : 1 / 1.1), 0.05, 8.0);
		panX = (int) (mouseX - (mouseX - panX) * (scale / was));
		panY = (int) (mouseY - (mouseY - panY) * (scale / was));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int x = (int) event.x();
		int y = (int) event.y();

		if (folders != null) {
			int row = (y - canvasTop()) / ROW;
			if (x >= canvasLeft() && x < canvasLeft() + 160 && row >= 0 && row < folders.size()) {
				take(folders.get(row));
			}
			folders = null;
			return true;
		}

		// A slot in the list.
		if (x >= MARGIN && x < MARGIN + SIDEBAR) {
			int row = (y - listTop()) / ROW;
			List<Puppet.Slot> slots = sheet.slots();
			if (row >= 0 && row < slots.size()) {
				picked = slots.get(row).name();
				return true;
			}
		}

		// A variant on the rail.
		int rail = width - MARGIN - RAIL;
		if (x >= rail && pickedSlot() != null) {
			int row = (y - canvasTop()) / (RAIL - 8);
			var parts = pickedSlot().parts();
			if (row >= 0 && row < parts.size()) {
				showing.put(picked, parts.get(row).label());
				return true;
			}
		}

		if (x >= canvasLeft() && pickedSlot() != null) {
			moving = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double byX, double byY) {
		if (moving && pickedSlot() != null) {
			// In the figure's own pixels rather than the screen's, so dragging feels the
			// same however far it is zoomed in.
			moveBy(Math.round((float) byX / scale), Math.round((float) byY / scale));
			return true;
		}
		return super.mouseDragged(event, byX, byY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		moving = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (naming != null && naming.isFocused()) return super.keyPressed(event);
		int step = event.hasShiftDown() ? 10 : 1;
		switch (event.key()) {
			case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> { moveBy(-step, 0); return true; }
			case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> { moveBy(step, 0); return true; }
			case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> { moveBy(0, -step); return true; }
			case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> { moveBy(0, step); return true; }
			case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,
					org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> { keep(); return true; }
			default -> { }
		}
		return super.keyPressed(event);
	}

	// ------------------------------------------------------------------ drawing

	private int listTop() {
		return MARGIN + 36;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xF00B0E12);

		drawCanvas(graphics);
		drawList(graphics);
		drawRail(graphics);

		graphics.text(font, title, MARGIN, MARGIN - 2, TEXT);
		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice),
				MARGIN + font.width(title) + 12, MARGIN - 2, TEXT_DIM);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		if (folders != null) drawFolders(graphics);
	}

	private void drawCanvas(GuiGraphicsExtractor graphics) {
		int left = canvasLeft();
		graphics.fill(left, canvasTop(), left + canvasWide(), canvasTop() + canvasHigh(), CANVAS);

		int wide = Math.max(1, Math.round(sheet.wide() * scale));
		int high = Math.max(1, Math.round(sheet.high() * scale));
		graphics.fill(panX - 1, panY - 1, panX + wide + 1, panY, EDGE);
		graphics.fill(panX - 1, panY + high, panX + wide + 1, panY + high + 1, EDGE);

		for (Puppet.Slot slot : sheet.slots()) {
			Puppet.Part part = slot.part(showing.getOrDefault(slot.name(), ""));
			if (part == null && !slot.parts().isEmpty()) part = slot.parts().get(0);
			if (part == null) continue;

			Identifier texture = Costumes.texture(part.picture());
			int[] size = Costumes.sizeOf(part.picture());
			if (texture == null || size == null) continue;

			int x = panX + Math.round((slot.x() + part.nudgeX()) * scale);
			int y = panY + Math.round((slot.y() + part.nudgeY()) * scale);
			int drawWide = Math.max(1, Math.round(size[0] * scale));
			int drawHigh = Math.max(1, Math.round(size[1] * scale));
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f,
				drawWide, drawHigh, size[0], size[1], size[0], size[1]);

			// The one being moved gets a box round it. Without it, a slot dropped behind
			// the body is invisible and the arrow keys appear to do nothing at all.
			if (slot.name().equals(picked)) {
				graphics.fill(x, y, x + drawWide, y + 1, PICKED);
				graphics.fill(x, y + drawHigh - 1, x + drawWide, y + drawHigh, PICKED);
				graphics.fill(x, y, x + 1, y + drawHigh, PICKED);
				graphics.fill(x + drawWide - 1, y, x + drawWide, y + drawHigh, PICKED);
			}
		}
	}

	private void drawList(GuiGraphicsExtractor graphics) {
		graphics.fill(MARGIN - 4, MARGIN - 6, MARGIN + SIDEBAR, height - MARGIN + 4, PANEL);
		graphics.text(font, Component.translatable("npc_studio.puppet.name"),
			MARGIN, MARGIN + 4, TEXT_DIM);

		int y = listTop();
		for (Puppet.Slot slot : sheet.slots()) {
			if (y > height - MARGIN - 90) break;
			boolean isPicked = slot.name().equals(picked);
			if (isPicked) graphics.fill(MARGIN - 2, y - 2, MARGIN + SIDEBAR - 4, y + ROW - 4, 0x40BA68C8);
			// A slot still at the corner is one nobody has placed. Said in the colour
			// rather than in a word, because it is the only thing the list is tracking.
			boolean placed = slot.x() != 0 || slot.y() != 0;
			graphics.text(font, Component.literal(shorten(slot.name())),
				MARGIN, y, placed ? TEXT : TEXT_DIM);
			graphics.text(font, Component.literal(String.valueOf(slot.parts().size())),
				MARGIN + SIDEBAR - 22, y, TEXT_DIM);
			y += ROW;
		}
	}

	private void drawRail(GuiGraphicsExtractor graphics) {
		Puppet.Slot slot = pickedSlot();
		if (slot == null) return;
		int left = width - MARGIN - RAIL;
		int cell = RAIL - 8;
		int y = canvasTop();
		for (Puppet.Part part : slot.parts()) {
			if (y + cell > height - MARGIN) break;
			boolean isShowing = part.label().equals(showing.get(slot.name()));
			graphics.fill(left, y, left + cell, y + cell, isShowing ? 0x60BA68C8 : PANEL);
			drawFitted(graphics, part.picture(), left + 2, y + 2, cell - 4, cell - 4);
			y += cell + 2;
		}
	}

	private void drawFolders(GuiGraphicsExtractor graphics) {
		int left = canvasLeft();
		int y = canvasTop();
		graphics.fill(left - 4, y - 4, left + 164, y + ROW * Math.max(1, folders.size()) + 4, 0xF01B2028);
		if (folders.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.puppet.no_folders"),
				left, y, TEXT_DIM);
			return;
		}
		for (String folder : folders) {
			graphics.text(font, Component.literal(shorten(folder)), left, y, TEXT);
			y += ROW;
		}
	}

	private void drawFitted(GuiGraphicsExtractor graphics, String fingerprint,
			int x, int y, int wide, int high) {
		Identifier texture = Costumes.texture(fingerprint);
		int[] size = Costumes.sizeOf(fingerprint);
		if (texture == null || size == null) return;
		float into = Math.min(wide / (float) size[0], high / (float) size[1]);
		int drawWide = Math.max(1, Math.round(size[0] * into));
		int drawHigh = Math.max(1, Math.round(size[1] * into));
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
			x + (wide - drawWide) / 2, y + (high - drawHigh) / 2, 0f, 0f,
			drawWide, drawHigh, size[0], size[1], size[0], size[1]);
	}

	private static String shorten(String label) {
		return label.length() <= 18 ? label : label.substring(0, 17) + "…";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.setScreenAndShow(parent);
	}
}
