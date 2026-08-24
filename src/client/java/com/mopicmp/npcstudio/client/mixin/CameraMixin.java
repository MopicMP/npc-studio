package com.mopicmp.npcstudio.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

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

	/**
	 * Where the scene's camera stands, worked out now rather than on the tick.
	 *
	 * At the head, and that is not arbitrary: the field of view is read partway
	 * through this same method, so a camera placed at the tail would be filmed with
	 * last frame's lens. Done whether or not we are looking through it, because the
	 * marker drawn over the world reads the same entity — and a marker a tick behind
	 * the shot is a marker that lies about the shot.
	 *
	 * See {@code SceneCameras.frame} for why a tick is not good enough.
	 */
	@Inject(method = "update", at = @At("HEAD"))
	private void npcStudio$placeSceneCamera(DeltaTracker delta, CallbackInfo info) {
		com.mopicmp.npcstudio.client.scene.SceneCameras.frame(
			delta.getGameTimeDeltaPartialTick(true));
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void npcStudio$cutscene(DeltaTracker delta, CallbackInfo info) {
		float partial = delta.getGameTimeDeltaPartialTick(true);

		// The scene's camera wins over both, and only while somebody has asked to
		// look through it. It is the one view that is the film rather than a way of
		// judging it, so nothing else may take it away while it is on.
		var sceneCamera = com.mopicmp.npcstudio.client.scene.SceneCameras.camera();
		if (com.mopicmp.npcstudio.client.scene.SceneCameras.through() && sceneCamera != null) {
			setPosition(sceneCamera.position());
			setRotation(sceneCamera.getYRot(), sceneCamera.getXRot());
			return;
		}

		// The workspace wins over a cutscene. Both want the camera, but only one of
		// them is somebody working: a scene that starts playing while a shot is
		// being framed must not take the view away from the person framing it.
		Vec3 orbiting = com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.position(partial);
		if (orbiting != null) {
			setPosition(orbiting);
			setRotation(com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.yaw(),
				com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.pitch());
			return;
		}

		DialogueCamera.Shot shot = DialogueCamera.shot(partial);
		if (shot == null) return;
		setPosition(shot.position());
		setRotation(shot.yaw(), shot.pitch());
	}

	/**
	 * How wide the shot is, when a scene's camera is the one taking it.
	 *
	 * <h2>Why here and not at the projection</h2>
	 *
	 * Because this is the one number the whole frame is built from, and it is read
	 * twice. {@code update} stores it and hands it to {@code setupPerspective},
	 * which is the picture; {@code getFov} hands the same field to everything that
	 * has to agree with the picture afterwards — our own projection of the handles
	 * among them, see {@code Gizmo.onScreen}. Changing the projection matrix alone
	 * would give a frame at one angle with handles drawn for another, and the two
	 * only come apart the moment somebody sets a camera to anything but seventy.
	 *
	 * A field of view is not a place, so this is not in the block above: the camera
	 * may be flown by hand while the scene's one is the subject, and only actually
	 * looking through it changes the lens.
	 */
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float npcStudio$sceneFov(float already) {
		var sceneCamera = com.mopicmp.npcstudio.client.scene.SceneCameras.camera();
		if (!com.mopicmp.npcstudio.client.scene.SceneCameras.through() || sceneCamera == null) {
			return already;
		}
		return sceneCamera.fov();
	}
}
