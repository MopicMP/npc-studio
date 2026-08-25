package com.mopicmp.npcstudio.foe;

import java.util.List;

import com.mopicmp.npcstudio.mixin.ProjectileWeaponAccess;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * Getting a shot out of a ranged weapon held by somebody who is not a player.
 *
 * <h2>What was actually in the way</h2>
 *
 * Not the animation, and not the projectile. Checked against the 26.2 jar, the
 * bow's own release begins:
 *
 * <pre>
 * public boolean releaseUsing(ItemStack stack, Level level, LivingEntity shooter, int left) {
 *     if (!(shooter instanceof Player player)) return false;
 * </pre>
 *
 * — and everything it goes on to call takes a {@code LivingEntity} and works.
 * The gate exists for two lines inside: finding the arrow in a player's
 * inventory, and awarding a player a statistic. So a bow in our hands drew
 * perfectly, sounded right, and fired nothing, which is very nearly the fault
 * every other mod has and arrived at from the opposite direction.
 *
 * This is that method with the player taken out of it: our own way of finding
 * the ammunition, and then the item's own {@code draw} and {@code shoot}, which
 * are what make the arrow.
 *
 * <h2>Where the arrow comes from</h2>
 *
 * From the item, still. {@code shoot} calls {@code createProjectile} on the
 * weapon and spawns it at the shooter's eye — which is the answer to "the arrow
 * came out of the chest", and it is the answer precisely because we did not
 * write it.
 */
public final class Firing {

	private Firing() { }

	/**
	 * The stack she will spend on the next shot, or empty if she has none.
	 *
	 * <h2>Why this is short and vanilla's is not</h2>
	 *
	 * Vanilla's version searches a player's thirty-six slots. Ours has two hands
	 * and no pockets yet, so it asks the game's own hand check and then the
	 * endless mark. When the character panel grows an inventory, the search goes
	 * in the middle, and nothing else here changes.
	 */
	public static ItemStack ammoFor(LivingEntity shooter, ProjectileWeaponItem weapon) {
		ItemStack held = ProjectileWeaponItem.getHeldProjectile(shooter,
			weapon.getSupportedHeldProjectiles());
		if (!held.isEmpty()) return held;
		// The same fallback a creative player gets, reached the same way: a character
		// marked as never running out has an arrow whenever one is needed. Vanilla's
		// own ammunition spending honours the mark as well, so nothing is taken from
		// her hands — one flag, and both halves agree.
		return shooter.hasInfiniteMaterials() ? new ItemStack(Items.ARROW) : ItemStack.EMPTY;
	}

	/**
	 * Looses a drawn bow.
	 *
	 * @param left what {@code releaseUsing} was handed: ticks of use remaining, so
	 *             the length of the draw is the item's whole duration less this
	 * @return whether a shot happened, so the bow can say no and be believed
	 */
	public static boolean fromBow(LivingEntity shooter, BowItem bow, ItemStack stack, int left) {
		ItemStack ammo = ammoFor(shooter, bow);
		if (ammo.isEmpty()) return false;

		int drawnFor = stack.getUseDuration(shooter) - left;
		float power = BowItem.getPowerForTime(drawnFor);
		// A tap on the trigger is not a shot. Vanilla's threshold, kept because a
		// character who fumbles arrows into the dirt at her feet is a character
		// somebody will report as broken.
		if (power < 0.1f) return false;

		List<ItemStack> drawn = ProjectileWeaponAccess.npcStudio$draw(stack, ammo, shooter);
		if (shooter.level() instanceof ServerLevel level && !drawn.isEmpty()) {
			((ProjectileWeaponAccess) bow).npcStudio$shoot(level, shooter,
				shooter.getUsedItemHand(), stack, drawn, power * 3f, 1f, power == 1f, null);
		}

		// Aloud, and as a player-made noise on purpose: a bowstring is exactly the
		// kind of thing the ears we already built are for, and an NPC firing near
		// another one ought to be the loudest news of her day.
		shooter.level().playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
			SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1f,
			1f / (shooter.getRandom().nextFloat() * 0.4f + 1.2f) + power * 0.5f);
		return true;
	}
}
