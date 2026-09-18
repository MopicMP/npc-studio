package com.mopicmp.npcstudio.client.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;
import com.mopicmp.npcstudio.net.EditorPayloads;

/**
 * What the editor offers when somebody makes a call.
 *
 * <h2>Why this is worth a test rather than a look</h2>
 *
 * Because the failure is silent at both ends. A call naming a skill that is not
 * there leaves a character standing perfectly still, and from outside that is
 * indistinguishable from a fight that has not started — which is exactly the
 * report that cost a session, twice, for two different reasons.
 *
 * The list is the cure, so the list is what has to be right: everything it
 * offers must be something the runtime can then actually find, and it must offer
 * the skills of other documents at all, since a library is useless otherwise.
 */
class SkillPickerTest {

	@BeforeEach
	void loadTheBuiltIns() {
		DialogueRegistry.registerBuiltIn();
		// The same listing the server sends when the editor opens, built the same
		// way. Built rather than hand-written: a hand-written one would go on
		// passing after the real one started leaving the skills out.
		DialogueNames.remember(DialogueRegistry.names().stream()
			.map(name -> DialogueRegistry.get(name)
				.map(graph -> new EditorPayloads.Known(name, graph.speaks(), graph.waits(),
					List.copyOf(graph.segments().keySet()), graph.kind().name().toLowerCase()))
				.orElseThrow())
			.toList());
	}

	@Test
	@DisplayName("everything the picker offers is something the runtime can find")
	void nothingOfferedIsAGhost() {
		for (String offered : DialogueNames.skillsFor("duel", List.of())) {
			int colon = offered.indexOf(':');
			Dialogue book = colon < 0
				? DialogueRegistry.get("duel").orElseThrow()
				: DialogueRegistry.get(offered.substring(0, colon)).orElse(null);
			assertTrue(book != null, offered + " names a document that does not exist");

			String skill = colon < 0 ? offered : offered.substring(colon + 1);
			assertTrue(book.segment(skill) != null,
				offered + " is offered but \"" + book.id() + "\" has no such skill");
		}
	}

	@Test
	@DisplayName("a library's skills are offered, or a library is of no use to anybody")
	void theLibraryIsReachable() {
		List<String> offered = DialogueNames.skillsFor("duel", List.of());
		assertTrue(offered.contains("fighting:fight"),
			"the one thing a call across documents exists for is missing: " + offered);
	}

	@Test
	@DisplayName("a document's own skills are named plainly, not through itself")
	void ownSkillsNeedNoPrefix() {
		// Because a call inside one document should not have to know its own name —
		// and because renaming a document would otherwise break every call it makes
		// to itself.
		List<String> offered = DialogueNames.skillsFor("fighting", List.of("fight"));
		assertTrue(offered.contains("fight"), "its own skill should be plain: " + offered);
		assertFalse(offered.contains("fighting:fight"),
			"and should not also appear through itself: " + offered);
	}
}
