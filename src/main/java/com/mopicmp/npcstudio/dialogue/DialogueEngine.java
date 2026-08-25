package com.mopicmp.npcstudio.dialogue;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a dialogue: bookmark plus input in, new bookmark plus effects out.
 *
 * Nothing here touches the world. The engine says what should happen; the
 * server does it. That split is the reason a whole conversation can be driven
 * in a unit test — and the reason a wrong answer shows up as a wrong list of
 * effects rather than as a player standing in a broken screen.
 *
 * The loop is the only subtle part. Nodes that do not wait for the player —
 * `Set`, `Branch`, `Act` — are run through immediately, because a writer means
 * "set this flag and then say that line", not "set this flag, and the player
 * clicks again, and then the line". The engine therefore runs forward until it
 * reaches something that waits, or the end.
 *
 * That forward run is exactly where a badly written dialogue could spin
 * forever, so it is bounded twice: by {@link #MAX_STEPS} here, and by the
 * validator, which refuses such a graph before a player ever sees it. The guard
 * stays anyway — a dialogue can arrive from an older save the validator never
 * saw.
 */
public final class DialogueEngine {

	/**
	 * How far the engine will run without the player before it gives up.
	 *
	 * Generous on purpose: a chain of forty assignments is unusual but legal,
	 * whereas a real cycle blows past any bound instantly. This catches the
	 * runaway without ever tripping on honest data.
	 */
	public static final int MAX_STEPS = 512;

	private DialogueEngine() { }

	/** What woke the graph. */
	public sealed interface Input {
		/**
		 * Carry on from the bookmark without moving it.
		 *
		 * For a conversation this is opening it, or coming back to it. For a
		 * behaviour graph it is every single tick: the character is standing at
		 * some node, and the question each tick is whether anything has changed
		 * enough to let her move on. Both are the same act — resume — which is
		 * why there is no separate input for a tick.
		 */
		record Begin() implements Input { }

		/** Moving past a line. */
		record Advance() implements Input { }

		/** Picking option number {@code index} of the current choice. */
		record Pick(int index) implements Input { }
	}

	/** What the presentation layer should now show. */
	public sealed interface Screen {
		record Line(String speaker, String text, Presentation mode, String animation) implements Screen { }

		record Choice(String speaker, String prompt, Presentation mode, List<Shown> options) implements Screen { }

		/** The conversation ended; nothing more to show. */
		record Finished() implements Screen { }

		/**
		 * Nobody is being shown anything: the graph is standing still on purpose.
		 *
		 * @param ticks how long there is no point asking again. Nought means ask
		 *              next tick — the graph is watching for something.
		 */
		record Waiting(int ticks) implements Screen { }

		/**
		 * One option as the player sees it.
		 *
		 * `index` is the position in the original list, not in the filtered one.
		 * The player picks what they see, but the engine has to map that back to
		 * the dialogue, and renumbering here would silently pick a different
		 * option the moment a condition hides one.
		 */
		record Shown(int index, String label, String colour) { }
	}

	/** The outcome of one step. */
	public record Step(DialogueState state, Screen screen, List<Effect> effects) { }

	/**
	 * Advances the conversation.
	 *
	 * @param dialogue the graph
	 * @param state    where the player left off
	 * @param input    what they just did
	 * @param world    read-only questions about the player, for conditions
	 * @throws DialogueFault when the graph is broken in a way that cannot be
	 *         recovered from mid-conversation — a missing node or a runaway loop
	 */
	public static Step step(Dialogue dialogue, DialogueState state, Input input, Condition.World world) {
		List<Effect> effects = new ArrayList<>();
		DialogueState now = state;

		Node current = require(dialogue, now.currentNode());

		// Work out where the input takes us. Begin does not move: it re-shows the
		// bookmark, which is what makes coming back to an NPC resume rather than
		// restart.
		String target = switch (input) {
			case Input.Begin _ -> now.currentNode();
			case Input.Advance _ -> {
				if (!(current instanceof Node.Line line)) {
					throw new DialogueFault("advanced past " + current.id() + ", which is not a line");
				}
				yield line.next();
			}
			case Input.Pick(int index) -> {
				if (!(current instanceof Node.Choice choice)) {
					throw new DialogueFault("picked an option at " + current.id() + ", which is not a choice");
				}
				if (index < 0 || index >= choice.options().size()) {
					throw new DialogueFault("option " + index + " does not exist at " + choice.id());
				}
				Node.Option picked = choice.options().get(index);
				// A hidden option must not be selectable. Without this check a client
				// that is out of date — or dishonest — could take a branch the player
				// was never offered.
				if (!picked.condition().test(now, world)) {
					throw new DialogueFault("option " + index + " at " + choice.id() + " is not available");
				}
				yield picked.next();
			}
		};

		if (!(input instanceof Input.Begin)) {
			now = now.at(target);
		}

		// Run forward through everything that does not wait for the player.
		int steps = 0;
		while (true) {
			if (++steps > MAX_STEPS) {
				throw new DialogueFault("dialogue " + dialogue.id() + " ran " + MAX_STEPS
					+ " nodes without waiting for the player — it is looping");
			}
			Node node = require(dialogue, now.currentNode());
			now = now.withVisited(node.id());

			switch (node) {
				case Node.Set set -> now = now.with(set.variable(), set.scope(), set.value()).at(set.next());
				case Node.Act act -> { effects.add(act.effect()); now = now.at(act.next()); }
				case Node.Branch branch -> {
					String next = branch.otherwise();
					for (Node.Arm arm : branch.arms()) {
						if (arm.condition().test(now, world)) { next = arm.next(); break; }
					}
					now = now.at(next);
				}
				case Node.End _ -> {
					return new Step(now, new Screen.Finished(), List.copyOf(effects));
				}
				// The bookmark is moved past the wait before parking, which is what
				// keeps a timer from needing any state of its own — coming back is
				// simply carrying on. The price is that a world saved mid-wait
				// resumes without serving out the remainder, and that is the right
				// trade: nobody should be able to tell, and the alternative is a
				// second kind of bookmark to save and get wrong.
				case Node.Every every -> {
					now = now.at(every.next());
					return new Step(now, new Screen.Waiting(Math.max(every.ticks(), 0)),
						List.copyOf(effects));
				}
				// Unlike the timer, this one stays where it is: the bookmark is the
				// question, and it is asked again on every entry until it holds.
				case Node.Until until -> {
					if (until.condition().test(now, world)) {
						now = now.at(until.next());
						continue;
					}
					return new Step(now, new Screen.Waiting(0), List.copyOf(effects));
				}
				case Node.Line line -> {
					// No length: a line's animation lasts as long as the line does, and
					// the next line replaces it. Giving it one would mean guessing how
					// long somebody takes to read.
					if (line.animation() != null) effects.add(new Effect.PlayAnimation(line.animation(), 0));
					return new Step(now,
						new Screen.Line(line.speaker(), line.text(), line.mode(), line.animation()),
						List.copyOf(effects));
				}
				case Node.Choice choice -> {
					List<Screen.Shown> shown = new ArrayList<>();
					List<Node.Option> options = choice.options();
					for (int i = 0; i < options.size(); i++) {
						Node.Option option = options.get(i);
						if (option.condition().test(now, world)) {
							shown.add(new Screen.Shown(i, option.label(), option.colour()));
						}
					}
					// Every option hidden means the player is offered a question with no
					// answers and can never move. The validator tries to catch this, but
					// it cannot know the state a player will actually arrive in, so the
					// engine refuses rather than showing an empty box.
					if (shown.isEmpty()) {
						throw new DialogueFault("no option is available at " + choice.id());
					}
					return new Step(now, new Screen.Choice(choice.speaker(), choice.prompt(), choice.mode(), List.copyOf(shown)),
						List.copyOf(effects));
				}
			}
		}
	}

	private static Node require(Dialogue dialogue, String id) {
		Node node = dialogue.node(id);
		if (node == null) {
			throw new DialogueFault("dialogue " + dialogue.id() + " has no node named " + id);
		}
		return node;
	}

	/** A dialogue that cannot be run as written. */
	public static final class DialogueFault extends RuntimeException {
		public DialogueFault(String message) { super(message); }
	}
}
