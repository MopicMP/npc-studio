package com.mopicmp.npcstudio.dialogue;

/**
 * A property that is simply true while something holds.
 *
 * <h2>Why this is a table and not a graph</h2>
 *
 * Everything else in this language is event-driven: something happens, and a graph runs.
 * "Double jump while you are carrying the feather" is not an event. It is not something
 * that happens; it is something that <em>is</em>, all the time, with no occasion.
 *
 * A standing property has no order and no flow. Written as a graph it would need a
 * branch, and a branch implies an order in which things are tried — which would be
 * pretending, and pretending in a language is dearer than a gap, because it gets
 * maintained.
 *
 * <h2>What it does not replace</h2>
 *
 * The switch, and only the switch. Spending energy on a jump, filling it back up over
 * time, forbidding it in the dungeon — those are events and they stay graph. This says
 * "given all that, is it on", and the graph moves the variables it reads.
 *
 * That division was got wrong once out loud: the table was offered as the answer to
 * abilities, when it is only half of one. The correction is worth keeping in view, since
 * the half it is not is the larger half.
 *
 * <h2>Why the name matters more than the amount</h2>
 *
 * A property goes on under a handle and comes off by the same handle. It lives in the
 * player's own saved data, survives a restart, and nothing in the game shows it — so one
 * applied under no name is a permanent invisible change to somebody with no way back.
 * That is the shape of the wall left standing, and it is why the validator refuses one.
 *
 * @param name      this document's own word for it, and the handle it comes off by
 * @param when      while this holds. {@link Condition.Always} for "all the time"
 * @param attribute what the game calls it, e.g. {@code minecraft:jump_strength}
 * @param how       how the number is read
 * @param amount    how much
 */
public record Standing(String name, Condition when, String attribute,
		Effect.Trait.How how, double amount) {

	public Standing {
		name = name == null ? "" : name;
		when = when == null ? new Condition.Always() : when;
		attribute = attribute == null ? "" : attribute;
		how = how == null ? Effect.Trait.How.ADD : how;
	}

	/** On all the time, which is the plainest form and the one most of them take. */
	public static Standing always(String name, String attribute,
			Effect.Trait.How how, double amount) {
		return new Standing(name, new Condition.Always(), attribute, how, amount);
	}

	public Standing whenever(Condition now) {
		return new Standing(name, now, attribute, how, amount);
	}

	public Standing giving(String nowAttribute, Effect.Trait.How nowHow, double nowAmount) {
		return new Standing(name, when, nowAttribute, nowHow, nowAmount);
	}
}
