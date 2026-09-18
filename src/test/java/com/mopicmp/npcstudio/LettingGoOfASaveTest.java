package com.mopicmp.npcstudio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the server lets go of when a world closes.
 *
 * <h2>Why this side needs the same guard as the client's</h2>
 *
 * In single player the server and the game are one process. Leaving a world and
 * opening another does not restart anything: whatever a static field was holding
 * when the first world closed is still holding it when the second opens.
 *
 * Three of these hold a path into the save folder — the costume library, the
 * models, the scenes — and a path from the last world used in this one would read
 * and write another map's files. They are opened when a world opens and closed
 * with it, which is right, and the closing is the half that is easy to forget
 * because nothing goes wrong until somebody plays two worlds in an evening.
 *
 * <h2>What this found and what it did not</h2>
 *
 * The three documents were already wired. The fight recorder was not: it writes
 * beside the game rather than inside a save, so it looks unattached, but it counts
 * in the world's ticks and its own limit is measured from a tick the next world
 * has never reached. Left rolling it never stops itself.
 *
 * Written after the same audit on the client side turned up four misses, three of
 * which had a method for letting go that nothing called.
 */
class LettingGoOfASaveTest {

	private static final Path MOD =
		Path.of("src", "main", "java", "com", "mopicmp", "npcstudio");

	private static String mainClass() throws IOException {
		return Files.readString(MOD.resolve("NpcStudio.java"), StandardCharsets.UTF_8);
	}

	/** Everything inside handlers for one lifecycle event, as one piece of text. */
	private static String inside(String source, String event) {
		StringBuilder all = new StringBuilder();
		Matcher found = Pattern.compile(event + "\\.register\\(").matcher(source);
		while (found.find()) {
			int at = found.end();
			int depth = 1;
			while (depth > 0 && at < source.length()) {
				char letter = source.charAt(at++);
				if (letter == '(') depth++;
				else if (letter == ')') depth--;
			}
			all.append(source, found.end(), at).append('\n');
		}
		return all.toString();
	}

	/** Classes that hold something for the life of one world and can be told to stop. */
	private static List<String> theOnesThatHoldASave() throws IOException {
		List<String> found = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(MOD)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				// The pair is the signature: something opened from a running server and
				// closed again. One without the other is not this shape and not this
				// test's business.
				if (!source.contains("public static void open(MinecraftServer")) continue;
				if (!source.contains("public static void close()")) continue;
				found.add(file.getFileName().toString().replace(".java", ""));
			}
		}
		return found;
	}

	@Test
	@DisplayName("everything opened with a world is closed with it")
	void everythingOpenedIsClosed() throws IOException {
		String source = mainClass();
		String opening = inside(source, "SERVER_STARTED");
		String closing = inside(source, "SERVER_STOPPED");

		List<String> leftOpen = new ArrayList<>();
		for (String name : theOnesThatHoldASave()) {
			if (!opening.contains(name + ".open(")) continue;
			if (!closing.contains(name + ".close()")) leftOpen.add(name);
		}
		// Left open means a path into the last world's save folder answering questions
		// asked about this one — and in single player that is one process, so nothing
		// clears it in between.
		assertTrue(leftOpen.isEmpty(), "opened with a world and never closed: " + leftOpen);
	}

	@Test
	@DisplayName("nothing is closed that was never opened")
	void nothingIsClosedUnopened() throws IOException {
		String source = mainClass();
		String opening = inside(source, "SERVER_STARTED");
		String closing = inside(source, "SERVER_STOPPED");

		List<String> odd = new ArrayList<>();
		for (String name : theOnesThatHoldASave()) {
			if (closing.contains(name + ".close()") && !opening.contains(name + ".open(")) {
				odd.add(name);
			}
		}
		// Not a crash, but a sign the two halves have drifted — most likely a rename
		// that landed on one side, which is how the closing quietly stops matching.
		assertTrue(odd.isEmpty(), "closed with a world and never opened: " + odd);
	}

	@Test
	@DisplayName("a recording is not left running into the next world")
	void theTapeIsStopped() throws IOException {
		// It writes beside the game rather than inside a save, so it does not look like
		// it belongs to a world — but it counts in the world's ticks, and its own limit
		// is measured from a moment the next world has never reached. Left rolling it
		// never stops itself and goes on writing.
		assertTrue(inside(mainClass(), "SERVER_STOPPED").contains("Tape.stop()"),
			"the fight recorder is not stopped when a world closes");
	}

	@Test
	@DisplayName("the reading found the handlers, so a green result means something")
	void theReadingWorked() throws IOException {
		String source = mainClass();
		assertTrue(theOnesThatHoldASave().size() >= 3,
			"found almost nothing that holds a save — the shape has probably changed");
		// Without this the two tests above would report perfect agreement about empty
		// text: green, and saying the opposite of the truth.
		assertTrue(inside(source, "SERVER_STARTED").contains(".open("),
			"found no handler that opens anything when a world starts");
		assertTrue(inside(source, "SERVER_STOPPED").contains(".close()"),
			"found no handler that closes anything when a world stops");
	}
}
