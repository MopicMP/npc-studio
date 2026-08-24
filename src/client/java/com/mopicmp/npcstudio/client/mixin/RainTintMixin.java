package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.mopicmp.npcstudio.client.scene.Tinted;
import com.mopicmp.npcstudio.client.scene.Weather;

import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.resources.Identifier;

/**
 * The rain, in whatever colour the scene asked for.
 *
 * <h2>Why the picture and not the drawing</h2>
 *
 * Because the drawing takes no colour. Rain binds its texture and draws it; there
 * is no tint anywhere in that to change, unlike the sun, which is handed a colour
 * on its way past. The one place a colour still exists is the picture itself.
 *
 * So the renderer is handed a different name at the single line where it asks for
 * one, and behind that name is the game's own rain multiplied through — see
 * {@link Tinted}. Nothing about how rain is drawn changes, which is the property
 * worth having: it goes on working when the drawing changes, and a scene that
 * asks for no colour is handed the game's own texture and costs nothing at all.
 *
 * Both are caught, rain and snow, because both go through this line and a scene
 * that turned the rain green over a snowy hill would otherwise be half green.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class RainTintMixin {

	@ModifyArg(
		method = "render",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/TextureManager;getTexture(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/AbstractTexture;"),
		require = 0)
	private Identifier npcStudio$rainColour(Identifier which) {
		if (!Weather.showing()) return which;
		return Tinted.of(which, Weather.rainColour(0xFFFFFF));
	}
}
