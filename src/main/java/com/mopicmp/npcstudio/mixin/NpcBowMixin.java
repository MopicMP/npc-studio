package com.mopicmp.npcstudio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.foe.Firing;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Lets a bow be fired by one of ours.
 *
 * <h2>Why the patch is here and not at the call site</h2>
 *
 * Because then the call site stays honest. An NPC firing a bow does exactly what
 * a player does — starts using the item, waits, releases it — and every other
 * item in the game, including every modded one, goes down that same path
 * untouched. If we had instead taught the NPC "bows are special, shoot them
 * yourself", we would have written the very thing we are trying not to write:
 * a character who mimes the item rather than using it, and who therefore knows
 * nothing about any item nobody told her about.
 *
 * The gap is in the bow, so the patch is in the bow. What it restores is what
 * vanilla does for a player, minus the two lines that need one — see
 * {@link Firing}.
 *
 * The crossbow has the same gate and is not patched here: it is charged and held
 * rather than drawn and loosed, which is a different piece of work with its own
 * loaded state to keep. Until then a crossbow in our hands will draw and fire
 * nothing, which is at least visible.
 */
@Mixin(BowItem.class)
public class NpcBowMixin {

	@Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
	private void npcStudio$looseForNpc(ItemStack stack, Level level, LivingEntity shooter,
			int left, CallbackInfoReturnable<Boolean> cir) {
		if (!(shooter instanceof NpcEntity)) return;
		cir.setReturnValue(Firing.fromBow(shooter, (BowItem) (Object) this, stack, left));
	}
}
