package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * Answering a question, and the two ways it could not be done.
 *
 * <h2>What was reported</h2>
 *
 * "The choice node has problems. In the first showing there is no reaction at all;
 * in the second the screen flashes black for a second and then nothing happens."
 *
 * Two faults with one shape between them and one shape apiece:
 *
 * <ul>
 * <li>the answer travelled with the character who asked, and a question a doorway
 *     asks has no character — the packet carried minus one, the server looked for an
 *     entity with that id, found none, and dropped it in silence;</li>
 * <li>the bar had no way to be clicked without opening the chat window first, which
 *     nothing on screen said and nothing else in the game requires.</li>
 * </ul>
 *
 * The second is input and is not testable here. The first is about which conversation
 * an answer belongs to, and the part of it that can be pinned down without a server
 * is what the engine will and will not accept — which is what decides whether a
 * mismatched press is a shrug or a scene thrown away.
 */
class AnsweringTest {

	private static Dialogue asking() {
		return Dialogue.builder("tutorial")
			.start("ask")
			.add(new Node.Choice("ask", "", Words.of("Finished?"), Presentation.FULLSCREEN,
				List.of(new Node.Option(Words.of("Yes"), "green", new Condition.Always(), "yes"),
					new Node.Option(Words.of("Not yet"), "red", new Condition.Always(), "no"))))
			.add(new Node.Line("yes", "", "Well done.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("no", "", "Take your time.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}

	private static DialogueState at(Dialogue graph, String node) {
		return new DialogueState(node, Map.of(), Map.of(), Map.of(), Set.of(),
			graph.variableTypes());
	}

	@Test
	@DisplayName("a question is answered by its index, and each answer leads its own way")
	void bothAnswersLeadSomewhere() {
		Dialogue graph = asking();
		var yes = DialogueEngine.step(graph, at(graph, "ask"),
			new DialogueEngine.Input.Pick(0), (item, count) -> false);
		assertEquals("Well done.",
			((DialogueEngine.Screen.Line) yes.screen()).text().plain());

		var no = DialogueEngine.step(graph, at(graph, "ask"),
			new DialogueEngine.Input.Pick(1), (item, count) -> false);
		assertEquals("Take your time.",
			((DialogueEngine.Screen.Line) no.screen()).text().plain());
	}

	@Test
	@DisplayName("answering something that is not a question is refused, so it is not asked")
	void whyAPickIsDemoted() {
		// The reason a click landing a tick after the scene moved on is turned into
		// "show me what is there" rather than passed through. Without that, an answer
		// arriving late ends the conversation with a red message — which is what the
		// player would see for a mistake that is entirely ordinary.
		Dialogue graph = asking();
		assertThrows(DialogueEngine.DialogueFault.class,
			() -> DialogueEngine.step(graph, at(graph, "yes"),
				new DialogueEngine.Input.Pick(0), (item, count) -> false));

		// And beginning at the same node simply shows it again.
		var shown = DialogueEngine.step(graph, at(graph, "yes"),
			new DialogueEngine.Input.Begin(), (item, count) -> false);
		assertInstanceOf(DialogueEngine.Screen.Line.class, shown.screen());
	}

	@Test
	@DisplayName("an answer that was never offered is refused rather than trusted")
	void anIndexOutOfRangeIsNotABranch() {
		// The packet carries a number chosen by a client. This is the check that makes
		// the number safe to accept from anybody — including from the new arm that
		// answers a question a place asked, where there is no entity to check instead.
		Dialogue graph = asking();
		assertThrows(DialogueEngine.DialogueFault.class,
			() -> DialogueEngine.step(graph, at(graph, "ask"),
				new DialogueEngine.Input.Pick(7), (item, count) -> false));
		assertThrows(DialogueEngine.DialogueFault.class,
			() -> DialogueEngine.step(graph, at(graph, "ask"),
				new DialogueEngine.Input.Pick(-1), (item, count) -> false));
	}

	@Test
	@DisplayName("a hidden answer keeps its own number, so the one picked is the one seen")
	void numbersAreTheDocumentsAndNotTheScreens() {
		// Which is why the bar draws the key to press rather than the number in the
		// packet: with a condition hiding the first answer, the one shown at the top of
		// the screen is number two, and printing "2" beside it was printing a key that
		// answers something else.
		Dialogue graph = Dialogue.builder("guard")
			.start("ask")
			.add(new Node.Choice("ask", "", Words.of("Well?"), Presentation.SUBTITLE,
				List.of(
					new Node.Option(Words.of("Bribe"), "", new Condition.Not(new Condition.Always()), "yes"),
					new Node.Option(Words.of("Leave"), "", new Condition.Always(), "no"))))
			.add(new Node.Line("yes", "", "Hm.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("no", "", "Good.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();

		var shown = DialogueEngine.step(graph, at(graph, "ask"),
			new DialogueEngine.Input.Begin(), (item, count) -> false);
		var question = (DialogueEngine.Screen.Choice) shown.screen();
		assertEquals(1, question.options().size(), "the bribe is not on offer");
		assertEquals(1, question.options().get(0).index(),
			"and the one that is keeps the number the document gave it");

		// Picking it by that number works; picking it by where it sits does not.
		assertEquals("Good.", ((DialogueEngine.Screen.Line) DialogueEngine.step(graph,
			at(graph, "ask"), new DialogueEngine.Input.Pick(1), (item, count) -> false)
			.screen()).text().plain());
		assertThrows(DialogueEngine.DialogueFault.class,
			() -> DialogueEngine.step(graph, at(graph, "ask"),
				new DialogueEngine.Input.Pick(0), (item, count) -> false),
			"the hidden one must not be reachable by asking for it");
	}
}
