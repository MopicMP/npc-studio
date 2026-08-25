package com.mopicmp.npcstudio.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * The two halves of firing a ranged weapon, opened up.
 *
 * <h2>Why these two and nothing else</h2>
 *
 * Everything underneath a bow shot already works for anybody: {@code draw} and
 * {@code shoot} are declared against {@code LivingEntity}, and checked against
 * the 26.2 jar they contain no mention of a player. They spend the ammunition,
 * read the enchantments, build the projectile from the item that fires it, spawn
 * it where the item says, and wear the weapon down.
 *
 * The only part that insists on a player is the layer above — {@code releaseUsing}
 * on the bow itself, which needs a player to search their inventory for arrows
 * and to award them a statistic. That is the layer we replace, and these are the
 * two calls it makes.
 *
 * They are {@code protected}, hence this. Making them reachable is cheaper than
 * writing our own arrow-maker, and honest in a way that writing our own is not:
 * whatever the item would have made is what gets made.
 */
@Mixin(ProjectileWeaponItem.class)
public interface ProjectileWeaponAccess {

	@Invoker("shoot")
	void npcStudio$shoot(ServerLevel level, LivingEntity shooter, InteractionHand hand,
		ItemStack weapon, List<ItemStack> ammo, float velocity, float inaccuracy,
		boolean crit, LivingEntity at);

	@Invoker("draw")
	static List<ItemStack> npcStudio$draw(ItemStack weapon, ItemStack ammo, LivingEntity shooter) {
		throw new AssertionError("replaced by the mixin");
	}
}
