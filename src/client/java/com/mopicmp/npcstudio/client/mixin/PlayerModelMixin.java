package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mopicmp.npcstudio.client.entity.GestureHolder;
import com.mopicmp.npcstudio.client.entity.NpcGestures;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Applies an NPC's gesture after the model has posed itself.
 *
 * At the tail, so walking, swimming and everything else vanilla does still
 * happens and the gesture is laid over the top rather than instead of it — an
 * NPC that waves while walking should do both.
 *
 * Players are untouched: the state only carries a gesture if our renderer put
 * one there, and it never does for a player.
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void npcStudio$gesture(AvatarRenderState state, CallbackInfo info) {
		PlayerModel model = (PlayerModel) (Object) this;

		// Unfolded first, for everyone. The game hands the same model to every
		// character wearing it, one after another, and vanilla's own reset does not
		// know about elbows — so an NPC that finished mid-fold would leave its bent
		// arm on the next player drawn.
		com.mopicmp.npcstudio.client.emote.EmoteApplier.rest(model);

		if (!(state instanceof GestureHolder holder)) return;

		// The eyes are handed to the head whether or not there is a gesture: a
		// character blinks while standing still, which is most of the time.
		((com.mopicmp.npcstudio.client.emote.BendablePart) (Object) model.head)
			.npcStudio$setEyes(holder.npcStudio$eyes());

		String name = holder.npcStudio$gesture();
		if (!name.isEmpty()) {
			NpcGestures.apply(model, name, holder.npcStudio$gestureAge(),
				holder.npcStudio$gestureStrength());
		}

		// Last, and outside the check above. An emote writes absolute positions for
		// every limb it mentions, so a build applied first would simply be painted
		// over — the character would stand broad-shouldered and snap back to
		// ordinary the moment it waved.
		com.mopicmp.npcstudio.client.entity.BodyBuilder.apply(model, holder.npcStudio$shape());
	}
}
