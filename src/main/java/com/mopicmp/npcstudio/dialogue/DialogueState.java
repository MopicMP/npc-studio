package com.mopicmp.npcstudio.dialogue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where one player stands in one dialogue — the bookmark.
 *
 * This is not "the dialogue is playing". It is a place the player left off and
 * comes back to: pressing Esc on a choice, walking away, logging out, the
 * server restarting. All of them leave the bookmark where it was, and clicking
 * the NPC again resumes at that node rather than starting over.
 *
 * That is why this is a value that can be saved and reloaded, and why the
 * engine takes it as an argument instead of holding it. World variables are
 * deliberately part of it too: the engine has to read them, but it is handed a
 * copy for the step and the server writes the changes back, so a test can drive
 * a conversation with nothing but a map.
 */
public final class DialogueState {

	private final String currentNode;
	private final Map<String, Value> playerVars;
	private final Map<String, Value> worldVars;
	private final Map<String, Value> characterVars;
	private final Set<String> visited;
	private final Map<String, String> declaredTypes;

	public DialogueState(String currentNode,
			Map<String, Value> playerVars,
			Map<String, Value> worldVars,
			Map<String, Value> characterVars,
			Set<String> visited,
			Map<String, String> declaredTypes) {
		this.currentNode = currentNode;
		this.playerVars = Map.copyOf(playerVars);
		this.worldVars = Map.copyOf(worldVars);
		this.characterVars = Map.copyOf(characterVars);
		this.visited = Set.copyOf(visited);
		this.declaredTypes = Map.copyOf(declaredTypes);
	}

	/** A fresh bookmark at the start of a dialogue, with nothing remembered. */
	public static DialogueState start(Dialogue dialogue, Map<String, Value> worldVars) {
		return start(dialogue, worldVars, Map.of());
	}

	/**
	 * A fresh bookmark for a character who already knows things.
	 *
	 * The two are separate because they are forgotten at different moments: where
	 * she had got to is dropped whenever she is interrupted, and what she learnt
	 * is not. A character who begins her graph again but still knows she has
	 * already raised the alarm is behaving correctly; one who forgets both every
	 * time she is disturbed raises it for ever.
	 */
	public static DialogueState start(Dialogue dialogue, Map<String, Value> worldVars,
			Map<String, Value> characterVars) {
		return new DialogueState(dialogue.start(), Map.of(), worldVars, characterVars,
			Set.of(), dialogue.variableTypes());
	}

	public String currentNode() { return currentNode; }

	public Map<String, Value> playerVars() { return playerVars; }

	public Map<String, Value> worldVars() { return worldVars; }

	public Set<String> visited() { return visited; }

	/**
	 * Reads a variable, falling back to the declared type's default.
	 *
	 * Never absent and never null: an unwritten variable reads as 0, "" or false.
	 * A dialogue that asks about a flag nobody has set yet is ordinary — it is
	 * how "have you met me before" is written — so that must not be an error at
	 * runtime. The validator is where an *undeclared* name is caught.
	 */
	public Value get(String name, Scope scope) {
		Map<String, Value> from = switch (scope) {
			case PLAYER -> playerVars;
			case WORLD -> worldVars;
			case CHARACTER -> characterVars;
			// Nothing is stored for a reading — it is asked of the world, by
			// Condition.test, which never gets this far with one. Reaching here means
			// somebody read a sense off the state, and the empty map is the honest
			// answer: there is nothing here to read.
			case SENSE -> Map.of();
		};
		Value found = from.get(name);
		if (found != null) return found;
		String type = declaredTypes.get(name);
		return type == null ? new Value.Flag(false) : Value.defaultFor(type);
	}

	public Map<String, Value> characterVars() { return characterVars; }

	public DialogueState at(String node) {
		return new DialogueState(node, playerVars, worldVars, characterVars, visited, declaredTypes);
	}

	public DialogueState withVisited(String node) {
		Set<String> next = new HashSet<>(visited);
		next.add(node);
		return new DialogueState(currentNode, playerVars, worldVars, characterVars, next, declaredTypes);
	}

	/**
	 * Writes a variable.
	 *
	 * A sense is refused here rather than silently dropped: it is the last line of
	 * a defence the validator already mounts, and a graph that got this far while
	 * assigning to what a character feels is a graph nobody checked.
	 */
	public DialogueState with(String name, Scope scope, Value value) {
		if (scope == Scope.SENSE) {
			throw new IllegalArgumentException(
				"\"" + name + "\" is something she perceives, not something she can be told");
		}
		Map<String, Value> player = new HashMap<>(playerVars);
		Map<String, Value> world = new HashMap<>(worldVars);
		Map<String, Value> character = new HashMap<>(characterVars);
		switch (scope) {
			case PLAYER -> player.put(name, value);
			case WORLD -> world.put(name, value);
			case CHARACTER -> character.put(name, value);
			case SENSE -> throw new AssertionError();
		}
		return new DialogueState(currentNode, player, world, character, visited, declaredTypes);
	}

	@Override
	public String toString() {
		return "DialogueState[at=" + currentNode
			+ ", player=" + new java.util.TreeMap<>(playerVars)
			+ ", world=" + new java.util.TreeMap<>(worldVars)
			+ ", character=" + new java.util.TreeMap<>(characterVars)
			+ ", visited=" + Collections.unmodifiableSet(new java.util.TreeSet<>(visited)) + "]";
	}
}
