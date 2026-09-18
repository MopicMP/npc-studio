package com.mopicmp.npcstudio.client.editor;

import java.util.List;

import com.mopicmp.npcstudio.net.VariablePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Every variable name the world knows, as the editor's list sees it.
 *
 * <h2>Why the editor could not offer a list until now</h2>
 *
 * Because it holds one document and the names live in all of them. A name was typed
 * from memory, and a name typed from memory is a name eventually typed twice with a
 * difference — at which point there are two variables, both declared, both valid, and
 * a branch that never fires with nothing anywhere to say why.
 *
 * <h2>Why names from other documents are in the same list</h2>
 *
 * Because they are the same variables. The store is one map for the whole world, keyed
 * by the player and not by the document, so a flag set by the errand and read by the
 * shopkeeper is one flag. Hiding the shopkeeper's names from the errand would be the
 * editor asserting a separation the runtime does not have.
 *
 * They are kept apart in the list all the same — this document's above, everybody
 * else's below — because reaching for a name on purpose and reaching for one by
 * accident look identical at the moment of the click, and only one of them is meant.
 */
public final class KnownVariables {

	private KnownVariables() { }

	private static List<VariablePayloads.Known> known = List.of();

	public static void ask() {
		ClientPlayNetworking.send(new VariablePayloads.Please());
	}

	public static void accept(List<VariablePayloads.Known> now) {
		known = List.copyOf(now);
	}

	/** Dropped with the world. The next one has its own documents and its own names. */
	public static void forget() {
		known = List.of();
	}

	public static List<VariablePayloads.Known> all() {
		return known;
	}
}
