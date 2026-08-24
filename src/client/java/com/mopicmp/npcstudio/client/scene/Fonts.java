package com.mopicmp.npcstudio.client.scene;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.mixin.FontManagerAccess;
import com.mopicmp.npcstudio.client.mixin.MinecraftFontsAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The typefaces there are to choose from.
 *
 * <h2>Why they are asked for rather than listed</h2>
 *
 * The game ships four and that is not the interesting number. A resource pack may
 * add a typeface, and somebody shooting a scene under three large packs — which is
 * this project's own case — should be able to put a title in one of them. A list
 * written here would offer exactly the fonts nobody installed a pack for, and
 * would go stale the first time Mojang added one.
 *
 * So the loaded set is read out of the font manager. It is private, which is why
 * there is an accessor; it is also the only place the answer exists, because
 * nothing in the game needs a list of fonts — everything that draws one already
 * knows which it wants.
 *
 * <h2>What is done with the ones nobody can read</h2>
 *
 * Nothing, and that is deliberate. {@code minecraft:alt} is the enchanting-table
 * alphabet and {@code minecraft:illageralt} is the one on the banners: both are
 * unreadable on purpose and both are exactly what somebody wants for a rune on a
 * sail. Filtering them out would be deciding for them.
 */
public final class Fonts {

	/** The empty name, meaning the ordinary font, first in every list. */
	public static final String DEFAULT = "";

	private Fonts() { }

	/**
	 * Every font that is loaded, by name, with the ordinary one first.
	 *
	 * Read afresh each time the list is opened. That is a handful of map keys, and
	 * remembering it would mean a font added by a pack does not appear until the
	 * game is restarted — which is the one workflow this exists for.
	 */
	/**
	 * What was found last time, so the panel may ask on every frame.
	 *
	 * It does ask on every frame — the count is drawn beside the dropdown — and a
	 * map walked and sorted sixty times a second to answer a question whose answer
	 * changes only when resources reload is work for nothing. Forgotten when the
	 * list is opened, which is the moment somebody might have added a pack.
	 */
	private static List<String> found;

	public static void forget() {
		found = null;
	}

	public static List<String> names() {
		if (found != null) return found;
		List<String> named = new ArrayList<>();
		named.add(DEFAULT);
		try {
			var manager = ((MinecraftFontsAccess) Minecraft.getInstance()).npcStudio$fontManager();
			if (manager instanceof FontManagerAccess reach) {
				List<String> loaded = new ArrayList<>();
				for (Identifier id : reach.npcStudio$fontSets().keySet()) loaded.add(id.toString());
				loaded.sort(String::compareToIgnoreCase);
				named.addAll(loaded);
			}
		} catch (RuntimeException | LinkageError notThere) {
			// A version that has moved the field leaves the ordinary font and nothing
			// else, which is a shorter list rather than a broken panel.
			com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
				"Could not read the loaded fonts: {}", notThere.toString());
		}
		// Once, and with the names in it. "The fonts do not work" is one sentence for
		// two completely different faults — nothing found, or found and not drawn —
		// and a line naming what was found settles which without anybody guessing.
		if (!said) {
			said = true;
			com.mopicmp.npcstudio.NpcStudio.LOGGER.info("Fonts available for captions: {}",
				named.size() == 1 ? "none but the default" : named.subList(1, named.size()));
		}
		found = List.copyOf(named);
		return found;
	}

	private static boolean said;

	/** Whether a font is one the game actually has, so a panel can say when it is not. */
	public static boolean loaded(String font) {
		return font == null || font.isEmpty() || names().contains(font);
	}

	/** What to call a font in a list, where the empty name has no name of its own. */
	public static String shown(String font) {
		if (font == null || font.isEmpty()) return "обычный";
		// The namespace is worth keeping only when it is not Minecraft's own: a list
		// reading "minecraft:alt, minecraft:uniform, minecraft:default" is three
		// copies of one word and the part that differs pushed to the right.
		return font.startsWith("minecraft:") ? font.substring("minecraft:".length()) : font;
	}
}
