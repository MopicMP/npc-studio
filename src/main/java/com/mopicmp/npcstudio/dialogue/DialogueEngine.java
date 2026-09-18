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

		/**
		 * Somebody pressed the shown thing called {@code shown}.
		 *
		 * <h2>Why this moves nothing when the graph is not waiting for it</h2>
		 *
		 * Because it is delivered to every graph the presser has running, and most of them
		 * are in the middle of something else. Unlike {@link Pick}, which is an answer to a
		 * question this graph asked and is a fault if it arrives anywhere else, a press is
		 * a thing that happened in the world: it is addressed to whoever cares.
		 *
		 * So a press at a node that is not {@link Node.Pressed}, or at one waiting on other
		 * names, is the same as {@link Begin} — the graph is asked to carry on and stays
		 * exactly where it was.
		 */
		record Pressed(String shown) implements Input { }
	}

	/** What the presentation layer should now show. */
	public sealed interface Screen {
		/**
		 * @param lasts what this line asked for in ticks, or nought for the document's
		 *              answer. Carried rather than resolved, because the engine has no
		 *              document — the thing that knows both is the runtime.
		 */
		record Line(String speaker, com.mopicmp.npcstudio.dialogue.text.Words text,
			Presentation mode, String animation, int lasts, Node.Line.Face face,
			String nameColour) implements Screen {

			public Line(String speaker, com.mopicmp.npcstudio.dialogue.text.Words text,
					Presentation mode, String animation) {
				this(speaker, text, mode, animation, Node.Line.USES_DOCUMENT);
			}

			public Line(String speaker, com.mopicmp.npcstudio.dialogue.text.Words text,
					Presentation mode, String animation, int lasts) {
				this(speaker, text, mode, animation, lasts, Node.Line.Face.SPEAKER, "");
			}
		}

		record Choice(String speaker, com.mopicmp.npcstudio.dialogue.text.Words prompt,
			Presentation mode, List<Shown> options) implements Screen { }

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
		record Shown(int index, com.mopicmp.npcstudio.dialogue.text.Words label, String colour) { }
	}

	/** The outcome of one step. */
	public record Step(DialogueState state, Screen screen, List<Effect> effects,
			List<Call> calls) {

		public Step(DialogueState state, Screen screen, List<Effect> effects) {
			this(state, screen, effects, List.of());
		}
	}

	/**
	 * A segment somebody asked to have running, or asked to have stopped.
	 *
	 * Kept apart from {@link Effect} on purpose, though it looks like one. An
	 * effect is something done to the world and forgotten; a call changes what is
	 * running, and the thing that has to act on it is the runtime holding the
	 * other bookmark rather than the one holding a sword.
	 *
	 * @param start false when this is a cancellation
	 */
	public record Call(String segment, String target, java.util.Map<String, Value> with,
			boolean start) { }

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
	/**
	 * What a {@link Node.Set} actually writes.
	 *
	 * <h2>Why adding to something that is not a number changes nothing</h2>
	 *
	 * Rather than joining text, or counting a flag as one, or throwing. The first two
	 * are things somebody could mean and could mean differently; the third would take a
	 * conversation down over an edit that the validator has already refused at the
	 * door. Leaving it alone is the answer that cannot be mistaken for having worked.
	 */
	private static Value written(DialogueState state, Node.Set set) {
		if (set.how() != Node.Set.Change.ADD) return set.value();
		if (!(state.get(set.variable(), set.scope()) instanceof Value.Num(double was))
				|| !(set.value() instanceof Value.Num(double by))) {
			return state.get(set.variable(), set.scope());
		}
		return Value.of(was + by);
	}

	/**
	 * The state a {@link Node.Forget} leaves behind.
	 *
	 * <h2>Why a name this document does not declare is left alone</h2>
	 *
	 * Because there is nothing to put it back to. A variable's default comes from its
	 * declared type, and the declaration lives in whichever document declares it —
	 * writing a flag over what another document keeps as a number would be this node
	 * quietly breaking a graph it cannot see. The validator says so at the door; this
	 * is the same answer at run time for a document that got past it in a file.
	 */
	private static DialogueState forgetting(Dialogue dialogue, DialogueState state,
			Node.Forget forget) {
		DialogueState now = state;
		java.util.Collection<String> names = forget.everything()
			? dialogue.variableTypes().keySet()
			: forget.naming();
		for (String name : names) {
			String type = dialogue.variableTypes().get(name);
			if (type == null) continue;
			now = now.with(name, forget.scope(), Value.defaultFor(type));
		}
		// This document's nodes and no others. The visited set belongs to the player and
		// holds everywhere they have ever been, so emptying it to replay one errand
		// would forget that they have met the innkeeper.
		if (forget.visited()) now = now.forgetting(dialogue.nodes().keySet());
		return now;
	}

	/** Nobody is watching, which is every step but the one somebody asked about. */
	private static final java.util.function.Consumer<String> NOBODY = _ -> { };

	public static Step step(Dialogue dialogue, DialogueState state, Input input, Condition.World world) {
		return step(dialogue, state, input, world, NOBODY);
	}

	/**
	 * The same, telling somebody what it did on the way.
	 *
	 * <h2>Why the account comes from the deciding pass</h2>
	 *
	 * Because a second evaluation can disagree with the first, and a debugging tool that
	 * lies is worse than none — it sends somebody looking in the wrong place with
	 * confidence. So there is one walk through the graph, and the watcher is handed what
	 * it found as it found it.
	 *
	 * Nothing else changes. The watcher is called and the answer is thrown away when
	 * nobody is watching, which is every step of every conversation on a running server
	 * except the one somebody has asked about.
	 *
	 * @param watching told, line by line, what was decided and why
	 */
	public static Step step(Dialogue dialogue, DialogueState state, Input input,
			Condition.World world, java.util.function.Consumer<String> watching) {
		List<Effect> effects = new ArrayList<>();
		List<Call> calls = new ArrayList<>();
		DialogueState now = state;
		if (watching != NOBODY) watching.accept("step from " + state.currentNode());

		/*
		 * The routes whose arrival has been taken during this step.
		 *
		 * Not state, and deliberately not on the bookmark: it lasts one step and one
		 * step only. It exists because the orders in `effects` have not happened yet
		 * — they leave when this returns — so within a step the world's answer to
		 * "has this route finished" cannot change, and a route leading back to itself
		 * would read the same yes for ever. See {@link Node.Walk}.
		 */
		java.util.Set<String> collected = new java.util.HashSet<>();

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
			// Standing still rather than throwing, and rather than being ignored earlier:
			// this is the one input that is about the world instead of about this graph.
			// See Input.Pressed.
			case Input.Pressed(String shown) -> {
				if (!(current instanceof Node.Pressed waiting)) yield now.currentNode();
				String next = waiting.next(shown);
				yield next == null ? now.currentNode() : next;
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
				case Node.Set set -> now = now.with(set.variable(), set.scope(),
					written(now, set)).at(set.next());
				// Putting back what this document remembers, so the scene can be played
				// again. After the visit mark above rather than before it, which means it
				// forgets its own visit too — right, and deliberately: a graph that has
				// forgotten everything except that it passed through the forgetting is a
				// graph with one node's worth of history in it, and that one node is the
				// one nobody would think to look at.
				case Node.Forget forget -> {
					now = forgetting(dialogue, now, forget);
					if (watching != NOBODY) {
						watching.accept("forget at " + forget.id() + ": "
							+ (forget.everything() ? "everything this document declares"
								: String.join(", ", forget.naming()))
							+ (forget.visited() ? ", and where it has been" : ""));
					}
					now = now.at(forget.next());
				}
				case Node.Act act -> { effects.add(act.effect()); now = now.at(act.next()); }
				// A call goes out and the graph walks on in the same breath. What is
				// waited for, if anything, the caller says for itself afterwards.
				case Node.Do call -> {
					calls.add(new Call(call.segment(), call.target(), call.with(), true));
					now = now.at(call.next());
				}
				case Node.Stop stop -> {
					calls.add(new Call(stop.segment(), null, java.util.Map.of(), false));
					now = now.at(stop.next());
				}
				case Node.Branch branch -> {
					String next = branch.otherwise();
					for (Node.Arm arm : branch.arms()) {
						if (arm.condition().test(now, world)) { next = arm.next(); break; }
					}
					// Every arm, in order, and not only the one that won. Order is the
					// thing people get wrong about branches — the first arm that holds
					// takes it — so an arm that is true and never reached is a real fault
					// and an invisible one until it is written out.
					if (watching != NOBODY) {
						watching.accept("branch " + branch.id() + ":");
						for (var told : Explain.arms(branch, now, world)) {
							watching.accept("  " + told.text());
						}
					}
					now = now.at(next);
				}
					// A fork nobody asked a question about. The throw comes from the world
				// rather than from here, which is what keeps a step a function of what it
				// was handed — see Condition.World.roll.
				case Node.Chance chance -> {
					String next = chance.chosen(world.roll(Math.max(1, chance.ways().size())));
					now = now.at(next);
				}
				// Writing on the canvas, and the only way to arrive at one is for a
				// document to name it as its start — every other route in is a way out
				// of some node, and a comment is nobody's way out. Treated as an ending
				// rather than as a fault, because the alternative is a conversation that
				// refuses to run over a piece of somebody's writing.
				case Node.Comment _ ->
					{ return new Step(now, new Screen.Finished(), List.copyOf(effects),
						List.copyOf(calls)); }
				case Node.End(String _, Node.Homing homing) -> {
					// Ordered here rather than left to whoever notices the graph has
					// finished, because the finishing is not always noticed: a
					// conversation's last node is reached with a player standing there,
					// a behaviour graph's is reached alone, and both go through this.
					switch (homing) {
						case WALK -> effects.add(new Effect.GoHome(true));
						case TELEPORT -> effects.add(new Effect.GoHome(false));
						case STAY -> { }
					}
					return new Step(now, new Screen.Finished(), List.copyOf(effects), List.copyOf(calls));
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
						List.copyOf(effects), List.copyOf(calls));
				}
				// A route. Three answers out of two readings — not started, going,
				// arrived — and no state on the bookmark at all; the count of which
				// point she is on lives on the character, where the walking does.
				//
				// The order is re-sent on every entry while she is on it only if she
				// is not: a character knocked off her round finds nothing carrying
				// her order and is put back on it, which is what a patrol should do
				// after being interrupted.
				case Node.Walk walk -> {
					if (walk.id().equals(said(world, Sense.WALKING_TO))) {
						return new Step(now, new Screen.Waiting(0),
							List.copyOf(effects), List.copyOf(calls));
					}
					// An arrival can be taken once, and the taking has to count from
					// this instant rather than from when the orders reach the body.
					//
					// Effects leave only when the step ends, so a route whose exit
					// leads back to itself would come round, ask again, and find its
					// own arrival still lying there — for five hundred and twelve
					// nodes, which is the runaway guard, on a graph that is not
					// runaway. It cost two tests to see and one line to fix.
					if (!collected.contains(walk.id())
							&& walk.id().equals(said(world, Sense.WALKED))) {
						collected.add(walk.id());
						// And told to the body as well, for the other way round: a
						// graph that goes away and comes back some ticks later would
						// otherwise find an arrival that has been sitting there since,
						// and walk straight past a route without walking it.
						effects.add(new Effect.Arrived(walk.id()));
						now = now.at(walk.next());
						continue;
					}
					effects.add(new Effect.Follow(walk.id(), walk.route()));
					return new Step(now, new Screen.Waiting(0),
						List.copyOf(effects), List.copyOf(calls));
				}
				// Unlike the timer, this one stays where it is: the bookmark is the
				// question, and it is asked again on every entry until it holds.
				case Node.Until until -> {
					if (until.condition().test(now, world)) {
						now = now.at(until.next());
						continue;
					}
					return new Step(now, new Screen.Waiting(0), List.copyOf(effects), List.copyOf(calls));
				}
				// A press has already moved the bookmark on its way in, above, if this node
				// was waiting for the one that arrived. Reaching here means it was not, so
				// there is nothing to decide: stand still and be asked again.
				//
				// Nought ticks rather than a rest, because the graph is not waiting on a
				// clock — the press arrives as an input of its own, and the beat is only
				// what keeps the thread alive to receive it.
				case Node.Pressed _ ->
					{ return new Step(now, new Screen.Waiting(0),
						List.copyOf(effects), List.copyOf(calls)); }
				case Node.Line line -> {
					// No length: a line's animation lasts as long as the line does, and
					// the next line replaces it. Giving it one would mean guessing how
					// long somebody takes to read.
					if (line.animation() != null) effects.add(new Effect.PlayAnimation(line.animation(), 0));
					return new Step(now,
						new Screen.Line(line.speaker(), line.text(), line.mode(), line.animation(),
							line.lasts(), line.face(), line.nameColour()),
						List.copyOf(effects), List.copyOf(calls));
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
						List.copyOf(effects), List.copyOf(calls));
				}
			}
		}
	}

	/**
	 * A reading, as the word it is, or empty when it is anything else.
	 *
	 * Empty rather than null for the case that matters: a conversation is stepped
	 * against a window that knows nothing about a character's legs and answers
	 * every reading with false. A route node in a conversation therefore orders the
	 * walk and stands there, which is the same thing {@code every} does in one, and
	 * is a great deal better than a class cast on a graph somebody wrote by hand.
	 */
	private static String said(Condition.World world, String reading) {
		return world.sense(reading) instanceof Value.Text(String word) ? word : "";
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
