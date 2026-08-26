package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;
import com.mopicmp.npcstudio.net.EditorPayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server half of the dialogue editor.
 *
 * Everything a client sends is treated as a request rather than as a fact.
 * Whoever is on the other end may be out of date, may have a stale copy, or may
 * not be someone who should be editing at all — so permission, parsing and
 * validation all happen here, and the client is told what came of it.
 */
public final class DialogueEditing {

	private DialogueEditing() { }

	/**
	 * Who may use the editor.
	 *
	 * The same bar as the commands, which is where server owners already control
	 * this through whatever permissions plugin they run. Writing a second
	 * mechanism would only be a second thing for them to configure and a second
	 * place for the two to disagree.
	 */
	private static boolean mayEdit(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.createCommandSourceStack().permissions());
	}

	/** Sends the list of dialogues so the player can pick one. */
	public static void browse(ServerPlayer player) {
		if (!mayEdit(player)) return;
		// With what each one can be used as. Only the server can say — the client
		// has a name and nothing else — and a name alone has already proved not to
		// be enough to choose with.
		ServerPlayNetworking.send(player, new EditorPayloads.Listing(
			DialogueRegistry.names().stream()
				.map(name -> DialogueRegistry.get(name)
					.map(graph -> new EditorPayloads.Known(name, graph.speaks(), graph.waits(),
						List.copyOf(graph.segments().keySet())))
					.orElseGet(() -> new EditorPayloads.Known(name, true, true, List.of())))
				.toList()));
	}

	public static void open(ServerPlayer player, String id) {
		if (!mayEdit(player)) return;

		Dialogue dialogue = id.isEmpty()
			? blank(freshName(player))
			: DialogueRegistry.get(id).orElseGet(() -> blank(id));

		JsonElement json = DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, dialogue)
			.result().orElse(null);
		if (json == null) return;

		ServerPlayNetworking.send(player,
			new EditorPayloads.Editing(json.toString(), DialogueRegistry.names()));
	}

	public static void save(ServerPlayer player, String json) {
		if (!mayEdit(player)) return;

		DataResult<Dialogue> parsed;
		try {
			parsed = DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
		} catch (RuntimeException malformed) {
			reply(player, false, "That is not valid JSON: " + malformed.getMessage());
			return;
		}

		if (parsed.isError()) {
			reply(player, false, parsed.error().orElseThrow().message());
			return;
		}

		// Validation lives in the store, so a dialogue that arrives from a datapack
		// and one built in the editor are held to exactly the same standard.
		String problem = WorldDialogues.of(player.level()).put(parsed.getOrThrow());
		reply(player, problem == null, problem == null ? "Saved." : problem);
	}

	private static void reply(ServerPlayer player, boolean ok, String message) {
		ServerPlayNetworking.send(player, new EditorPayloads.Saved(ok, message));
	}

	/**
	 * A new dialogue that already runs.
	 *
	 * One line and an end, rather than an empty graph. An empty one would fail
	 * validation the moment it was saved, and greeting someone with an error for
	 * having created something is a poor way to start.
	 */
	private static Dialogue blank(String id) {
		return Dialogue.builder(id)
			.start("line1")
			.add(new Node.Line("line1", "", "Hello.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}

	/** A name that is not taken yet, so a second new dialogue does not overwrite the first. */
	private static String freshName(ServerPlayer player) {
		List<String> taken = DialogueRegistry.names();
		// No colon: that reads as a namespace, and a name a player invents has no
		// business claiming one. It also kept the name from being typed into a
		// command at all.
		String base = player.getGameProfile().name().toLowerCase() + "_dialogue";
		for (int n = 1; ; n++) {
			String candidate = base + n;
			if (!taken.contains(candidate)) return candidate;
		}
	}
}
