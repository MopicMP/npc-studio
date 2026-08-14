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
	private final Set<String> visited;
	private final Map<String, String> declaredTypes;

	public DialogueState(String currentNode,
			Map<String, Value> playerVars,
			Map<String, Value> worldVars,
			Set<String> visited,
			Map<String, String> declaredTypes) {
		this.currentNode = currentNode;
		this.playerVars = Map.copyOf(playerVars);
		this.worldVars = Map.copyOf(worldVars);
		this.visited = Set.copyOf(visited);
		this.declaredTypes = Map.copyOf(declaredTypes);
	}

	/** A fresh bookmark at the start of a dialogue. */
	public static DialogueState start(Dialogue dialogue, Map<String, Value> worldVars) {
		return new DialogueState(dialogue.start(), Map.of(), worldVars, Set.of(), dialogue.variableTypes());
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
		Map<String, Value> from = scope == Scope.PLAYER ? playerVars : worldVars;
		Value found = from.get(name);
		if (found != null) return found;
		String type = declaredTypes.get(name);
		return type == null ? new Value.Flag(false) : Value.defaultFor(type);
	}

	public DialogueState at(String node) {
		return new DialogueState(node, playerVars, worldVars, visited, declaredTypes);
	}

	public DialogueState withVisited(String node) {
		Set<String> next = new HashSet<>(visited);
		next.add(node);
		return new DialogueState(currentNode, playerVars, worldVars, next, declaredTypes);
	}

	public DialogueState with(String name, Scope scope, Value value) {
		Map<String, Value> player = new HashMap<>(playerVars);
		Map<String, Value> world = new HashMap<>(worldVars);
		(scope == Scope.PLAYER ? player : world).put(name, value);
		return new DialogueState(currentNode, player, world, visited, declaredTypes);
	}

	@Override
	public String toString() {
		return "DialogueState[at=" + currentNode
			+ ", player=" + new java.util.TreeMap<>(playerVars)
			+ ", world=" + new java.util.TreeMap<>(worldVars)
			+ ", visited=" + Collections.unmodifiableSet(new java.util.TreeSet<>(visited)) + "]";
	}
}
