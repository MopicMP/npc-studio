package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * When an option is offered, or a branch taken.
 *
 * The language is deliberately small and total: it always answers true or false,
 * never throws, never loops, never has a side effect. That is what lets the
 * validator check a whole dialogue up front — an expression that could run
 * arbitrary code could not be checked at all, only run and hoped for.
 *
 * `HasItem` reaches outside the dialogue into the player's inventory, so it goes
 * through {@link World} rather than being answered from dialogue state. Keeping
 * that one call behind an interface is what keeps this file free of Minecraft.
 */
public sealed interface Condition {

	/** Always offered. The default when a map maker writes no condition at all. */
	record Always() implements Condition { }

	record Not(Condition inner) implements Condition { }

	/** True when every part is true. Empty means true. */
	record All(List<Condition> parts) implements Condition { }

	/** True when any part is true. Empty means false. */
	record Any(List<Condition> parts) implements Condition { }

	record Compare(String variable, Scope scope, Op op, Value value) implements Condition { }

	record HasItem(String item, int count) implements Condition { }

	/** True once the player has been through that node at least once. */
	record Visited(String node) implements Condition { }

	enum Op { EQ, NE, LT, LE, GT, GE }

	/**
	 * Everything the outside world has to answer for a condition to be decided.
	 *
	 * Only two questions, both read-only. A condition that could change the world
	 * would make the order of evaluation matter, and then whether an option is
	 * shown could depend on which options were checked before it.
	 */
	interface World {
		boolean hasItem(String item, int count);
	}

	default boolean test(DialogueState state, World world) {
		return switch (this) {
			case Always _ -> true;
			case Not(Condition inner) -> !inner.test(state, world);
			case All(List<Condition> parts) -> parts.stream().allMatch(c -> c.test(state, world));
			case Any(List<Condition> parts) -> parts.stream().anyMatch(c -> c.test(state, world));
			case Visited(String node) -> state.visited().contains(node);
			case HasItem(String item, int count) -> world.hasItem(item, count);
			case Compare(String name, Scope scope, Op op, Value wanted) -> {
				Value actual = state.get(name, scope);
				var order = actual.compareTo(wanted);
				// Types disagree: the comparison has no answer. Equality is still
				// meaningful — a number is never equal to a piece of text — but an
				// ordering is not, so it counts as false rather than guessing.
				if (order.isEmpty()) {
					yield op == Op.NE;
				}
				int c = order.getAsInt();
				yield switch (op) {
					case EQ -> c == 0;
					case NE -> c != 0;
					case LT -> c < 0;
					case LE -> c <= 0;
					case GT -> c > 0;
					case GE -> c >= 0;
				};
			}
		};
	}

	/** Every variable this condition reads, for the validator. */
	default void collectVariables(List<VariableUse> into) {
		switch (this) {
			case Compare(String name, Scope scope, Op _, Value value) ->
				into.add(new VariableUse(name, scope, value));
			case Not(Condition inner) -> inner.collectVariables(into);
			case All(List<Condition> parts) -> parts.forEach(p -> p.collectVariables(into));
			case Any(List<Condition> parts) -> parts.forEach(p -> p.collectVariables(into));
			default -> { }
		}
	}

	/** One place a variable is read, with the value it is compared against. */
	record VariableUse(String name, Scope scope, Value comparedWith) { }
}
