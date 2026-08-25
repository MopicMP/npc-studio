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
		Map<String, String> variableTypes,
		Map<String, String> segments) {

	/**
	 * A graph may have more than one way in.
	 *
	 * <h2>What a segment is for</h2>
	 *
	 * {@code start} is where the graph begins when it is somebody's own. A
	 * segment is a second, named beginning, meant to be called from elsewhere:
	 * "how to fight", "how to take cover", "how to look busy". The nodes and the
	 * declared variables are shared, so a document is a set of related skills
	 * rather than a pile of unrelated files.
	 *
	 * <h2>Why this and not a graph per skill</h2>
	 *
	 * Because skills come in families and want to share. Fighting and retreating
	 * read the same variables and lead into one another; kept as separate
	 * documents they would need those variables declared twice, in step, for ever.
	 *
	 * A document with no segments is an ordinary graph and always was — which is
	 * why every one written before this goes on working without a line changed.
	 */

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
		segments = Collections.unmodifiableMap(new LinkedHashMap<>(segments));
	}

	/**
	 * The five-part form, for everything written before segments existed.
	 *
	 * Not a convenience. A graph without segments is the ordinary case and will
	 * stay the ordinary case, and making every caller pass an empty map would be
	 * asking them all to say the same nothing.
	 */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes) {
		this(id, formatVersion, start, nodes, variableTypes, Map.of());
	}

	/** Where a named way in begins, or null if there is no such way in. */
	public String segment(String name) {
		return segments.get(name);
	}

	/**
	 * Whether anybody says anything in it.
	 *
	 * <h2>Why a graph has a sort at all</h2>
	 *
	 * One language, two runtimes, and each can serve only part of it: a
	 * conversation cannot stand and wait, and a brain has nobody to speak to.
	 * Both refuse when handed the wrong thing — but a refusal that arrives when a
	 * player clicks an NPC is a refusal nobody sees.
	 *
	 * Asked here, it can be used at the moment somebody is choosing, which is the
	 * moment they are looking. That was reported the hard way, twice: a behaviour
	 * graph put in the conversation field, two characters standing still, and
	 * nothing anywhere saying why.
	 */
	public boolean speaks() {
		return nodes().values().stream()
			.anyMatch(node -> node instanceof Node.Line || node instanceof Node.Choice);
	}

	/** Whether it ever stands still on its own account: a timer or a question. */
	public boolean waits() {
		return nodes().values().stream()
			.anyMatch(node -> node instanceof Node.Every || node instanceof Node.Until);
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
		private final Map<String, String> segments = new LinkedHashMap<>();

		private Builder(String id) { this.id = id; }

		/** Names a way in, at the node the next {@link #add} will put there. */
		public Builder segment(String name, String at) {
			segments.put(name, at);
			return this;
		}

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
			return new Dialogue(id, CURRENT_FORMAT, start, nodes, variables, segments);
		}
	}
}
