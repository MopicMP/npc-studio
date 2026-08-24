package com.mopicmp.npcstudio.client.mixin;

import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mopicmp.npcstudio.client.scene.Filter;
import com.mopicmp.npcstudio.client.scene.Weather;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;

/**
 * The other half of the sky.
 *
 * <h2>What was wrong</h2>
 *
 * Setting the sky's colour changed the sky and left a pale band along the horizon
 * in the old one, which was reported exactly as it looked: the colour changes part
 * of the sky and not all of it. It is not one surface. The dome overhead is a flat
 * disc drawn in {@code skyColor}, and everything from the horizon down — the band,
 * the haze that distant water fades into, the wash over far terrain — is the fog,
 * computed somewhere else entirely from the biome and the time of day.
 *
 * <h2>Why the fog is filtered and the dome is painted</h2>
 *
 * Because the fog is carrying things the dome is not. It is what makes distance
 * readable, what goes dark at night and what turns grey in a storm; a colour
 * painted straight onto it would be a horizon exactly as bright at midnight as at
 * noon, in rain as in sun. So it takes the chosen colour's <em>shape</em> and
 * keeps its own brightness — see {@link Filter}, where the arithmetic and the
 * reason for it live together.
 *
 * <h2>Where it is hooked</h2>
 *
 * At the end of the one method that works the colour out, whose last act is to
 * write three numbers into the vector it was handed. Which means everything that
 * reads the fog afterwards — the shader uniform, the horizon, the far haze — reads
 * the tinted one, and there is no second place for the two to disagree.
 */
@Mixin(FogRenderer.class)
public abstract class FogTintMixin {

	@Inject(method = "computeFogColor", at = @At("TAIL"), require = 0)
	private void npcStudio$sceneFog(Camera camera, float partial, ClientLevel level,
			int renderDistance, float darkness, Vector4f into, CallbackInfo info) {
		float[] filter = Weather.skyFilter();
		if (!Filter.changes(filter)) return;
		into.set(into.x * filter[0], into.y * filter[1], into.z * filter[2], into.w);
	}
}
