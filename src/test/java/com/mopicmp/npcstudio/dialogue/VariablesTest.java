package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finding the variables a document names, so the editor can offer to declare them.
 *
 * <h2>What this is really about</h2>
 *
 * The validator has always been able to say "reads gold, which is not declared". It only
 * ever said it: there was nowhere in the editor to declare anything, so the message was
 * a red line naming a fault with no door leading to the fix. Reported as "why does it
 * say the variable is not declared, I do not understand how to work with this", which is
 * the only reasonable thing to say about a rule with no way to satisfy it.
 *
 * One reading serves both now. These tests are what stops it drifting from the
 * validator's — an editor offering to fix something still refused would be worse than
 * either.
 */
class VariablesTest {

	private static Dialogue writes(Value value) {
		return Dialogue.builder("doc")
			.start("set")
			.add(new Node.Set("set", "gold", Scope.PLAYER, value, "end"))
			.add(new Node.End("end"))
			.build();
	}

	@Test
	@DisplayName("a name written to is offered with the type that was written into it")
	void writingImpliesTheType() {
		assertEquals(Map.of("gold", "number"), Variables.undeclared(writes(Value.of(3))));
		assertEquals(Map.of("gold", "flag"), Variables.undeclared(writes(Value.of(true))));
		assertEquals(Map.of("gold", "text"), Variables.undeclared(writes(Value.of("some"))));
	}

	@Test
	@DisplayName("a name read is offered with the type it is compared against")
	void readingImpliesItToo() {
		Dialogue graph = Dialogue.builder("doc")
			.start("ask")
			.add(new Node.Branch("ask", List.of(new Node.Arm(
				new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ, Value.of(true)),
				"end")), "end"))
			.add(new Node.End("end"))
			.build();
		assertEquals(Map.of("met", "flag"), Variables.undeclared(graph));
	}

	@Test
	@DisplayName("what the editor offers is exactly what the validator refuses")
	void theTwoReadingsAgree() {
		// The one thing that must hold. Two readings would drift, and the drift would be
		// an editor offering to declare something the validator still complains about —
		// or worse, saying nothing while the validator says there is a fault.
		Dialogue graph = writes(Value.of(3));
		assertFalse(DialogueValidator.validate(graph).ok());

		Dialogue declared = new Dialogue(graph.id(), graph.formatVersion(), graph.start(),
			graph.nodes(), Variables.undeclared(graph), graph.segments(), graph.areas(),
			graph.manner(), graph.places());

		assertTrue(Variables.undeclared(declared).isEmpty(), "nothing left to offer");
		assertTrue(DialogueValidator.validate(declared).ok(),
			"and taking the offer makes the graph run: " + DialogueValidator.validate(declared));
	}

	@Test
	@DisplayName("a character's senses and a caller's values are not this document's to declare")
	void someNamesAreSomebodyElsesToSay() {
		// A sense is offered by the character and the list of them is fixed; a value
		// handed in is declared by whoever calls. Offering to declare either would be
		// offering to write down something that is not this document's to say.
		Dialogue graph = Dialogue.builder("doc")
			.start("wait")
			.add(new Node.Until("wait", new Condition.Compare(Sense.PLAYER_DISTANCE,
				Scope.SENSE, Condition.Op.LT, Value.of(4)), "end"))
			.add(new Node.End("end"))
			.build();
		assertTrue(Variables.undeclared(graph).isEmpty());
	}

	@Test
	@DisplayName("a declared name nothing mentions is named as scrap")
	void unusedOnesAreFound() {
		// Not an error — a variable declared ahead of the nodes that will use it is an
		// ordinary half-written graph. Worth showing greyed, because it is the one thing
		// worth knowing before taking a declaration away.
		Dialogue graph = Dialogue.builder("doc")
			.variable("spare", "number")
			.start("end")
			.add(new Node.End("end"))
			.build();
		assertEquals(List.of("spare"), Variables.unused(graph));
		assertTrue(DialogueValidator.validate(graph).ok(), "and it is not a fault");
	}

	@Test
	@DisplayName("a name used two ways is offered once, and the disagreement stays findable")
	void oneNameTwoTypes() {
		// Declaring it as one of the two turns a vague silence into an error the
		// validator can point at a node with. Declaring nothing would leave the report
		// exactly as unhelpful as it was.
		Dialogue graph = Dialogue.builder("doc")
			.start("set")
			.add(new Node.Set("set", "gold", Scope.PLAYER, Value.of(3), "also"))
			.add(new Node.Set("also", "gold", Scope.PLAYER, Value.of(true), "end"))
			.add(new Node.End("end"))
			.build();

		assertEquals(Map.of("gold", "number"), Variables.undeclared(graph),
			"the first use decides");

		Dialogue declared = new Dialogue(graph.id(), graph.formatVersion(), graph.start(),
			graph.nodes(), Variables.undeclared(graph), graph.segments(), graph.areas(),
			graph.manner(), graph.places());
		String said = DialogueValidator.validate(declared).toString();
		assertTrue(said.contains("also"), "and the node that disagrees is named: " + said);
	}
}
