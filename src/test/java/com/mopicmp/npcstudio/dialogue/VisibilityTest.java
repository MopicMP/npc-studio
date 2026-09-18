package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;

/**
 * Which documents answer for somebody standing somewhere.
 *
 * <h2>Why both halves matter, and why the second is the one that gets forgotten</h2>
 *
 * The obvious half is that a guest inside a lesson should not be shown the map's
 * documents. It is obvious because it is the half somebody notices: a list with the wrong
 * things in it.
 *
 * The half that is quiet is the other one. A document of rules watches every player on
 * the server, so without scoping, a guest who walks into a lesson drags the whole map's
 * ground triggers in with them. Nothing errors. The lesson simply behaves strangely, for
 * reasons that are written down on a document the guest cannot see and would never think
 * to look at.
 */
class VisibilityTest {

	private static Dialogue rules(String id, String within) {
		Dialogue base = Dialogue.builder(id).add(new Node.End("end")).build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), List.of(), base.voices(), Dialogue.Kind.PLAYER,
			List.of(), List.of(), Optional.empty(), within);
	}

	private static Dialogue place(String id) {
		Dialogue base = Dialogue.builder(id).add(new Node.End("end")).build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("вошли", "end"), base.areas(), base.manner(),
			base.places(), List.of(), base.voices(), Dialogue.Kind.LOCATION,
			List.of(), List.of(), Optional.of(Location.of("земля")), "");
	}

	@AfterEach
	void clean() {
		// The registry is one thing for the whole server, so a test that leaves its own
		// documents in it is a test that changes the next one's answers.
		for (String name : List.of("карта", "урок", "поляна")) DialogueRegistry.removeWorld(name);
	}

	@Test
	@DisplayName("the map's documents are the ones seen from nowhere in particular")
	void theMapIsTheDefault() {
		DialogueRegistry.putWorld(rules("карта", ""));
		DialogueRegistry.putWorld(rules("урок", "поляна"));

		assertTrue(DialogueRegistry.names("").contains("карта"));
		assertFalse(DialogueRegistry.names("").contains("урок"),
			"an author on their own map is not shown a lesson's workings");
		assertTrue(DialogueRegistry.names(null).contains("карта"),
			"nowhere and the map are the same word");
	}

	@Test
	@DisplayName("inside a lesson only that lesson answers")
	void insideALessonOnlyItsOwn() {
		DialogueRegistry.putWorld(rules("карта", ""));
		DialogueRegistry.putWorld(rules("урок", "поляна"));

		assertEquals(List.of("урок"), DialogueRegistry.names("поляна"));
		// The quiet half. If this ever passes the map's rules through, nothing breaks
		// loudly: the lesson simply starts doing things nobody in it can account for.
		assertFalse(DialogueRegistry.names("поляна").contains("карта"));
	}

	@Test
	@DisplayName("two lessons do not see each other")
	void lessonsAreSealedFromEachOther() {
		DialogueRegistry.putWorld(rules("урок", "поляна"));
		assertTrue(DialogueRegistry.names("пещера").isEmpty(),
			"another lesson's documents are not another lesson's business");
	}

	@Test
	@DisplayName("a name still means one document wherever it is asked from")
	void namesStayUnique() {
		// Visibility was what was asked for; ambiguity was not. A character holds the
		// name of her document as a plain word and a call reaches across by name, so two
		// places each having a "greeting" would make every one of those words mean two
		// things — and the place it was asked from would have to be carried everywhere a
		// name goes.
		DialogueRegistry.putWorld(rules("урок", "поляна"));
		assertTrue(DialogueRegistry.get("урок").isPresent(),
			"found by name from anywhere, because a name is a name");
		assertEquals("поляна", DialogueRegistry.get("урок").orElseThrow().within());
	}

	@Test
	@DisplayName("a place's own document belongs to the map, not to itself")
	void aLocationLivesOnTheMap() {
		// Otherwise nothing could ever find it to enter it. The document that describes
		// a lesson is the author's; only what is inside the lesson lives inside it.
		DialogueRegistry.putWorld(place("поляна"));
		assertTrue(DialogueRegistry.names("").contains("поляна"));
		assertFalse(DialogueRegistry.names("поляна").contains("поляна"));
	}
}
