package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatSlider;
import com.mopicmp.npcstudio.client.editor.ToggleSwitch;
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

	/** A switch is as wide as it needs to be to be a switch, not as wide as the panel. */
	private static final int SWITCH = 34;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	/** The character these fields are about, so a change of selection is noticed. */
	private int about = -1;

	private String skinText = "";
	private String dialogueText = "";

	/**
	 * How she behaves when nobody is talking to her.
	 *
	 * These three arrived as commands and did not belong there. Which graph runs a
	 * character is the same sort of fact as which dialogue she opens or which skin
	 * she wears: it belongs to this one character, it is authorship, and a command
	 * is a place to try something rather than a place for a setting to live.
	 */
	private boolean watchful;
	private boolean endless;
	private final List<String> animations = new ArrayList<>();
	private final List<String> held = new ArrayList<>();
	private float scale = 1f;

	private EditBox skin;

	/**
	 * Whether the list of conversations is open over the panel.
	 *
	 * <h2>Why a list and not a box to type in</h2>
	 *
	 * Because a name typed from memory is a name typed wrong, and a dialogue name
	 * that is one letter out is a character who silently has nothing to say. The
	 * field here used to be a plain box with the hint "dialogue name" in it, which
	 * meant the only way to assign a conversation correctly was to already know
	 * what it was called.
	 *
	 * There was a command that offered the real names as you typed. Taking it away
	 * without putting the list here was a straight loss, and it was reported as one.
	 */
	private Choosing picking = Choosing.NEITHER;

	/**
	 * Whether the graph list is open.
	 *
	 * <h2>There used to be two of these</h2>
	 *
	 * A conversation and a brain, chosen separately, and the pair cost a test
	 * session twice over. First because they did not look alike — one was a list
	 * of real names and the other a bare box eight rows down, so everything went
	 * into the first. Then, after I made them identical lists, because the
	 * question they answered was still wrong: a character does not have a brain.
	 *
	 * A brain is a library of skills and the only thing that calls a skill is a
	 * dialogue. So there is one field, and choosing wrongly between two is no
	 * longer something anybody can do here.
	 */
	private enum Choosing { NEITHER, CONVERSATION }
	private EditBox mainHand;
	private EditBox offHand;

	private String notice = "";

	/** Where the button ended up, so its list can open under it. */
	private int dialogueRow;

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
		watchful = details.watchful();
		endless = details.endless();
		skin = null;
		mainHand = null;
		offHand = null;
		picking = Choosing.NEITHER;
		// Only the server knows what conversations exist, so ask while the panel is
		// being filled in rather than when somebody opens the list. A menu that
		// appears empty and fills in a moment later is a menu people click through
		// before it is ready.
		ClientPlayNetworking.send(new com.mopicmp.npcstudio.net.EditorPayloads.Browse());
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
		touched();
	}

	private void touched() {
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
		mainHand = null;
		offHand = null;
		picking = Choosing.NEITHER;
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
		dialogueRow = y;
		add(new IconTextButton(PAD, y - 1, across, ROW, Icon.DIALOGUE,
			Component.literal(dialogueText.isEmpty()
				? Component.translatable("npc_studio.character.no_dialogue").getString()
				: dialogueText),
			ACCENT, () -> {
				picking = picking == Choosing.CONVERSATION
					? Choosing.NEITHER : Choosing.CONVERSATION;
			}));
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

		// Two switches on one row: they are both one-word facts about a character,
		// and a full-width track for a yes-or-no reads as a slider somebody forgot
		// to finish.
		y += LABEL;
		int half = across / 2;
		add(new ToggleSwitch(PAD, y, SWITCH, ROW,
			Component.translatable("npc_studio.character.watchful.what"), ACCENT,
			() -> watchful, () -> {
				watchful = !watchful;
				touched();
			}));
		add(new ToggleSwitch(PAD + half, y, SWITCH, ROW,
			Component.translatable("npc_studio.character.endless.what"), ACCENT,
			() -> endless, () -> {
				endless = !endless;
				touched();
			}));
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
		// The one row with two things on it, so it is written out rather than
		// going through the helper that assumes one label to a row.
		graphics.text(font, Component.translatable("npc_studio.character.watchful"),
			PAD, y, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.character.endless"),
			PAD + (width - PAD * 2) / 2, y, TEXT_DIM);

		if (!notice.isEmpty()) {
			graphics.text(font, Component.literal(notice), PAD, height - 12, TEXT_DIM);
		}
	}

	private static final int ROW_HEIGHT = 14;
	private static final int MENU = 0xFF161A20;
	private static final int MENU_EDGE = 0xFF2C333D;
	private static final int HOVER = 0xFF232A34;

	/**
	 * What the open list should offer, with "none" first so it can be taken off.
	 *
	 * Everything that can be given to a character, which is everything with a
	 * beginning. A library of skills has none — there is nowhere for it to start —
	 * and offering one here would be offering the mistake this field was just
	 * rebuilt to remove.
	 */
	private java.util.List<String> choices() {
		java.util.List<String> all = new ArrayList<>();
		all.add("");
		all.addAll(com.mopicmp.npcstudio.client.editor.DialogueNames.forCharacter());
		return all;
	}

	private int openRow() {
		return dialogueRow;
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (picking == Choosing.NEITHER) return;
		java.util.List<String> all = choices();
		int across = width - PAD * 2;
		int top = openRow() + ROW;
		// Never off the bottom of the panel. A list that runs past the edge is a
		// list whose last entries cannot be clicked, and the ones that cannot be
		// clicked are always the ones somebody wants.
		int shown = Math.max(1, Math.min(all.size(), (height - top - PAD) / ROW_HEIGHT));

		graphics.fill(PAD, top, PAD + across, top + shown * ROW_HEIGHT + 2, MENU);
		graphics.fill(PAD, top, PAD + across, top + 1, MENU_EDGE);
		for (int i = 0; i < shown; i++) {
			int at = top + 1 + i * ROW_HEIGHT;
			boolean hovered = mouseY >= at && mouseY < at + ROW_HEIGHT
				&& mouseX >= PAD && mouseX < PAD + across;
			if (hovered) graphics.fill(PAD, at, PAD + across, at + ROW_HEIGHT, HOVER);
			String name = all.get(i);
			graphics.text(font, Component.literal(name.isEmpty()
					? Component.translatable("npc_studio.character.no_dialogue").getString()
					: name),
				PAD + 4, at + 3, name.equals(dialogueText) ? ACCENT : TEXT);
		}
		if (all.size() > shown) {
			graphics.text(font, Component.literal("+" + (all.size() - shown)),
				PAD + across - 18, top + shown * ROW_HEIGHT - 10, TEXT_DIM);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
			boolean doubleClick) {
		if (picking != Choosing.NEITHER) {
			java.util.List<String> all = choices();
			int across = width - PAD * 2;
			int top = openRow() + ROW;
			int shown = Math.max(1, Math.min(all.size(), (height - top - PAD) / ROW_HEIGHT));
			int row = (int) ((event.y() - top - 1) / ROW_HEIGHT);
			if (event.x() >= PAD && event.x() < PAD + across && row >= 0 && row < shown) {
				dialogueText = all.get(row);
				touched();
			}
			// Closed either way: a click anywhere else means "not that one", which is
			// what clicking away from an open menu has always meant.
			picking = Choosing.NEITHER;
			rebuild();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
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
			new NpcPayloads.Apply(about, skinText, dialogueText, animations, held, scale,
				watchful, endless));
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
