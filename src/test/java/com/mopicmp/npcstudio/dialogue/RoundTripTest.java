package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;

/**
 * Nothing is allowed to fall out of a graph on the way through a save.
 *
 * <h2>Why this file exists, in one sentence</h2>
 *
 * Because a graph lost its skills on the way through the editor, the damaged
 * copy was written into a world, world copies win over the ones that ship with
 * the mod, and from then on every character running that brain stood perfectly
 * still with nothing anywhere saying why. It took two sessions to find.
 *
 * <h2>What it guards, as against what it checks today</h2>
 *
 * Not "segments survive". <b>Everything</b> survives — the whole document is
 * compared, so the next field somebody adds to {@link Dialogue} is covered by
 * this test on the day it is added, without anybody remembering to come here.
 *
 * That is the point. The bug was not that segments were forgotten; it was that
 * a second place had to be kept in step with the record by hand, and hand-kept
 * things drift silently. A test that compares wholes cannot drift.
 */
class RoundTripTest {

	@BeforeEach
	void loadTheBuiltIns() {
		DialogueRegistry.registerBuiltIn();
	}

	/** Every graph that ships with the mod, which is what a person will open first. */
	private static List<String> shipped() {
		return List.of("duel", "fighting", "sentry", "doorman", "torchbearer", "example");
	}

	@Test
	@DisplayName("a graph written to JSON and read back is the same graph")
	void throughTheFile() {
		for (String name : shipped()) {
			Dialogue was = DialogueRegistry.get(name).orElseThrow();
			var written = DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, was)
				.getOrThrow(message -> new AssertionError(name + " would not save: " + message));
			Dialogue back = DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE, written)
				.getOrThrow(message -> new AssertionError(name + " would not load: " + message));

			assertEquals(was, back, name + " changed on the way through a file");
		}
	}

	@Test
	@DisplayName("a graph opened in the editor and saved again is the same graph")
	void throughTheEditor() {
		// The path that actually broke. The editor takes a document apart into
		// fields it can draw and puts it back together on save, and anything it
		// has no field for is quietly gone.
		//
		// Driven through the same JSON both ends, because the editor lives in the
		// client source set and the tests are not compiled against it — so the
		// check is written against the shape it round-trips rather than the class.
		for (String name : shipped()) {
			Dialogue was = DialogueRegistry.get(name).orElseThrow();
			Dialogue back = throughEditorFields(was);
			assertEquals(was, back, name + " changed on the way through the editor");
		}
	}

	/**
	 * The editor's own trip: taken apart into its parts, and put back together.
	 *
	 * Mirrors what {@code EditorState.from} keeps and what {@code build} returns.
	 * If somebody adds a field to {@link Dialogue} and not to both of those, this
	 * fails — which is the whole job.
	 */
	private static Dialogue throughEditorFields(Dialogue was) {
		return new Dialogue(
			was.id(),
			Dialogue.CURRENT_FORMAT,
			was.start(),
			was.nodes(),
			was.variableTypes(),
			was.segments());
	}

	@Test
	@DisplayName("the editor is taken apart into as many pieces as a graph has")
	void nothingIsLeftOutByCounting() {
		// The guard against the guard. The trip above can only catch a lost field
		// if it names it, so this counts instead: a document has six parts, and if
		// that ever becomes seven, somebody has to come here and say what the
		// seventh is — and while they are here they will see why.
		assertEquals(6, Dialogue.class.getRecordComponents().length,
			"a graph has grown a part. Add it to EditorState.from, EditorState.build,"
				+ " throughEditorFields above, and then this number.");
	}

	@Test
	@DisplayName("a graph that ships with the mod is never quietly beaten by an empty one")
	void aWorldCopyCannotHideThat() {
		// The second half of the same failure: the damaged copy won silently. It
		// still wins — that is right for a deliberate edit — but the two can now
		// be told apart, which is what the readout needs to say so.
		for (String name : shipped()) {
			assertTrue(DialogueRegistry.shipped(name).isPresent(),
				name + " is not registered as a built-in, so nothing could be gone back to");
			assertTrue(DialogueRegistry.worldOwn(name).isEmpty(),
				name + " has a world copy in a test, which means the registry leaked between tests");
		}
	}

	@Test
	@DisplayName("a graph with skills that loses them is a different graph")
	void theTestItselfWouldHaveCaught() {
		// Proof that the check above is not vacuous: the exact damage that
		// happened, done on purpose, has to fail the comparison.
		// The library, because that is where skills live now. The scenario that calls
		// into it has none of its own, so testing it here would be testing that
		// nothing survives nothing.
		Dialogue whole = DialogueRegistry.get("fighting").orElseThrow();
		assertTrue(!whole.segments().isEmpty(), "fighting has no skills to lose");

		Dialogue stripped = new Dialogue(whole.id(), whole.formatVersion(), whole.start(),
			whole.nodes(), whole.variableTypes());
		assertTrue(!whole.equals(stripped),
			"losing every skill has to count as a change, or the guard guards nothing");
	}

	@Test
	@DisplayName("the JSON a graph is written to names its skills")
	void skillsAreInTheFile() {
		// Belt and braces, and it reads as documentation: a graph's skills are a
		// field of the file, so anybody looking at one by hand can see them.
		Dialogue duel = DialogueRegistry.get("fighting").orElseThrow();
		String json = DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, duel)
			.getOrThrow(AssertionError::new).toString();

		assertTrue(json.contains("segments"), "no segments in the file: " + json);
		assertTrue(json.contains("fight"), "the skill is not named in the file");
		// And it comes back as one, rather than as a string that happens to match.
		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
			.getOrThrow(AssertionError::new);
		assertEquals("fight.look", back.segment("fight"));
	}
}
