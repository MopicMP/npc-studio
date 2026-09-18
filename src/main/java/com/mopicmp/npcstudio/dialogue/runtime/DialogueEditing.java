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

	/**
	 * Sends the list of dialogues.
	 *
	 * @param toShow carried back untouched: what the asker meant to do with the
	 *               answer is the asker's business, and the server keeps no view of
	 *               anybody's windows
	 */
	public static void browse(ServerPlayer player, boolean toShow) {
		if (!mayEdit(player)) return;
		// With what each one can be used as. Only the server can say — the client
		// has a name and nothing else — and a name alone has already proved not to
		// be enough to choose with.
		// What this person can see from where they are standing. An author on their own
		// map sees the map's; a guest inside a lesson sees that lesson's and nothing
		// else, which is the whole of what "their sets must not overlap" asked for.
		ServerPlayNetworking.send(player, new EditorPayloads.Listing(
			DialogueRegistry.names(com.mopicmp.npcstudio.map.Whereabouts.of(player)).stream()
				.map(name -> DialogueRegistry.get(name)
					.map(graph -> new EditorPayloads.Known(name, graph.speaks(), graph.waits(),
						List.copyOf(graph.segments().keySet()),
						graph.kind().name().toLowerCase()))
					.orElseGet(() -> new EditorPayloads.Known(name, true, true, List.of(), "scene")))
				.toList(),
			toShow));
	}

	public static void open(ServerPlayer player, String id) {
		open(player, id, "scene");
	}

	/**
	 * Opens a document, or makes one of the sort the window asked for.
	 *
	 * The sort is only listened to when there is nothing to open. An existing document
	 * knows what it is, and taking the packet's word for it would mean any document
	 * could be turned into any other by opening it from the wrong tab.
	 */
	public static void open(ServerPlayer player, String id, String kind) {
		if (!mayEdit(player)) return;

		Dialogue.Kind wanted = kindOf(kind);
		Dialogue dialogue = id.isEmpty()
			? blank(freshName(player, wanted), wanted)
			: DialogueRegistry.get(id).orElseGet(() -> blank(id, wanted));

		JsonElement json = DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, dialogue)
			.result().orElse(null);
		if (json == null) return;

		ServerPlayNetworking.send(player,
			new EditorPayloads.Editing(json.toString(), DialogueRegistry.names()));
	}

	/**
	 * Gives a dialogue another name, and takes the old one away.
	 *
	 * <h2>The two halves, and why they are one act</h2>
	 *
	 * Writing the document under the new name is the easy half; forgetting the old
	 * one is the half that makes it a rename. Done as two steps by the author — save
	 * as, then delete — the window in between is a world with two copies of the same
	 * conversation, and whichever characters were pointing at it are still pointing
	 * at the one about to be deleted.
	 *
	 * <h2>What it refuses</h2>
	 *
	 * A name already taken, because putting one document on top of another is not a
	 * rename either. An empty name, which is not a name. And a name identical to the
	 * one it has, which is not a refusal so much as nothing to do.
	 *
	 * Characters carrying the old name are <em>not</em> repointed, and that is worth
	 * saying out loud rather than leaving to be discovered: a character holds the
	 * name of its dialogue, and this does not go round the world rewriting them. So
	 * the answer says how many are left holding a name that no longer exists.
	 */
	public static void rename(ServerPlayer player, String from, String to) {
		if (!mayEdit(player)) return;

		String wanted = to == null ? "" : to.trim();
		if (wanted.isEmpty()) {
			reply(player, false, "A dialogue needs a name.");
			return;
		}
		if (wanted.equals(from)) return;

		WorldDialogues book = WorldDialogues.of(player.level());
		if (DialogueRegistry.get(wanted).isPresent()) {
			reply(player, false, "There is already a dialogue called \"" + wanted + "\".");
			return;
		}
		Dialogue was = DialogueRegistry.get(from).orElse(null);
		if (was == null) {
			reply(player, false, "There is no dialogue called \"" + from + "\".");
			return;
		}

		Dialogue now = new Dialogue(wanted, was.formatVersion(), was.start(),
			was.nodes(), was.variableTypes(), was.segments(), was.areas());
		String problem = book.put(now);
		if (problem != null) {
			reply(player, false, problem);
			return;
		}
		// Only once the new one is safely in. A remove that ran first would, on a
		// document the validator then refused, have thrown the dialogue away in order
		// to fail to rename it.
		book.remove(from);

		// And the editor is handed the renamed document, so the window somebody is
		// looking at is about the thing that now exists rather than about a name the
		// world has forgotten.
		ServerPlayNetworking.send(player, new EditorPayloads.Editing(
			DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, now)
				.getOrThrow(IllegalStateException::new).toString(),
			DialogueRegistry.names()));
		reply(player, true, "");
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
		Dialogue saving = parsed.getOrThrow();
		String problem = WorldDialogues.of(player.level()).put(saving);
		if (problem != null) {
			reply(player, false, problem);
			return;
		}
		// Nothing is said about a save that worked. The editor saves by itself now, so
		// a word of confirmation would arrive about once a second while somebody types
		// — and a place where a message appears constantly is a place the eye learns
		// to skip, including on the day the message is a warning.
		reply(player, true, missingPlaces(player, saving));
	}

	/**
	 * A note about places this graph points at that the world has not got.
	 *
	 * <h2>Why this is a note on a success and not a refusal</h2>
	 *
	 * Because writing the graph before placing the gate is an ordinary order of
	 * work, and refusing would make it an impossible one. The validator cannot say
	 * this at all — which places exist is a fact about a saved world, and the
	 * validator is on the side of the fence that must not know about worlds — so
	 * the only place the question can be asked is here, where both halves are to
	 * hand.
	 *
	 * <h2>Why it is worth saying</h2>
	 *
	 * A mark naming nothing is not an error at run time and must not be: a lead that
	 * is not there behaves the same way and every graph copes with it. So a typo in
	 * a place name produces a character who does not walk — the hardest symptom in
	 * this mod to attribute, and one with no error anywhere to connect it to.
	 *
	 * This is the moment it can be caught: the author is looking at the graph they
	 * just wrote, and the difference between "ворота" and "воротa" is visible when
	 * both are on the screen and invisible a week later.
	 */
	private static String missingPlaces(ServerPlayer player, Dialogue saving) {
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return "";
		var known = com.mopicmp.npcstudio.map.WorldSpots.of(level);

		java.util.List<String> nowhere = new java.util.ArrayList<>();
		for (String place : com.mopicmp.npcstudio.dialogue.Mark.placesIn(saving.marksUsed())) {
			if (known.at(place) == null) nowhere.add(place);
		}
		// And properties this game has not got, said in the same breath and for the same
		// reason. A misspelt attribute is a verb that does nothing for ever, and the only
		// moment anybody can tell is while looking at the graph they just wrote.
		java.util.List<String> unknown = new java.util.ArrayList<>();
		for (Node node : saving.nodes().values()) {
			if (!(node instanceof Node.Act act)) continue;
			if (!(act.effect() instanceof com.mopicmp.npcstudio.dialogue.Effect.Trait trait)) continue;
			if (trait.attribute().isBlank() || Traits.known(trait.attribute())) continue;
			if (!unknown.contains(trait.attribute())) unknown.add(trait.attribute());
		}

		java.util.List<String> said = new java.util.ArrayList<>();
		if (!nowhere.isEmpty()) {
			said.add("It points at places this world has not got: " + String.join(", ", nowhere) + ".");
		}
		if (!unknown.isEmpty()) {
			said.add("It gives properties this game has not got: " + String.join(", ", unknown) + ".");
		}
		return String.join(" ", said);
	}

	private static void reply(ServerPlayer player, boolean ok, String message) {
		ServerPlayNetworking.send(player, new EditorPayloads.Saved(ok, message));
	}

	/**
	 * Forgets that this player has ever played one document, so they can play it again.
	 *
	 * <h2>Why all three and not only the variables</h2>
	 *
	 * Because a scene is not put back to the start by its variables alone. "Has this
	 * node been visited" is a condition the language has, so a document reset without
	 * its visits replays with some of its branches already decided — and which ones is
	 * invisible, since nothing anywhere shows the visited set. And a bookmark left
	 * standing means the next click resumes the old run rather than beginning a new
	 * one, which reads as the reset having done nothing at all.
	 *
	 * <h2>What it cannot reach, and why that is said out loud</h2>
	 *
	 * What a character knows about herself is kept on her, not in the world's book, so
	 * a document reset here leaves it — which is right, since twenty guards sharing one
	 * script each keep their own half of it and only one of them is standing in front
	 * of anybody. And the visited set holds bare node ids shared across every document,
	 * so a node called "start" in two documents is one entry: resetting one may forget
	 * a visit belonging to the other. Both are worth knowing before the button is
	 * trusted, which is why the reply says what was done rather than only that it was.
	 */
	public static void replay(ServerPlayer player, String id) {
		if (!mayEdit(player)) return;
		Dialogue graph = DialogueRegistry.get(id).orElse(null);
		if (graph == null) {
			reply(player, false, "There is no dialogue called \"" + id + "\".");
			return;
		}
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return;

		DialogueSaveData store = DialogueSaveData.of(level);
		java.util.UUID who = player.getUUID();

		java.util.Map<String, com.mopicmp.npcstudio.dialogue.Value> vars =
			new java.util.HashMap<>(store.varsOf(who));
		java.util.Map<String, com.mopicmp.npcstudio.dialogue.Value> world =
			new java.util.HashMap<>(store.worldVars());
		// Both scopes, because a name is declared once and the scope is chosen at each
		// row that uses it — so which of the two a flag actually landed in is not a
		// thing this can know, and clearing one of them would be a reset that works
		// until the day somebody wrote "world" instead of "player".
		int cleared = 0;
		for (String name : graph.variableTypes().keySet()) {
			if (vars.remove(name) != null) cleared++;
			if (world.remove(name) != null) cleared++;
		}

		java.util.Set<String> seen = new java.util.HashSet<>(store.visitedBy(who));
		int forgotten = 0;
		for (String node : graph.nodes().keySet()) {
			if (seen.remove(node)) forgotten++;
		}

		// And the properties this document handed out. They live in the player's own
		// saved data and survive a restart, so a scene replayed without clearing them
		// would begin with every boon of the last run still on — which is exactly not
		// what "play it again" means.
		java.util.List<String> traits = new java.util.ArrayList<>();
		for (Node node : graph.nodes().values()) {
			if (node instanceof Node.Act act
					&& act.effect() instanceof com.mopicmp.npcstudio.dialogue.Effect.Trait trait
					&& !trait.name().isBlank()) {
				traits.add(trait.name());
			}
		}
		Traits.clear(player, traits);

		store.remember(who, vars, seen, world);
		store.clearBookmarks(who);
		DialogueDisplay.hide(player);
		DialogueRuntime.forget(player);

		reply(player, true, "Played again: " + cleared + " variables put back, "
			+ forgotten + " visits forgotten, " + traits.size() + " properties taken off, "
			+ "and every bookmark of yours dropped. "
			+ "What characters know about themselves is theirs and is left alone.");
	}

	/**
	 * Every variable name every document knows, for the editor's list.
	 *
	 * <h2>Why every document and not this one</h2>
	 *
	 * Because the store is not per document. A variable in the player's scope is one
	 * entry in one map for the whole world — see DialogueSaveData, which keys it by the
	 * player and by nothing else — so a name written by one graph is the same name read
	 * by another, whether or not the person writing the second graph knew it existed.
	 *
	 * Which cuts both ways, and both are worth seeing: reaching for a name another
	 * document already keeps is the point of the feature, and reaching for one by
	 * accident is how two unrelated scenes end up sharing a flag.
	 *
	 * <h2>Why a scope goes with each name</h2>
	 *
	 * Because a name on its own does not identify a variable. Written under "player"
	 * and read under "world" it is two variables that look like one, both valid, both
	 * declared, and the branch simply never fires. The list is the one place that can
	 * say so without anybody having to already suspect it.
	 */
	public static void sendVariables(ServerPlayer player) {
		if (!mayEdit(player)) return;

		List<com.mopicmp.npcstudio.net.VariablePayloads.Known> known = new java.util.ArrayList<>();
		for (String id : DialogueRegistry.names()) {
			Dialogue graph = DialogueRegistry.get(id).orElse(null);
			if (graph == null) continue;

			// One row per name-and-scope, not per place it is mentioned: a flag read at
			// nine branches is one variable, and nine identical rows would bury the
			// eight other names under it.
			java.util.Set<String> pairs = new java.util.HashSet<>();
			java.util.Set<String> named = new java.util.HashSet<>();
			for (com.mopicmp.npcstudio.dialogue.Variables.Use use
					: com.mopicmp.npcstudio.dialogue.Variables.used(graph)) {
				named.add(use.name());
				if (!pairs.add(use.name() + " " + use.scope())) continue;
				// The declared type wins over the one guessed from the use. A comparison
				// against 0 says nothing about whether the variable is a number — the
				// declaration does, and it is the one the engine will honour.
				String type = graph.variableTypes().getOrDefault(use.name(), use.type());
				known.add(new com.mopicmp.npcstudio.net.VariablePayloads.Known(
					use.name(), use.scope().name(), type, id));
			}
			for (var declared : graph.variableTypes().entrySet()) {
				if (named.contains(declared.getKey())) continue;
				known.add(new com.mopicmp.npcstudio.net.VariablePayloads.Known(
					declared.getKey(), "", declared.getValue(), id));
			}
		}

		// Cut at the end rather than while gathering, so that what survives is whole
		// documents' worth rather than half of one and none of the rest.
		if (known.size() > com.mopicmp.npcstudio.net.VariablePayloads.MOST) {
			known = known.subList(0, com.mopicmp.npcstudio.net.VariablePayloads.MOST);
		}
		ServerPlayNetworking.send(player,
			new com.mopicmp.npcstudio.net.VariablePayloads.Names(List.copyOf(known)));
	}

	/**
	 * A new dialogue that already runs.
	 *
	 * One line and an end, rather than an empty graph. An empty one would fail
	 * validation the moment it was saved, and greeting someone with an error for
	 * having created something is a poor way to start.
	 */
	private static Dialogue blank(String id) {
		return blank(id, Dialogue.Kind.SCENE);
	}

	/**
	 * A new document of one sort, already in the shape that sort is used in.
	 *
	 * <h2>Why the four are not the same graph</h2>
	 *
	 * They are the same language, and that is the point; but they are not the same
	 * first page. A conversation opens on a line, because a conversation is a thing
	 * somebody clicks and the first thing it does is say something. The other three are
	 * carried by nobody: there is nothing to click, so a starting line would be a node
	 * nothing could ever reach — and the validator says so, which would mean greeting
	 * somebody with an error for having pressed "new".
	 *
	 * So those three open on an end and a way in, which is the smallest thing that both
	 * runs and can be saved.
	 */
	private static Dialogue blank(String id, Dialogue.Kind kind) {
		if (kind == Dialogue.Kind.SCENE) {
			return Dialogue.builder(id)
				.start("line1")
				.add(new Node.Line("line1", "", "Hello.", Presentation.SUBTITLE, null, "end"))
				.add(new Node.End("end"))
				.build();
		}
		Dialogue base = Dialogue.builder(id).add(new Node.End("end")).build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), List.of(), base.voices(), kind, List.of(), List.of(),
			// A place has to carry its own settings from the first moment, because a
			// document whose sort and settings disagree is refused — and a refusal holds
			// the save back, so a brand new location would be unsaveable until somebody
			// found the page that fixes it.
			kind == Dialogue.Kind.LOCATION
				? java.util.Optional.of(com.mopicmp.npcstudio.dialogue.Location.of(""))
				: java.util.Optional.empty(),
			"");
	}

	/** What the window called it, as a sort, falling back to a conversation. */
	private static Dialogue.Kind kindOf(String written) {
		if (written == null) return Dialogue.Kind.SCENE;
		for (Dialogue.Kind kind : Dialogue.Kind.values()) {
			if (kind.name().equalsIgnoreCase(written)) return kind;
		}
		return Dialogue.Kind.SCENE;
	}

	/** A name that is not taken yet, so a second new dialogue does not overwrite the first. */
	private static String freshName(ServerPlayer player, Dialogue.Kind kind) {
		List<String> taken = DialogueRegistry.names();
		// No colon: that reads as a namespace, and a name a player invents has no
		// business claiming one. It also kept the name from being typed into a
		// command at all.
		//
		// The sort is in the name because the four tabs are four lists, and a map with
		// eight documents all called somebody_dialogue is a map where the tabs are the
		// only thing telling them apart — which stops being true the moment a name is
		// read anywhere else, such as on a character's panel.
		String base = player.getGameProfile().name().toLowerCase() + "_" + switch (kind) {
			case SCENE -> "dialogue";
			case PLAYER -> "rules";
			case THING -> "item";
			case LOCATION -> "place";
		};
		for (int n = 1; ; n++) {
			String candidate = base + n;
			if (!taken.contains(candidate)) return candidate;
		}
	}
}
