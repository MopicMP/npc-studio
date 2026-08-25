package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * One step of a graph.
 *
 * The split is by what the engine has to do, not by what the writer is saying.
 * Some kinds stop and wait; the rest run through in one go. That difference is
 * the whole reason the engine can promise a graph never hangs: a cycle is only
 * fatal if it contains no node that waits, and that is a property of the graph
 * the validator can check.
 *
 * <h2>Four of these were never about talking</h2>
 *
 * `Set`, `Branch`, `Act` and `End` are assignment, branching, doing and
 * returning. They are a programming language that happened to be written for
 * conversations, and calling the file a dialogue engine hid that for a while.
 * `Line` and `Choice` are the two that are really about a conversation, and what
 * they have in common with the rest is only that they wait.
 *
 * So what turns this into a language for behaviour is not new machinery. It is
 * admitting that <em>waiting for the player</em> is one reason to wait among
 * several — see {@link Every} and {@link Until}.
 */
public sealed interface Node {

	String id();

	/**
	 * True when the engine stops here rather than running on.
	 *
	 * Was called {@code waitsForPlayer}, which was true of every node that
	 * answered yes right up until it was not. The validator relies on this to
	 * tell a loop that is a conversation going round from a loop that is a
	 * freeze, and that reasoning holds whatever the graph is waiting for.
	 */
	boolean waits();

	/**
	 * A spoken line.
	 *
	 * @param speaker whose name and portrait are shown; empty means the NPC itself
	 * @param text    the line, in the markup the presentation layer understands
	 * @param mode    how it is shown; see {@link Presentation}
	 * @param animation animation to play on the NPC, or null
	 * @param next    the node after this one
	 */
	record Line(String id, String speaker, String text, Presentation mode,
			String animation, String next) implements Node {
		@Override public boolean waits() { return true; }
	}

	/**
	 * A question with options.
	 *
	 * Nothing moves until the player picks one — that is what makes Esc a
	 * bookmark rather than a way to skip past a decision.
	 */
	record Choice(String id, String speaker, String prompt, Presentation mode,
			List<Option> options) implements Node {

		/** Without a speaker named, a question is asked by nobody in particular. */
		public Choice(String id, String prompt, Presentation mode, List<Option> options) {
			this(id, "", prompt, mode, options);
		}
		/**
		 * A question defaults to taking over the screen.
		 *
		 * A line can be spoken in passing, but being asked something is a stop:
		 * the player has to read the answers and pick one, and doing that from a
		 * strip above the hotbar while the world carries on is the harder version
		 * of the same moment. A writer who wants it lighter can say so.
		 */
		public Choice(String id, String prompt, List<Option> options) {
			this(id, "", prompt, Presentation.FULLSCREEN, options);
		}

		@Override public boolean waits() { return true; }
	}

	/** Writes a variable, then moves on. */
	record Set(String id, String variable, Scope scope, Value value, String next) implements Node {
		@Override public boolean waits() { return false; }
	}

	/** Takes the first branch whose condition holds, otherwise `otherwise`. */
	record Branch(String id, List<Arm> arms, String otherwise) implements Node {
		@Override public boolean waits() { return false; }
	}

	/** Does something to the world, then moves on. */
	record Act(String id, Effect effect, String next) implements Node {
		@Override public boolean waits() { return false; }
	}

	/** The conversation is over. */
	record End(String id) implements Node {
		@Override public boolean waits() { return false; }
	}

	/**
	 * Stands here for a while, then moves on.
	 *
	 * The timer, and the reason a behaviour graph does not need a loop construct:
	 * a graph that ends by leading back to its own start is a character who goes
	 * on living, and this is what stops that from being a frozen server. You
	 * decided the occasion belongs to whoever places the node, and this is the
	 * cheapest occasion there is.
	 *
	 * @param ticks how long to stand here; nought means until the next tick
	 */
	record Every(String id, int ticks, String next) implements Node {
		@Override public boolean waits() { return true; }
	}

	/**
	 * Stands here until something is true.
	 *
	 * <h2>Why there is no event node, and no event bus</h2>
	 *
	 * Because an event is a question you ask often. A graph that waits for a
	 * player to come within ten blocks and one that subscribes to a
	 * somebody-came-close event do the same thing, except that the second needs
	 * an event to be invented, published, subscribed and — the part that actually
	 * bites — unsubscribed when the character dies mid-wait.
	 *
	 * The cost is that the condition is tested every tick. That cost is visible
	 * in the graph, which is exactly where you wanted it: a character who watches
	 * for something expensive is a character somebody can see watching for
	 * something expensive.
	 */
	record Until(String id, Condition condition, String next) implements Node {
		@Override public boolean waits() { return true; }
	}

	/**
	 * Sets a segment of the brain running, and carries straight on.
	 *
	 * <h2>Why calling does not wait</h2>
	 *
	 * Because a conversation that starts a fight has to be able to end while the
	 * fight goes on. Under a call that waited, the last line would be spoken only
	 * once somebody had won, which is not a thing anybody would ever write on
	 * purpose.
	 *
	 * So a segment runs alongside whatever called it. The caller keeps its own
	 * turn every tick and can stop the segment whenever it likes — see
	 * {@link Stop} — which is the same bargain as {@link Effect.WalkTo}: an order
	 * goes out, and watching for the end of it is the caller's own business.
	 *
	 * <h2>What the segment is told</h2>
	 *
	 * A {@link Mark} to act on, which it reads back as {@link Mark#TARGET}, and
	 * any number of named values, which it reads in {@link Scope#GIVEN}. That is
	 * what makes one "how to fight" serve a duel, a brawl and a guard post: the
	 * skill is the same and the circumstances are handed in.
	 */
	record Do(String id, String segment, String target, java.util.Map<String, Value> with,
			String next) implements Node {

		public Do(String id, String segment, String target, String next) {
			this(id, segment, target, java.util.Map.of(), next);
		}

		@Override public boolean waits() { return false; }
	}

	/**
	 * Stops a running segment, if that is the one running.
	 *
	 * Named rather than "stop whatever is going on", so that a scenario cannot
	 * accidentally cancel something it did not start.
	 */
	record Stop(String id, String segment, String next) implements Node {
		@Override public boolean waits() { return false; }
	}

	/** One option of a {@link Choice}. */
	record Option(String label, String colour, Condition condition, String next) {
		public Option(String label, String next) {
			this(label, null, new Condition.Always(), next);
		}
	}

	/** One arm of a {@link Branch}. */
	record Arm(Condition condition, String next) { }

	/** Every node this one can lead to. */
	default List<String> exits() {
		return switch (this) {
			case Line line -> List.of(line.next());
			case Choice choice -> choice.options().stream().map(Option::next).toList();
			case Set set -> List.of(set.next());
			case Act act -> List.of(act.next());
			case Branch branch -> {
				var out = new java.util.ArrayList<String>(branch.arms().stream().map(Arm::next).toList());
				out.add(branch.otherwise());
				yield List.copyOf(out);
			}
			case Do doing -> List.of(doing.next());
			case Stop stop -> List.of(stop.next());
			case Every every -> List.of(every.next());
			case Until until -> List.of(until.next());
			case End _ -> List.of();
		};
	}
}
