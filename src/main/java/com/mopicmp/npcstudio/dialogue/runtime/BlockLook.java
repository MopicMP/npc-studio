package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Reading a block in the world, in the words the game already uses for one.
 *
 * <h2>Why the description is a string and not a structure</h2>
 *
 * {@code minecraft:campfire[lit=true]} is written on the debug screen, taken by
 * commands, and listed on every wiki page about a block. Somebody who wants to know
 * what to type can find out from the game. A structure of our own would be a second
 * thing to learn that says the same thing less well, and would need a case adding
 * every time somebody asked about a block we had not thought of.
 *
 * The description lives in the language as text and is understood only here, which is
 * what keeps the engine free of Minecraft: a graph can be run in a unit test with no
 * game at all, and this is the one place that knows what a campfire is.
 *
 * <h2>Why only the properties written are compared</h2>
 *
 * Because "is the door open" and "is the door open, facing north, with the hinge on
 * the left and the lower half here" are different questions, and only the first is
 * usually meant. Naming no properties asks only about the kind of block.
 *
 * <h2>Why a bad description is said out loud once</h2>
 *
 * A misspelt block name answers "no" for ever, which looks exactly like a fire nobody
 * has lit — the worst kind of fault this project has, and the one it keeps building
 * doors for. The parse is kept, so the complaint is made the first time and not once a
 * tick afterwards.
 */
public final class BlockLook {

	private BlockLook() { }

	/** A description that has been read, or the failure to read it, kept either way. */
	private record Wanted(Block block, Map<String, String> properties) {
		static final Wanted NONSENSE = new Wanted(null, Map.of());
	}

	private static final Map<String, Wanted> READ = new ConcurrentHashMap<>();

	/**
	 * Whether the block at a place matches a description.
	 *
	 * @param level where to look, which is the level the asking happened in
	 * @param at    the place, already resolved from a mark
	 */
	public static boolean matches(ServerLevel level, BlockPos at, String description) {
		if (at == null || description == null || description.isEmpty()) return false;
		// A chunk nobody is near has no answer, and loading one to find out would mean a
		// graph could keep chunks alive by asking about them. No is the honest answer
		// and the cheap one: nobody is lighting a fire nobody is near.
		if (!level.isLoaded(at)) return false;

		Wanted wanted = READ.computeIfAbsent(description, BlockLook::read);
		if (wanted.block() == null) return false;

		BlockState state = level.getBlockState(at);
		if (!state.is(wanted.block())) return false;
		for (var asked : wanted.properties().entrySet()) {
			Property<?> property = state.getBlock().getStateDefinition()
				.getProperty(asked.getKey());
			if (property == null) return false;
			if (!nameOf(state, property).equals(asked.getValue())) return false;
		}
		return true;
	}

	/**
	 * The state a description means, for putting one down rather than reading one.
	 *
	 * <h2>Why the properties not written keep the block's own defaults</h2>
	 *
	 * Because "put a campfire here, lit" says nothing about which way it faces, and
	 * inventing an answer would mean a fire that turns as it is lit. The unwritten ones
	 * come from the block's default state, which is what the game itself uses when
	 * anything places a block without being specific.
	 *
	 * That is the same rule the matching half uses read the other way round — there,
	 * unwritten means "do not care"; here it means "leave as it comes". Both are the
	 * honest reading of saying nothing.
	 */
	public static BlockState state(String description) {
		Wanted wanted = READ.computeIfAbsent(description, BlockLook::read);
		if (wanted.block() == null) return null;

		BlockState state = wanted.block().defaultBlockState();
		for (var asked : wanted.properties().entrySet()) {
			Property<?> property = state.getBlock().getStateDefinition()
				.getProperty(asked.getKey());
			if (property == null) {
				NpcStudio.LOGGER.error("Block description \"{}\" names no property \"{}\".",
					description, asked.getKey());
				continue;
			}
			state = with(state, property, asked.getValue(), description);
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state,
			Property<T> property, String value, String description) {
		return property.getValue(value)
			.map(chosen -> state.setValue(property, chosen))
			.orElseGet(() -> {
				// The property exists and the value does not — "lit=maybe". Left as it
				// was rather than guessed at, and said out loud, because a block placed
				// in nearly the right state is the hardest kind of wrong to notice.
				NpcStudio.LOGGER.error("Block description \"{}\" has no value \"{}\" for \"{}\".",
					description, value, property.getName());
				return state;
			});
	}

	/** Whether a description can be read at all, for the editor and the validator. */
	public static boolean readable(String description) {
		return description != null && !description.isEmpty()
			&& READ.computeIfAbsent(description, BlockLook::read).block() != null;
	}

	private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static Wanted read(String description) {
		String name = description.trim();
		Map<String, String> properties = new LinkedHashMap<>();

		int bracket = name.indexOf('[');
		if (bracket >= 0) {
			if (!name.endsWith("]")) {
				NpcStudio.LOGGER.error(
					"Block description \"{}\" opens a bracket and never closes it.", description);
				return Wanted.NONSENSE;
			}
			String inside = name.substring(bracket + 1, name.length() - 1);
			name = name.substring(0, bracket).trim();
			for (String pair : inside.split(",")) {
				if (pair.isBlank()) continue;
				int equals = pair.indexOf('=');
				if (equals < 0) {
					NpcStudio.LOGGER.error(
						"Block description \"{}\" has \"{}\" where it wants name=value.",
						description, pair.trim());
					return Wanted.NONSENSE;
				}
				properties.put(pair.substring(0, equals).trim(),
					pair.substring(equals + 1).trim());
			}
		}

		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			// The whole reason this is said at all. A name with a letter out of place
			// reads false for ever, and a condition that is false for ever is
			// indistinguishable from a fire nobody has lit.
			NpcStudio.LOGGER.error("Block description \"{}\" names no block this game has.",
				description);
			return Wanted.NONSENSE;
		}
		return new Wanted(BuiltInRegistries.BLOCK.getValue(id), Map.copyOf(properties));
	}
}
