package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.entity.AnimationCatalogue;
import com.mopicmp.npcstudio.client.skin.SkinImport;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.NpcPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * One character's own settings.
 *
 * Separate from the dialogue editor, and that separation is the point. A
 * dialogue is a document that any number of NPCs can share; how this one looks,
 * how it stands and how it walks belong to this one and follow it around. An
 * NPC with nothing to say still has to stand somehow.
 *
 * The character itself stands beside the settings. That is not decoration: the
 * first version put the fields on a bare screen with two thirds of it empty,
 * and the emptiness was the tell — the one thing missing was the thing all the
 * fields are about. Everything here changes how a character looks or moves, so
 * the character is where you look to see whether it worked.
 */
public class NpcScreen extends Screen {

	private static final int ROW = 24;
	private static final int LABEL_WIDTH = 84;
	private static final int FIELD_WIDTH = 190;
	private static final int PADDING = 16;
	private static final int PANEL_WIDTH = LABEL_WIDTH + FIELD_WIDTH + PADDING * 2;
	private static final int PREVIEW_WIDTH = 180;
	private static final int GAP = 12;

	private static final int CANVAS = 0xFF101318;
	private static final int PANEL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	private static NpcScreen open;

	/** Hands a freshly arrived description to the screen, opening one if needed. */
	public static void show(net.minecraft.client.Minecraft client, NpcPayloads.Details details) {
		NpcScreen screen = new NpcScreen(details);
		screen.parent = NpcSettingsButton.returnTo();
		open = screen;
		client.setScreenAndShow(screen);
	}

	public static NpcScreen current() {
		return open;
	}

	/** Where this was opened from, or null when it was opened from the world. */
	private Screen parent;

	private final int entityId;
	private final List<String> animations;
	private final List<String> held;

	private EditBox skin;
	private EditBox dialogue;
	private EditBox mainHand;
	private EditBox offHand;
	private String skinText;
	private String dialogueText;

	private String notice = "";

	private boolean dialogueOpen;
	private int dialogueListTop;

	/**
	 * What the character was holding when this opened.
	 *
	 * Kept so that closing without saving puts it back. The preview writes to the
	 * real entity to show a change straight away, and a change nobody committed
	 * should not outlive the screen that was showing it.
	 */
	private List<net.minecraft.world.item.ItemStack> original;
	private float spin;
	private float pitch = PreviewFigure.RESTING_PITCH;
	private float scale;
	private Float originalScale;
	private boolean dragging;
	private double dragFrom;
	private double dragFromY;

	public NpcScreen(NpcPayloads.Details details) {
		super(Component.literal("NPC"));
		this.entityId = details.entityId();
		this.skinText = details.skin();
		this.dialogueText = details.dialogue();

		// Padded out to the full set, so an NPC saved before a state existed still
		// has a slot to put one in rather than an index that is not there.
		this.animations = new ArrayList<>(details.animations());
		while (animations.size() < NpcEntity.Motion.values().length) animations.add("");

		this.held = new ArrayList<>(details.held());
		while (held.size() < 2) held.add("");
		this.scale = details.scale() <= 0 ? 1f : details.scale();
	}

	// ------------------------------------------------------------ layout

	private int panelLeft() {
		return (width - (PANEL_WIDTH + GAP + PREVIEW_WIDTH)) / 2;
	}

	private int panelTop() {
		return Math.max(20, (height - panelHeight()) / 2);
	}

	/** Exactly as tall as its contents, so there is no empty half to explain. */
	private int panelHeight() {
		int rows = 2 + NpcEntity.Motion.values().length + 4;
		return PADDING + 22 + rows * ROW + 24 + 20 + PADDING;
	}

	private int previewLeft() {
		return panelLeft() + PANEL_WIDTH + GAP;
	}

	@Override
	protected void init() {
		// Asked for once on the way in, so the list is already there when somebody
		// opens it. The server answers with the names and this screen keeps itself.
		if (DialogueNames.known().isEmpty()) {
			ClientPlayNetworking.send(new com.mopicmp.npcstudio.net.EditorPayloads.Browse());
		}
		int left = panelLeft() + PADDING + LABEL_WIDTH;
		int y = panelTop() + PADDING + 22;

		// The skin can be a name or a file. Both are on the same line because they
		// answer the same question, and one of them being a button is what says
		// "this one is not typed".
		skin = field(skin, left, y, FIELD_WIDTH - 74, skinText, text -> skinText = text, "player name");
		addRenderableWidget(new FlatButton(left + FIELD_WIDTH - 70, y - 1, 70, 20,
			Component.literal("from file…"), ACCENT, this::importSkin));
		y += ROW;

		// Typed or chosen, same as the skin. The name still has to match exactly,
		// and offering a box for something that must match exactly is a way of
		// manufacturing typos — so the list is where the answer normally comes from.
		dialogue = field(dialogue, left, y, FIELD_WIDTH - 74, dialogueText,
			text -> dialogueText = text, "dialogue name");
		addRenderableWidget(new FlatButton(left + FIELD_WIDTH - 70, y - 1, 70, 20,
			Component.literal(dialogueOpen ? "close" : "choose…"), ACCENT, () -> {
				dialogueOpen = !dialogueOpen;
				rebuildWidgets();
			}));
		dialogueListTop = y + 20;
		y += ROW;

		// One button per state, each opening the same picker. The label is the
		// animation itself rather than "choose", because the useful thing to see at
		// a glance is what this NPC is doing, not that a button exists.
		for (NpcEntity.Motion motion : NpcEntity.Motion.values()) {
			int slot = motion.ordinal();
			addRenderableWidget(new FlatButton(left, y - 1, FIELD_WIDTH, 20,
				Component.literal(nameOf(animations.get(slot))), ACCENT,
				() -> minecraft.setScreenAndShow(new AnimationPickerScreen(this,
					animations.get(slot), () -> AnimationPickerScreen.lookFor(entityId),
					picked -> animations.set(slot, picked)))));
			y += ROW;
		}

		// What it is holding. The name can still be typed, because sometimes that
		// is quickest, but nobody should have to: the button opens a grid of items
		// to point at, and offers whatever is in the player's hand as the first
		// answer, because usually it is.
		mainHand = field(mainHand, left, y, FIELD_WIDTH - 74, held.get(0),
			text -> held.set(0, text), "minecraft:stone_sword");
		addRenderableWidget(new FlatButton(left + FIELD_WIDTH - 70, y - 1, 70, 20,
			Component.literal("choose…"), ACCENT, () -> chooseItem(0, mainHand)));
		y += ROW;
		offHand = field(offHand, left, y, FIELD_WIDTH - 74, held.get(1),
			text -> held.set(1, text), "minecraft:shield");
		addRenderableWidget(new FlatButton(left + FIELD_WIDTH - 70, y - 1, 70, 20,
			Component.literal("choose…"), ACCENT, () -> chooseItem(1, offHand)));
		y += ROW;

		// The wardrobe is its own screen now. Three buttons crammed in beside the
		// animations was a poor answer to "let me change costume": costumes are the
		// world's rather than this character's, there can be hundreds, and they want
		// looking at rather than reading. One way in, and the rest lives there.
		addRenderableWidget(new FlatButton(left, y - 1, FIELD_WIDTH / 2 - 2, 20,
			Component.literal("костюмы…"), ACCENT,
			() -> minecraft.setScreenAndShow(
				new com.mopicmp.npcstudio.client.wardrobe.WardrobeScreen(this, entityId))));
		// Beside the costumes because they are the same question asked twice: what
		// does this character look like. Clothes and build travel together — a
		// costume carries a build, and this is where that build is decided.
		addRenderableWidget(new FlatButton(left + FIELD_WIDTH / 2 + 2, y - 1, FIELD_WIDTH / 2 - 2, 20,
			Component.literal("телосложение…"), ACCENT, () -> {
				var npc = minecraft.level == null ? null : minecraft.level.getEntity(entityId);
				minecraft.setScreenAndShow(new BodyShapeScreen(this, entityId,
					npc instanceof com.mopicmp.npcstudio.entity.NpcEntity found
						? found.bodyShape() : com.mopicmp.npcstudio.entity.BodyShape.DEFAULT));
			}));
		y += ROW + 8;

		// The game's own scale attribute, so this is a real size and not a drawing
		// trick: the hitbox, the eye height and how far the character can reach all
		// follow it. The preview beside the fields shows it immediately.
		addRenderableWidget(new FlatSlider(left, y - 1, FIELD_WIDTH, 20, "size",
			0.25, 4.0, 0.05, ACCENT, () -> scale, picked -> {
				scale = picked.floatValue();
				resizePreview();
			}));
		y += ROW + 8;

		addRenderableWidget(new FlatButton(left, y, 92, 20,
			Component.literal("save"), 0xFF66BB6A, this::save));
		addRenderableWidget(new FlatButton(left + 98, y, 92, 20,
			Component.literal("cancel"), 0xFF8A99A6, () -> minecraft.setScreenAndShow(parent)));
	}

	/**
	 * Reuses the box if there is one, so a rebuild does not throw away what is
	 * half typed — the same trap the picker's search box fell into.
	 */
	private EditBox field(EditBox existing, int x, int y, int boxWidth, String value,
			java.util.function.Consumer<String> onEdit, String hint) {
		EditBox box = existing;
		if (box == null) {
			box = new EditBox(font, x, y, boxWidth, 18, Component.literal(hint));
			box.setMaxLength(128);
			box.setValue(value);
			box.setSuggestion(value.isEmpty() ? hint : "");
			EditBox self = box;
			box.setResponder(text -> {
				self.setSuggestion(text.isEmpty() ? hint : "");
				onEdit.accept(text);
			});
		}
		box.setPosition(x, y);
		box.setWidth(boxWidth);
		addRenderableWidget(box);
		return box;
	}

	private String nameOf(String id) {
		if (id == null || id.isEmpty()) return "— none —";
		AnimationCatalogue.Entry entry = AnimationCatalogue.byId(id);
		return entry == null ? id
			: com.mopicmp.npcstudio.client.emote.EmoteLibrary.strip(entry.name());
	}

	/**
	 * Picks a skin file and sends it up.
	 *
	 * Off the render thread, because the system's file chooser blocks until it is
	 * answered and the game would freeze behind it.
	 */
	private void importSkin() {
		notice = "choosing a file…";
		Thread chooser = new Thread(() -> {
			SkinImport.Result result = SkinImport.choose();
			minecraft.execute(() -> {
				if (result.pixels() != null) {
					ClientPlayNetworking.send(new NpcPayloads.SkinUpload(entityId, result.pixels()));
					skinText = "";
					if (skin != null) skin.setValue("");
				}
				notice = result.message();
			});
		}, "npc-studio-skin-import");
		chooser.setDaemon(true);
		chooser.start();
	}

	/**
	 * Opens the item grid and writes the answer into both the list and the box.
	 *
	 * Both, because the box is what the person is looking at and the list is what
	 * gets sent. Setting only one of them is how a screen comes to disagree with
	 * itself.
	 */
	private void chooseItem(int slot, EditBox box) {
		minecraft.setScreenAndShow(new ItemPickerScreen(this, picked -> {
			held.set(slot, picked);
			if (box != null) box.setValue(picked);
			dressPreview();
		}));
	}

	/**
	 * Puts what the form says onto the character standing beside it.
	 *
	 * The preview draws the real entity, and the real entity only learns about a
	 * change when the server tells it — which is after saving. So an item chosen
	 * here appeared to do nothing until the screen was closed and opened again.
	 * Setting it on the client's own copy shows it at once; the server still has
	 * the last word, and says it on save.
	 */
	/** Shows the new size at once, the same way an item change is shown at once. */
	private void resizePreview() {
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(entityId) instanceof NpcEntity npc)) return;
		if (originalScale == null) originalScale = npc.scale();
		npc.setScale(scale);
	}

	private void dressPreview() {
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(entityId) instanceof NpcEntity npc)) return;
		if (original == null) {
			original = List.of(
				npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).copy(),
				npc.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).copy());
		}
		npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stackOf(held.get(0)));
		npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, stackOf(held.get(1)));
	}

	private static net.minecraft.world.item.ItemStack stackOf(String id) {
		if (id == null || id.isBlank()) return net.minecraft.world.item.ItemStack.EMPTY;
		net.minecraft.resources.Identifier name = net.minecraft.resources.Identifier.tryParse(id.trim());
		if (name == null) return net.minecraft.world.item.ItemStack.EMPTY;
		net.minecraft.world.item.Item item =
			net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(name);
		return item == null ? net.minecraft.world.item.ItemStack.EMPTY
			: new net.minecraft.world.item.ItemStack(item);
	}

	private boolean saved;

	private String trim(String label) {
		return font.width(label) <= FIELD_WIDTH / 2 - 8 ? label
			: font.plainSubstrByWidth(label, FIELD_WIDTH / 2 - 14) + "…";
	}

	private void save() {
		saved = true;
		ClientPlayNetworking.send(
			new NpcPayloads.Apply(entityId, skinText, dialogueText, animations, held, scale));
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public void removed() {
		if (open == this) open = null;
		if (!saved) restorePreview();
	}

	/** Undoes what the preview borrowed, for a screen that was closed rather than saved. */
	private void restorePreview() {
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(entityId) instanceof NpcEntity npc)) return;
		if (original != null) {
			npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, original.get(0));
			npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, original.get(1));
		}
		if (originalScale != null) npc.setScale(originalScale);
	}

	// ----------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);

		int left = panelLeft();
		int top = panelTop();
		int bottom = top + panelHeight();
		graphics.fill(left, top, left + PANEL_WIDTH, bottom, PANEL);
		graphics.fill(left, top, left + PANEL_WIDTH, top + 1, EDGE);
		graphics.text(font, title, left + PADDING, top + PADDING, TEXT);

		int y = top + PADDING + 22;
		label(graphics, "skin", left, y + 5);
		y += ROW;
		label(graphics, "dialogue", left, y + 5);
		y += ROW;
		for (NpcEntity.Motion motion : NpcEntity.Motion.values()) {
			label(graphics, motion.name().toLowerCase(java.util.Locale.ROOT), left, y + 5);
			y += ROW;
		}
		label(graphics, "main hand", left, y + 5);
		y += ROW;
		label(graphics, "off hand", left, y + 5);

		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice), left + PADDING, bottom + 6, TEXT_DIM);
		}

		drawPreview(graphics, top, bottom);

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// Last, so it covers the fields underneath rather than being covered by
		// them — the same lesson as the animation picker's category list.
		drawDialogueList(graphics, mouseX, mouseY);
	}

	/**
	 * The character itself, as the server currently has it.
	 *
	 * Drawn from the real entity rather than from a stand-in, so what is on
	 * screen is what everyone else can see — including the skin and whatever it
	 * is holding, which is the whole point of having it here.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int top, int bottom) {
		int left = previewLeft();
		int right = left + PREVIEW_WIDTH;
		graphics.fill(left, top, right, bottom, PANEL);
		graphics.fill(left, top, right, top + 1, EDGE);

		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(entityId) instanceof LivingEntity npc)) {
			String gone = "the NPC is out of sight";
			graphics.text(font, Component.literal(gone),
				(left + right) / 2 - font.width(gone) / 2, (top + bottom) / 2, TEXT_DIM);
			return;
		}

		try {
			PreviewFigure.draw(graphics, left, top, right, bottom - 18,
				(bottom - top) / 4f, 0.0625f, spin, pitch, npc);
		} catch (RuntimeException failed) {
			// The same rule as everywhere else: a picture is a convenience and must
			// never be the reason a screen stops working.
			com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
				"Could not draw the NPC preview: {}", failed.toString());
			return;
		}

		String hint = "drag to turn";
		graphics.text(font, Component.literal(hint),
			(left + right) / 2 - font.width(hint) / 2, bottom - 12, TEXT_DIM);
	}

	private static final int LIST_ROW = 13;

	private int dialogueListLeft() {
		return panelLeft() + PADDING + LABEL_WIDTH;
	}

	private int dialogueRows() {
		return Math.min(DialogueNames.known().size(), 10);
	}

	private int dialogueAt(double mouseX, double mouseY) {
		if (!dialogueOpen) return -1;
		int left = dialogueListLeft();
		if (mouseX < left || mouseX > left + FIELD_WIDTH) return -1;
		int row = (int) (mouseY - dialogueListTop) / LIST_ROW;
		return row >= 0 && row < dialogueRows() ? row : -1;
	}

	private void drawDialogueList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!dialogueOpen) return;
		List<String> names = DialogueNames.known();
		int left = dialogueListLeft();
		int rows = dialogueRows();

		if (rows == 0) {
			graphics.fill(left, dialogueListTop, left + FIELD_WIDTH, dialogueListTop + LIST_ROW, PANEL);
			graphics.text(font, Component.literal("no dialogues yet"),
				left + 5, dialogueListTop + 3, TEXT_DIM);
			return;
		}

		int bottom = dialogueListTop + rows * LIST_ROW;
		graphics.fill(left - 1, dialogueListTop - 1, left + FIELD_WIDTH + 1, bottom + 1, EDGE);
		graphics.fill(left, dialogueListTop, left + FIELD_WIDTH, bottom, PANEL);

		int hovered = dialogueAt(mouseX, mouseY);
		for (int i = 0; i < rows; i++) {
			int y = dialogueListTop + i * LIST_ROW;
			boolean on = names.get(i).equals(dialogueText);
			if (on || i == hovered) {
				graphics.fill(left + 1, y, left + FIELD_WIDTH - 1, y + LIST_ROW,
					on ? 0xFF27313E : 0xFF232A34);
			}
			graphics.text(font, Component.literal(names.get(i)), left + 5, y + 3,
				on ? ACCENT : TEXT);
		}
	}

	private void label(GuiGraphicsExtractor graphics, String text, int panelLeft, int y) {
		graphics.text(font, Component.literal(text), panelLeft + PADDING, y, TEXT_DIM);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The open list comes first, before the widgets. It is drawn over them and
		// has to be clicked over them too — checking the widgets first meant the
		// animation button underneath answered instead, so choosing a dialogue
		// opened the animation picker.
		if (dialogueOpen) {
			int row = dialogueAt(event.x(), event.y());
			if (row >= 0) {
				dialogueText = DialogueNames.known().get(row);
				if (dialogue != null) dialogue.setValue(dialogueText);
			}
			dialogueOpen = false;
			rebuildWidgets();
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.x() >= previewLeft()) {
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
	public boolean isPauseScreen() {
		return false;
	}
}
