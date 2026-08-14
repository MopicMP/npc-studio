package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * One step of a dialogue.
 *
 * Six kinds, and the split is by what the engine has to do, not by what the
 * writer is saying. `Line` and `Choice` wait for the player; the rest run
 * through in one go. That difference is the whole reason the engine can promise
 * a dialogue never hangs: a cycle is only fatal if it contains no node that
 * waits, and that is a property of the graph the validator can check.
 */
public sealed interface Node {

	String id();

	/**
	 * True when the engine stops here and waits for the player.
	 *
	 * The validator relies on this to tell a loop that is a conversation going
	 * round from a loop that is a freeze.
	 */
	boolean waitsForPlayer();

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
		@Override public boolean waitsForPlayer() { return true; }
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

		@Override public boolean waitsForPlayer() { return true; }
	}

	/** Writes a variable, then moves on. */
	record Set(String id, String variable, Scope scope, Value value, String next) implements Node {
		@Override public boolean waitsForPlayer() { return false; }
	}

	/** Takes the first branch whose condition holds, otherwise `otherwise`. */
	record Branch(String id, List<Arm> arms, String otherwise) implements Node {
		@Override public boolean waitsForPlayer() { return false; }
	}

	/** Does something to the world, then moves on. */
	record Act(String id, Effect effect, String next) implements Node {
		@Override public boolean waitsForPlayer() { return false; }
	}

	/** The conversation is over. */
	record End(String id) implements Node {
		@Override public boolean waitsForPlayer() { return false; }
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
			case End _ -> List.of();
		};
	}
}
