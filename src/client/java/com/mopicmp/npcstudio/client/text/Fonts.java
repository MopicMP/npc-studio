package com.mopicmp.npcstudio.client.text;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.client.mixin.FontManagerAccess;
import com.mopicmp.npcstudio.client.mixin.FontSetAccess;
import com.mopicmp.npcstudio.client.mixin.MinecraftFontsAccess;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.resources.Identifier;

/**
 * The typefaces there are, and putting new ones in.
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
 * <h2>Why a resource pack is not how fonts get in</h2>
 *
 * Because a pack is a zip to build, a reload to sit through and a thing to hand
 * out, and none of that is needed. Every piece the game uses to make a typeface
 * out of a file is public — checked against the jar, not remembered:
 * {@code TrueTypeGlyphProvider} takes a {@link ByteBuffer}, {@code GlyphStitcher}
 * and {@code FontSet} both have public constructors, and {@code FontSet.reload}
 * is public. Exactly one thing is private, the map the game looks fonts up in,
 * and that is one accessor.
 *
 * So a typeface is a file dropped in {@link #folder()}, and it is there the next
 * time anything asks. No zip, no reload of every resource, nothing to install.
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
	 * How large the glyphs are baked, and how much sharper than that.
	 *
	 * These two numbers have no right answer to be looked up: the game ships no
	 * typeface of this kind, so there is nothing of Mojang's to copy. Eleven is
	 * what resource packs that do this settle on; the oversampling is higher than
	 * they use because text here can be asked to be four times its size, and
	 * anything baked at exactly its size goes soft the moment it is enlarged.
	 *
	 * Written as named constants rather than buried in the call because they are
	 * the two numbers to turn when somebody looks at the result and says it is
	 * fuzzy, and that has not happened yet — nothing here has been seen by eye.
	 */
	private static final float SIZE = 11f;
	private static final float SHARPNESS = 4f;

	/** Where a typeface is dropped to become available. */
	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("npc_studio").resolve("fonts");
	}

	// ------------------------------------------------------------- the list

	/**
	 * What was found last time, so a panel may ask on every frame.
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
			Map<Identifier, FontSet> sets = sets();
			if (sets != null) {
				List<String> loaded = new ArrayList<>();
				for (Identifier id : sets.keySet()) loaded.add(id.toString());
				loaded.sort(String::compareToIgnoreCase);
				named.addAll(loaded);
			}
		} catch (RuntimeException | LinkageError notThere) {
			// A version that has moved the field leaves the ordinary font and nothing
			// else, which is a shorter list rather than a broken panel.
			NpcStudio.LOGGER.warn("Could not read the loaded fonts: {}", notThere.toString());
		}
		// Once, and with the names in it. "The fonts do not work" is one sentence for
		// two completely different faults — nothing found, or found and not drawn —
		// and a line naming what was found settles which without anybody guessing.
		if (!said) {
			said = true;
			NpcStudio.LOGGER.info("Fonts available: {}",
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
		return font.startsWith("minecraft:") ? font.substring("minecraft:".length())
			: font.startsWith(NpcStudio.MOD_ID + ":")
				? font.substring(NpcStudio.MOD_ID.length() + 1) : font;
	}

	// -------------------------------------------------------- putting them in

	/** One typeface we made, so it can be put back and taken apart. */
	private record Made(Identifier id, Path file, FontSet set, GlyphProvider provider) { }

	private static final Map<String, Made> ours = new LinkedHashMap<>();

	/**
	 * Puts our typefaces back if something took them out, and picks up new files.
	 *
	 * Called from the client tick, which sounds wasteful and is one map lookup. The
	 * alternative was a resource-reload listener, and it is the wrong tool twice
	 * over: the order listeners run in is not ours to choose, so one registered
	 * beside the font manager's own may run before it and put fonts into a map
	 * about to be replaced; and a font can also go missing without a reload at all.
	 * Asking "are they still there" cannot be wrong about either.
	 */
	public static void ensure() {
		Map<Identifier, FontSet> sets = sets();
		if (sets == null) return;

		boolean missing = false;
		for (Made made : ours.values()) {
			if (sets.get(made.id()) != made.set()) missing = true;
		}
		if (!missing && !ours.isEmpty()) return;
		if (ours.isEmpty() && !scanned) {
			scanned = true;
			scan();
			return;
		}
		if (missing) {
			// Rebuilt rather than put back. A reload has thrown away the glyph textures
			// these were baked into, so the FontSet is a shell that would draw nothing —
			// which is worse than being absent, because being absent falls back.
			List<Path> files = new ArrayList<>();
			for (Made made : ours.values()) files.add(made.file());
			forgetOurs();
			for (Path file : files) install(file);
			forget();
		}
	}

	private static boolean scanned;

	/** Reads the folder again, for when somebody has just put a file in it. */
	public static void scan() {
		Path folder = folder();
		try {
			Files.createDirectories(folder);
			try (var files = Files.list(folder)) {
				for (Path file : files.sorted().toList()) {
					if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf")) {
						install(file);
					}
				}
			}
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read the fonts folder: {}", failed.toString());
		}
		forget();
	}

	/**
	 * Makes a typeface out of a file and puts it where the game looks.
	 *
	 * @return the name it can be asked for by, or null when the file was not one
	 */
	public static String install(Path file) {
		String name = nameOf(file);
		Identifier id = Identifier.tryBuild(NpcStudio.MOD_ID, name);
		if (id == null) {
			NpcStudio.LOGGER.warn("{} cannot be a font name", file.getFileName());
			return null;
		}

		Map<Identifier, FontSet> sets = sets();
		if (sets == null) return null;

		ByteBuffer memory = null;
		try {
			// The game's own reader, and it has to be: what comes back is off-heap, and
			// the provider frees it with memFree when it is closed. A buffer allocated
			// any other way would be freed by a call that does not own it.
			try (InputStream stream = new BufferedInputStream(Files.newInputStream(file))) {
				memory = TextureUtil.readResource(stream);
			}
			memory.flip();

			GlyphProvider provider = faceOf(memory);
			FontSet set = new FontSet(new GlyphStitcher(
				Minecraft.getInstance().getTextureManager(), id));

			List<GlyphProvider.Conditional> stack = new ArrayList<>();
			stack.add(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS));
			stack.addAll(behind(sets));
			set.reload(stack, Set.of());

			sets.put(id, set);
			ours.put(name, new Made(id, file, set, provider));
			forget();
			return id.toString();
		} catch (Exception | LinkageError failed) {
			// The buffer is only ours until the provider takes it; if we never got that
			// far, nobody else will free it.
			if (memory != null) org.lwjgl.system.MemoryUtil.memFree(memory);
			NpcStudio.LOGGER.warn("Could not read the font {}: {}",
				file.getFileName(), failed.toString());
			return null;
		}
	}

	/**
	 * The ordinary font's own providers, to sit under ours.
	 *
	 * A typeface with only Latin in it has nothing to draw a Russian sentence with,
	 * and a glyph nobody has is a box. Falling through to the game's font is mixed
	 * and imperfect and much better than a row of boxes — and it is the answer this
	 * mod already promised for the case, so it is done here rather than left for
	 * somebody to notice.
	 */
	private static List<GlyphProvider.Conditional> behind(Map<Identifier, FontSet> sets) {
		FontSet ordinary = sets.get(Identifier.withDefaultNamespace("default"));
		if (!(ordinary instanceof FontSetAccess reach)) return List.of();
		try {
			return List.copyOf(reach.npcStudio$allProviders());
		} catch (RuntimeException | LinkageError moved) {
			return List.of();
		}
	}

	/**
	 * Opens the file with FreeType, exactly as the game opens one from a pack.
	 *
	 * Under the same lock, because FreeType's library handle is one object shared
	 * by everything and the game takes that lock every time it touches it.
	 */
	private static GlyphProvider faceOf(ByteBuffer memory) throws Exception {
		synchronized (FreeTypeUtil.LIBRARY_LOCK) {
			FT_Face face;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer pointer = stack.mallocPointer(1);
				FreeTypeUtil.assertError(
					FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), memory, 0, pointer),
					"Initializing font face");
				face = FT_Face.create(pointer.get());
			}
			String format = FreeType.FT_Get_Font_Format(face);
			if (!"TrueType".equals(format)) {
				throw new java.io.IOException("expected a TrueType font, got " + format);
			}
			FreeTypeUtil.assertError(
				FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE),
				"Find unicode charmap");
			return new TrueTypeGlyphProvider(memory, face, SIZE, SHARPNESS, 0f, 0f, "");
		}
	}

	private static void forgetOurs() {
		for (Made made : ours.values()) {
			try {
				made.set().close();
				made.provider().close();
			} catch (Exception ignored) {
				// A texture that will not let go is not worth taking the window down for.
			}
		}
		ours.clear();
	}

	/** A file name turned into something that may be part of an identifier. */
	private static String nameOf(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".ttf")) name = name.substring(0, name.length() - 4);
		return name.replaceAll("[^a-z0-9_.-]+", "_");
	}

	private static Map<Identifier, FontSet> sets() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) return null;
		FontManager manager = ((MinecraftFontsAccess) client).npcStudio$fontManager();
		return manager instanceof FontManagerAccess reach ? reach.npcStudio$fontSets() : null;
	}
}
