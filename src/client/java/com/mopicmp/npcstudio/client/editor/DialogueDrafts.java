package com.mopicmp.npcstudio.client.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Copies of a dialogue as it was when somebody walked away from it.
 *
 * Closing the editor without saving is not usually a decision — it is Escape
 * pressed by reflex, or a step back to look something up, or the game being
 * shut. An hour of graph should not depend on remembering which of those it
 * was, so leaving with unsaved changes writes a draft.
 *
 * On the client's disk rather than the server's, because the client is where
 * the unsaved version exists. Nobody else has it: the server still holds the
 * last thing that was actually saved, and that is exactly what the draft is
 * there to be different from.
 */
public final class DialogueDrafts {

	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").withZone(ZoneId.systemDefault());

	/**
	 * How many to keep per dialogue.
	 *
	 * Enough to step back past a mistake made while trying to fix an earlier one,
	 * few enough that the list can be read at a glance.
	 */
	private static final int KEEP = 10;

	/** One saved copy, as the restore screen shows it. */
	public record Draft(Path file, String dialogue, String when) { }

	private DialogueDrafts() { }

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("npc_studio").resolve("drafts");
	}

	/**
	 * Writes a draft, unless it would say the same as the last one.
	 *
	 * Opening the editor and closing it again is common and changes nothing, and
	 * a list of identical drafts is a list with the useful one buried in it.
	 */
	public static void keep(String dialogueId, String json) {
		if (dialogueId == null || dialogueId.isBlank() || json == null || json.isBlank()) return;
		try {
			List<Draft> already = of(dialogueId);
			if (!already.isEmpty() && json.equals(read(already.get(0)))) return;

			Path folder = folder();
			Files.createDirectories(folder);
			Path file = folder.resolve(safe(dialogueId) + "__" + WHEN.format(Instant.now()) + ".json");
			Files.writeString(file, json, StandardCharsets.UTF_8);
			prune(dialogueId);
		} catch (Exception failed) {
			// A draft is insurance. Failing to write one must not also cost the
			// player the thing it was insuring.
			NpcStudio.LOGGER.warn("Could not keep a draft of {}: {}", dialogueId, failed.toString());
		}
	}

	/**
	 * What the autosave writes when the graph cannot be sent, marked as its own.
	 *
	 * <h2>Why this is not simply {@link #keep}</h2>
	 *
	 * Because it happens once a second rather than once a session. A graph is
	 * half-finished for as long as it takes to finish it, and every second of that
	 * the server is refusing it — quite rightly, an unfinished wire is not something
	 * other people's characters should be running. If each of those seconds wrote a
	 * draft, the ten slots this keeps would hold the last ten seconds of typing, and
	 * the copy from before the mistake — the one the whole folder exists for — would
	 * have been pushed out by the mistake itself.
	 *
	 * So there is exactly one of these per dialogue and it is replaced in place. It
	 * still carries the time it was written, because a draft you cannot date is a
	 * draft you cannot decide about, and it says {@code (auto)} because whether a
	 * copy was left deliberately or left behind is the first thing anybody wants to
	 * know when looking at a list of them.
	 */
	public static void keepUnsent(String dialogueId, String json) {
		if (dialogueId == null || dialogueId.isBlank() || json == null || json.isBlank()) return;
		try {
			Path folder = folder();
			Files.createDirectories(folder);

			String prefix = safe(dialogueId) + "__";
			try (var files = Files.list(folder)) {
				for (Path file : files.toList()) {
					String name = file.getFileName().toString();
					if (name.startsWith(prefix) && name.endsWith(AUTO)) Files.deleteIfExists(file);
				}
			}
			Files.writeString(folder.resolve(prefix + WHEN.format(Instant.now()) + AUTO),
				json, StandardCharsets.UTF_8);
		} catch (Exception failed) {
			// Same rule as above: insurance that fails must not cost more than it
			// covers. The editor still has the graph; only the copy is missing.
			NpcStudio.LOGGER.warn("Could not keep the unsent copy of {}: {}",
				dialogueId, failed.toString());
		}
	}

	/**
	 * What marks the autosaved copy, and it is part of the name on purpose.
	 *
	 * Everything about a draft is read off its filename — which dialogue, and when —
	 * so a second kind of draft has to be told apart there too, or it would need a
	 * second file beside it saying so, and two files that can disagree is one file
	 * too many.
	 */
	private static final String AUTO = " (auto).json";

	/** Everything kept for one dialogue, newest first. */
	public static List<Draft> of(String dialogueId) {
		Path folder = folder();
		if (!Files.isDirectory(folder)) return List.of();

		String prefix = safe(dialogueId) + "__";
		List<Draft> found = new ArrayList<>();
		try (var files = Files.list(folder)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (!name.startsWith(prefix) || !name.endsWith(".json")) continue;
				found.add(new Draft(file, dialogueId,
					name.substring(prefix.length(), name.length() - 5)));
			}
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not list drafts: {}", failed.toString());
		}
		// By name, which sorts by time because the name is the time. One less thing
		// to read off the filesystem and one less thing to disagree with it.
		found.sort(Comparator.comparing(Draft::when).reversed());
		return found;
	}

	public static String read(Draft draft) {
		try {
			return Files.readString(draft.file(), StandardCharsets.UTF_8);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read {}: {}", draft.file(), failed.toString());
			return null;
		}
	}

	private static void prune(String dialogueId) {
		List<Draft> kept = of(dialogueId);
		for (int i = KEEP; i < kept.size(); i++) {
			try {
				Files.deleteIfExists(kept.get(i).file());
			} catch (Exception ignored) {
				// An old draft that will not delete is not worth telling anybody about.
			}
		}
	}

	/** A dialogue name is free text; a filename is not. */
	private static String safe(String id) {
		return id.replaceAll("[^A-Za-z0-9_.-]+", "_");
	}
}
