package com.mopicmp.npcstudio.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.mopicmp.npcstudio.client.scene.Weather;
import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.world.level.Level;

/**
 * Holds the rain and the storm where a scene put them.
 *
 * <h2>Why the two numbers rather than the renderer</h2>
 *
 * Because everything downstream already asks these. The weather renderer builds
 * its columns from the rain level, the sky dims by it, the sound plays by it, the
 * puddles on the shader's ground darken by it. Overriding the renderer would have
 * meant finding each of those and overriding it too, and missing one is a scene
 * where it rains in silence or rains out of a bright sky.
 *
 * <h2>Why it cannot run out</h2>
 *
 * Because there is nothing running. Vanilla weather is a countdown started by a
 * command; this is an answer given afresh every time anybody asks, for as long as
 * the scene is open. A take that runs four minutes ends in exactly the rain it
 * started in.
 *
 * <h2>The client only, deliberately</h2>
 *
 * The world underneath goes on with its own weather, so closing the scene puts
 * the sky back with nothing to undo and two people editing two scenes on one
 * server do not fight. What it costs is that this is a picture and not the
 * weather: nothing gets wet, and somebody standing beside you without the scene
 * open sees their own sky.
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {

	@ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"), require = 0)
	private float npcStudio$sceneRain(float already) {
		if (!npcStudio$mine()) return already;
		return Weather.says(Channels.RAIN) ? Weather.rain(already) : already;
	}

	@ModifyReturnValue(method = "getThunderLevel", at = @At("RETURN"), require = 0)
	private float npcStudio$sceneStorm(float already) {
		if (!npcStudio$mine()) return already;
		return Weather.says(Channels.STORM) ? Weather.storm(already) : already;
	}

	/**
	 * Whether this is the level being looked at.
	 *
	 * A {@code Level} is both sides of the game, and on a single-player world the
	 * server's copy is in the same process. Answering for that one would be a scene
	 * quietly changing the real weather for everybody, which is the one thing this
	 * was written not to do.
	 */
	private boolean npcStudio$mine() {
		Level self = (Level) (Object) this;
		if (!self.isClientSide()) return false;
		return net.minecraft.client.Minecraft.getInstance().level == self && Weather.showing();
	}
}
