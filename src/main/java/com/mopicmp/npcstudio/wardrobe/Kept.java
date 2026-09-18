package com.mopicmp.npcstudio.wardrobe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * A small file kept in versions, so that changing it is never the end of the matter.
 *
 * <h2>Why this is its own class</h2>
 *
 * Because there is a second file that wants it. The picture list has been kept this way
 * since it existed, for a reason that is not about pictures at all: it costs almost
 * nothing and it is the difference between an afternoon and a project. Layout sheets are
 * the same kind of thing — small, edited by hand, and painful to lose.
 *
 * Taken out at the second user rather than the third, which is the one lesson this
 * codebase has learned the hard way three times over. A copy of this logic would be
 * forty lines that have to agree with another forty lines for ever, and the two rules
 * inside it are exactly the kind that quietly stop agreeing: never overwrite a version,
 * and keep only so many.
 *
 * <h2>The two rules</h2>
 *
 * A name that collides is a version silently lost, so a stamp that already exists gets a
 * number after it. And the stamp goes down to the millisecond because seconds were not
 * enough — the tests said so: several changes inside one second all wrote the same name,
 * each quietly replacing the last, and the history was one entry deep. A safety net with
 * one strand is not one.
 */
public final class Kept {

	private Kept() { }

	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS").withZone(ZoneId.systemDefault());

	/** How many past versions to keep. Generous: they are tiny. */
	public static final int VERSIONS = 40;

	/**
	 * Copies the file aside under a stamp, and throws away all but the newest few.
	 *
	 * Does nothing when there is no file yet, which is the first save of anything and
	 * not a fault.
	 */
	public static void aside(Path file, Path versions) {
		try {
			if (!Files.isRegularFile(file)) return;
			Files.createDirectories(versions);
			String stamp = WHEN.format(Instant.now());
			Path target = versions.resolve(stamp + ".json");
			for (int attempt = 1; Files.exists(target); attempt++) {
				target = versions.resolve(stamp + "-" + attempt + ".json");
			}
			Files.copy(file, target);
			prune(versions);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not keep a version of {}: {}", file, failed.toString());
		}
	}

	/** The versions kept aside, newest first. */
	public static List<String> history(Path versions) {
		if (!Files.isDirectory(versions)) return List.of();
		try (var files = Files.list(versions)) {
			return files.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.map(name -> name.substring(0, name.length() - 5))
				.sorted(Comparator.reverseOrder())
				.toList();
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not list versions in {}: {}", versions, failed.toString());
			return List.of();
		}
	}

	/**
	 * The file a version name points at, or null if the name has no business being one.
	 *
	 * <h2>Why this is not just a path join</h2>
	 *
	 * The name arrives from whoever clicked, which means it arrives over the network,
	 * which means it is not to be trusted with a filesystem. A name like
	 * {@code ../../../server} resolved perfectly happily before this check existed.
	 *
	 * Two locks rather than one: the shape of the name, and the fact that the resolved
	 * path still sits inside the folder it was supposed to. Either alone has been enough
	 * for somebody, somewhere, right up until it was not.
	 */
	public static Path find(Path versions, String version) {
		if (version == null || !version.matches("[0-9 :-]{1,40}")) return null;
		Path file = versions.resolve(version + ".json").normalize();
		if (!file.startsWith(versions.normalize())) return null;
		return Files.isRegularFile(file) ? file : null;
	}

	private static void prune(Path versions) {
		List<String> kept = history(versions);
		for (int i = VERSIONS; i < kept.size(); i++) {
			try {
				Files.deleteIfExists(versions.resolve(kept.get(i) + ".json"));
			} catch (Exception ignored) {
				// An old version that will not delete is nobody's problem.
			}
		}
	}
}
