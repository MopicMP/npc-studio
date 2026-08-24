package com.mopicmp.npcstudio.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
	@Inject(at = @At("HEAD"), method = "loadLevel")
	private void init(CallbackInfo info) {
		// A world is being loaded, so whatever was banging about in the last one is
		// over. Static state outlives the world it belonged to — a noise remembered
		// from a save somebody has left would have a character in the next one
		// turning to look at a place that means nothing.
		com.mopicmp.npcstudio.foe.Din.forget();
	}
}