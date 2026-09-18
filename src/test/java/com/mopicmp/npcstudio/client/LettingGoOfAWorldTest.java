package com.mopicmp.npcstudio.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the client lets go of when a world does.
 *
 * <h2>The hazard, stated in this codebase four separate times</h2>
 *
 * Almost everything the editing side remembers is static, because it has to
 * outlive the screen it belongs to. Static also means it outlives the <em>world</em>
 * it belongs to, and nearly all of it is world-scoped: entity ids are handed out
 * per world and handed out again in the next, and documents live in a save.
 *
 * The comments beside the handlers say it plainly and say it more than once — the
 * undo history, the remembered skins, the scene's cast and the sky each carry
 * their own version of "carried into the next world this names somebody else".
 *
 * <h2>And it still went wrong four times</h2>
 *
 * Which is the argument for this file. Writing the reason down a fifth time would
 * not have caught the selection, the graph list, the model being built or the
 * scene camera — two of which already had a method for letting go that nothing
 * ever called. A method written and not wired is the quietest failure of the lot:
 * it reads, in review, exactly like the thing being done.
 */
class LettingGoOfAWorldTest {

	private static final Path CLIENT =
		Path.of("src", "client", "java", "com", "mopicmp", "npcstudio", "client");

	/**
	 * Things that let go on purpose without being tied to a disconnect, with why.
	 *
	 * A list rather than a rule, so that each is a decision somebody made and can be
	 * read here. A rule broad enough to excuse these would excuse a real omission
	 * wearing the same shape — which is how the four were missed.
	 */
	private static final Map<String, String> NOT_ON_LEAVING = new LinkedHashMap<>(Map.of(
		"Fonts", "reads the resource packs, which belong to the game rather than a world",
		"SkinPixels", "a cache of pictures by their own contents, true in any world",
		"PlayerHeads", "faces fetched by name from outside the game entirely",
		"Captions", "let go when a scene stops, which is nearer than when a world does",
		"Posing", "a resting pose worked out from the model, not from anything in a world"));

	private static String client() throws IOException {
		return Files.readString(CLIENT.resolve("NpcStudioClient.java"), StandardCharsets.UTF_8);
	}

	/** Everything inside a DISCONNECT handler, as one piece of text. */
	private static String onLeaving(String source) {
		StringBuilder all = new StringBuilder();
		Matcher found = Pattern.compile("DISCONNECT\\.register\\(").matcher(source);
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

	/** Every client class that offers a way to let go of what it is holding. */
	private static List<String> theOnesThatCanLetGo() throws IOException {
		List<String> found = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(CLIENT)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				if (!source.contains("public static void forget()")) continue;
				String name = file.getFileName().toString().replace(".java", "");
				found.add(name);
			}
		}
		return found;
	}

	@Test
	@DisplayName("everything that can let go of a world is asked to when one goes")
	void everythingIsAsked() throws IOException {
		String leaving = onLeaving(client());
		List<String> holding = new ArrayList<>();
		for (String name : theOnesThatCanLetGo()) {
			if (NOT_ON_LEAVING.containsKey(name)) continue;
			if (!leaving.contains(name + ".forget()")) holding.add(name);
		}
		// A class with a way to let go that nobody calls is the quietest of these
		// failures: in review it reads exactly like the thing being done.
		assertTrue(holding.isEmpty(),
			"can let go of a world and is never asked to: " + holding);
	}

	@Test
	@DisplayName("nothing is excused that no longer exists")
	void theExcusesAreCurrent() throws IOException {
		List<String> able = theOnesThatCanLetGo();
		List<String> stale = NOT_ON_LEAVING.keySet().stream()
			.filter(name -> !able.contains(name)).toList();
		// An excuse for a class that has been renamed or deleted is an excuse that
		// might silently cover something else one day, and it is a reason nobody can
		// check any more.
		assertTrue(stale.isEmpty(), "excused and no longer there: " + stale);
	}

	@Test
	@DisplayName("the two things this reads were actually found")
	void theReadingWorked() throws IOException {
		// The assertion that stops the others passing by finding nothing at all. Both
		// would report perfect agreement about empty sets — green, saying the opposite
		// of the truth, which is the one result worse than red.
		assertTrue(theOnesThatCanLetGo().size() >= 10,
			"found almost nothing that can let go — the shape has probably changed");
		assertTrue(onLeaving(client()).contains(".forget()"),
			"found no disconnect handler that lets go of anything");
	}
}
