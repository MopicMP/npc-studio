package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.audio.Library;

import net.minecraft.client.sounds.SoundEngine;

/**
 * The game's own connection to the sound card.
 *
 * <h2>Why reach in rather than open our own</h2>
 *
 * Because there is one sound card and one connection to it. A second
 * {@code Library} would open a second OpenAL device — which works on some
 * machines, is silent on others, and on the rest gives you two listeners in one
 * head, so a scene's music would not fall quiet when the game does.
 *
 * There is nothing to invent: the game has a device, it has a pool of free
 * channels, and it lends one out to anybody who asks. This is the asking.
 */
@Mixin(SoundEngine.class)
public interface SoundDeviceAccess {

	@Accessor("library")
	Library npcStudio$library();
}
