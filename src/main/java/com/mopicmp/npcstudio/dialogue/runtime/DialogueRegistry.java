package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Value;

import net.minecraft.resources.Identifier;

/**
 * Every dialogue the server knows, by name.
 *
 * An NPC stores the name and looks the conversation up here, so twenty copies
 * of the same guard share one script and fixing a line fixes all of them.
 *
 * Filled from one built-in example for now. Reading dialogues from datapacks is
 * the same job as saving them into a world — both need codecs — so they are
 * being done together rather than half each.
 */
public final class DialogueRegistry {

	private static final Map<String, Dialogue> DIALOGUES = new ConcurrentHashMap<>();

	/**
	 * Dialogues written in-game, held apart from the ones packs provide.
	 *
	 * Two maps rather than one because they are refilled at different moments:
	 * packs on every reload, the world's own when the world opens. Merging them
	 * into a single map would mean a reload quietly deleting everything someone
	 * had built in the editor.
	 */
	private static final Map<String, Dialogue> WORLD = new ConcurrentHashMap<>();

	private DialogueRegistry() { }

	/** The world's own wins: someone editing is looking at the thing they changed. */
	public static Optional<Dialogue> get(String id) {
		Dialogue own = WORLD.get(id);
		return Optional.ofNullable(own != null ? own : DIALOGUES.get(id));
	}

	public static List<String> names() {
		return java.util.stream.Stream.concat(DIALOGUES.keySet().stream(), WORLD.keySet().stream())
			.distinct().sorted().toList();
	}

	public static void putWorld(Dialogue dialogue) {
		WORLD.put(dialogue.id(), dialogue);
	}

	public static void removeWorld(String id) {
		WORLD.remove(id);
	}

	public static void replaceWorld(Map<String, Dialogue> dialogues) {
		WORLD.clear();
		WORLD.putAll(dialogues);
	}

	/**
	 * Adds a dialogue, refusing one that will not run.
	 *
	 * The validator is not advisory here. A dialogue with a dead end or a loop
	 * that never waits would strand whoever walked into it, and the error belongs
	 * at load time in a server log — not mid-sentence in front of a player.
	 */
	public static boolean register(Dialogue dialogue) {
		DialogueValidator.Report report = DialogueValidator.validate(dialogue);
		if (!report.ok()) {
			NpcStudio.LOGGER.error("Dialogue \"{}\" was refused:\n{}", dialogue.id(), report);
			return false;
		}
		for (DialogueValidator.Problem problem : report.problems()) {
			NpcStudio.LOGGER.warn("Dialogue \"{}\": {}", dialogue.id(), problem);
		}
		DIALOGUES.put(dialogue.id(), dialogue);
		return true;
	}

	public static void registerBuiltIn() {
		register(example());
		register(torchbearer());
	}

	/**
	 * A behaviour graph, to prove there is now such a thing.
	 *
	 * <h2>What it is for</h2>
	 *
	 * The same job as {@link #example()}: something to reach for to check the
	 * thing works at all, without writing a datapack first. It uses both of the
	 * new occasions and nothing else, so what it demonstrates is exactly what was
	 * built.
	 *
	 * She stands doing nothing until she is holding a torch, waves when she is,
	 * then waits three seconds and looks again. Put a torch in her hand with
	 * {@code /npc arm} and she starts; take it away and she stops. The waiting is
	 * the point: the first is a question asked every tick, the second is a timer,
	 * and between them they are every occasion a character needs.
	 */
	private static Dialogue torchbearer() {
		return Dialogue.builder("torchbearer")
			.start("dark")
			.add(new Node.Until("dark", new Condition.HasItem("minecraft:torch", 1), "wave"))
			.add(new Node.Act("wave", new Effect.PlayAnimation("wave", 40), "again"))
			.add(new Node.Every("again", 60, "dark"))
			.build();
	}

	/**
	 * Takes the dialogues a datapack reload produced.
	 *
	 * Everything from packs is thrown away first, so a dialogue deleted from a
	 * datapack really disappears instead of lingering until the server restarts —
	 * a writer testing a rename would otherwise be talking to a ghost.
	 *
	 * The built-in example survives, because it is not from a pack and is what
	 * someone reaches for to check the mod works at all.
	 */
	public static void replaceLoaded(Map<Identifier, Dialogue> loaded) {
		DIALOGUES.clear();
		registerBuiltIn();

		int accepted = 0;
		for (Map.Entry<Identifier, Dialogue> entry : loaded.entrySet()) {
			String id = entry.getKey().toString();
			Dialogue dialogue = entry.getValue();
			// The file's own path is the name. Trusting the id inside the file would
			// let two dialogues claim one name, and the loser would vanish silently.
			Dialogue named = new Dialogue(id, dialogue.formatVersion(), dialogue.start(),
				dialogue.nodes(), dialogue.variableTypes());
			if (register(named)) accepted++;
		}
		DialogueLoader.report(accepted);
	}

	/**
	 * One conversation that exercises the parts worth seeing work.
	 *
	 * It remembers whether you have been here, offers an option only to someone
	 * carrying emeralds, and ends differently depending on the answer — so a
	 * single walk through it shows variables, conditions and branching at once.
	 */
	private static Dialogue example() {
		return Dialogue.builder("example")
			.variable("met", "flag")
			.start("check")
			.add(new Node.Branch("check", List.of(new Node.Arm(
				new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ, Value.of(true)), "again")),
				"first"))
			.add(new Node.Line("first", "Villager", "You are new here.", Presentation.SUBTITLE, null, "remember"))
			.add(new Node.Set("remember", "met", Scope.PLAYER, Value.of(true), "menu"))
			.add(new Node.Line("again", "Villager", "Back so soon?", Presentation.SUBTITLE, null, "menu"))
			.add(new Node.Choice("menu", "What do you want?", List.of(
				new Node.Option("Trade an emerald.", "green",
					new Condition.HasItem("minecraft:emerald", 1), "trade"),
				new Node.Option("Nothing, sorry.", null, new Condition.Always(), "bye"))))
			// Taking the emerald is a step of its own. `has_item` on the option is
			// only a condition — it decides whether the offer is shown, and does not
			// touch the inventory. Leaving it at that gave away bread for nothing,
			// which is the sort of mistake a map maker will make constantly: the
			// condition reads like it does the trade.
			.add(new Node.Act("trade", new Effect.TakeItem("minecraft:emerald", 1), "paid"))
			.add(new Node.Act("paid", new Effect.GiveItem("minecraft:bread", 3), "traded"))
			.add(new Node.Line("traded", "Villager", "Bread for an emerald. Fair enough.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("bye", "Villager", "Then stop blocking the road.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}
}
