package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * The few abilities the game has no word for, and what makes one an ability.
 *
 * <h2>Why the list is short, and why that is the whole result</h2>
 *
 * Forty properties the game already keeps — jump height, reach, mining speed, fall
 * damage, gravity, size — all applied on the server and synchronised without asking. So
 * "give the player something they have not got" turned out to be one verb for nearly all
 * of it, and this list is the remainder. Each one here is real work; making the list
 * short was the point of counting the forty first.
 *
 * <h2>What makes it an ability rather than a setting</h2>
 *
 * That it can cost something. A knack that can be switched on and off and never charged
 * for is a setting with a grand name — so the cause that fires when one is used is not a
 * convenience, it is the half that makes the idea whole.
 */
class KnackTest {

	private static Dialogue withRules(List<Standing> standing, List<Trigger> triggers) {
		Dialogue base = Dialogue.builder("прыжок")
			.variable("энергия", "number")
			.add(new Node.Set("spend", "энергия", Scope.PLAYER, Value.of(-1),
				Node.Set.Change.ADD, "end"))
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("потратить", "spend"), base.areas(), base.manner(),
			base.places(), triggers, base.voices(), Dialogue.Kind.PLAYER, standing);
	}

	/** The whole worked example: a jump that costs energy and stops when there is none. */
	private static Dialogue theJump() {
		return withRules(
			List.of(new Standing(Knack.DOUBLE_JUMP,
				new Condition.Compare("энергия", Scope.PLAYER, Condition.Op.GE, Value.of(1)),
				Knack.DOUBLE_JUMP, Effect.Trait.How.ADD, 0)),
			List.of(Trigger.onKnack(Knack.DOUBLE_JUMP, new Condition.Always(), "потратить")));
	}

	@Test
	@DisplayName("an ability is told from the game's own properties by its namespace")
	void oursAreToldApartByName() {
		// So that one table can hold both and an author never has to know which of them
		// the game happens to keep. The file format already had namespaces; nothing new
		// had to be invented to say this.
		assertTrue(Knack.is(Knack.DOUBLE_JUMP));
		assertFalse(Knack.is("minecraft:jump_strength"));
		assertTrue(Knack.DOUBLE_JUMP.startsWith("npc_studio:"));
	}

	@Test
	@DisplayName("an ability lives in the same table as the game's forty")
	void oursSitInTheSameTable() {
		Dialogue graph = theJump();
		assertEquals(1, graph.standing().size());
		assertEquals(Knack.DOUBLE_JUMP, graph.standing().get(0).attribute());
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("a cost is a rule of its own, so the switch and the spending stay apart")
	void theCostIsARule() {
		// The division the whole design rests on: the table says whether it is on, and
		// the graph says what it costs. Neither knows about the other, and the variable
		// is what joins them.
		Dialogue graph = theJump();
		assertEquals(1, graph.triggers().size());
		assertTrue(graph.triggers().get(0).onKnack());
		assertTrue(graph.triggers().get(0).isRule(),
			"charging for a jump runs now and is over, like every rule");
		assertEquals(Knack.DOUBLE_JUMP, graph.triggers().get(0).about());
	}

	@Test
	@DisplayName("the whole arrangement survives the file")
	void itAllSurvivesTheFile() {
		Dialogue graph = theJump();
		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, graph).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(graph.standing(), back.standing());
		assertEquals(graph.triggers(), back.triggers());
	}

	@Test
	@DisplayName("a rule answering an ability nobody wrote is refused")
	void anUnknownAbilityIsRefused() {
		// The one name here that cannot be checked at run time either: which abilities
		// exist is a fact about this mod, known now and known here. Left unchecked it
		// would be a rule that never fires, which is silence.
		Dialogue graph = withRules(List.of(),
			List.of(Trigger.onKnack("npc_studio:triple_jump", new Condition.Always(),
				"потратить")));
		var report = DialogueValidator.validate(graph);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("triple_jump")),
			report.errors().toString());
	}

	@Test
	@DisplayName("an amount on an ability is a word, not a refusal")
	void anAmountOnAnAbilityIsIgnored() {
		// On or off: half a double jump is not a thing. Said rather than refused, because
		// a number left over from having been an attribute is a tidying job and not a
		// broken document.
		Dialogue graph = withRules(
			List.of(Standing.always(Knack.DOUBLE_JUMP, Knack.DOUBLE_JUMP,
				Effect.Trait.How.ADD, 0.5)),
			List.of());
		var report = DialogueValidator.validate(graph);
		assertTrue(report.ok(), "still runnable: " + report.errors());
		assertTrue(report.problems().stream()
			.anyMatch(problem -> problem.message().contains("the amount is ignored")),
			report.problems().toString());
	}

	@Test
	@DisplayName("the variable gating an ability is held to the same standard as any other")
	void theGateIsChecked() {
		// A misspelt name here is an ability that never turns on, and nothing anywhere
		// would say why — the exact silence the rest of this is built to break.
		Dialogue graph = withRules(
			List.of(new Standing(Knack.DOUBLE_JUMP,
				new Condition.Compare("энергiя", Scope.PLAYER, Condition.Op.GE, Value.of(1)),
				Knack.DOUBLE_JUMP, Effect.Trait.How.ADD, 0)),
			List.of());
		assertFalse(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}
}
