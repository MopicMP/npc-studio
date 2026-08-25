package com.mopicmp.npcstudio.foe;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;

/**
 * What sort of thing is in her hand, asked of the item rather than of a list.
 *
 * <h2>By how it is used, not by what it is</h2>
 *
 * There is no list of weapons here and there never will be. {@code SwordItem}
 * stopped existing in 26.2 and weapons became components, which is the game
 * agreeing with the principle: an item says how it behaves, and anything that
 * says the same thing behaves the same way.
 *
 * So a modded rifle lands in the right box on its own, because that is how it
 * declared itself, and neither we nor its author had to know about the other.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * Decide anything. Whether to shoot, close in or run is the graph's business —
 * this only answers what she is holding, and it answers it as a reading a graph
 * can branch on. An item nobody can place is {@link Kind#OTHER} rather than a
 * guess, because a wrong confident answer costs more than an honest shrug: the
 * author can teach the rest with blocks, and cannot unteach us.
 */
public final class Arms {

	private Arms() { }

	/** How a thing in a hand is worked. */
	public enum Kind {
		/** Nothing in it. */
		NOTHING,
		/** Swing it at somebody within reach. */
		MELEE,
		/** Hold it back and let go: a bow, a trident. */
		DRAWN,
		/** Load it once and then it goes off at a touch: a crossbow. */
		LOADED,
		/**
		 * Looks like a melee weapon and is not.
		 *
		 * Fires by being swung, through an enchantment hung on hitting. This is the
		 * shape guns++ uses and it would have been missed entirely if a real
		 * datapack had not been unpacked: a swing at nothing, aimed at somebody
		 * forty blocks away.
		 */
		SWUNG,
		/** Held up to stop things. */
		SHIELD,
		/** Thrown, and therefore travelling along a curve rather than a line. */
		THROWN,
		/** Held, and none of the above. */
		OTHER
	}

	/**
	 * What an item said about itself, gathered in one place.
	 *
	 * Separated from the reading so that the <em>order</em> the signs are weighed
	 * in can be tested without a running game — and the order is the whole of the
	 * difficulty. Several of these are true at once on ordinary items, and which
	 * one wins decides whether a gun is a gun or a sword.
	 */
	public record Signs(boolean empty, boolean swung, boolean loaded, boolean drawn,
			boolean shield, boolean melee, boolean thrown) { }

	/**
	 * Weighs the signs.
	 *
	 * <h2>The order, and the one line of it that matters</h2>
	 *
	 * {@link Kind#SWUNG} is tested before {@link Kind#MELEE}, and that is not a
	 * preference. A guns++ rifle is a carrot on a stick carrying
	 * {@code piercing_weapon={}} <em>and</em> the enchantment that fires it. Asked
	 * the other way round it is a melee weapon, and a character holding a rifle
	 * would walk up to you and hit you with it — which is exactly the failure
	 * every other mod has, arrived at from a new direction.
	 *
	 * The ranged kinds come before the melee ones for the same reason: a trident
	 * carries a weapon component too, and a spear is not a club.
	 */
	public static Kind kindOf(Signs signs) {
		if (signs.empty()) return Kind.NOTHING;
		if (signs.swung()) return Kind.SWUNG;
		if (signs.loaded()) return Kind.LOADED;
		if (signs.drawn()) return Kind.DRAWN;
		if (signs.shield()) return Kind.SHIELD;
		if (signs.melee()) return Kind.MELEE;
		if (signs.thrown()) return Kind.THROWN;
		return Kind.OTHER;
	}

	/** Reads the signs off a real item. */
	public static Signs signsOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return new Signs(true, false, false, false, false, false, false);
		}
		ItemUseAnimation how = stack.getUseAnimation();
		return new Signs(false,
			firesOnHitting(stack),
			stack.has(DataComponents.CHARGED_PROJECTILES) || how == ItemUseAnimation.CROSSBOW,
			how == ItemUseAnimation.BOW || how == ItemUseAnimation.TRIDENT
				|| how == ItemUseAnimation.SPEAR,
			stack.has(DataComponents.BLOCKS_ATTACKS),
			stack.has(DataComponents.WEAPON) || stack.has(DataComponents.PIERCING_WEAPON)
				|| stack.has(DataComponents.KINETIC_WEAPON),
			stack.getItem() instanceof ProjectileItem);
	}

	public static Kind of(ItemStack stack) {
		return kindOf(signsOf(stack));
	}

	/**
	 * Whether hitting with this thing sets something else off.
	 *
	 * The signal is an enchantment that hangs an effect on the attack landing.
	 * That is a strange way to build a gun and it is what a datapack has to do,
	 * having no code of its own — and since datapacks are the only weapon mods
	 * that will ever run on every loader at once, it is worth reading properly.
	 */
	private static boolean firesOnHitting(ItemStack stack) {
		var enchantments = stack.get(DataComponents.ENCHANTMENTS);
		if (enchantments == null || enchantments.isEmpty()) return false;
		for (var held : enchantments.keySet()) {
			var effects = held.value().effects();
			if (effects.has(EnchantmentEffectComponents.POST_ATTACK)
					|| effects.has(EnchantmentEffectComponents.POST_PIERCING_ATTACK)) {
				return true;
			}
		}
		return false;
	}
}
