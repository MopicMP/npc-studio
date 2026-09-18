package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mopicmp.npcstudio.client.map.StartOptions;

import net.minecraft.client.OptionInstance;

/**
 * Lets a map refuse a change to a setting it is holding.
 *
 * <h2>Why here and not at the slider</h2>
 *
 * Because this is the one place every change passes through — the video settings
 * slider, the key that steps the field of view, the options file being loaded.
 * A guard on the slider is a guard on one of those, and the other two are the
 * ones somebody would find.
 *
 * It is also the difference between a refusal and a fight. Putting the value back
 * every tick would leave a slider that jumps out from under the mouse; cancelling
 * at the source means the value never moved, so there is nothing to put back and
 * nothing to see moving. {@link StartOptions} says why nothing happened, because
 * a control that silently does nothing is indistinguishable from a broken game.
 *
 * The generic {@code set(T)} erases to {@code set(Object)}, which is why the
 * parameter is typed the way it is.
 */
@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin {

	@Inject(method = "set", at = @At("HEAD"), cancellable = true)
	private void npcStudio$refuseHeld(Object value, CallbackInfo info) {
		if (StartOptions.refuses((OptionInstance<?>) (Object) this, value)) {
			info.cancel();
		}
	}
}
