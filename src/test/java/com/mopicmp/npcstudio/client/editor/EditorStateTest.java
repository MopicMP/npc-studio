package com.mopicmp.npcstudio.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.Route;
import com.mopicmp.npcstudio.dialogue.Shown;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the editor is not allowed to do to a document.
 *
 * <h2>Why these are tests and not care</h2>
 *
 * Because the failure they are about was invisible from inside the window. A location was
 * made, a node was added, "make this the start" was pressed — and from then on the document
 * could not be saved at all, with the only clue a red line about a starting node the author
 * had never asked for and could not remove. Deleting the node did not clear it either: the
 * message went on naming a node that no longer existed anywhere.
 *
 * Nothing about that is visible in a screenshot of a graph. It is the document's own
 * invariants, so it is tested where the document lives.
 */
class EditorStateTest {

	private static EditorState of(Dialogue dialogue) {
		return EditorState.from(DialogueCodecs.DIALOGUE
			.encodeStart(JsonOps.INSTANCE, dialogue).result().orElseThrow().toString(),
			List.of());
	}

	/** A place as the server makes one: no beginning, one end, its own settings. */
	private static Dialogue place() {
		Dialogue base = Dialogue.builder("place1").add(new Node.End("end")).build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), List.of(), base.voices(), Dialogue.Kind.LOCATION, List.of(),
			List.of(), java.util.Optional.of(
				com.mopicmp.npcstudio.dialogue.Location.of("")), "");
	}

	/** A box to hang a trigger on, since a trigger about no box is a trigger about nothing. */
	private static com.mopicmp.npcstudio.dialogue.Area box() {
		return new com.mopicmp.npcstudio.dialogue.Area(
			new Route.Point.At(0, 0, 0), new Route.Point.At(4, 4, 4), Route.From.WORLD);
	}

	private static List<String> errors(EditorState state) {
		return state.problems().stream()
			.filter(problem -> problem.severity() == DialogueValidator.Severity.ERROR)
			.map(DialogueValidator.Problem::message)
			.toList();
	}

	@Test
	@DisplayName("a document nobody carries cannot be given a beginning")
	void aPlaceHasNoStart() {
		EditorState state = of(place());
		state.add(new Node.Act("show1", new Effect.Show("show1",
			Shown.of("Hello"), new Route.Point.Go(2, 1, 0)), "end"));

		state.start("show1");

		assertEquals("", state.start(), "the press does nothing rather than spoiling the file");
		assertTrue(errors(state).isEmpty(), "and the document is still saveable");
	}

	@Test
	@DisplayName("a conversation still can be, which is what the refusal must not break")
	void aSceneStillHasOne() {
		Dialogue talk = Dialogue.builder("talk")
			.start("hello")
			.add(new Node.Line("hello", "", "Good day", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
		EditorState state = of(talk);

		state.start("end");
		assertEquals("end", state.start());
	}

	@Test
	@DisplayName("deleting the starting node clears the start rather than leaving wreckage")
	void deletingTheStart() {
		Dialogue talk = Dialogue.builder("talk")
			.start("hello")
			.add(new Node.Line("hello", "", "Good day", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
		EditorState state = of(talk);

		state.remove(state.nodeIds().indexOf("hello"));

		assertEquals("", state.start(),
			"a start naming a node that is gone is nobody's decision to keep");
		assertFalse(errors(state).stream().anyMatch(said -> said.contains("hello")),
			"and nothing goes on complaining about a node that no longer exists");
	}

	@Test
	@DisplayName("deleting a node takes its way in, and the trigger that named it, with it")
	void deletingAWayIn() {
		EditorState state = of(place());
		state.add(new Node.Act("show1", new Effect.Show("show1",
			Shown.of("Hello"), new Route.Point.Go(2, 1, 0)), "end"));
		state.area("room", box());
		state.segment("show1", "show1");
		state.trigger("room", "show1");
		assertEquals(1, state.triggers().size());

		state.remove(state.nodeIds().indexOf("show1"));

		assertTrue(state.segments().isEmpty(), "a way in to nowhere is not a way in");
		assertTrue(state.triggers().isEmpty(),
			"and a trigger pointing at a way in that has gone would only move the refusal down");
	}

	@Test
	@DisplayName("deleting one node leaves everybody else's wires alone")
	void wiresAreTheAuthorsBusiness() {
		Dialogue talk = Dialogue.builder("talk")
			.start("hello")
			.add(new Node.Line("hello", "", "Good day", Presentation.SUBTITLE, null, "middle"))
			.add(new Node.Line("middle", "", "And then", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
		EditorState state = of(talk);

		state.remove(state.nodeIds().indexOf("middle"));

		// The wire from "hello" still names it. That is a decision somebody made about
		// where the graph goes, and the validator names it so it can be pointed somewhere
		// on purpose rather than quietly rehung.
		assertTrue(state.nodes().get(0) instanceof Node.Line line && line.next().equals("middle"));
		assertTrue(errors(state).stream()
			.anyMatch(said -> said.contains("\"middle\", which does not exist")));
	}

	@Test
	@DisplayName("a fresh place with a hologram and a way in saves cleanly")
	void thePathTheAuthorActuallyTook() {
		// The whole of the report, walked through: make a place, add the node, give it a
		// way in, and a trigger to set it off. Nothing in that sequence may leave the
		// document unsaveable.
		EditorState state = of(place());
		state.add(new Node.Act("show1", new Effect.Show("show1",
			Shown.of("Hello"), new Route.Point.Go(2, 1, 0)), "end"));
		state.area("room", box());
		state.segment("in", "show1");
		state.trigger("room", "in");

		assertTrue(errors(state).isEmpty(), () -> "still refused: " + errors(state));
	}
}
