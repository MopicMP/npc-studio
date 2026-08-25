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
	 * The ones worth offering for a job.
	 *
	 * <h2>Why the two lists are not the same list</h2>
	 *
	 * Because a character has two fields that both take the name of a graph, and
	 * putting a behaviour graph in the conversation one leaves her standing
	 * perfectly still with nothing anywhere saying why. That happened twice, and
	 * the second time the field was already a list — it simply offered everything.
	 *
	 * A graph that can serve either is offered in both. A sketch with neither
	 * lines nor waiting is offered in both as well: it is not finished, and
	 * guessing which half it will grow into would be worse than offering it.
	 */
	public static List<String> forBrain() {
		return known.stream()
			.filter(com.mopicmp.npcstudio.net.EditorPayloads.Known::fitsAsBrain)
			.map(com.mopicmp.npcstudio.net.EditorPayloads.Known::name).toList();
	}

	public static List<String> forConversation() {
		return known.stream()
			.filter(com.mopicmp.npcstudio.net.EditorPayloads.Known::fitsAsConversation)
			.map(com.mopicmp.npcstudio.net.EditorPayloads.Known::name).toList();
	}
}
