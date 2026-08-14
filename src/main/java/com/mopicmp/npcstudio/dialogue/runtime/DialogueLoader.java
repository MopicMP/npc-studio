package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.Map;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Reads dialogues out of datapacks.
 *
 * A file at {@code data/<namespace>/dialogue/<name>.json} becomes the dialogue
 * {@code <namespace>:<name>}. Datapacks are the right home for these: they are
 * versioned with the map, travel with it, and reload without restarting the
 * server — which matters when a writer is iterating on a scene.
 *
 * The id inside the file is ignored in favour of the file's own path. Two
 * sources of truth for a name is one too many, and the one the game uses to
 * find the file should win.
 */
public class DialogueLoader extends SimpleJsonResourceReloadListener<Dialogue>
		implements IdentifiableResourceReloadListener {

	public static final Identifier ID = NpcStudio.id("dialogues");

	public DialogueLoader() {
		super(DialogueCodecs.DIALOGUE, FileToIdConverter.json("dialogue"));
	}

	@Override
	public Identifier getFabricId() {
		return ID;
	}

	@Override
	protected void apply(Map<Identifier, Dialogue> found, ResourceManager manager, ProfilerFiller profiler) {
		DialogueRegistry.replaceLoaded(found);
	}

	/**
	 * How a broken file is reported.
	 *
	 * The reload listener quietly drops a file it cannot parse, which is the
	 * worst possible behaviour here: the writer sees an NPC with no conversation
	 * and no reason why. Counting what arrived against what was on disk is how we
	 * notice, and the count goes into the log either way so a silent zero is
	 * still visible.
	 */
	public static void report(int loaded) {
		if (loaded == 0) {
			NpcStudio.LOGGER.info("No dialogues found in any datapack.");
		} else {
			NpcStudio.LOGGER.info("Loaded {} dialogue(s) from datapacks.", loaded);
		}
	}
}
