package com.mopicmp.npcstudio.client.workspace;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * The joins between the panel list, the icon sheet and the two dictionaries.
 *
 * <h2>Why these are worth a test when nothing here is arithmetic</h2>
 *
 * Because every one of them fails <em>quietly</em>, and quietly in a way that
 * looks deliberate.
 *
 * A panel whose id has no icon does not throw: {@code Dock.iconOf} catches the
 * lookup and hands back the library icon. So the panel appears with somebody
 * else's picture on its tab, in the strip and in every menu, and it reads as a
 * choice rather than as a mistake.
 *
 * A rail button naming an id the panel list has never heard of does not throw
 * either: {@code Panels.make} returns null and the summon gives up. Pressing it
 * does nothing at all, which is indistinguishable from the button being broken —
 * and that exact symptom is what started this whole piece of work.
 *
 * A missing translation shows the key. That one at least announces itself, but
 * only to whoever is running the language it is missing from, which is how a
 * mod ends up shipping English strings to everybody else.
 *
 * None of it is caught by the compiler, because all of it is strings meeting
 * strings at run time.
 */
class PanelsAreCompleteTest {

	private static final Path LANG =
		Path.of("src", "main", "resources", "assets", "npc_studio", "lang");

	private static Map<String, String> words(String language) throws IOException {
		String json = Files.readString(LANG.resolve(language + ".json"), StandardCharsets.UTF_8);
		return new Gson().fromJson(json, new TypeToken<LinkedHashMap<String, String>>() { }.getType());
	}

	/** Whether the icon sheet has a picture of its own for this id. */
	private static boolean hasIcon(String id) {
		try {
			Icon.valueOf(id.toUpperCase(Locale.ROOT));
			return true;
		} catch (IllegalArgumentException none) {
			return false;
		}
	}

	@Test
	@DisplayName("every panel has a name in both languages")
	void everyPanelIsNamed() throws IOException {
		Map<String, String> en = words("en_us");
		Map<String, String> ru = words("ru_ru");

		List<String> missing = new ArrayList<>();
		for (String id : Panels.known()) {
			String key = "npc_studio.panel." + id;
			if (!en.containsKey(key)) missing.add(key + " (en)");
			if (!ru.containsKey(key)) missing.add(key + " (ru)");
		}
		assertTrue(missing.isEmpty(), "not named: " + missing);
	}

	@Test
	@DisplayName("every panel has a picture of its own, not the fallback")
	void everyPanelHasItsOwnIcon() {
		// The fallback is silent by design — a tab with no icon at all would be worse —
		// so the only way to notice a panel wearing the library's picture is to ask.
		List<String> borrowed = new ArrayList<>();
		for (String id : Panels.known()) {
			if (!hasIcon(id)) borrowed.add(id);
		}
		assertTrue(borrowed.isEmpty(), "wearing somebody else's icon: " + borrowed);
	}

	@Test
	@DisplayName("every button on a strip names a panel that can be made")
	void railsNameRealPanels() {
		// A strip button that names nothing does nothing when pressed, silently. That
		// is the exact symptom the whole workspace rebuild started from, so it is the
		// last thing these strips should be able to do.
		//
		// Asked of the list rather than by making one: Panels.make walks this same
		// list, so being on it is the whole of the guarantee, and building a panel
		// outside a running game is not possible — every one of them takes the font
		// from a client that does not exist here.
		for (String id : onTheStrips()) {
			assertTrue(Panels.known().contains(id), id + " is on a strip and is not a panel");
		}
	}

	private static List<String> onTheStrips() {
		List<String> ids = new ArrayList<>(Rail.left());
		ids.addAll(Rail.right());
		return ids;
	}

	@Test
	@DisplayName("no panel is on both strips, and none is on one twice")
	void theStripsDoNotOverlap() {
		List<String> ids = onTheStrips();
		// Two buttons for one panel would each toggle it, so pressing one would put out
		// the light on the other — and the strips exist to answer "is it already up".
		assertTrue(ids.size() == ids.stream().distinct().count(), "twice on the strips: " + ids);
	}

	/*
	 * There was a sixth test here and it has been taken out rather than made to pass.
	 *
	 * It said every panel on a strip must declare a minimum width of its own, on the
	 * grounds that the inherited 120 is too narrow for a panel with fields in it. The
	 * scene panel failed it — and the scene panel was right. It is a list of names in
	 * whatever language somebody typed them, so there is no width that would be
	 * enough: any number would be too wide for most rows and still too narrow for one
	 * of them. What it owed was to behave when narrow, which it now does by cutting a
	 * name to the room there is.
	 *
	 * The test was encoding a suspicion rather than a rule, and a suspicion in a test
	 * is worse than none: it comes back green after somebody has written a number
	 * nobody believes, only to stop the test complaining. The rule about width does
	 * exist and is true — a panel is never framed narrower than it asks for — and it
	 * is pinned in SummonWidthTest, where it belongs.
	 */

	@Test
	@DisplayName("the two dictionaries hold exactly the same keys")
	void theLanguagesAgree() throws IOException {
		Map<String, String> en = words("en_us");
		Map<String, String> ru = words("ru_ru");

		List<String> onlyEn = en.keySet().stream().filter(k -> !ru.containsKey(k)).toList();
		List<String> onlyRu = ru.keySet().stream().filter(k -> !en.containsKey(k)).toList();
		// A key in one and not the other is a line that appears in the wrong language
		// for half the people who read it, and nothing anywhere says so.
		assertTrue(onlyEn.isEmpty(), "only in English: " + onlyEn);
		assertTrue(onlyRu.isEmpty(), "only in Russian: " + onlyRu);
	}
}
