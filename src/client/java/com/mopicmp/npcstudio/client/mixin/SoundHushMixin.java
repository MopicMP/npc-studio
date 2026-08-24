package com.mopicmp.npcstudio.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.mopicmp.npcstudio.client.scene.Hush;

import net.minecraft.client.sounds.SoundEngine;

/**
 * Holds the world quiet while a scene is being scored.
 *
 * <h2>Where this is hooked, and why not somewhere simpler</h2>
 *
 * At the one place every sound's loudness is worked out. Refusing to play sounds
 * instead would be the obvious alternative and is worse in two ways: a sound
 * refused is a sound that never starts again when the workspace closes, and a
 * long one already running — a river, a jukebox — would carry on regardless
 * because it was started before anybody asked for quiet.
 *
 * A volume of nought has neither problem. Everything goes on being played,
 * tracked and stopped exactly as it was; it is merely inaudible, and the moment
 * the categories are refreshed it is audible again. Nothing is saved and there is
 * nothing to put back — which matters, because the alternative anybody reaches
 * for first is writing zeroes into the person's own volume sliders, and that
 * survives a crash.
 *
 * <h2>What is deliberately not silenced</h2>
 *
 * The scene's own music. It never passes through here: a file in the mod's folder
 * is decoded and played on a channel taken straight from the device, so the one
 * thing left audible is the thing being worked on.
 */
@Mixin(SoundEngine.class)
public abstract class SoundHushMixin {

	@ModifyReturnValue(
		method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F",
		at = @At("RETURN"), require = 0)
	private float npcStudio$hush(float loud) {
		return Hush.hushing() ? 0f : loud;
	}
}
