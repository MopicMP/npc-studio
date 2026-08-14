package com.mopicmp.npcstudio.dialogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A whole conversation: nodes, where it starts, and the variables it uses.
 *
 * Variables are declared rather than sprung into being on first write. It costs
 * the writer one line and buys the validator the ability to say "you compared
 * `gold` against a piece of text" instead of shrugging. Undeclared names are an
 * error, not a new variable — a typo in a name would otherwise create a second
 * variable that is always zero, and the dialogue would just quietly take the
 * wrong branch forever.
 *
 * `formatVersion` is here from the first commit on purpose. Dialogues live in
 * other people's world saves, the format will change, and a file that arrives
 * without a version number cannot be migrated — only guessed at.
 */
public record Dialogue(
		String id,
		int formatVersion,
		String start,
		Map<String, Node> nodes,
		Map<String, String> variableTypes) {

	public static final int CURRENT_FORMAT = 1;

	public Dialogue {
		// Not Map.copyOf: that returns an unordered map and throws away the order
		// the nodes were written in. It matters twice over — a writer opening their
		// own file wants their own order back, and without it saving a dialogue
		// twice produces two different files for no reason. The round-trip test
		// caught this; comparing maps for equality does not, since equality ignores
		// order.
		nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		variableTypes = Collections.unmodifiableMap(new LinkedHashMap<>(variableTypes));
	}

	public Node node(String id) {
		return nodes.get(id);
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	/** Assembling a dialogue in code, for tests and for the editor. */
	public static final class Builder {
		private final String id;
		private String start;
		private final Map<String, Node> nodes = new LinkedHashMap<>();
		private final Map<String, String> variables = new LinkedHashMap<>();

		private Builder(String id) { this.id = id; }

		public Builder start(String node) { this.start = node; return this; }

		public Builder variable(String name, String type) {
			// Fail here rather than at validation: a type that does not exist is a
			// mistake in the caller, not in the dialogue being described.
			Value.defaultFor(type);
			variables.put(name, type);
			return this;
		}

		public Builder add(Node node) {
			nodes.put(node.id(), node);
			if (start == null) start = node.id();
			return this;
		}

		public Dialogue build() {
			return new Dialogue(id, CURRENT_FORMAT, start, nodes, variables);
		}
	}
}
