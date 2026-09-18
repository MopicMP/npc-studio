package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.runtime.Cast;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueSaveData;

/**
 * What says a place already has a conversation running.
 *
 * <h2>The report this is written from</h2>
 *
 * "The blocks work only once per visit to the world. Even starting the dialogue
 * again does not bring them back — they only work again if you rejoin the world."
 *
 * The answer was a second map beside the bookmark holding "this trigger is running".
 * Three paths emptied it: the graph reaching its ending, the minute of patience
 * running out, and the player disconnecting. Anything else — and a scene that cannot
 * be advanced is very much anything else, since a place has nobody to click — left an
 * entry standing for ever, meaning "already running", so the trigger never fired
 * again. Rejoining worked because the disconnect was the only sweep that ever ran.
 *
 * So the question is answered from the bookmark now, and the bookmark is what these
 * are about: it is saved, it is cleared by everything that ends a conversation, and
 * it cannot fall out of step with itself.
 */
class ZoneLatchTest {

	private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());

	@Test
	@DisplayName("a place with no bookmark is not running, and one with a bookmark is")
	void theBookmarkIsTheWholeAnswer() {
		DialogueSaveData store = new DialogueSaveData();
		UUID zone = Cast.Nobody.named("tutorial/leaving");

		assertNull(store.bookmark(PLAYER, zone), "nothing has begun yet");

		store.putBookmark(PLAYER, zone, "ask");
		assertEquals("ask", store.bookmark(PLAYER, zone), "and now it is under way");

		// Every way a conversation ends goes through this, which is the property the
		// second map did not have.
		store.clearBookmark(PLAYER, zone);
		assertNull(store.bookmark(PLAYER, zone), "so the doorway may speak again");
	}

	@Test
	@DisplayName("two places of one document are two conversations, not one")
	void eachDoorwayKeepsItsOwnPlace() {
		DialogueSaveData store = new DialogueSaveData();
		UUID leaving = Cast.Nobody.named("tutorial/leaving");
		UUID arriving = Cast.Nobody.named("tutorial/arriving");

		store.putBookmark(PLAYER, leaving, "ask");
		assertNull(store.bookmark(PLAYER, arriving),
			"walking into the second room is not the first room being ignored");

		store.clearBookmark(PLAYER, leaving);
		store.putBookmark(PLAYER, arriving, "greet");
		assertEquals("greet", store.bookmark(PLAYER, arriving));
	}

	@Test
	@DisplayName("a character and a place in one document keep separate bookmarks")
	void bothThreadsAtOnce() {
		// The whole point of a document being a place as well as a conversation. Keyed
		// by player and subject, and a place's subject is a name rather than a body.
		DialogueSaveData store = new DialogueSaveData();
		UUID guard = UUID.nameUUIDFromBytes("guard".getBytes());
		UUID zone = Cast.Nobody.named("tutorial/leaving");

		store.putBookmark(PLAYER, guard, "greet");
		store.putBookmark(PLAYER, zone, "ask");

		assertEquals("greet", store.bookmark(PLAYER, guard));
		assertEquals("ask", store.bookmark(PLAYER, zone));

		store.clearBookmark(PLAYER, zone);
		assertEquals("greet", store.bookmark(PLAYER, guard),
			"the doorway finishing does not end the conversation with the guard");
	}

	@Test
	@DisplayName("carrying on past something that is not a line is refused, so it is not asked")
	void whyTheAdvanceIsDemoted() {
		// The reason the click on empty space asks to begin rather than to advance when
		// the scene has already moved past the line. A press landing a tick late is an
		// ordinary thing; this is what it would cost if it were passed straight through.
		Dialogue graph = Dialogue.builder("tutorial")
			.start("wait")
			.add(new Node.Until("wait", new Condition.Always(), "end"))
			.add(new Node.End("end"))
			.build();
		DialogueState at = new DialogueState("wait", Map.of(), Map.of(), Map.of(), Set.of(),
			graph.variableTypes());

		assertThrows(DialogueEngine.DialogueFault.class,
			() -> DialogueEngine.step(graph, at, new DialogueEngine.Input.Advance(),
				(item, count) -> false),
			"which would end the scene with a red message rather than a shrug");

		// Begin at the same node is the shrug: it re-shows what is there and moves on
		// if the graph is ready to.
		assertNotNull(DialogueEngine.step(graph, at, new DialogueEngine.Input.Begin(),
			(item, count) -> false));
	}

	@Test
	@DisplayName("a place is named the same tomorrow as it is today")
	void theNameOutlivesTheSession() {
		// It has to: the bookmark is written into the world's save under this, and the
		// scene has to still be recognised as itself after a restart. Two zones of one
		// document must also stay two.
		assertEquals(Cast.Nobody.named("tutorial/leaving"),
			Cast.Nobody.named("tutorial/leaving"));
		assertEquals(List.of(true),
			List.of(!Cast.Nobody.named("tutorial/leaving")
				.equals(Cast.Nobody.named("tutorial/arriving"))));
	}
}
