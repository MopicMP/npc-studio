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

	/**
	 * Whether the player is standing inside one of this document's boxes.
	 *
	 * <h2>Why a condition and not a node of its own</h2>
	 *
	 * Because waiting already exists. {@link Node.Until} stands still until a
	 * condition holds, so "walk on until they have crossed the doorway" is that node
	 * with this word in it, and nothing new has to learn how to wait.
	 *
	 * It buys more than one node's worth. Written here it is also a branch — "if
	 * they came the back way, say this instead" — and a guard on an answer, which a
	 * dedicated trigger node could not have been. And the next member of the family,
	 * whatever it turns out to be, costs one record rather than one node.
	 *
	 * It sits beside {@link HasItem} on purpose: both are facts about the person
	 * being talked to rather than about the character talking, and both are asked of
	 * the world rather than remembered.
	 *
	 * @param area a name from the document's own boxes; see {@link Area}
	 */
	record Inside(String area) implements Condition { }

	/**
	 * Whether the block at a marked place is a given kind, in a given state.
	 *
	 * <h2>Why the state is written in Minecraft's own words</h2>
	 *
	 * {@code minecraft:campfire[lit=true]} is not a syntax this mod invented. It is
	 * what the game already writes on the debug screen, what commands already take, and
	 * what every wiki page about a block already lists — so somebody who wants to know
	 * what to type here can find out from the game rather than from us.
	 *
	 * It is also a superset by construction. Every block, every property, and the
	 * campfire that prompted it is one line of it; a syntax of our own would have to
	 * grow a case each time somebody asked about a different block.
	 *
	 * <h2>Why the properties are optional</h2>
	 *
	 * Because "is there still a door there" and "is the door open" are both ordinary
	 * questions. Named without brackets, only the kind of block is asked about; named
	 * with them, only the properties written are compared and the rest are ignored —
	 * so asking about {@code lit} does not accidentally also demand a particular
	 * facing.
	 *
	 * <h2>What it cannot say</h2>
	 *
	 * A place that is not loaded has no answer, and the answer given is no. Which is
	 * the honest one: a chunk nobody is near is a chunk where nobody is lighting
	 * anything. It does mean a graph must not use this to decide something that has to
	 * stay true while nobody is looking — for that the thing to keep is a variable.
	 *
	 * @param mark  a place, in the same words as everywhere else — see {@link Mark}
	 * @param block the kind and state, as Minecraft writes it
	 */
	record Block(String mark, String block) implements Condition { }

	/**
	 * What the player has in hand right now.
	 *
	 * <h2>Why this is not {@link HasItem}</h2>
	 *
	 * Because pockets and hands are different questions and the difference is the whole
	 * of a rule like "light it with a flint and steel". Somebody carrying one in their
	 * bag has not lit anything; somebody holding one has. {@code HasItem} answers the
	 * first and there was no way to ask the second.
	 *
	 * <h2>Why an empty hand is written as air</h2>
	 *
	 * Because that is what the game calls it, and because the alternative was a field
	 * whose emptiness meant a different question — which is the shape of mistake this
	 * document has now made and corrected twice. {@code minecraft:air} is an item id
	 * like any other, the comparison is the same comparison, and there is no special
	 * case anywhere to forget.
	 *
	 * @param item an item id; {@code minecraft:air} for an empty hand
	 */
	record Holding(String item) implements Condition { }

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

		/**
		 * Whether the player is inside the named box of this document.
		 *
		 * Defaulted to no, and "no" is the honest answer rather than a stub: a world
		 * with nobody in it has nobody standing anywhere, and a dialogue being tested
		 * without a map is exactly such a world. A box that does not exist reads the
		 * same way, for the reason the sense readings do — a graph naming something
		 * that is not there is for the validator to catch before it runs, not
		 * something to stop a conversation over.
		 */
		default boolean inside(String area) {
			return false;
		}

		/**
		 * What a character perceives, by name — see {@link Scope#SENSE}.
		 *
		 * Defaulted rather than abstract, and not out of politeness to the callers
		 * that already exist: a world with nobody in it genuinely has no senses.
		 * A dialogue being tested from a unit test is such a world, and answering
		 * "no" to every reading is the honest answer there, not a stub.
		 *
		 * Never null. An unknown name reads as false, for the same reason an
		 * unwritten variable does: a graph asking about something that is not
		 * there is a mistake for the validator to catch before it runs, not
		 * something to crash a character over.
		 */
		default Value sense(String name) {
			return new Value.Flag(false);
		}

		/**
		 * A number from nought up to but not including {@code sides}.
		 *
		 * <h2>Why the throw comes from out here</h2>
		 *
		 * Because the engine has no state of its own and is worth keeping that way. It
		 * is a function of a graph, a bookmark and a world, and everything that makes a
		 * step come out one way rather than another arrives through this interface —
		 * which is what lets a test say "she has no bread, the door is shut, the throw
		 * was a two" and get one answer, every time, in any order.
		 *
		 * Defaulted to nought rather than to a real throw, for the same reason every
		 * other reading here has a default: a world with nothing in it always takes the
		 * first way out, which is a dull scene and not a broken one.
		 */
		default int roll(int sides) {
			return 0;
		}

		/**
		 * Whether the block at a marked place is of the given kind and state.
		 *
		 * Defaulted to no for the same reason the rest are: a dialogue being run without
		 * a map has no blocks in it, and "no" is what is true there rather than a stub.
		 *
		 * A place that is not loaded also answers no, and that one is worth knowing
		 * about while writing rather than while debugging: a graph cannot use this to
		 * decide something that has to stay true while nobody is near it.
		 */
		default boolean blockAt(String mark, String block) {
			return false;
		}

		/**
		 * Whether the player is holding this item.
		 *
		 * Defaulted to no, like the rest — but note what that means for an empty hand:
		 * a world with nobody in it answers "no, not holding air" rather than "yes,
		 * holding nothing". That is the honest answer to a question about a hand that
		 * does not exist, and a test that wants the other one says so.
		 */
		default boolean holding(String item) {
			return false;
		}
	}

	default boolean test(DialogueState state, World world) {
		return switch (this) {
			case Always _ -> true;
			case Not(Condition inner) -> !inner.test(state, world);
			case All(List<Condition> parts) -> parts.stream().allMatch(c -> c.test(state, world));
			case Any(List<Condition> parts) -> parts.stream().anyMatch(c -> c.test(state, world));
			case Visited(String node) -> state.visited().contains(node);
			case HasItem(String item, int count) -> world.hasItem(item, count);
			case Inside(String area) -> world.inside(area);
			case Block(String mark, String block) -> world.blockAt(mark, block);
			case Holding(String item) -> world.holding(item);
			case Compare(String name, Scope scope, Op op, Value wanted) -> {
				// A reading is asked of the world; everything else is remembered.
				Value actual = scope == Scope.SENSE ? world.sense(name) : state.get(name, scope);
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
