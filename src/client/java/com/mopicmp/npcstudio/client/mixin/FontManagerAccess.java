package com.mopicmp.npcstudio.client.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;

/**
 * Which typefaces are actually loaded.
 *
 * There is no public way to ask. The game needs no list because everything that
 * uses a font names one it already knows about; a panel offering a choice is the
 * one case where the set itself is the question, and the set lives in a private
 * map.
 *
 * Worth reaching for rather than writing down the four vanilla names, because the
 * interesting ones are not vanilla: a resource pack may add a typeface, and a
 * scene shot under that pack should be able to use it. A hardcoded list would
 * offer exactly the fonts nobody chose a pack for.
 */
@Mixin(FontManager.class)
public interface FontManagerAccess {

	@Accessor("fontSets")
	Map<Identifier, FontSet> npcStudio$fontSets();
}
