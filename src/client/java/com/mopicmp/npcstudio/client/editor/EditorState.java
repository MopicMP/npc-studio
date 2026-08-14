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
		state.otherNames = otherNames;
		return state;
	}

	public String id() { return id; }

	public void id(String value) { id = value; }

	public String start() { return start; }

	public void start(String value) { start = value; }

	public List<Node> nodes() { return nodes; }

	public Map<String, String> variables() { return variables; }

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
		return new Dialogue(id, Dialogue.CURRENT_FORMAT, start, byId, variables);
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
