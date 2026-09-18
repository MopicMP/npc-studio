package com.mopicmp.npcstudio.brain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.Sense;

/**
 * The vocabulary of readings against the things that answer them.
 *
 * <h2>The rule, which was written down and never checked</h2>
 *
 * {@code Senses} says it in its own words: <em>"Every case here must have a name
 * in Sense.KNOWN and every name there must have a case here. A name with no case
 * reads as false for ever, which looks exactly like a graph that does not
 * work."</em>
 *
 * Both halves fail silently and they fail differently.
 *
 * A name in the dictionary with nothing behind it is the worse one. The validator
 * accepts a graph using it, because the validator's whole job is to check names
 * against that list; the switch falls through to {@code false}; and a character
 * waits for ever on a condition that can never come true. From outside that is a
 * character standing still, which is also what a dozen other things look like —
 * the perception work went several rounds telling exactly those apart.
 *
 * A case with no name is the milder one: unreachable code that reads as a working
 * feature, so somebody writes a graph against it and the validator refuses a
 * reading that is fully implemented.
 *
 * <h2>Why this is read out of the source</h2>
 *
 * Because the answer to a reading needs a live character with a world around her,
 * and there is not one here. What can be checked without a game is which names the
 * switch mentions, and that is precisely the half the prose promises.
 */
class SensesAreCompleteTest {

	private static final Path SENSES = Path.of("src", "main", "java", "com", "mopicmp",
		"npcstudio", "brain", "Senses.java");

	/** Every constant on {@link Sense}, by its Java name, so a case can be read back. */
	private static Map<String, String> namesOfConstants() {
		Map<String, String> found = new LinkedHashMap<>();
		for (Field field : Sense.class.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			if (field.getType() != String.class) continue;
			try {
				found.put(field.getName(), (String) field.get(null));
			} catch (IllegalAccessException unreachable) {
				throw new AssertionError(unreachable);
			}
		}
		return found;
	}

	/** The readings the switch actually names, as their spoken names. */
	private static List<String> answered() throws IOException {
		String source = Files.readString(SENSES, StandardCharsets.UTF_8);
		Map<String, String> constants = namesOfConstants();

		List<String> spoken = new ArrayList<>();
		var cases = Pattern.compile("case Sense\\.([A-Z_]+)\\b").matcher(source);
		while (cases.find()) {
			String value = constants.get(cases.group(1));
			if (value != null) spoken.add(value);
		}
		return spoken;
	}

	@Test
	@DisplayName("every reading a graph may ask for has something that answers it")
	void everyNameIsAnswered() throws IOException {
		List<String> answered = answered();
		List<String> silent = Sense.KNOWN.stream().filter(name -> !answered.contains(name)).toList();
		// These are the ones that read false for ever while the validator says the
		// graph is fine.
		assertTrue(silent.isEmpty(), "in the dictionary and never answered: " + silent);
	}

	@Test
	@DisplayName("nothing is answered that a graph is not allowed to ask for")
	void everyAnswerIsInTheDictionary() throws IOException {
		List<String> orphans = answered().stream()
			.filter(name -> !Sense.KNOWN.contains(name)).toList();
		// Unreachable work that reads as a feature: somebody writes a graph against it
		// and the validator refuses a reading that is fully implemented.
		assertTrue(orphans.isEmpty(), "answered and not in the dictionary: " + orphans);
	}

	@Test
	@DisplayName("no reading is named twice in the dictionary")
	void theDictionaryHasNoDuplicates() {
		// Two entries for one name is one of them shadowing the other, and which wins
		// is whichever the switch happens to mention first.
		assertTrue(Sense.KNOWN.size() == Sense.KNOWN.stream().distinct().count(),
			"named twice: " + Sense.KNOWN);
	}

	@Test
	@DisplayName("the source really was read, so a green result means something")
	void theReadingItselfWorks() throws IOException {
		// A guard on the test rather than on the code. If the file moves or the shape
		// of a case label changes, both tests above would find nothing missing by
		// finding nothing at all — and would pass, loudly saying the opposite of the
		// truth. This is the one assertion that catches that.
		assertTrue(Files.exists(SENSES), "the file this test reads has moved: " + SENSES);
		assertTrue(answered().size() >= Sense.KNOWN.size(),
			"read " + answered().size() + " cases for " + Sense.KNOWN.size() + " names — "
				+ "the case labels are probably no longer written as `case Sense.NAME`");
	}
}
