package com.mopicmp.npcstudio.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.font.GlyphProvider;

import net.minecraft.client.gui.font.FontSet;

/**
 * What a typeface is actually made of.
 *
 * Wanted for one thing: putting the ordinary font <em>behind</em> a loaded one.
 * A typeface with only Latin in it — which is most of the interesting ones — has
 * nothing to draw a Russian sentence with, and a glyph nobody has comes out as a
 * box. Reading the default font's own providers and appending them means the
 * letters it does not carry fall through to the game's, which is mixed and
 * imperfect and enormously better than a row of boxes.
 *
 * Sharing the provider objects is safe, and that was checked rather than
 * assumed: {@code FontSet.close()} closes its stitcher and nothing else, so a
 * font of ours being thrown away cannot take the game's own font down with it.
 */
@Mixin(FontSet.class)
public interface FontSetAccess {

	@Accessor("allProviders")
	List<GlyphProvider.Conditional> npcStudio$allProviders();
}
