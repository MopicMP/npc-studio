package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;

/**
 * The way to the font manager, which the game keeps to itself.
 *
 * A second file rather than a second method on the mixin that already touches
 * {@code Minecraft}, because that one is a class mixin and this is an interface
 * one — and a mixin config that mixes the two kinds in a single class is refused
 * at load time rather than at compile time. The same lesson the sound accessors
 * taught, in the same shape.
 */
@Mixin(Minecraft.class)
public interface MinecraftFontsAccess {

	@Accessor("fontManager")
	FontManager npcStudio$fontManager();
}
