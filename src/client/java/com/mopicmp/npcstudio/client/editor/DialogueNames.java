package com.mopicmp.npcstudio.client.editor;

import java.util.List;

/**
 * The dialogues this server has, as last reported.
 *
 * Only the server knows what exists, so the client cannot compile this list for
 * itself. It is kept because a drop-down has to be able to open without a round
 * trip: a menu that appears empty and fills in a moment later is a menu people
 * click through before it is ready.
 */
public final class DialogueNames {

	private static List<com.mopicmp.npcstudio.net.EditorPayloads.Known> known = List.of();

	private DialogueNames() { }

	public static void remember(List<com.mopicmp.npcstudio.net.EditorPayloads.Known> graphs) {
		known = List.copyOf(graphs);
	}

	public static List<String> known() {
		return known.stream().map(com.mopicmp.npcstudio.net.EditorPayloads.Known::name).toList();
	}

	/**
	 * The ones worth offering for a character's own field.
	 *
	 * <h2>Why this used to be two lists</h2>
	 *
	 * Because a character had two fields that both took the name of a graph, and
	 * putting a behaviour graph in the conversation one left her standing perfectly
	 * still with nothing anywhere saying why. That happened twice — the second time
	 * the field was already a list, it simply offered everything.
	 *
	 * There is one field now. A brain is a library of skills, and a library is not
	 * something a character is given; it is something her dialogue calls. So the
	 * question is no longer "which of the two jobs does this fit" but "can this be
	 * carried at all", which is asked once — see {@code Known.fitsOnACharacter}.
	 */
	public static List<String> forCharacter() {
		return known.stream()
			.filter(com.mopicmp.npcstudio.net.EditorPayloads.Known::fitsOnACharacter)
			.map(com.mopicmp.npcstudio.net.EditorPayloads.Known::name).toList();
	}

	/**
	 * Every skill anything here can be called into, as a call would name it.
	 *
	 * A skill of this document is named plainly; one of anybody else's carries the
	 * document it lives in — {@code fighting:fight} — which is how a call reaches
	 * across, and how a library of skills is usable at all.
	 *
	 * Offered rather than typed, on purpose. A skill named by typing is a skill
	 * misspelt sooner or later, and a call to a skill that is not there leaves a
	 * character standing perfectly still — which from outside is indistinguishable
	 * from a fight that never started. That has now happened twice for two
	 * different reasons, and both times the cure was a list instead of a box.
	 */
	public static List<String> skillsFor(String thisDocument, Iterable<String> own) {
		List<String> all = new java.util.ArrayList<>();
		for (String skill : own) all.add(skill);
		for (var graph : known) {
			if (graph.name().equals(thisDocument)) continue;
			for (String skill : graph.skills()) all.add(graph.name() + ":" + skill);
		}
		return List.copyOf(all);
	}
}
