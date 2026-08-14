package com.mopicmp.npcstudio.client.mixin;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mopicmp.npcstudio.client.emote.Emote;
import com.mopicmp.npcstudio.client.emote.EmoteLibrary;
import com.mopicmp.npcstudio.client.entity.GestureHolder;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Puts a held item where the emote wants it.
 *
 * The pack has channels for this — {@code rightItem} and {@code leftItem}, with
 * sixteen thousand keyframes between them — and until now they were read and
 * thrown away. An item followed the hand and nothing more, so a character
 * running with a sword held it like a bunch of flowers: right place, wrong
 * angle entirely.
 *
 * This is where the game has already moved to the hand and not yet drawn
 * anything, which is exactly the seam an animator is describing when they
 * position an item: everything here is relative to the fist holding it.
 */
@Mixin(PlayerModel.class)
public abstract class HeldItemMixin {

	/** Item offsets are model pixels, and a pose stack works in blocks. */
	private static final float PIXEL = 1 / 16f;

	/** The same bound as the limbs, and for the same emotes — see {@code EmoteApplier}. */
	private static final float LIMIT = 64f;

	@Inject(method = "translateToHand(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At("TAIL"))
	private void npcStudio$placeItem(AvatarRenderState state, HumanoidArm arm,
			PoseStack pose, CallbackInfo info) {
		// Off unless asked for, and that is an admission rather than caution. Where
		// exactly these numbers are measured from is not something the pack settles:
		// the game keeps moving after this point — a quarter turn, a half turn and a
		// shift — before it reaches the hand, and an offset means a different thing
		// on either side of that. Everything else about these animations was worked
		// out by measuring the pack; this one part was guessed, and it shows.
		//
		// So it is a switch, and it starts off. Vanilla's grip is wrong for some
		// poses, but it is wrong in a way people already know.
		if (!com.mopicmp.npcstudio.client.NpcStudioConfig.get().useItemChannels) return;
		if (!(state instanceof GestureHolder holder)) return;
		String name = holder.npcStudio$gesture();
		if (name.isEmpty()) return;

		EmoteLibrary.emote(name).ifPresent(emote -> {
			Emote.Bone bone = arm == HumanoidArm.RIGHT ? Emote.Bone.RIGHT_ITEM : Emote.Bone.LEFT_ITEM;
			Emote.Pose at = emote.poseAt(emote.timeAt(holder.npcStudio$gestureAge()));
			if (!at.has(bone)) return;

			pose.translate(
				clamp(at.or(bone, Emote.Channel.X, 0)) * PIXEL,
				clamp(at.or(bone, Emote.Channel.Y, 0)) * PIXEL,
				clamp(at.or(bone, Emote.Channel.Z, 0)) * PIXEL);
			pose.mulPose(new Quaternionf()
				.rotateZ(at.or(bone, Emote.Channel.ROLL, 0))
				.rotateY(at.or(bone, Emote.Channel.YAW, 0))
				.rotateX(at.or(bone, Emote.Channel.PITCH, 0)));
		});
	}

	private static float clamp(float pixels) {
		return Math.clamp(pixels, -LIMIT, LIMIT);
	}
}
