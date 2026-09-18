package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What each entity writes to the save against what it reads back.
 *
 * <h2>Why this pair is the most dangerous in the mod</h2>
 *
 * It is two hand-written functions that have to mirror each other by nothing but
 * a matching pair of quoted names, and the names are never compared by anything.
 * Miss one and a character loses her costume, her graph or her settings the next
 * time the world is opened — with no error, no log line, and a save file that
 * looks perfectly healthy.
 *
 * It is also the pair that grows. Every setting added to a character adds one
 * line to each side, and adding one to only one side is the easiest mistake in
 * this codebase to make and the hardest to notice: the character is right for the
 * whole session you added it in.
 *
 * <h2>Why the source rather than a live entity</h2>
 *
 * Making one needs a world and a server. What the two functions <em>name</em>
 * needs neither, and the names are the whole of the seam.
 */
class SavedFieldsMirrorTest {

	private static final Path ENTITIES =
		Path.of("src", "main", "java", "com", "mopicmp", "npcstudio", "entity");

	/**
	 * Keys read but deliberately not written, with the reason.
	 *
	 * <h2>Why a list and not a rule</h2>
	 *
	 * Because every one of these is a decision about somebody else's saved world, and
	 * a rule that quietly allowed them would allow a genuine mistake wearing the same
	 * shape. Adding to this list should cost a sentence.
	 *
	 * <h2>The two on it</h2>
	 *
	 * {@code Brain} was a second document a character used to carry. It is read so an
	 * old save can be moved on, and not written so the move happens once rather than
	 * every load for ever; what became of it goes under {@code MovedBrain}, which is
	 * a note about what was done rather than the thing itself.
	 *
	 * The three eye maps are the same story at a smaller scale: a face used to be
	 * three packed numbers at an eighth of the size and is now one encoded mask.
	 * Worlds are full of the old form, so it is still read and grown; writing it back
	 * would mean carrying both shapes for ever and choosing between them on every
	 * load.
	 *
	 * Both were invisible here until the reading of built keys was made exact — under
	 * the loose version all six outfit fields were called {@code Outfit} and agreed
	 * with themselves.
	 */
	private static final Set<String> READ_ON_PURPOSE_ONLY = Set.of(
		"Brain", "Outfit*Eyes", "Outfit*Whites", "Outfit*Brows");

	private static String bodyOf(String source, String method) {
		Matcher head = Pattern.compile("void " + method + "\\([^)]*\\)\\s*\\{").matcher(source);
		if (!head.find()) return "";
		int at = head.end();
		int depth = 1;
		while (depth > 0 && at < source.length()) {
			char letter = source.charAt(at++);
			if (letter == '{') depth++;
			else if (letter == '}') depth--;
		}
		return source.substring(head.end(), at);
	}

	/**
	 * The names a piece of saving or loading mentions, built keys included.
	 *
	 * <h2>Why the whole expression and not the first string in it</h2>
	 *
	 * Because several keys here are made rather than written: {@code "Outfit" + i +
	 * "Label"}, one per garment per field. Reading only the first literal calls every
	 * one of those {@code Outfit}, so the two sides agree about a single name while
	 * the six suffixes behind it are never compared at all — which is precisely where
	 * a costume would go missing, and precisely the seam this test is for.
	 *
	 * A built key becomes {@code Outfit*Label}: the parts that are decided here, with
	 * a mark where the number goes.
	 */
	private static Set<String> keysIn(String body) {
		Set<String> found = new LinkedHashSet<>();
		Matcher call = Pattern.compile("\\.(?:put|store|get|read)\\w*\\(").matcher(body);
		while (call.find()) {
			String key = firstArgument(body, call.end());
			if (key != null) found.add(key);
		}
		return found;
	}

	/** The first argument of a call, as its literal parts joined by a star. */
	private static String firstArgument(String body, int from) {
		int depth = 0;
		int at = from;
		StringBuilder expression = new StringBuilder();
		while (at < body.length()) {
			char letter = body.charAt(at);
			if (letter == '(') depth++;
			else if (letter == ')') {
				if (depth == 0) break;
				depth--;
			} else if (letter == ',' && depth == 0) {
				break;
			}
			expression.append(letter);
			at++;
		}

		Matcher parts = Pattern.compile("\"([^\"]*)\"").matcher(expression);
		StringBuilder key = new StringBuilder();
		boolean any = false;
		while (parts.find()) {
			if (any) key.append('*');
			key.append(parts.group(1));
			any = true;
		}
		// A key held in a variable or a constant rather than written here. Not this
		// test's business, and not silently counted as a name either.
		return any ? key.toString() : null;
	}

	private static String source(String name) throws IOException {
		return Files.readString(ENTITIES.resolve(name + ".java"), StandardCharsets.UTF_8);
	}

	/** The entities that keep anything of their own in the save. */
	private static final List<String> KEEPERS = List.of("NpcEntity", "ModelObject");

	@Test
	@DisplayName("nothing is written that is never read again")
	void nothingIsWrittenIntoTheVoid() throws IOException {
		for (String name : KEEPERS) {
			String source = source(name);
			Set<String> written = keysIn(bodyOf(source, "addAdditionalSaveData"));
			Set<String> read = keysIn(bodyOf(source, "readAdditionalSaveData"));

			List<String> lost = written.stream().filter(key -> !read.contains(key)).toList();
			// Written and never read is a setting that takes up room in every save file
			// and comes back as its default — the character is right until the world is
			// closed, which is the worst possible time to find out.
			assertTrue(lost.isEmpty(), name + " writes and never reads: " + lost);
		}
	}

	@Test
	@DisplayName("nothing is read that is never written, unless it is a migration")
	void nothingIsReadFromNowhere() throws IOException {
		for (String name : KEEPERS) {
			String source = source(name);
			Set<String> written = keysIn(bodyOf(source, "addAdditionalSaveData"));
			Set<String> read = keysIn(bodyOf(source, "readAdditionalSaveData"));

			List<String> orphans = read.stream()
				.filter(key -> !written.contains(key))
				.filter(key -> !READ_ON_PURPOSE_ONLY.contains(key))
				.toList();
			// Read and never written is either a name that has been renamed on one side
			// only — so the setting is silently lost — or a migration, which belongs on
			// the list above with its reason beside it.
			assertTrue(orphans.isEmpty(), name + " reads from nowhere: " + orphans);
		}
	}

	@Test
	@DisplayName("the pair was actually found, so a green result means something")
	void thePairWasRead() throws IOException {
		// The assertion that stops the two above from passing by finding nothing at
		// all. If these methods are renamed or the calls stop looking like this, both
		// would report perfect agreement about an empty set — green, and saying the
		// exact opposite of the truth.
		for (String name : KEEPERS) {
			String source = source(name);
			assertTrue(keysIn(bodyOf(source, "addAdditionalSaveData")).size() >= 5,
				name + ": found almost nothing being written — the saving has probably "
					+ "been rewritten in a shape this test cannot read");
			assertTrue(keysIn(bodyOf(source, "readAdditionalSaveData")).size() >= 5,
				name + ": found almost nothing being read");
		}
	}

	@Test
	@DisplayName("a camera keeps nothing, and says so on both sides")
	void theCameraKeepsNothing() throws IOException {
		// A scene camera is a thing this client is drawing, not a thing the world has.
		// One saved key here would mean a camera coming back out of somebody's save
		// file in a world that has no editor open.
		String source = source("SceneCamera");
		assertTrue(keysIn(bodyOf(source, "addAdditionalSaveData")).isEmpty(),
			"a camera has started saving something");
		assertTrue(keysIn(bodyOf(source, "readAdditionalSaveData")).isEmpty(),
			"a camera has started loading something");
	}
}
