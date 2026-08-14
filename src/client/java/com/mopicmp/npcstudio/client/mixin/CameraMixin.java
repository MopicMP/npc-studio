package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mopicmp.npcstudio.client.dialogue.DialogueCamera;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;

/**
 * Lets a cutscene take the camera off the player.
 *
 * There is no other way in. The camera is positioned from the entity it is
 * attached to, every frame, with no hook to say "not this time" — so the only
 * honest option is to let it finish and then move it.
 *
 * At the tail rather than the head: everything the game wants to do to the
 * camera has happened by then, including the bits that depend on the player's
 * pose, so overriding here cannot be undone further down the same call.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow protected abstract void setPosition(Vec3 position);

	@Shadow protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void npcStudio$cutscene(DeltaTracker delta, CallbackInfo info) {
		DialogueCamera.Shot shot = DialogueCamera.shot(delta.getGameTimeDeltaPartialTick(true));
		if (shot == null) return;
		setPosition(shot.position());
		setRotation(shot.yaw(), shot.pitch());
	}
}
