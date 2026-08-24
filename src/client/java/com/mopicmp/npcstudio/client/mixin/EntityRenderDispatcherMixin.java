package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

/**
 * Keeps the player out of their own scene.
 *
 * The camera has to be detached to fly around, and a detached camera is a third
 * person camera, so the game draws the body — standing wherever it was left,
 * in the middle of the shot, looking at nothing. That is not a character in the
 * scene, it is the person building it, and it does not belong in the picture.
 *
 * Refusing to render is the honest way to say so. Invisibility would have been
 * fewer lines and would have been wrong twice: it is a property the server owns
 * and resends, so it would flicker back, and armour keeps rendering through it.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void npcStudio$hidePlayer(E entity, Frustum frustum,
			double cameraX, double cameraY, double cameraZ, CallbackInfoReturnable<Boolean> answer) {
		if (Workspace.showPlayer()) return;
		// Either window. The modelling mode has the same detached camera and the
		// same body left standing in the shot, so the same answer applies; it simply
		// was not asked, and a body in the middle of a model is worse than one in
		// the middle of a scene because it is usually inside the model.
		if (!WorkspaceScreen.embedded()
			&& !com.mopicmp.npcstudio.client.model.ModellingScreen.showing()) {
			return;
		}
		if (entity != Minecraft.getInstance().player) return;
		answer.setReturnValue(false);
	}
}
