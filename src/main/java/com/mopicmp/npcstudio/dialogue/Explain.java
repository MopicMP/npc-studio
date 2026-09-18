package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * A condition written out with the values that were actually in it.
 *
 * <h2>What this is for</h2>
 *
 * Reported as: "I worked with the branch, declared the variables, everything looks
 * right, but something breaks and I cannot tell what." Which is the honest description
 * of a graph that runs correctly and does something else than intended — nothing is
 * wrong, so nothing complains, and the only visible symptom is a scene going the wrong
 * way.
 *
 * The validator cannot help here. It checks what can be known without running: that a
 * name is declared, that types match, that a node exists. Whether {@code errand_sent} is
 * true at this moment is not that kind of question — it has an answer only while
 * somebody is standing in front of a character.
 *
 * So the answer is to say what happened, in the words the author wrote, with the values
 * that were in them: {@code errand_sent (player) = false, wanted true — no}.
 *
 * <h2>Why it is rendered from the same pass that decided</h2>
 *
 * Because a second evaluation can disagree with the first, and a debugging tool that
 * lies is worse than none — it sends somebody looking in the wrong place with
 * confidence. Everything here is handed the same state the engine used and reads the
 * same values through the same door.
 */
public final class Explain {

	private Explain() { }

	/** One arm of a branch, as it read at the moment it was asked. */
	public record Told(String text, boolean held) { }

	/**
	 * Every arm of a branch, in order, with the last word on which one won.
	 *
	 * In order because order is the thing people get wrong about branches: the first arm
	 * that holds wins, so an arm that is true and never reached is a real and invisible
	 * fault. Written out in order, it is visible.
	 */
	public static List<Told> arms(Node.Branch branch, DialogueState state, Condition.World world) {
		var told = new java.util.ArrayList<Told>();
		boolean settled = false;
		for (Node.Arm arm : branch.arms()) {
			boolean held = arm.condition().test(state, world);
			String mark = !held ? "no" : settled ? "yes, but too late" : "yes";
			told.add(new Told(say(arm.condition(), state, world) + " → " + arm.next()
				+ "  [" + mark + "]", held));
			if (held) settled = true;
		}
		told.add(new Told("otherwise → " + branch.otherwise()
			+ (settled ? "" : "  [taken]"), !settled));
		return told;
	}

	/**
	 * A condition as text, with each value it looked at spelled out.
	 *
	 * The value first and what it was wanted to be second, because the value is what
	 * somebody does not know and the wanted is what they wrote.
	 */
	public static String say(Condition condition, DialogueState state, Condition.World world) {
		return switch (condition) {
			case Condition.Always _ -> "always";
			case Condition.Not(Condition inner) -> "not (" + say(inner, state, world) + ")";
			case Condition.All(List<Condition> parts) -> join(parts, " and ", state, world);
			case Condition.Any(List<Condition> parts) -> join(parts, " or ", state, world);
			case Condition.Visited(String node) ->
				"visited " + node + " = " + state.visited().contains(node);
			case Condition.HasItem(String item, int count) ->
				"has " + count + " x " + item + " = " + world.hasItem(item, count);
			case Condition.Inside(String area) -> "inside " + area + " = " + world.inside(area);
			// A block reads false both when it is something else and when nobody is near
			// enough for the chunk to be loaded, and those are worth telling apart while
			// debugging — but the world has only one answer to give, so the line says
			// which place it looked at and leaves the rest to whoever is standing there.
			case Condition.Block(String mark, String block) ->
				block + " at " + mark + " = " + world.blockAt(mark, block);
			case Condition.Holding(String item) ->
				"holding " + item + " = " + world.holding(item);
			case Condition.Compare(String name, Scope scope, Condition.Op op, Value wanted) -> {
				Value actual = scope == Scope.SENSE ? world.sense(name) : state.get(name, scope);
				// The types are said only when they disagree, which is the case where the
				// comparison quietly has no answer. Printed always, they would be noise on
				// every line of a working graph.
				String types = actual.typeName().equals(wanted.typeName())
					? "" : "  (" + actual.typeName() + " against " + wanted.typeName() + ")";
				yield name + " (" + scope.name().toLowerCase() + ") = " + show(actual)
					+ ", wanted " + op.name().toLowerCase() + " " + show(wanted) + types;
			}
		};
	}

	private static String join(List<Condition> parts, String between,
			DialogueState state, Condition.World world) {
		if (parts.isEmpty()) return "nothing";
		var said = new java.util.ArrayList<String>();
		for (Condition part : parts) said.add(say(part, state, world));
		return "(" + String.join(between, said) + ")";
	}

	/**
	 * A value as it would be written in the editor.
	 *
	 * Text is quoted and everything else is not, because the one thing worth telling
	 * apart at a glance is the number three from the word three — which is exactly the
	 * comparison that silently never matches.
	 */
	public static String show(Value value) {
		return switch (value) {
			case Value.Text(String text) -> "\"" + text + "\"";
			case Value.Flag(boolean flag) -> String.valueOf(flag);
			case Value.Num(double number) -> number == Math.floor(number) && !Double.isInfinite(number)
				? String.valueOf((long) number) : String.valueOf(number);
		};
	}

	/**
	 * Everything the document may remember, and what is in it right now.
	 *
	 * Driven by the declarations rather than by what has been written, so a variable
	 * nothing has set yet appears with its type's default instead of being absent. That
	 * is the whole question in "the branch did not fire": the name is there, and it
	 * holds false because nobody wrote to it.
	 */
	public static List<String> variables(Dialogue dialogue, DialogueState state) {
		var said = new java.util.ArrayList<String>();
		for (var declared : dialogue.variableTypes().entrySet()) {
			String name = declared.getKey();
			// Every scope it could be in, because a name is declared once and may be
			// written in any of them — and "set in the wrong scope" is a fault that looks
			// exactly like "not set at all" from the branch that reads it.
			said.add(name + ": player " + show(state.get(name, Scope.PLAYER))
				+ ", world " + show(state.get(name, Scope.WORLD))
				+ ", character " + show(state.get(name, Scope.CHARACTER)));
		}
		return said;
	}
}
