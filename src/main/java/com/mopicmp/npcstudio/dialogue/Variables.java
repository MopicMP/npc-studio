package com.mopicmp.npcstudio.dialogue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which variables a document actually uses, and what type each use implies.
 *
 * <h2>Why this exists apart from the validator</h2>
 *
 * The validator has known this all along — it is how it says "reads gold, which is not
 * declared". But it only ever <em>reported</em> it, and the editor had no way to declare
 * anything, so the report was a dead end: a red line naming a fault with no door
 * anywhere leading to the fix. Reported in exactly those words — "why does it say the
 * variable is not declared, I do not understand how to work with this".
 *
 * So the same reading is available to the editor, which offers to declare what it finds.
 * One reading rather than two, because two would drift and the drift would be an editor
 * offering to fix something the validator still refuses.
 *
 * <h2>Why the declaration is kept at all</h2>
 *
 * It would be easy to conclude the requirement is the problem and drop it. It is not.
 * Without a declaration a mistyped name is a second variable that is always zero, and
 * the graph takes the wrong branch for ever with nothing anywhere to notice — which is
 * the failure that has no symptom. What was wrong was never the rule; it was that the
 * rule had no door.
 */
public final class Variables {

	private Variables() { }

	/**
	 * One place a document names a variable.
	 *
	 * @param type what this use implies it is: what is written into it, or what it is
	 *             compared against. A guess in the sense that a use does not
	 *             <em>declare</em> anything — but it is the guess somebody would make
	 *             looking at the node, which is what makes it worth offering.
	 */
	public record Use(String name, Scope scope, String type, String node) { }

	/**
	 * Every use of a variable the document is responsible for declaring.
	 *
	 * Readings from a character's senses and values handed in by a caller are left out.
	 * Neither is declared here — one is offered by the character and the list is fixed,
	 * the other is declared by whoever calls — so offering to declare them would be
	 * offering to write down something that is not this document's to say.
	 */
	public static List<Use> used(Dialogue dialogue) {
		List<Use> found = new ArrayList<>();
		for (Node node : dialogue.nodes().values()) {
			List<Condition.VariableUse> reads = new ArrayList<>();
			switch (node) {
				case Node.Choice choice ->
					choice.options().forEach(o -> o.condition().collectVariables(reads));
				case Node.Branch branch ->
					branch.arms().forEach(a -> a.condition().collectVariables(reads));
				case Node.Until until -> until.condition().collectVariables(reads);
				case Node.Set set -> {
					if (set.scope() != Scope.SENSE && set.scope() != Scope.GIVEN) {
						found.add(new Use(set.variable(), set.scope(),
							set.value().typeName(), node.id()));
					}
				}
				// A reset is left out on purpose, and it is the one omission worth
				// writing down. This list exists to offer a declaration for a name that
				// has not got one, and the offer needs a type — which every other use
				// supplies by comparing against something or writing something. A reset
				// compares against nothing: there is no evidence anywhere in the node of
				// what the name holds. Guessing would put a confident wrong answer in
				// front of somebody, and the validator already names the node.
				case Node.Forget _ -> { }
				default -> { }
			}
			for (Condition.VariableUse read : reads) {
				if (read.scope() == Scope.SENSE || read.scope() == Scope.GIVEN) continue;
				found.add(new Use(read.name(), read.scope(),
					read.comparedWith().typeName(), node.id()));
			}
		}
		// And what the standing properties read. A condition there is a condition like
		// any other, and leaving it out meant a name gating an ability could be misspelt
		// with nothing to say so — an ability that never turns on, which is exactly the
		// silence everything else here is built to break.
		for (com.mopicmp.npcstudio.dialogue.Standing rule : dialogue.standing()) {
			List<Condition.VariableUse> reads = new ArrayList<>();
			rule.when().collectVariables(reads);
			for (Condition.VariableUse read : reads) {
				if (read.scope() == Scope.SENSE || read.scope() == Scope.GIVEN) continue;
				found.add(new Use(read.name(), read.scope(), read.comparedWith().typeName(),
					"the property \"" + rule.name() + "\""));
			}
		}
		// And what the gauges show. A gauge naming a variable with a letter out of place
		// shows nought for ever — which reads as a stamina that never fills, and is
		// exactly the silence everything else here is built to break.
		for (com.mopicmp.npcstudio.dialogue.Gauge gauge : dialogue.gauges()) {
			if (!gauge.variable().isEmpty()) {
				found.add(new Use(gauge.variable(), gauge.scope(), "number",
					"the gauge \"" + gauge.label() + "\""));
			}
			List<Condition.VariableUse> reads = new ArrayList<>();
			gauge.when().collectVariables(reads);
			for (Condition.VariableUse read : reads) {
				if (read.scope() == Scope.SENSE || read.scope() == Scope.GIVEN) continue;
				found.add(new Use(read.name(), read.scope(), read.comparedWith().typeName(),
					"the gauge \"" + gauge.label() + "\""));
			}
		}
		return found;
	}

	/**
	 * The names this document uses and has not declared, with the type each implies.
	 *
	 * <h2>When one name is used two ways</h2>
	 *
	 * The first use wins, and that is deliberate rather than arbitrary. Declaring it as
	 * one of the two turns the disagreement into an error the validator can name — "you
	 * wrote a number into a flag" — pointing at the node that disagrees. Declaring
	 * nothing, or guessing at what was meant, would leave the same disagreement with the
	 * vaguer message it has now.
	 */
	public static Map<String, String> undeclared(Dialogue dialogue) {
		Map<String, String> missing = new LinkedHashMap<>();
		for (Use use : used(dialogue)) {
			if (dialogue.variableTypes().containsKey(use.name())) continue;
			if (use.name().isEmpty()) continue;
			missing.putIfAbsent(use.name(), use.type());
		}
		return missing;
	}

	/** Declared names nothing in the document mentions. Scrap, and safe to take away. */
	public static List<String> unused(Dialogue dialogue) {
		List<String> named = used(dialogue).stream().map(Use::name).toList();
		List<String> idle = new ArrayList<>();
		for (String declared : dialogue.variableTypes().keySet()) {
			if (!named.contains(declared)) idle.add(declared);
		}
		return idle;
	}
}
