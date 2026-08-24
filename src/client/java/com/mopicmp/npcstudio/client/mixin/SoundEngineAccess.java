package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/**
 * The engine behind the sound manager.
 *
 * Half of the way to the game's own sound device; the other half is
 * {@link SoundDeviceAccess}. Two files rather than one with two interfaces in it,
 * because the config names mixins and a test checks that every name it gives is a
 * file — which is worth more than keeping two four-line interfaces together.
 */
@Mixin(SoundManager.class)
public interface SoundEngineAccess {

	@Accessor("soundEngine")
	SoundEngine npcStudio$engine();
}
