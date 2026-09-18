package com.mopicmp.npcstudio.dialogue;

/**
 * A standing rule: something happens in the world, and a way into this graph runs.
 *
 * <h2>Why this is a list and no longer a map</h2>
 *
 * It was {@code box -> trigger}, filed under the thing it watched, and that was right
 * while everything it could watch had a name: a box, a marked place. It stopped being
 * right the moment a rule could be about a <em>kind</em> of block — "any campfire" has
 * no name to be filed under, and two rules about the same kind would have collided.
 *
 * Which is also why the change was made in one go rather than when the third such rule
 * turned up. A map keyed by the subject is the sort of thing five call sites come to
 * depend on quietly, and unpicking it later costs more than the feature that forced it.
 *
 * <h2>Why the cause is its own small language</h2>
 *
 * There were seven flat fields, and two of them were really one axis pretending to be
 * two: what is watched, and where. Sealed, the compiler names every place that has to
 * learn a new kind of cause, and no combination of fields can say something that is not
 * a cause — no "watching a block, filed under a box, with no block named".
 */
public record Trigger(Cause cause, Condition who, String wayIn, Again again,
		Manner manner, boolean instead) {

	/**
	 * What sets a trigger off.
	 *
	 * <h2>The asymmetry between watching and answering, which is real</h2>
	 *
	 * {@link Became} has to be about one named place, and {@link UsedAny} may be about a
	 * whole kind of block. That is not an oversight to be tidied up later: watching is
	 * <em>polled</em>, so watching "any campfire" would mean sweeping the world every
	 * few ticks looking for one, and there is no cheap way to do that. Answering is an
	 * <em>event</em> — the game says which block was clicked — so a kind costs nothing.
	 *
	 * Written down here because the two look alike in the editor and somebody will ask.
	 */
	public sealed interface Cause {

		/**
		 * A player standing in one of this document's boxes.
		 *
		 * Every trigger written before any of the rest existed, which is why it keeps
		 * the short form in the file: a bare name under a box name.
		 */
		record Inside(String area) implements Cause { }

		/**
		 * The block at a marked place becoming what is written.
		 *
		 * Becoming and not being: "light the fire three times" is three changes, not
		 * three ticks of it being alight. The runtime holds what it last saw to tell the
		 * two apart, and that memory lasts as long as the server runs and no longer — so
		 * a fire already burning when the world opens is not counted as just lit.
		 *
		 * One place and not a kind, for the reason above: this one is polled.
		 */
		record Became(String place, String block) implements Cause { }

		/** Somebody used the block at a marked place. */
		record Used(String place, String block) implements Cause { }

		/**
		 * Somebody used any block of this kind, anywhere.
		 *
		 * The form that makes a rule a rule about the world rather than about one spot:
		 * every campfire on the map behaves this way, including the ones built after the
		 * graph was written.
		 *
		 * A blank block would mean every click on everything, which is refused: it is
		 * not a rule anybody means, and it would run a graph on every right click of
		 * every player.
		 */
		record UsedAny(String block) implements Cause { }

		/**
		 * The player used one of this mod's own abilities.
		 *
		 * <h2>Why an ability needs this to be an ability at all</h2>
		 *
		 * Without it a knack can be switched on and off and can never cost anything —
		 * and something that costs nothing is not an ability, it is a setting. "Double
		 * jump, and it takes a point of energy" needs a moment to take the point at, and
		 * the moment is the jump.
		 *
		 * It is the one cause that is about a thing this mod wrote rather than about
		 * something in the world, which is why it names a knack and not a block.
		 */
		record Knack(String knack) implements Cause { }

		/**
		 * The player used an item of this kind.
		 *
		 * <h2>Why an item is a cause and not a subject</h2>
		 *
		 * A stack copies, splits, merges, sits in chests and burns in lava; it has no
		 * identity that survives being handled. So "a graph attached to a sword" means
		 * one of two things and an author expects a third: either a rule about the kind
		 * of item, which is this, or memory on one stack, which the {@code THING} kind
		 * offers and which comes with a rule of its own — see {@code Scope.CHARACTER}
		 * in a document about things, and what it costs.
		 *
		 * Named by kind rather than by place, because an item has no place: it is
		 * wherever somebody carried it, which is the whole point of one.
		 */
		record UsedItem(String item) implements Cause { }
	}

	/**
	 * Whether what this starts is a scene or a rule.
	 *
	 * <h2>Why the difference cannot be worked out from the graph</h2>
	 *
	 * The tempting shortcut is "no waiting nodes, therefore a rule". It is wrong: a rule
	 * may perfectly well say a line — "you burn your hand" — and still be a rule. So it
	 * is written down.
	 *
	 * <h2>What it actually decides, which is two things</h2>
	 *
	 * A scene waits its turn: a trigger that cut across a character mid-sentence would
	 * make every doorway a hazard to write near. A rule does not — somebody who clicked
	 * a fire has clicked the fire, and a rule that silently did nothing because a
	 * conversation was on screen would be a rule that works except when it matters.
	 *
	 * And a scene keeps a bookmark, so it can be walked away from and come back to. A
	 * rule keeps none: it runs and it is over, and a bookmark left behind would make the
	 * second click resume the first click.
	 */
	public enum Manner {
		/** Waits its turn and remembers where it got to. Every trigger written before this. */
		SCENE,

		/** Runs now and is over. Nothing is remembered and nothing is waited for. */
		RULE
	}

	/** Whether setting it off again may start it again. */
	public enum Again {
		/**
		 * Every time, so long as nothing else is being said.
		 *
		 * The ordinary answer and the one every trigger written before this had, which
		 * is why it is first: an old file has a bare name where this record now goes,
		 * and a bare name has to keep meaning exactly what it meant.
		 */
		EVERY_TIME,

		/**
		 * Once, and then not again until the player rejoins.
		 *
		 * The scene a room plays the first time you walk into it. Only a fresh start is
		 * counted — a scene this trigger began and the player stepped away from can
		 * still be picked up by walking back in, because that is not the trigger firing
		 * again, it is the same scene carrying on.
		 */
		ONCE_A_VISIT
	}

	public Trigger {
		cause = cause == null ? new Cause.Inside("") : cause;
		who = who == null ? new Condition.Always() : who;
		wayIn = wayIn == null ? "" : wayIn;
		again = again == null ? Again.EVERY_TIME : again;
		manner = manner == null ? Manner.SCENE : manner;
	}

	/** A box that starts a way in, entered every time. What every old file says. */
	public static Trigger of(String area, String wayIn) {
		return new Trigger(new Cause.Inside(area), new Condition.Always(), wayIn,
			Again.EVERY_TIME, Manner.SCENE, true);
	}

	/** Watching one place for a block to become something. */
	public static Trigger onBlock(String place, String block, Condition who, String wayIn) {
		return new Trigger(new Cause.Became(place, block), who, wayIn,
			Again.EVERY_TIME, Manner.SCENE, true);
	}

	/**
	 * Answering a click, at one place or on a whole kind.
	 *
	 * A rule rather than a scene, and instead of vanilla rather than beside it, because
	 * both are what somebody writing one of these means nearly every time. Either can be
	 * turned round afterwards; neither should have to be, to write the ordinary thing.
	 */
	public static Trigger onUse(Cause cause, Condition who, String wayIn) {
		return new Trigger(cause, who, wayIn, Again.EVERY_TIME, Manner.RULE, true);
	}

	public Trigger asking(Again now) {
		return new Trigger(cause, who, wayIn, now, manner, instead);
	}

	public Trigger caused(Cause now) {
		return new Trigger(now, who, wayIn, again, manner, instead);
	}

	public Trigger inManner(Manner now, boolean nowInstead) {
		return new Trigger(cause, who, wayIn, again, now, nowInstead);
	}

	public boolean onceOnly() {
		return again == Again.ONCE_A_VISIT;
	}

	/** Whether what it starts runs now and is over, rather than waiting its turn. */
	public boolean isRule() {
		return manner == Manner.RULE;
	}

	/** Whether this one answers a click rather than watching or measuring the ground. */
	public boolean onUse() {
		return cause instanceof Cause.Used || cause instanceof Cause.UsedAny;
	}

	/** Whether it answers an item of some kind being used. */
	public boolean onItem() {
		return cause instanceof Cause.UsedItem;
	}

	/** A rule about a kind of item: it runs now and is over, like every other rule. */
	public static Trigger onItem(String item, Condition who, String wayIn) {
		return new Trigger(new Cause.UsedItem(item), who, wayIn, Again.EVERY_TIME,
			Manner.RULE, true);
	}

	/** Whether it answers one of this mod's own abilities being used. */
	public boolean onKnack() {
		return cause instanceof Cause.Knack;
	}

	/** A rule charging for an ability: it runs now and is over, like every other rule. */
	public static Trigger onKnack(String knack, Condition who, String wayIn) {
		return new Trigger(new Cause.Knack(knack), who, wayIn, Again.EVERY_TIME,
			Manner.RULE, true);
	}

	/** Whether it waits for a block at a place to change. */
	public boolean onBlock() {
		return cause instanceof Cause.Became;
	}

	/**
	 * The place this trigger is about, or empty when it is about a kind or a box.
	 *
	 * Used by everything that asks "which places does this document point at", which is
	 * the one check that catches a place name with a letter out of it.
	 */
	public String place() {
		return switch (cause) {
			case Cause.Became(String place, String ignored) -> place;
			case Cause.Used(String place, String ignored) -> place;
			case Cause.Inside _, Cause.UsedAny _, Cause.Knack _, Cause.UsedItem _ -> "";
		};
	}

	/** The box this trigger is about, or empty when it is about a block. */
	public String area() {
		return cause instanceof Cause.Inside(String area) ? area : "";
	}

	/** What block it is about, or empty for a box. */
	public String block() {
		return switch (cause) {
			case Cause.Became(String ignored, String block) -> block;
			case Cause.Used(String ignored, String block) -> block;
			case Cause.UsedAny(String block) -> block;
			case Cause.Inside _, Cause.Knack _, Cause.UsedItem _ -> "";
		};
	}

	/**
	 * What this trigger is filed under, for a person reading a list of them.
	 *
	 * Not an identity: two rules may perfectly well be about the same kind of block, and
	 * that being allowed is the whole reason this stopped being a map.
	 */
	public String about() {
		return switch (cause) {
			case Cause.Inside(String area) -> area;
			case Cause.Became(String place, String ignored) -> place;
			case Cause.Used(String place, String ignored) -> place;
			case Cause.UsedAny(String block) -> block;
			case Cause.Knack(String knack) -> knack;
			case Cause.UsedItem(String item) -> item;
		};
	}
}
