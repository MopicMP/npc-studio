package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * The dialogue being edited, on the client.
 *
 * Held as a plain mutable list of nodes rather than as a {@link Dialogue},
 * because a dialogue is immutable and correct by construction while something
 * half-edited is neither. Turning it back into one happens on save, which is
 * also where it gets checked.
 *
 * The validator runs on every change, not only on save. Someone building a
 * conversation should see "menu leads to repair, which does not exist" while
 * they are still looking at the node they broke, not after they have moved on
 * and forgotten it.
 */
public final class EditorState {

	private String id;
	private String start;
	private final List<Node> nodes = new ArrayList<>();
	private final Map<String, String> variables = new LinkedHashMap<>();

	/**
	 * The named ways into this document: the skills it holds.
	 *
	 * Carried through the editor rather than only through the file. Left out, a
	 * brain opened here and saved would come back with every skill gone — the
	 * nodes still present and nothing able to reach them — and nobody would find
	 * out until a character stopped fighting.
	 */
	private final Map<String, String> segments = new LinkedHashMap<>();
	private List<String> otherNames = List.of();

	public static EditorState from(String json, List<String> otherNames) {
		Dialogue dialogue = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
			.getOrThrow(message -> new IllegalStateException("the server sent a dialogue we cannot read: " + message));

		EditorState state = new EditorState();
		state.id = dialogue.id();
		state.start = dialogue.start();
		state.nodes.addAll(dialogue.nodes().values());
		state.variables.putAll(dialogue.variableTypes());
		state.segments.putAll(dialogue.segments());
		state.otherNames = otherNames;
		return state;
	}

	public String id() { return id; }

	public void id(String value) { id = value; }

	public String start() { return start; }

	public void start(String value) { start = value; }

	public List<Node> nodes() { return nodes; }

	public Map<String, String> variables() { return variables; }

	/** The skills this document holds, name to the node they begin at. */
	public Map<String, String> segments() { return segments; }

	/**
	 * Names a skill at a node, or takes the name off it.
	 *
	 * Taking it off does not delete the nodes. A skill nobody calls is still a
	 * piece of work somebody did, and losing it to a mis-click would be the sort
	 * of thing people stop using an editor over.
	 */
	public void segment(String name, String at) {
		if (at == null) segments.remove(name);
		else segments.put(name, at);
	}

	public List<String> otherNames() { return otherNames; }

	/** Every node id in this dialogue, for the "goes to" pickers. */
	public List<String> nodeIds() {
		return nodes.stream().map(Node::id).toList();
	}

	public void replace(int index, Node node) {
		nodes.set(index, node);
	}

	public void add(Node node) {
		nodes.add(node);
	}

	public void remove(int index) {
		nodes.remove(index);
	}

	/**
	 * A node id nothing else is using.
	 *
	 * Numbered by type, so a graph reads as "line3 goes to choice1" rather than
	 * as a list of anonymous numbers. Names can be changed afterwards; this only
	 * has to be unique and not meaningless.
	 */
	public String freshId(String prefix) {
		List<String> taken = nodeIds();
		for (int n = 1; ; n++) {
			String candidate = prefix + n;
			if (!taken.contains(candidate)) return candidate;
		}
	}

	public Dialogue build() {
		Map<String, Node> byId = new LinkedHashMap<>();
		for (Node node : nodes) byId.put(node.id(), node);
		return new Dialogue(id, Dialogue.CURRENT_FORMAT, start, byId, variables, segments);
	}

	public String toJson() {
		return DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, build())
			.getOrThrow(message -> new IllegalStateException(message))
			.toString();
	}

	/** What is wrong with it right now, in the order the validator found it. */
	public List<DialogueValidator.Problem> problems() {
		if (nodes.isEmpty()) return List.of();
		return DialogueValidator.validate(build()).problems();
	}
}
