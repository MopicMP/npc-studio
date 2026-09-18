package com.mopicmp.npcstudio.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;

/**
 * Who a new node takes its speaker from.
 *
 * <h2>What is actually being protected here</h2>
 *
 * That this is a <em>default</em> and never a correction. A convenience that fills
 * an empty field is worth having; the same convenience reaching into a field
 * somebody typed in is a thing that quietly rewrites work, and it would be found
 * out long afterwards, in a conversation where one character has picked up
 * another's name.
 *
 * The other half is that it must not guess. Two conversations meeting at one node
 * is where the answer is genuinely unknown, and a guess there reads exactly like a
 * fact — the field is filled in and looks as deliberate as any other.
 */
class SpeakerCarryTest {

	private static Node.Line line(String id, String speaker, String next) {
		return new Node.Line(id, speaker, "…", Presentation.SUBTITLE, null, next);
	}

	private static Node.Act act(String id, String next) {
		return new Node.Act(id, new Effect.Halt(), next);
	}

	@Test
	@DisplayName("the node the wire left lends its name")
	void straightFromTheSource() {
		List<Node> graph = List.of(line("a", "Гарри", "b"), line("b", "", "b"));
		assertEquals("Гарри", SpeakerCarry.after(graph, "a"));
	}

	@Test
	@DisplayName("a silent node in between is walked through")
	void throughSilence() {
		// The case that makes the difference between a rule and a demo. A line, a
		// pause, another line is an ordinary shape, and reading only the immediate
		// source would work on every graph until somebody put something between two
		// lines — which is the worst way for a convenience to fail, because by then
		// it has been trusted.
		List<Node> graph = List.of(
			line("a", "Гарри", "wait"), act("wait", "b"), line("b", "", "b"));
		assertEquals("Гарри", SpeakerCarry.after(graph, "wait"));
	}

	@Test
	@DisplayName("a fork is not guessed at")
	void twoWaysInMeansNoAnswer() {
		List<Node> graph = List.of(
			line("a", "Гарри", "wait"),
			line("c", "Рон", "wait"),
			act("wait", "b"),
			line("b", "", "b"));
		assertNull(SpeakerCarry.after(graph, "wait"),
			"two speakers lead here and neither is more right than the other");
	}

	@Test
	@DisplayName("a name already written is never touched")
	void neverOverwrites() {
		Node.Line already = line("b", "Рон", "b");
		assertSame(already, SpeakerCarry.given(already, "Гарри"));
	}

	@Test
	@DisplayName("an empty field is filled and everything else about the node is kept")
	void fillsTheBlank() {
		Node.Line blank = new Node.Line("b", "", "Привет.", Presentation.CUTSCENE, "wave", "c");
		Node.Line filled = (Node.Line) SpeakerCarry.given(blank, "Гарри");

		assertEquals("Гарри", filled.speaker());
		// Rebuilding a record is where fields go missing, and a gesture or a wire lost
		// to a convenience would look like the convenience working.
		assertEquals("Привет.", filled.text().plain());
		assertEquals(Presentation.CUTSCENE, filled.mode());
		assertEquals("wave", filled.animation());
		assertEquals("c", filled.next());
		assertEquals("b", filled.id());
	}

	@Test
	@DisplayName("a question inherits as a line does")
	void choicesCountToo() {
		Node.Choice blank = new Node.Choice("q", "", "Ну?", Presentation.FULLSCREEN,
			List.of(new Node.Option("да", "q")));
		assertEquals("Гарри", ((Node.Choice) SpeakerCarry.given(blank, "Гарри")).speaker());

		// And lends its own, which is the half that would be easy to leave out: a
		// question is a talking node too, and the reply after it is the same person
		// carrying on.
		List<Node> graph = List.of(
			new Node.Choice("q", "Гарри", "Ну?", Presentation.FULLSCREEN,
				List.of(new Node.Option("да", "b"))),
			line("b", "", "b"));
		assertEquals("Гарри", SpeakerCarry.after(graph, "q"));
	}

	@Test
	@DisplayName("nothing to inherit is an ordinary answer, not a failure")
	void nobodyHasSpokenYet() {
		assertNull(SpeakerCarry.after(List.of(line("a", "", "a")), "a"));
		assertNull(SpeakerCarry.after(List.of(line("a", "", "a")), "nosuchnode"));

		// And nothing is what a null must do to a node, rather than clearing it.
		Node.Line said = line("b", "Рон", "b");
		assertSame(said, SpeakerCarry.given(said, null));
		assertSame(said, SpeakerCarry.given(said, "   "));
	}

	@Test
	@DisplayName("a ring of nodes does not spin")
	void cyclesEnd() {
		// Every node this editor creates points at itself to begin with, and a graph
		// being built is full of loops that are not mistakes. Walking backwards through
		// one has to stop rather than hang the game.
		List<Node> ring = List.of(act("a", "b"), act("b", "c"), act("c", "a"));
		assertNull(SpeakerCarry.after(ring, "a"));

		// A node pointing at itself is not a node leading into itself: counted as one,
		// every fresh node would look like a fork and nothing would ever be inherited.
		List<Node> fresh = List.of(line("a", "Гарри", "b"), act("b", "b"));
		assertEquals("Гарри", SpeakerCarry.after(fresh, "b"));
	}
}
