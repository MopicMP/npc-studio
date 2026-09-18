package com.mopicmp.npcstudio.client.editor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mopicmp.npcstudio.dialogue.Node;

/**
 * Who was speaking just before this point in the graph.
 *
 * <h2>What it is for</h2>
 *
 * A conversation is almost always one person talking for several nodes running.
 * Wiring a line onto the end of another line and then typing the same name into
 * the same field for the fourth time is not authoring, it is copying — and the
 * copy is the part that goes wrong, because a name spelt two ways is two
 * speakers as far as everything downstream is concerned.
 *
 * So drawing a wire into a node that has no speaker of its own fills it in from
 * where the wire came. It is a default and nothing more: a name already written
 * is never touched, and the field goes on being an ordinary field afterwards.
 *
 * <h2>Why it walks backwards instead of reading one node</h2>
 *
 * Because the node a wire leaves is often not a talking node. A line, then a
 * pause, then the next line is an ordinary shape, and the pause has no speaker
 * to lend — so reading only the immediate source would work on the plain case
 * and quietly stop working the moment anything was put between two lines, which
 * is the worst kind of half-feature: one that teaches you to trust it.
 *
 * The walk stops at a fork rather than guessing. Two different conversations
 * meeting at a node is exactly where the answer is genuinely unknown, and
 * putting one of the two names in would be a guess wearing the clothes of a
 * fact.
 *
 * <h2>Why this is a class of its own</h2>
 *
 * So it can be tested. The editor it belongs to is a screen, and a screen cannot
 * be built without a window; every rule that lives inside one is a rule nothing
 * checks. This is the same split as {@code Shortening} and for the same reason.
 */
public final class SpeakerCarry {

	private SpeakerCarry() { }

	/** Far enough to cross any run of silent nodes anybody writes on purpose. */
	private static final int BACK = 16;

	/**
	 * The speaker a node wired on after {@code afterId} should inherit, or null.
	 *
	 * Null is the ordinary answer for a graph that has not named anybody yet, and
	 * means "leave the field alone" rather than "clear it".
	 */
	public static String after(List<Node> nodes, String afterId) {
		Set<String> seen = new HashSet<>();
		String at = afterId;
		for (int step = 0; step < BACK && at != null && seen.add(at); step++) {
			Node node = find(nodes, at);
			if (node == null) return null;

			String said = speakerOf(node);
			if (said != null && !said.isBlank()) return said;

			at = onlyBefore(nodes, at);
		}
		return null;
	}

	/**
	 * The same node with a speaker filled in, or the very same node.
	 *
	 * Returning the same object when nothing changed matters to the caller: the
	 * editor writes back only what it was given, and a node rebuilt identically
	 * would still count as an edit and set the autosave going for no reason.
	 */
	public static Node given(Node node, String speaker) {
		if (speaker == null || speaker.isBlank()) return node;
		return switch (node) {
			case Node.Line line when isBlank(line.speaker()) -> line.withSpeaker(speaker);
			case Node.Choice choice when isBlank(choice.speaker()) ->
				new Node.Choice(choice.id(), speaker, choice.prompt(), choice.mode(),
					choice.options());
			default -> node;
		};
	}

	/** The name a node says its lines under, or null when it is not a talking node. */
	public static String speakerOf(Node node) {
		return switch (node) {
			case Node.Line line -> line.speaker();
			case Node.Choice choice -> choice.speaker();
			default -> null;
		};
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static Node find(List<Node> nodes, String id) {
		for (Node node : nodes) {
			if (node.id().equals(id)) return node;
		}
		return null;
	}

	/**
	 * The one node leading here, or null when there is not exactly one.
	 *
	 * A node's own loop back to itself does not count as leading anywhere: every
	 * node this editor creates starts out pointing at itself, so counting it would
	 * make almost every fresh node look like a fork.
	 */
	private static String onlyBefore(List<Node> nodes, String id) {
		String only = null;
		for (Node node : nodes) {
			if (node.id().equals(id)) continue;
			if (!node.exits().contains(id)) continue;
			if (only != null) return null;
			only = node.id();
		}
		return only;
	}
}
