package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatSlider;
import com.mopicmp.npcstudio.client.entity.AnimationCatalogue;
import com.mopicmp.npcstudio.client.skin.SkinImport;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.NpcPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * One character's own settings, now answering to whatever is selected.
 *
 * This was a screen with the character drawn beside the fields, because a form
 * on an empty screen gave you no way to see what the form was about. The
 * drawing is gone and nothing was lost: the viewport is right there, showing
 * the same character at whatever size and angle suits, which is what the little
 * figure was standing in for.
 *
 * What did change is who it is about. The screen was handed an entity id at the
 * door and kept it for its whole life; this follows the selection, so two
 * characters in the same scene are two clicks apart rather than two round trips
 * through the world.
 */
public class CharacterPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int LABEL = 10;
	private static final int ROW = 18;
	private static final int GAP = 5;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	/** The character these fields are about, so a change of selection is noticed. */
	private int about = -1;

	private String skinText = "";
	private String dialogueText = "";
	private final List<String> animations = new ArrayList<>();
	private final List<String> held = new ArrayList<>();
	private float scale = 1f;

	private EditBox skin;
	private EditBox dialogue;
	private EditBox mainHand;
	private EditBox offHand;

	private String notice = "";

	/** How big the character was before a slider was dragged at it. */
	private Float originalScale;

	public CharacterPanel() {
		blank();
	}

	private void blank() {
		animations.clear();
		while (animations.size() < NpcEntity.Motion.values().length) animations.add("");
		held.clear();
		held.add("");
		held.add("");
	}

	@Override
	public String id() {
		return "character";
	}

	@Override
	public int minimumWidth() {
		return 150;
	}

	/**
	 * Fills the fields from what the server sent.
	 *
	 * Called from the packet rather than asked for here, because only the server
	 * knows a character's dialogue and skin — the client has the body in front of
	 * it and none of the paperwork.
	 */
	public void accept(NpcPayloads.Details details) {
		if (details.entityId() != Workspace.selected()) return;
		about = details.entityId();
		skinText = details.skin();
		dialogueText = details.dialogue();
		blank();
		for (int i = 0; i < details.animations().size() && i < animations.size(); i++) {
			animations.set(i, details.animations().get(i));
		}
		for (int i = 0; i < details.held().size() && i < held.size(); i++) {
			held.set(i, details.held().get(i));
		}
		scale = details.scale() <= 0 ? 1f : details.scale();
		skin = null;
		dialogue = null;
		mainHand = null;
		offHand = null;
		rebuild();
	}

	/**
	 * Puts something in a hand, on behalf of the panel it was chosen in.
	 *
	 * Not sent anywhere on its own: it lands in the same fields the rest of the
	 * character lives in and travels with them when the character is saved. One
	 * character, one packet, one moment at which the server learns anything.
	 */
	public void hold(int slot, String item) {
		if (about < 0 || slot < 0 || slot >= held.size()) return;
		held.set(slot, item);
		EditBox box = slot == 0 ? mainHand : offHand;
		if (box != null) box.setValue(item);
		notice = Component.translatable("npc_studio.character.unsaved").getString();
	}

	/**
	 * Sets one of the four states, on behalf of the library it was chosen in.
	 *
	 * The same bargain as {@link #hold}: it lands in the fields here and travels
	 * with the rest of the character when it is saved, so there is one place that
	 * knows what a character is and one packet that says so.
	 */
	public void setAnimation(int slot, String animation) {
		if (about < 0 || slot < 0 || slot >= animations.size()) return;
		animations.set(slot, animation);
		notice = Component.translatable("npc_studio.character.unsaved").getString();
		rebuild();
	}

	@Override
	public void tick() {
		int now = Workspace.selected();
		if (now == about) return;
		// The fields are wrong the moment the selection moves, so they are emptied
		// at once and filled when the answer arrives. Showing the last character's
		// skin under the new character's name for a round trip is worse than
		// showing nothing.
		about = now;
		skinText = "";
		dialogueText = "";
		blank();
		scale = 1f;
		skin = null;
		dialogue = null;
		mainHand = null;
		offHand = null;
		rebuild();
		if (now >= 0) ClientPlayNetworking.send(new NpcPayloads.Open(now));
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void build() {
		if (about < 0) return;

		int across = width - PAD * 2;
		if (across < 60) return;
		int y = PAD;

		y += LABEL;
		skin = field(skin, PAD, y, across - IconTextButton.FOLDED - 4, skinText,
			text -> skinText = text, "player name");
		add(new IconTextButton(PAD + across - IconTextButton.FOLDED, y - 1,
			IconTextButton.FOLDED, ROW, Icon.FILE,
			Component.translatable("npc_studio.character.from_file"), ACCENT, this::importSkin));
		y += ROW + GAP;

		y += LABEL;
		dialogue = field(dialogue, PAD, y, across, dialogueText, text -> dialogueText = text,
			"dialogue name");
		y += ROW + GAP;

		for (NpcEntity.Motion motion : NpcEntity.Motion.values()) {
			int slot = motion.ordinal();
			y += LABEL;
			add(new IconTextButton(PAD, y - 1, across, ROW, Icon.PLAY,
				Component.literal(nameOf(animations.get(slot))), ACCENT,
				() -> Workspace.askAnimation(new Workspace.Pick(animations.get(slot),
					picked -> animations.set(slot, picked)))));
			y += ROW + GAP;
		}

		y += LABEL;
		mainHand = field(mainHand, PAD, y, across, held.get(0), text -> held.set(0, text),
			"minecraft:stone_sword");
		y += ROW + GAP;
		y += LABEL;
		offHand = field(offHand, PAD, y, across, held.get(1), text -> held.set(1, text),
			"minecraft:shield");
		y += ROW + GAP + 4;

		add(new FlatSlider(PAD, y, across, ROW, "size", 0.25, 4.0, 0.05, ACCENT,
			() -> scale, picked -> {
				scale = picked.floatValue();
				resizePreview();
			}));
		y += ROW + GAP + 4;

		add(new IconTextButton(PAD, y, across, ROW, Icon.SAVE,
			Component.translatable("npc_studio.character.save"), 0xFF66BB6A, this::save));
	}

	private EditBox field(EditBox existing, int x, int y, int boxWidth, String value,
			java.util.function.Consumer<String> onEdit, String hint) {
		EditBox box = existing;
		if (box == null) {
			box = new EditBox(font, x, y, boxWidth, ROW - 2, Component.literal(hint));
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
		add(box);
		return box;
	}

	private String nameOf(String id) {
		if (id == null || id.isEmpty()) {
			return Component.translatable("npc_studio.character.no_animation").getString();
		}
		AnimationCatalogue.Entry entry = AnimationCatalogue.byId(id);
		return entry == null ? id
			: com.mopicmp.npcstudio.client.emote.EmoteLibrary.strip(entry.name());
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (about < 0) {
			graphics.text(font, Component.translatable("npc_studio.character.nothing"),
				PAD, PAD, TEXT_DIM);
			return;
		}

		int y = PAD;
		y = label(graphics, "npc_studio.character.skin", y);
		y = label(graphics, "npc_studio.character.dialogue", y);
		for (NpcEntity.Motion motion : NpcEntity.Motion.values()) {
			y = label(graphics, "npc_studio.motion." + motion.name().toLowerCase(java.util.Locale.ROOT), y);
		}
		y = label(graphics, "npc_studio.character.main_hand", y);
		y = label(graphics, "npc_studio.character.off_hand", y);

		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice), PAD, height - 12, TEXT_DIM);
		}
	}

	private int label(GuiGraphicsExtractor graphics, String key, int y) {
		graphics.text(font, Component.translatable(key), PAD, y, TEXT_DIM);
		return y + LABEL + ROW + GAP;
	}

	// ------------------------------------------------------------------ doing

	/**
	 * Picks a skin file and sends it up.
	 *
	 * On a thread of its own, because the system's file chooser blocks until it
	 * is answered and the game would stand still behind it.
	 */
	private void importSkin() {
		int who = about;
		notice = Component.translatable("npc_studio.character.choosing").getString();
		Thread chooser = new Thread(() -> {
			SkinImport.Result result = SkinImport.choose();
			minecraft.execute(() -> {
				if (result.pixels() != null) {
					ClientPlayNetworking.send(new NpcPayloads.SkinUpload(who, result.pixels()));
					skinText = "";
					if (skin != null) skin.setValue("");
				}
				notice = result.message();
			});
		}, "npc-studio-skin-import");
		chooser.setDaemon(true);
		chooser.start();
	}

	/** Shows a new size at once; the server still has the last word, on save. */
	private void resizePreview() {
		if (minecraft.level == null) return;
		if (!(minecraft.level.getEntity(about) instanceof NpcEntity npc)) return;
		if (originalScale == null) originalScale = npc.scale();
		npc.setScale(scale);
	}

	private void save() {
		if (about < 0) return;
		ClientPlayNetworking.send(
			new NpcPayloads.Apply(about, skinText, dialogueText, animations, held, scale));
		// Saved is the new starting point, so closing afterwards must not put the
		// old size back.
		originalScale = null;
		notice = Component.translatable("npc_studio.character.saved").getString();
	}

	@Override
	public void closed() {
		restoreSize();
	}

	/** Puts back a size that was dragged at and never saved. */
	private void restoreSize() {
		if (minecraft.level == null || originalScale == null) return;
		if (minecraft.level.getEntity(about) instanceof NpcEntity npc) npc.setScale(originalScale);
	}
}
