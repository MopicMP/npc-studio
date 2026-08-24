package com.mopicmp.npcstudio.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place that happens exactly once per drawn frame.
 *
 * A tick will not do for a capture. Ticks come twenty a second whatever the
 * machine is doing, and a frame is what the capture is counting — the whole
 * point of it is that the scene advances because a frame was written rather than
 * because time passed.
 *
 * At the tail rather than the head, so the frame being written is the one that
 * has just been drawn instead of the one about to be.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

	@Inject(at = @At("TAIL"), method = "runTick", require = 0)
	private void npcStudio$frame(boolean render, CallbackInfo info) {
		com.mopicmp.npcstudio.client.scene.Capture.frame();
	}
}