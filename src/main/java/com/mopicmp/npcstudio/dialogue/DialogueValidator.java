package com.mopicmp.npcstudio.dialogue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a dialogue before anyone can walk into it.
 *
 * This is not a nicety. A dialogue is data typed by hand, usually by someone
 * who is not a programmer, and its failures are quiet: a branch that can never
 * be taken, a name misspelt so a flag is always false, a cycle with no way out.
 * None of those look wrong on the page, and all of them look like the mod is
 * broken when a player hits them.
 *
 * The checks are ordered by how hard the problem is to notice by eye, not by
 * how hard it is to implement. The loop check comes last because it is the one
 * nobody would ever find by reading.
 */
public final class DialogueValidator {

	private DialogueValidator() { }

	public enum Severity { ERROR, WARNING }

	/**
	 * One thing found.
	 *
	 * `where` is the node id so the editor can jump straight to it. A message
	 * with no location is a message the writer has to go hunting with.
	 */
	public record Problem(Severity severity, String where, String message) {
		@Override public String toString() {
			return severity + " at " + (where == null ? "(dialogue)" : where) + ": " + message;
		}
	}

	public record Report(List<Problem> problems) {
		public boolean ok() {
			return problems.stream().noneMatch(p -> p.severity() == Severity.ERROR);
		}

		public List<Problem> errors() {
			return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList();
		}

		@Override public String toString() {
			if (problems.isEmpty()) return "no problems";
			return String.join(System.lineSeparator(), problems.stream().map(Problem::toString).toList());
		}
	}

	public static Report validate(Dialogue dialogue) {
		List<Problem> found = new ArrayList<>();

		checkStart(dialogue, found);
		checkExitsExist(dialogue, found);
		checkVariables(dialogue, found);
		checkMarks(dialogue, found);
		checkCalls(dialogue, found);
		checkChoices(dialogue, found);
		checkReachable(dialogue, found);
		checkEveryPathEnds(dialogue, found);
		checkNoFreeze(dialogue, found);

		return new Report(List.copyOf(found));
	}

	/**
	 * Whether there is a way in, and whether it goes anywhere.
	 *
	 * <h2>A document with no start is not broken</h2>
	 *
	 * It is a <b>library</b>: a set of named skills that other documents call into,
	 * which is what a brain actually is. Nobody carries one — a character carries a
	 * dialogue — so there is nowhere for it to begin and asking it to begin
	 * somewhere would be asking it to be a different kind of thing.
	 *
	 * This is the second time this file has refused something for lacking a shape
	 * it never needed. The first was the end node: a graph that waits is also a way
	 * out, and demanding an ending turned every behaviour graph into an error.
	 * A rule about the shape of a document is worth suspecting whenever a new use
	 * of the same language turns up, because the language keeps being more general
	 * than the rules written around it.
	 */
	private static void checkStart(Dialogue dialogue, List<Problem> found) {
		if (dialogue.nodes().isEmpty()) {
			found.add(new Problem(Severity.ERROR, null, "the graph has no nodes at all"));
			return;
		}
		if (dialogue.isLibrary()) {
			if (dialogue.segments().isEmpty()) {
				found.add(new Problem(Severity.ERROR, null,
					"the graph has neither a starting node nor any skills, so nothing can "
						+ "ever run it"));
			}
			return;
		}
		if (dialogue.node(dialogue.start()) == null) {
			found.add(new Problem(Severity.ERROR, null,
				"the starting node \"" + dialogue.start() + "\" does not exist"));
		}
	}

	/** A transition to a node that was never written — usually a rename left half done. */
	private static void checkExitsExist(Dialogue dialogue, List<Problem> found) {
		for (Node node : dialogue.nodes().values()) {
			for (String exit : node.exits()) {
				if (exit == null) {
					found.add(new Problem(Severity.ERROR, node.id(), "leads nowhere: the next node is missing"));
				} else if (dialogue.node(exit) == null) {
					found.add(new Problem(Severity.ERROR, node.id(),
						"leads to \"" + exit + "\", which does not exist"));
				}
			}
		}
	}

	/**
	 * Undeclared names, and comparisons that can never be true.
	 *
	 * The type check earns its keep: comparing a number against text is not a
	 * crash, it is a condition that is quietly false forever, so the option is
	 * simply never offered and the writer assumes the game is broken.
	 */
	private static void checkVariables(Dialogue dialogue, List<Problem> found) {
		Map<String, String> declared = dialogue.variableTypes();

		for (Node node : dialogue.nodes().values()) {
			List<Condition.VariableUse> uses = new ArrayList<>();
			switch (node) {
				case Node.Choice choice -> choice.options().forEach(o -> o.condition().collectVariables(uses));
				case Node.Branch branch -> branch.arms().forEach(a -> a.condition().collectVariables(uses));
				// A question a character stands and waits on is as much a condition as
				// one on a branch, and was missed here at first — a typo inside `until`
				// meant a character waiting for something that could never come true,
				// which from outside is a character doing nothing at all.
				case Node.Until until -> until.condition().collectVariables(uses);
				case Node.Do call -> {
					if (!Mark.known(call.target())) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"points at \"" + call.target() + "\", which is not something she can "
								+ "be pointed at. Known: " + String.join(", ", Mark.KNOWN)));
					}
				}
				case Node.Set set -> {
					if (set.scope() == Scope.SENSE || set.scope() == Scope.GIVEN) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"writes to \"" + set.variable() + "\" in " + set.scope()
								+ ", which is read-only"));
						break;
					}
					String type = declared.get(set.variable());
					if (type == null) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"writes to \"" + set.variable() + "\", which is not declared"));
					} else if (!type.equals(set.value().typeName())) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"writes " + set.value().typeName() + " into \"" + set.variable()
								+ "\", which is declared as " + type));
					}
				}
				default -> { }
			}

			for (Condition.VariableUse use : uses) {
				// A reading is not declared by the graph — it is offered by the
				// character, and the list of them is fixed. So the two checks are the
				// same shape against a different book.
				// What a call handed in is declared by whoever calls, not by the
				// document, so there is nothing here to check it against.
				if (use.scope() == Scope.GIVEN) continue;
				if (use.scope() == Scope.SENSE) {
					String kind = Sense.typeOf(use.name());
					if (kind == null) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"asks about \"" + use.name() + "\", which is not something she can "
								+ "sense. Known: " + String.join(", ", Sense.KNOWN)));
					} else if (!kind.equals(use.comparedWith().typeName())) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"compares \"" + use.name() + "\" (" + kind + ") against a "
								+ use.comparedWith().typeName() + " — that can never match"));
					}
					continue;
				}
				String type = declared.get(use.name());
				if (type == null) {
					found.add(new Problem(Severity.ERROR, node.id(),
						"reads \"" + use.name() + "\", which is not declared"));
				} else if (!type.equals(use.comparedWith().typeName())) {
					found.add(new Problem(Severity.ERROR, node.id(),
						"compares \"" + use.name() + "\" (" + type + ") against a "
							+ use.comparedWith().typeName() + " — that can never match"));
				}
			}
		}
	}

	/**
	 * A verb pointed at something nobody has heard of.
	 *
	 * The same failure as a misspelt reading and worth the same care: a mark that
	 * names nothing resolves to nothing, and a character told to walk to nothing
	 * stands still. From outside that is a graph which does not work, with nothing
	 * in it to point at.
	 */
	private static void checkMarks(Dialogue dialogue, List<Problem> found) {
		for (Node node : dialogue.nodes().values()) {
			if (!(node instanceof Node.Act act)) continue;
			String mark = switch (act.effect()) {
				case Effect.WalkTo(String at, float _) -> at;
				case Effect.LookAt(String at) -> at;
				case Effect.Fire(String at) -> at;
				case Effect.Strike(String at) -> at;
				default -> null;
			};
			if (mark == null || Mark.known(mark)) continue;
			found.add(new Problem(Severity.ERROR, node.id(),
				"points at \"" + mark + "\", which is not something she can be pointed at. "
					+ "Known: " + String.join(", ", Mark.KNOWN)));
		}
	}

	/**
	 * A call to a skill this document does not have.
	 *
	 * Checked against the document's own segments, because a character has one
	 * brain and calls into it. A name that is not there means a scenario that
	 * looks complete and does nothing at all - which is the failure this whole
	 * layer exists to make impossible to write by accident.
	 */
	private static void checkCalls(Dialogue dialogue, List<Problem> found) {
		for (Node node : dialogue.nodes().values()) {
			String wanted = switch (node) {
				case Node.Do call -> call.segment();
				case Node.Stop stop -> stop.segment();
				default -> null;
			};
			if (wanted == null) continue;
			// Only checked when the document has any at all. A scenario in one
			// document calling a skill in another is the ordinary arrangement once
			// there are two documents, and refusing it here would forbid it.
			if (dialogue.segments().isEmpty()) continue;
			if (dialogue.segment(wanted) == null) {
				found.add(new Problem(Severity.WARNING, node.id(),
					"calls \"" + wanted + "\", which this brain does not have. Known: "
						+ String.join(", ", dialogue.segments().keySet())));
			}
		}
	}

	/** A question the player cannot answer. */
	private static void checkChoices(Dialogue dialogue, List<Problem> found) {
		for (Node node : dialogue.nodes().values()) {
			if (!(node instanceof Node.Choice choice)) continue;

			if (choice.options().isEmpty()) {
				found.add(new Problem(Severity.ERROR, node.id(), "is a choice with no options"));
				continue;
			}
			// At least one option has to be offered no matter what the player has
			// done. Without one, arriving here in the wrong state is a dead end, and
			// the engine can only refuse at that point — too late to be useful.
			boolean anyUnconditional = choice.options().stream()
				.anyMatch(o -> o.condition() instanceof Condition.Always);
			if (!anyUnconditional) {
				found.add(new Problem(Severity.WARNING, node.id(),
					"every option here has a condition — if none of them hold, the player is stuck"));
			}
		}
	}

	/** Nodes nobody can get to: usually leftovers from a rewrite. */
	/**
	 * Nodes nobody can get to.
	 *
	 * <h2>A graph may have several ways in</h2>
	 *
	 * The start is one of them; each named segment is another. Walking only from
	 * the start would report every node of every skill as unreachable, which is
	 * both wrong and the worst kind of wrong — a warning that is always there is
	 * a warning nobody reads, and the one real leftover hides among them.
	 */
	private static void checkReachable(Dialogue dialogue, List<Problem> found) {
		if (!dialogue.hasStart() || dialogue.node(dialogue.start()) == null) return;

		Set<String> seen = new HashSet<>(reachableFrom(dialogue, dialogue.start()));
		for (Map.Entry<String, String> way : dialogue.segments().entrySet()) {
			if (dialogue.node(way.getValue()) == null) {
				found.add(new Problem(Severity.ERROR, null, "the segment \"" + way.getKey()
					+ "\" begins at \"" + way.getValue() + "\", which does not exist"));
				continue;
			}
			seen.addAll(reachableFrom(dialogue, way.getValue()));
		}
		for (String id : dialogue.nodes().keySet()) {
			if (!seen.contains(id)) {
				found.add(new Problem(Severity.WARNING, id, "cannot be reached from the start"));
			}
		}
	}

	/**
	 * Every path must be able to finish.
	 *
	 * Checked backwards from the `End` nodes: anything that cannot reach one is
	 * a conversation the player can enter and never leave properly.
	 *
	 * <h2>Unless it is not a conversation</h2>
	 *
	 * A behaviour graph has no end and should not have one — a character goes on
	 * living, and the graph that runs her is a loop by nature. This rule was
	 * written when every graph was something a player stood in front of, and it
	 * would now forbid half the programs the language can express.
	 *
	 * So a graph that stands still on its own account is let through. What made
	 * "no end" fatal was a player trapped in a box with no way out, and a graph
	 * that waits has already handed control back: the world carries on around a
	 * character who is standing there, which is not a trap but a Tuesday.
	 *
	 * A conversation with neither an end nor a wait is still refused, which is
	 * the case the rule was really about.
	 */
	private static void checkEveryPathEnds(Dialogue dialogue, List<Problem> found) {
		Set<String> canEnd = new HashSet<>();
		for (Node node : dialogue.nodes().values()) {
			if (node instanceof Node.End) canEnd.add(node.id());
			// Standing still is a way out of a graph, for whoever is running it.
			if (node instanceof Node.Every || node instanceof Node.Until) canEnd.add(node.id());
		}
		if (canEnd.isEmpty()) {
			found.add(new Problem(Severity.ERROR, null, "the dialogue never ends: there is no end node"));
			return;
		}

		// Grow the set until it stops growing: a node can end if any exit can.
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Node node : dialogue.nodes().values()) {
				if (canEnd.contains(node.id())) continue;
				if (node.exits().stream().anyMatch(canEnd::contains)) {
					canEnd.add(node.id());
					grew = true;
				}
			}
		}

		Set<String> reachable = new HashSet<>(
			dialogue.hasStart() ? reachableFrom(dialogue, dialogue.start()) : Set.<String>of());
		for (String at : dialogue.segments().values()) {
			if (dialogue.node(at) != null) reachable.addAll(reachableFrom(dialogue, at));
		}
		for (String id : reachable) {
			if (!canEnd.contains(id)) {
				found.add(new Problem(Severity.ERROR, id, "no path from here ever reaches an end"));
			}
		}
	}

	/**
	 * The check nobody would make by eye: a cycle the player cannot interrupt.
	 *
	 * A conversation that goes round in circles is fine and common — ask about
	 * the weather, come back to the menu. What is fatal is a cycle made only of
	 * nodes that do not wait for the player: `Set` to `Branch` and back again.
	 * The engine would spin through it until it gave up, and the player would
	 * see a frozen game with no idea why.
	 *
	 * So the search runs only over nodes that never stop, and any cycle inside
	 * that subgraph is a freeze. Nodes that wait break every cycle they are in,
	 * which is exactly the property we want.
	 */
	private static void checkNoFreeze(Dialogue dialogue, List<Problem> found) {
		Set<String> visiting = new LinkedHashSet<>();
		Set<String> done = new HashSet<>();

		for (Node node : dialogue.nodes().values()) {
			if (node.waits() || done.contains(node.id())) continue;
			List<String> cycle = findCycle(dialogue, node.id(), visiting, done);
			if (cycle != null) {
				found.add(new Problem(Severity.ERROR, cycle.get(0),
					"loops without ever waiting for the player: "
						+ String.join(" -> ", cycle) + " -> " + cycle.get(0)));
				return; // one report is enough; the writer fixes it and runs again
			}
		}
	}

	private static List<String> findCycle(Dialogue dialogue, String id,
			Set<String> visiting, Set<String> done) {
		if (visiting.contains(id)) {
			// Trim the path down to the cycle itself, so the message names only the
			// nodes actually involved rather than how we got there.
			List<String> path = new ArrayList<>(visiting);
			return path.subList(path.indexOf(id), path.size());
		}
		if (done.contains(id)) return null;

		Node node = dialogue.node(id);
		if (node == null || node.waits()) return null;

		visiting.add(id);
		for (String exit : node.exits()) {
			List<String> cycle = findCycle(dialogue, exit, visiting, done);
			if (cycle != null) return cycle;
		}
		visiting.remove(id);
		done.add(id);
		return null;
	}

	private static Set<String> reachableFrom(Dialogue dialogue, String start) {
		Set<String> seen = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty()) {
			Node node = dialogue.node(queue.poll());
			if (node == null) continue;
			for (String exit : node.exits()) {
				if (exit != null && seen.add(exit)) queue.add(exit);
			}
		}
		return seen;
	}
}
