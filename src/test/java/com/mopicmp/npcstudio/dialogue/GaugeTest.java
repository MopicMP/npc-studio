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
 * Letting the player see what they are carrying.
 *
 * <h2>Why this was the last thing and not the first</h2>
 *
 * "The player has stamina" turned out to be a variable — which existed all along — plus
 * events that move it, which the graph does. What was missing was that <b>a variable the
 * player cannot see is a variable they do not know about</b>: an ability gated on energy,
 * without a gauge, is an ability that works sometimes for reasons nobody in the game can
 * find out.
 *
 * It went last because it is presentation rather than language, and presentation done in
 * passing is presentation done badly. It is here at all because leaving it out would have
 * left everything above it half-usable.
 */
class GaugeTest {

	private static Dialogue showing(List<Gauge> gauges) {
		Dialogue base = Dialogue.builder("выносливость")
			.variable("энергия", "number")
			.variable("устал", "flag")
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("никуда", "end"), base.areas(), base.manner(),
			base.places(),
			List.of(Trigger.onKnack(Knack.DOUBLE_JUMP, new Condition.Always(), "никуда")),
			base.voices(), Dialogue.Kind.PLAYER, List.of(), gauges);
	}

	@Test
	@DisplayName("a bar with no maximum is refused, because there is nothing to be full of")
	void aBarNeedsAMaximum() {
		// The one worth refusing outright: "full at what" has no answer anybody could
		// guess, and anything that tried to draw it would be dividing by nought.
		var report = DialogueValidator.validate(showing(List.of(
			new Gauge("Силы", "энергия", Scope.PLAYER, Gauge.Look.BAR, 0,
				Gauge.Corner.TOP_LEFT, "", new Condition.Always()))));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("nothing for it to be full of")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a number needs no maximum, because it has no bar to fill")
	void aNumberNeedsNoMaximum() {
		Dialogue graph = showing(List.of(
			new Gauge("Монеты", "энергия", Scope.PLAYER, Gauge.Look.NUMBER, 0,
				Gauge.Corner.TOP_RIGHT, "gold", new Condition.Always())));
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("two gauges with one label are refused, or they would sit on each other")
	void oneLabelTwiceIsRefused() {
		var report = DialogueValidator.validate(showing(List.of(
			Gauge.bar("Силы", "энергия", 100), Gauge.bar("Силы", "энергия", 50))));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("written twice")),
			report.errors().toString());
	}

	@Test
	@DisplayName("showing something that is not a number is a word, not a refusal")
	void showingAFlagIsWarnedAbout() {
		// A bar of a flag draws nothing useful, but it is a document somebody is still
		// building rather than one that cannot run — and refusing it would hold their
		// save back over a field they are in the middle of.
		var report = DialogueValidator.validate(showing(List.of(
			Gauge.bar("Устал", "устал", 1))));
		assertTrue(report.ok(), "still saveable: " + report.errors());
		assertTrue(report.problems().stream()
			.anyMatch(problem -> problem.message().contains("only a number has anything to draw")),
			report.problems().toString());
	}

	@Test
	@DisplayName("the variable a gauge shows is counted, so a misspelt one is caught")
	void theShownVariableIsCounted() {
		// A name with a letter out of it shows nought for ever, which reads as a stamina
		// that never fills — the same silence everything else here is built to break.
		Dialogue graph = showing(List.of(Gauge.bar("Силы", "энергiя", 100)));
		assertTrue(Variables.undeclared(graph).containsKey("энергiя"),
			Variables.undeclared(graph).toString());
		assertFalse(DialogueValidator.validate(graph).ok());
	}

	@Test
	@DisplayName("a gauge shown only sometimes keeps its condition through the file")
	void theConditionSurvives() {
		// "While it is not full" is the ordinary way to keep a screen quiet, and losing
		// it would leave a bar permanently on somebody's view of the world.
		Gauge quiet = Gauge.bar("Силы", "энергия", 100).shownWhen(
			new Condition.Compare("энергия", Scope.PLAYER, Condition.Op.LT, Value.of(100)));
		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, showing(List.of(quiet)))
				.getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(List.of(quiet), back.gauges());
	}

	@Test
	@DisplayName("a document showing nothing writes no field, so old files do not grow one")
	void nothingShownMeansNoField() {
		Dialogue plain = Dialogue.builder("greeting")
			.start("end").add(new Node.End("end")).build();
		var written = DialogueCodecs.DIALOGUE
			.encodeStart(JsonOps.INSTANCE, plain).getOrThrow(IllegalStateException::new);
		assertFalse(written.getAsJsonObject().has("gauges"),
			"a document with none says nothing: " + written);
	}

	@Test
	@DisplayName("the plainest form is a bar in a corner, on all the time")
	void thePlainestForm() {
		Gauge bar = Gauge.bar("Силы", "энергия", 100);
		assertEquals(Gauge.Look.BAR, bar.look());
		assertEquals(Gauge.Corner.TOP_LEFT, bar.corner());
		assertEquals(Scope.PLAYER, bar.scope());
		assertTrue(bar.when() instanceof Condition.Always);
		assertTrue(DialogueValidator.validate(showing(List.of(bar))).ok());
	}
}
