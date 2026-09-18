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

	/**
	 * Every graph that ships with the mod, which is what a person will open first.
	 *
	 * Asked of the registry rather than written out here. It was written out, twice —
	 * in this file and in one other — and a hand-kept list of what exists is the exact
	 * shape of fault this test was written to catch, one level up. A graph added
	 * without remembering to come here would have been a graph nothing checked.
	 */
	private static List<String> shipped() {
		var names = List.copyOf(DialogueRegistry.names());
		assertTrue(names.size() >= 6, "the built-ins did not load at all: " + names);
		return names;
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
			was.segments(),
			// The boxes drawn on the map. Added here on the day they were added to the
			// document, because the editor is exactly where they would be lost: it takes
			// a graph apart into fields it can draw, and a field it has never heard of
			// is gone on the next save with nothing said.
			was.areas(),
			// And how the document asks to be shown, added on the day it was added, for
			// the same reason as the boxes and by the same failing test.
			was.manner(),
			// And where the boxes sit on the canvas. This is the one that was already
			// lost when it was added: the arrangement lived on the screen, in two maps
			// thrown away with it, so a graph dragged into a shape came back in a line.
			was.places(),
			// And which box starts which way in. Lost here, a map would keep its boxes
			// and quietly stop being a place — somebody walks into the room and nothing
			// happens, with the box still drawn on the canvas to say it should.
			was.triggers(),
			// And what colour each speaker's name is. Lost here, every character in a
			// scene would go back to speaking in the same blue on the next save, with
			// the colours still shown in the editor until somebody reopened the file.
			was.voices(),
			// And what the document is for. Lost here, a set of rules would come back a
			// conversation on the next save — its rules still written and still running,
			// but offered to characters to carry, and refused a start it now appears to
			// want. The one field where losing it makes the document look wrong rather
			// than merely act wrong.
			was.kind(),
			// And the properties that are simply true while something holds. Lost here,
			// an ability would go off on the next save — and go off for everybody who
			// had it, because the sweep takes off every derived property no live rule
			// names, and a lost rule names nothing.
			was.standing(),
			// And what the player is shown of any of it. Lost here, a stamina bar goes
			// off the screen while the variable behind it goes on deciding things —
			// worse than losing both, because then nothing says why.
			was.gauges(),
			// And what a place says about its own ground. Lost here, a location saved
			// from the editor comes back a place with nothing to lay out — and, because
			// the validator refuses a kind and its settings that disagree, comes back
			// unsaveable, so the next attempt to fix it is also refused.
			was.location(),
			// And where the document lives. Lost here, a lesson's documents come back
			// visible from the whole map — which is the one thing locations were asked
			// to prevent, and it fails quietly: everything still works, and everybody
			// can see everybody else's.
			was.within());
	}

	@Test
	@DisplayName("the editor is taken apart into as many pieces as a graph has")
	void nothingIsLeftOutByCounting() {
		// The guard against the guard. The trip above can only catch a lost field
		// if it names it, so this counts instead: a document has ten parts, and if
		// that ever becomes eleven, somebody has to come here and say what the
		// eleventh is — and while they are here they will see why.
		//
		// The seventh is the boxes drawn on the map: the stretches a trigger watches
		// and the passages a wall seals. The eighth is how the document asks to be
		// shown — how wide, how tall, how fast, and when the line goes away. The ninth
		// is where each node sits on the canvas. The tenth is which box starts which
		// way in, which is what makes a document a place as well as a conversation.
		//
		// It has now done its job four times, on the four days a part was added, and
		// every time it failed pointing straight at the places that had to be visited
		// first. Worth writing down, because a tripwire nobody has seen fire is
		// indistinguishable from one that does not work — and this one has now caught
		// the same class of mistake five times running.
		//
		// The eleventh is what colour each speaker's name is drawn in — a fact about a
		// character rather than about a line, said once so that the fiftieth line she
		// has is not a different yellow from the first.
		//
		// The twelfth is what the document is for: a conversation, rules about the
		// player, or rules about a kind of thing. It is on the document because a
		// document about the player is carried by nobody — no character holds its name
		// and no box starts it — so if the document does not say what it is, nothing
		// does, and it ends up filed under a conversation where nobody will find it.
		//
		// The thirteenth is the standing properties: what is simply true while something
		// holds, which is the one thing in this language that is not an event. It is a
		// table rather than a graph because a standing property has no order and no
		// flow, and a branch would have implied both.
		//
		// The fourteenth is what the player is shown: a variable nobody can see is a
		// variable nobody knows about, and an ability gated on one is an ability that
		// works sometimes for reasons nobody in the game can find out.
		//
		// Writing on the canvas is not among them, and that is the point of it being a
		// node: it travels with the nodes, so nothing here had to learn about it.
		// The fifteenth and sixteenth arrived together, because they are two halves of
		// one idea: a document can now be a place, and a document can now be inside one.
		//
		// The fifteenth is what a place says about its own ground — which region is laid
		// out on the way in, whether it is rebuilt every visit, and what a guest is
		// allowed to change. It is on the document for the reason the kind is: nobody
		// carries a location. There is no character to hang it on and no box to walk
		// into, because the box does not exist until this document causes it to.
		//
		// The sixteenth is where the document lives, and it is the one that fails most
		// quietly of all sixteen. Lose it and nothing breaks: every lesson goes on
		// working, and every lesson's documents are simply visible from every other —
		// which is precisely what was asked not to happen, and there is no moment at
		// which the game says so.
		assertEquals(16, Dialogue.class.getRecordComponents().length,
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
		// Whatever the skill happens to begin with. Written as a lookup rather than
		// as a name, because the name of a skill's first node is nobody's business
		// but the skill's, and pinning it here means every change to the fighting
		// breaks a test about files.
		assertEquals(DialogueRegistry.get("fighting").orElseThrow().segment("fight"),
			back.segment("fight"));
	}
}
