package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * What one particular stack of something remembers.
 *
 * <h2>The trap this is built around rather than into</h2>
 *
 * A stack has no identity that survives being handled: it copies, splits, merges, sits
 * in chests and burns in lava. So "this sword remembers" is a wish the game cannot grant
 * as stated, and a mod that pretended otherwise would give somebody two swords with one
 * memory the first time they split a stack, and one memory fewer the first time they
 * merged one.
 *
 * The game solves this itself, and has for years: <b>things with insides do not stack</b>.
 * A shulker box holds one, a written book with a signature is one. So a stack that
 * remembers is given a maximum stack size of one — which is not a restriction we invented
 * but the game's own component, applied for the game's own reason.
 *
 * That turns a trap into a rule: it is visible in the tooltip, it is why the item behaves
 * the way it does, and there is no case left where memory quietly goes missing.
 *
 * <h2>Why this is not how a player remembers things</h2>
 *
 * Because a player is one of a kind and a stack is not. The player's variables live in
 * the world's save keyed by who they are; a stack's live on the stack, travel with it,
 * and are copied when it is copied. Both are "what the subject remembers" — see
 * {@code Scope.CHARACTER} — and the subject is what differs.
 */
public final class Kept {

	private Kept() { }

	/**
	 * The variables a stack carries.
	 *
	 * Written into the stack rather than into a table keyed by some id we invented,
	 * because the whole point is that it travels: dropped, picked up, put in a chest,
	 * carried to another world in a shulker box, it is the same sword.
	 */
	public static final DataComponentType<Map<String, Value>> MEMORY =
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			NpcStudio.id("memory"),
			DataComponentType.<Map<String, Value>>builder()
				.persistent(Codec.unboundedMap(Codec.STRING, DialogueCodecs.VALUE))
				.build());

	/** Nothing is registered until something asks; this is what asks. */
	public static void register() {
		// The field above does the work. This exists so that a caller has something to
		// call: a class nobody mentions is a class the loader never loads, and a
		// component nobody registers is a component that silently is not there.
	}

	/** What this stack remembers, which is nothing for nearly every stack there is. */
	public static Map<String, Value> of(ItemStack stack) {
		Map<String, Value> held = stack.get(MEMORY);
		return held == null ? Map.of() : held;
	}

	/**
	 * Writes what a stack remembers, and stops it stacking if it now remembers anything.
	 *
	 * <h2>Why the stack size is set here and not asked for</h2>
	 *
	 * Because it is not a decision, it is what having a memory means. Left stackable, a
	 * sword that remembers would merge with a sword that remembers something else and
	 * one of the two memories would be gone with nothing said — which is the failure
	 * this whole arrangement exists to prevent.
	 *
	 * And it is put back when the memory empties, so a stack that has been forgotten
	 * goes back to being ordinary rather than staying mysteriously unstackable for ever.
	 */
	public static void remember(ItemStack stack, Map<String, Value> vars) {
		Map<String, Value> kept = new LinkedHashMap<>(vars);
		kept.values().removeIf(java.util.Objects::isNull);
		if (kept.isEmpty()) {
			stack.remove(MEMORY);
			stack.remove(DataComponents.MAX_STACK_SIZE);
			return;
		}
		stack.set(MEMORY, Map.copyOf(kept));
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
	}
}
