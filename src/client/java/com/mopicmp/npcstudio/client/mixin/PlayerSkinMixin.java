package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Everybody on this map wears what the map says.
 *
 * <h2>Why here and not in the renderer</h2>
 *
 * Because {@code getSkin} is the one question, and everything that draws a player
 * asks it: the body, the face beside a line, the tab list, the inventory doll. Hung
 * on the renderer instead, a map would dress the figure in the world and leave the
 * old face in every other place it appears — which is worse than not doing it at
 * all, because it looks like two different people.
 *
 * <h2>Why every player and not only the one holding the keyboard</h2>
 *
 * A map that dresses one of four people in a scene has not dressed anybody. This is
 * a fact about the map, so it is true of everyone standing in it.
 *
 * <h2>What happens when the picture has not arrived</h2>
 *
 * Nothing: the call falls through and the player is their ordinary self for a
 * moment. That is the right failure — a face, briefly the wrong one, rather than a
 * missing texture — and it lasts exactly as long as one request over the wire.
 */
@Mixin(AbstractClientPlayer.class)
public class PlayerSkinMixin {

	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void npcStudio$wearWhatTheMapSays(CallbackInfoReturnable<PlayerSkin> back) {
		PlayerSkin worn = com.mopicmp.npcstudio.client.map.WornHere.skin();
		if (worn != null) back.setReturnValue(worn);
	}
}
