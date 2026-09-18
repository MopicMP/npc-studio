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
		checkTraits(dialogue, found);
		checkScopes(dialogue, found);
		checkStanding(dialogue, found);
		checkGauges(dialogue, found);
		checkLocation(dialogue, found);
		checkCalls(dialogue, found);
		checkChoices(dialogue, found);
		checkReachable(dialogue, found);
		checkEveryPathEnds(dialogue, found);
		checkNoFreeze(dialogue, found);
		checkTriggers(dialogue, found);
		checkChances(dialogue, found);
		checkShown(dialogue, found);

		return new Report(List.copyOf(found));
	}

	/**
	 * Whether the boxes that begin something can begin it.
	 *
	 * <h2>The one that is not about a name</h2>
	 *
	 * A box written as steps is measured from a character's home and turned by the
	 * quarter she faces. A conversation a doorway began has no character — so those
	 * steps are measured from the origin, and the box a writer drew round their own
	 * front door is a box somewhere under the world spawn.
	 *
	 * That is an error rather than a warning, and the difference is worth stating.
	 * Nothing crashes; the graph is perfectly runnable. It simply cannot ever happen,
	 * and it fails by nothing happening at all — which is the failure that costs the
	 * most, because there is no message to search for and the obvious conclusion is
	 * that you bound the trigger wrong. A refusal to save is a worse afternoon than a
	 * bad box and a much better one than a silent one.
	 */
	/**
	 * A fork that cannot fork.
	 *
	 * No ways at all is a node the engine walks into and comes out of pointing at
	 * nothing, which is the one shape that becomes a crash rather than a dull scene.
	 * One way is not a crash and not a lie either — it is simply a fork that always
	 * goes the same way, which is worth saying out loud because it is nearly always
	 * an author who added the node and has not finished.
	 */
	/**
	 * Things shown in the air, and the waits that are about them.
	 *
	 * <h2>Why an unnamed one is refused rather than given a name</h2>
	 *
	 * Because the name is the only handle there is. A hologram nobody can name cannot be
	 * changed, cannot be taken down and cannot be pressed — it is a thing hanging in the
	 * room for ever with nothing anywhere pointing at it. Naming it here for the author
	 * would put a name in the world that is not written in the graph, which is worse.
	 *
	 * <h2>Why a wait for something this document never shows is only a warning</h2>
	 *
	 * Because it can be right. A location lays its room out, and a conversation held in
	 * that room may perfectly well wait on a label the room put up. What it usually is,
	 * though, is a typo — so it is said, and saying it does not stop the file being saved.
	 */
	private static void checkShown(Dialogue dialogue, List<Problem> found) {
		java.util.Set<String> shows = new java.util.LinkedHashSet<>();
		for (Node node : dialogue.nodes().values()) {
			if (!(node instanceof Node.Act act)) continue;
			switch (act.effect()) {
				case Effect.Show(String name, Shown what, Route.Point ignored) -> {
					shows.add(name);
					if (name.isBlank()) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"shows something with no name, and the name is the only way "
								+ "anything else can reach it — to change it, to take it "
								+ "down, or to wait for it to be pressed"));
					}
					// Words may be empty on purpose: a label that is filled in a moment
					// later by another `show` of the same name is an ordinary way to write
					// a menu. A block or an item cannot be empty and mean anything.
					if (what.plain().isBlank() && what.kind() != Shown.Kind.TEXT) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"shows \"" + name + "\" as a " + what.kind().name().toLowerCase(
								java.util.Locale.ROOT) + " without saying which one"));
					}
				}
				case Effect.Unshow(String name) -> {
					if (name.isBlank()) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"takes something away without saying what"));
					}
				}
				default -> { }
			}
		}

		for (Node node : dialogue.nodes().values()) {
			if (!(node instanceof Node.Pressed waiting)) continue;
			if (waiting.presses().isEmpty()) {
				// Nothing can ever move it, and it waits rather than failing — so the graph
				// stops here for good, in the one way that produces no message at all.
				found.add(new Problem(Severity.ERROR, node.id(),
					"waits for a press and names nothing to be pressed, so nothing can "
						+ "ever move it on"));
			}
			java.util.Set<String> already = new java.util.HashSet<>();
			for (Node.Press press : waiting.presses()) {
				if (press.shown().isBlank()) {
					found.add(new Problem(Severity.ERROR, node.id(),
						"waits for a press on something with no name"));
					continue;
				}
				if (!already.add(press.shown())) {
					// The first arm wins, so the second is a way out of this node that
					// nothing can ever take — and it is drawn on the canvas as if it could.
					found.add(new Problem(Severity.ERROR, node.id(),
						"waits twice for \"" + press.shown() + "\"; the second way out can "
							+ "never be taken"));
					continue;
				}
				if (!shows.contains(press.shown())) {
					found.add(new Problem(Severity.WARNING, node.id(),
						"waits for \"" + press.shown() + "\" to be pressed, and nothing in "
							+ "this document shows anything by that name"));
				}
			}
		}
	}

	private static void checkChances(Dialogue dialogue, List<Problem> found) {
		for (Node node : dialogue.nodes().values()) {
			if (!(node instanceof Node.Chance chance)) continue;
			if (chance.ways().isEmpty()) {
				found.add(new Problem(Severity.ERROR, node.id(),
					"is a fork with no ways out of it at all"));
			} else if (chance.ways().size() == 1) {
				found.add(new Problem(Severity.WARNING, node.id(),
					"is a fork with one way out, so the throw never decides anything"));
			} else if (chance.ways().size() > Node.Chance.MOST) {
				found.add(new Problem(Severity.ERROR, node.id(),
					"has " + chance.ways().size() + " ways out; " + Node.Chance.MOST
						+ " is as many as one throw may have"));
			}
		}
	}

	private static void checkTriggers(Dialogue dialogue, List<Problem> found) {
		for (Trigger trigger : dialogue.triggers()) {
			String segment = trigger.wayIn();
			// Every kind of trigger has to start a way in that exists, so it is asked
			// once, up here, rather than three times with three wordings that drift.
			if (!dialogue.segments().containsKey(segment)) {
				found.add(new Problem(Severity.ERROR, null,
					"a trigger about \"" + trigger.about() + "\" starts \"" + segment
						+ "\", which is not a way into this graph"));
			}

			switch (trigger.cause()) {
				case Trigger.Cause.Inside(String area) -> {
					Area box = dialogue.area(area);
					if (box == null) {
						found.add(new Problem(Severity.ERROR, null,
							"a box called \"" + area + "\" is set to start \"" + segment
								+ "\", but no box of that name is drawn"));
					} else if (box.needsAnchor()) {
						found.add(new Problem(Severity.ERROR, null,
							"the box \"" + area + "\" is written as steps from a character, "
								+ "but a place has none to measure from — write it down in "
								+ "coordinates instead, from the box's own page"));
					}
				}
				// Waiting for nothing in particular is not a thing: this one is polled,
				// and what it polls for is the state. Which place, and whether the world
				// has it, is answered where the world is — see DialogueEditing.
				case Trigger.Cause.Became(String place, String block) -> {
					if (block.isBlank()) {
						found.add(new Problem(Severity.ERROR, null,
							"the place \"" + place + "\" waits for a block and does not say "
								+ "which — write it as the game does, like "
								+ "minecraft:campfire[lit=true]"));
					}
				}
				// A rule at a place may say nothing about the state, and that is its
				// ordinary form: "click it, whatever it is".
				case Trigger.Cause.Used _ -> { }
				// A rule about a kind of block with no kind named is every click on
				// everything: a graph run on every right click of every player, which is
				// not a rule anybody means.
				// An ability nobody wrote is a rule that never fires, and it is the one
				// name here that cannot be checked at run time either: which abilities
				// exist is a fact about this mod, known now and known here.
				case Trigger.Cause.Knack(String knack) -> {
					if (!com.mopicmp.npcstudio.dialogue.Knack.is(knack)) {
						found.add(new Problem(Severity.ERROR, null,
							"a rule answers the ability \"" + knack + "\", which this mod "
								+ "does not have. Known: "
								+ String.join(", ", com.mopicmp.npcstudio.dialogue.Knack.KNOWN)));
					}
				}
				// A rule about every item whatever would run a graph on every right click
				// of every player holding anything, which is the same refusal the block
				// version gets and for the same reason.
				case Trigger.Cause.UsedItem(String item) -> {
					if (item.isBlank()) {
						found.add(new Problem(Severity.ERROR, null,
							"a rule answers using any item whatever, which would run on "
								+ "every click in the world — name a kind of item"));
					}
				}
				case Trigger.Cause.UsedAny(String block) -> {
					if (block.isBlank()) {
						found.add(new Problem(Severity.ERROR, null,
							"a rule answers a click on any block whatever, which would run "
								+ "on every click in the world — name a kind of block"));
					}
				}
			}
		}
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
		// A document of rules is carried by nobody, so there is nothing to click and no
		// beginning to have. One with a beginning is a document somebody has changed the
		// kind of and not finished — said out loud rather than quietly ignored, because
		// a start that never runs is a graph half of which is unreachable.
		if (dialogue.isRules()) {
			if (dialogue.hasStart()) {
				found.add(new Problem(Severity.ERROR, null,
					"this document is a set of rules, which nobody carries — so its "
						+ "starting node \"" + dialogue.start() + "\" can never be reached. "
						+ "Rules run through their triggers"));
			}
			if (dialogue.triggers().isEmpty()) {
				// A warning and not an error: a rules document with nothing in it yet is
				// an ordinary half hour's work in progress, and refusing to save it would
				// strand somebody in the middle of writing one.
				found.add(new Problem(Severity.WARNING, null,
					"this document is a set of rules and has no triggers, so nothing will "
						+ "ever set it off"));
			}
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
			return;
		}
		// A graph cannot begin at a piece of writing. It is the one way a comment can be
		// reached at all — everything else in a graph is somebody's way out, and a
		// comment is nobody's — and reaching one ends the conversation immediately, which
		// from outside is a character who says nothing when you click her.
		if (dialogue.node(dialogue.start()).isWriting()) {
			found.add(new Problem(Severity.ERROR, dialogue.start(),
				"is a comment, so it cannot be where the graph begins"));
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
								+ "be pointed at. Known: " + pointable()));
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
					} else if (set.how() == Node.Set.Change.ADD && !"number".equals(type)) {
						// Joining text and counting flags are both things somebody could
						// mean, and could mean differently. Refused here rather than
						// guessed at in the engine, which is the one place a wrong guess
						// would look like it had worked.
						found.add(new Problem(Severity.ERROR, node.id(),
							"adds to \"" + set.variable() + "\", which is declared as "
								+ type + " — only numbers can be added to"));
					} else if (!type.equals(set.value().typeName())) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"writes " + set.value().typeName() + " into \"" + set.variable()
								+ "\", which is declared as " + type));
					}
				}
				// A reset that names a variable this document does not declare resets
				// nothing, silently — there is no declared type to put back, so the
				// engine leaves it alone. Which reads, from outside, as a scene that
				// half replays: exactly the fault the node exists to prevent.
				case Node.Forget forget -> {
					if (forget.scope() == Scope.SENSE || forget.scope() == Scope.GIVEN) {
						found.add(new Problem(Severity.ERROR, node.id(),
							"puts back variables in " + forget.scope() + ", which is read-only"));
						break;
					}
					for (String name : forget.naming()) {
						if (declared.containsKey(name)) continue;
						found.add(new Problem(Severity.ERROR, node.id(),
							"puts back \"" + name + "\", which is not declared"));
					}
					// Naming no variables and leaving the visits alone is a node that
					// does nothing at all. A warning rather than an error: it is a
					// half-finished edit, not a broken graph, and an error would hold the
					// editor's save back over one somebody is still in the middle of.
					if (forget.naming().isEmpty() && !forget.everything() && !forget.visited()) {
						found.add(new Problem(Severity.WARNING, node.id(),
							"puts nothing back — no variables are ticked and visits are kept"));
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
					+ "Known: " + pointable()));
		}
	}

	/**
	 * A property given under no name, which can never be taken back.
	 *
	 * <h2>Why this is an error and not a warning</h2>
	 *
	 * Because what it produces is permanent and invisible. An attribute modifier lives
	 * in the player's own saved data, survives a restart, and nothing anywhere in the
	 * game shows it — so one applied under no handle is a change to somebody that
	 * neither they nor the author can ever find again. That is the shape of the wall
	 * left standing, and it is worth refusing at the door.
	 *
	 * Whether the property itself exists is not asked here and cannot be: which
	 * attributes a game has is a fact about a running game, and this class is on the
	 * side of the fence that knows nothing about games. It is asked where the world is
	 * — see {@code DialogueEditing.missingThings}, the same place a misspelt place name
	 * is caught.
	 */
	/**
	 * The scope that means two different things in a document about the player.
	 *
	 * "What the subject remembers" is the character's own store in a conversation and a
	 * place's in a zone. In a document about the player the subject <em>is</em> the
	 * player — so it would be a second name for the store "player" already names, and
	 * two names for one store is a variable written under one and read under the other.
	 *
	 * That is not a hypothetical: it is the fault this whole run of work started from,
	 * and refusing it here is the cheapest place it will ever be caught.
	 */
	/**
	 * A standing property that can never be taken off, or names nothing.
	 *
	 * The same two refusals the verb gets and for the same reason: a modifier lives in
	 * the player's own saved data, survives a restart, and nothing in the game shows it.
	 * One applied under no name is a permanent invisible change to somebody with no way
	 * back — and here it is worse than for the verb, because a standing rule applies
	 * itself to everybody who ever meets its condition.
	 */
	private static void checkStanding(Dialogue dialogue, List<Problem> found) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (com.mopicmp.npcstudio.dialogue.Standing rule : dialogue.standing()) {
			String where = "the property \"" + rule.name() + "\"";
			if (rule.name().isBlank()) {
				found.add(new Problem(Severity.ERROR, null,
					"a standing property has no name, so nothing could ever take it off"));
			} else if (!seen.add(rule.name())) {
				// Two rules under one handle are one modifier: the second replaces the
				// first, silently, and the first simply never applies.
				found.add(new Problem(Severity.ERROR, null,
					where + " is named twice, and the second would quietly replace the first"));
			}
			if (rule.attribute().isBlank()) {
				found.add(new Problem(Severity.ERROR, null,
					where + " does not say which property it gives"));
			}
			// An ability of ours takes no number: it is on or off, and half a double
			// jump is not a thing. Said as a word rather than refused, because an
			// amount left over from having been an attribute is a tidying job and not
			// a broken document.
			if (com.mopicmp.npcstudio.dialogue.Knack.is(rule.attribute()) && rule.amount() != 0) {
				found.add(new Problem(Severity.WARNING, null,
					where + " is an ability, which is on or off — the amount is ignored"));
			}
			// And the names its condition reads, held to the same standard as everywhere
			// else. A name with a letter out of place gates an ability that never turns
			// on — the silence the whole of this is built to break — and the check that
			// catches it elsewhere walks nodes, which a property is not.
			List<Condition.VariableUse> reads = new java.util.ArrayList<>();
			rule.when().collectVariables(reads);
			for (Condition.VariableUse use : reads) {
				if (use.scope() == Scope.SENSE || use.scope() == Scope.GIVEN) continue;
				String type = dialogue.variableTypes().get(use.name());
				if (type == null) {
					found.add(new Problem(Severity.ERROR, null,
						where + " reads \"" + use.name() + "\", which is not declared"));
				} else if (!type.equals(use.comparedWith().typeName())) {
					found.add(new Problem(Severity.ERROR, null,
						where + " compares \"" + use.name() + "\" (" + type + ") against a "
							+ use.comparedWith().typeName() + " — that can never match"));
				}
			}
		}
	}

	/**
	 * A gauge that cannot be drawn, or that shows nothing.
	 *
	 * A bar with no maximum is the one worth refusing outright: it would be a division
	 * by nought if anything tried, and what it means — "full at what?" — has no answer
	 * anybody could guess.
	 */
	private static void checkGauges(Dialogue dialogue, List<Problem> found) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (com.mopicmp.npcstudio.dialogue.Gauge gauge : dialogue.gauges()) {
			String where = "the gauge \"" + gauge.label() + "\"";
			if (gauge.label().isBlank()) {
				found.add(new Problem(Severity.ERROR, null,
					"a gauge has no label, so nothing on screen would say what it is"));
			} else if (!seen.add(gauge.label())) {
				found.add(new Problem(Severity.ERROR, null,
					where + " is written twice, and two of them would sit on top of each other"));
			}
			if (gauge.variable().isBlank()) {
				found.add(new Problem(Severity.ERROR, null,
					where + " does not say which variable it shows"));
			}
			if (gauge.look() == com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR
					&& gauge.most() <= 0) {
				found.add(new Problem(Severity.ERROR, null,
					where + " is a bar with no maximum, so there is nothing for it to be "
						+ "full of — give it one, or show the number instead"));
			}
			String type = dialogue.variableTypes().get(gauge.variable());
			if (type == null && !gauge.variable().isBlank()) {
				// The same standard everything else is held to. A name with a letter out
				// of it shows nought for ever, which reads as a stamina that never fills.
				found.add(new Problem(Severity.ERROR, null,
					where + " shows \"" + gauge.variable() + "\", which is not declared"));
			} else if (type != null && !"number".equals(type)) {
				found.add(new Problem(Severity.WARNING, null,
					where + " shows \"" + gauge.variable() + "\", which is " + type
						+ " — only a number has anything to draw"));
			}
			// And the names its condition reads, for the reason a standing property's
			// are checked: a gauge shown "while something holds" is invisible when the
			// something is misspelt, and invisible is the hardest state to debug.
			List<Condition.VariableUse> reads = new java.util.ArrayList<>();
			gauge.when().collectVariables(reads);
			for (Condition.VariableUse use : reads) {
				if (use.scope() == Scope.SENSE || use.scope() == Scope.GIVEN) continue;
				if (dialogue.variableTypes().containsKey(use.name())) continue;
				found.add(new Problem(Severity.ERROR, null,
					where + " is shown while \"" + use.name() + "\" holds, and that is "
						+ "not declared"));
			}
		}
	}

	/**
	 * A document that is a place, and a document that says where it lives.
	 *
	 * <h2>Why the mismatch is refused rather than ignored</h2>
	 *
	 * A conversation carrying a location's settings, or a location carrying none, are
	 * both documents that will behave in a way nobody can read off them: the first would
	 * have rules and a region that nothing ever looks at, the second would be a place
	 * with no ground. Both are the same mistake — the kind was changed and the rest was
	 * not — and both are silent, which is what this validator exists to stop.
	 */
	private static void checkLocation(Dialogue dialogue, List<Problem> found) {
		if (dialogue.isLocation() && dialogue.location().isEmpty()) {
			found.add(new Problem(Severity.ERROR, null,
				"this document says it is a place, but nothing says which ground it lays "
					+ "out or what holds there"));
		}
		if (!dialogue.isLocation() && dialogue.location().isPresent()) {
			found.add(new Problem(Severity.ERROR, null,
				"this document carries a place's settings but is not a place, so nothing "
					+ "will ever read them — change what it is, or take them off"));
		}

		dialogue.location().ifPresent(place -> {
			if (place.region().length() > Location.LONGEST) {
				found.add(new Problem(Severity.ERROR, null,
					"the ground this place lays out is named with more than "
						+ Location.LONGEST + " letters"));
			}
			if (place.rules().size() > Location.RULES_MOST) {
				found.add(new Problem(Severity.ERROR, null,
					"this place sets " + place.rules().size() + " rules, and no more than "
						+ Location.RULES_MOST + " may be set"));
			}
			// Game rules belong to a world, and every place shares one world — so two
			// places cannot disagree about a rule while two guests are standing in them
			// at once. Only the hub may set them, because a map has one hub and there is
			// nobody for it to disagree with.
			//
			// Refused rather than quietly shared. A setting that works while you are
			// testing it alone and stops working when a second person arrives is the
			// worst kind of setting there is: it is not wrong at the moment anybody looks
			// at it. What a place wants for one person — what they may break, whether
			// they can fly, whether falling hurts — is a standing property, and that is
			// per person already.
			if (place.resets() && !place.rules().isEmpty()) {
				found.add(new Problem(Severity.ERROR, null,
					"this place sets game rules (" + String.join(", ", place.rules().keySet())
						+ "), and rules belong to a world rather than to a place — every "
						+ "place shares one. Set them on the hub, or say what you want as a "
						+ "standing property, which is about one person"));
			}
			// A place that is never rebuilt and can be changed is a place that drifts
			// away from what its author built, with nothing to put it back. That is the
			// hub, and the hub is the one thing a map cannot afford to lose.
			if (!place.resets() && !(place.mayEdit() instanceof Condition.Not(
					Condition.Always ignored))) {
				found.add(new Problem(Severity.WARNING, null,
					"this place is never rebuilt and lets guests change what is in it, so "
						+ "nothing will ever put back what they change"));
			}
		});

		if (dialogue.within().isEmpty()) return;
		if (dialogue.within().equals(dialogue.id())) {
			found.add(new Problem(Severity.ERROR, null,
				"this document says it lives inside itself"));
		}
		// Locations do not nest. Not because nesting is hard, but because "which
		// documents can I see" would stop having one answer, and that question is asked
		// on every opening of the list by somebody who needs it to be obvious.
		if (dialogue.isLocation()) {
			found.add(new Problem(Severity.ERROR, null,
				"a place cannot live inside another place"));
		}
		if (dialogue.within().length() > Dialogue.LONGEST_NAME) {
			found.add(new Problem(Severity.ERROR, null,
				"the place this document lives in is named with more than "
					+ Dialogue.LONGEST_NAME + " letters"));
		}
	}

	private static void checkScopes(Dialogue dialogue, List<Problem> found) {
		// Only in a document about the player. In one about things the subject is the
		// stack, so "what the subject remembers" is the stack's own memory — a real and
		// separate store, and the whole reason things are their own kind.
		if (dialogue.kind() != Dialogue.Kind.PLAYER) return;
		for (Variables.Use use : Variables.used(dialogue)) {
			if (use.scope() != Scope.CHARACTER) continue;
			found.add(new Problem(Severity.ERROR, use.node(),
				"uses \"" + use.name() + "\" in the character's own memory, but this "
					+ "document is about the player — say \"player\" instead"));
		}
	}

	private static void checkTraits(Dialogue dialogue, List<Problem> found) {
		for (Node node : dialogue.nodes().values()) {
			if (!(node instanceof Node.Act act)) continue;
			if (!(act.effect() instanceof Effect.Trait trait)) continue;
			if (trait.name().isBlank()) {
				found.add(new Problem(Severity.ERROR, node.id(),
					"gives a property under no name, so nothing could ever take it back"));
			}
			if (trait.attribute().isBlank()) {
				found.add(new Problem(Severity.ERROR, node.id(),
					"gives a property without saying which one"));
			}
		}
	}

	/**
	 * What may follow a verb that aims, said the same way in both places it is said.
	 *
	 * The named places are not listed and cannot be: which ones exist is a fact
	 * about a saved world, and this class is on the side of the fence that knows
	 * nothing about worlds. So the form is named instead, because the form is the
	 * part somebody gets wrong — writing "gate" where they meant "at:gate".
	 */
	private static String pointable() {
		return String.join(", ", Mark.KNOWN) + ", or " + Mark.AT + "<the name of a place>";
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
			// Writing on the canvas is unreachable by construction: nothing leads to it
			// and nothing is meant to. Warned about, it would put a permanent complaint
			// on every well-commented graph — and a warning that is always there is a
			// warning nobody reads, which is the fault this whole check guards against.
			if (dialogue.node(id).isWriting()) continue;
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
			// And writing has nothing to finish. It is not on any path, so asking it to
			// reach an ending is asking a question about a thing that is not in the
			// conversation at all.
			if (node.isWriting()) canEnd.add(node.id());
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
