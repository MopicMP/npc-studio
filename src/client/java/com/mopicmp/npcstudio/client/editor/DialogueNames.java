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

	private static List<String> known = List.of();

	private DialogueNames() { }

	public static void remember(List<String> names) {
		known = List.copyOf(names);
	}

	public static List<String> known() {
		return known;
	}
}
