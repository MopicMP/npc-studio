package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Dialogues written inside the game, kept in the world save.
 *
 * Separate from the ones datapacks provide, and deliberately so. A datapack is
 * a thing you ship: versioned, copied between worlds, edited in a text editor.
 * What someone builds with the in-game editor belongs to the world they built
 * it in, and writing it back into a datapack is not something a running server
 * can do anyway.
 *
 * They win over a datapack of the same name. Someone editing in-game is looking
 * at the thing they are changing, and having their change silently lose to a
 * file they cannot see from there would be baffling.
 */
public class WorldDialogues extends SavedData {

	public static final Codec<WorldDialogues> CODEC =
		Codec.unboundedMap(Codec.STRING, DialogueCodecs.DIALOGUE)
			.xmap(WorldDialogues::new, data -> data.dialogues);

	private static final SavedDataType<WorldDialogues> TYPE = new SavedDataType<>(
		NpcStudio.id("world_dialogues"), WorldDialogues::new, CODEC, DataFixTypes.LEVEL);

	private final Map<String, Dialogue> dialogues = new HashMap<>();

	public WorldDialogues() { }

	private WorldDialogues(Map<String, Dialogue> loaded) {
		dialogues.putAll(loaded);
	}

	public static WorldDialogues of(ServerLevel level) {
		return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Map<String, Dialogue> all() {
		return Map.copyOf(dialogues);
	}

	/**
	 * Stores a dialogue, refusing one that cannot be run.
	 *
	 * The same gate the datapack loader uses. It matters more here, though: the
	 * person who just broke it is stood in the editor waiting to hear, and can
	 * fix it immediately if told.
	 *
	 * @return null when it was accepted, otherwise what is wrong with it
	 */
	public String put(Dialogue dialogue) {
		var report = com.mopicmp.npcstudio.dialogue.DialogueValidator.validate(dialogue);
		if (!report.ok()) {
			return report.errors().stream()
				.map(problem -> problem.where() == null
					? problem.message()
					: problem.where() + ": " + problem.message())
				.reduce((a, b) -> a + "\n" + b)
				.orElse("the dialogue cannot be run");
		}
		dialogues.put(dialogue.id(), dialogue);
		setDirty();
		DialogueRegistry.putWorld(dialogue);
		return null;
	}

	public void remove(String id) {
		if (dialogues.remove(id) != null) {
			setDirty();
			DialogueRegistry.removeWorld(id);
		}
	}

	/**
	 * Hands everything to the registry when a world opens.
	 *
	 * The registry is what the rest of the mod asks, and it is rebuilt on every
	 * datapack reload; without this the world's own dialogues would vanish the
	 * first time someone ran {@code /reload}.
	 */
	public void publish() {
		DialogueRegistry.replaceWorld(dialogues);
	}
}
