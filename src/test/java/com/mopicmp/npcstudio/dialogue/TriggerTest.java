package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A document that is a place as well as a conversation.
 *
 * The part that can be asked without a world is asked here: what the binding means,
 * that a document without one is untouched by any of it, and that a zone's boxes are
 * measured the way a zone has to measure them.
 */
class TriggerTest {

	private static Dialogue withTrigger() {
		Dialogue base = Dialogue.builder("tutorial")
			.start("greet")
			.add(new Node.Line("greet", "", "Hello.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("ask", "", "Done with the tutorial?", Presentation.SUBTITLE,
				null, "end"))
			.add(new Node.End("end"))
			.area("door", new Area(new Route.Point.At(10, 64, 10),
				new Route.Point.At(14, 66, 14), Route.From.WORLD))
			.build();
		// Put together by hand rather than through the builder, which has no word for
		// either of these yet. Writing one would be inventing an editor for a test.
		return new Dialogue(base.id(), base.formatVersion(), base.start(), base.nodes(),
			base.variableTypes(), Map.of("leaving", "ask"), base.areas(), base.manner(),
			base.places(), java.util.List.of(Trigger.of("door", "leaving")));
	}

	@Test
	@DisplayName("a document with no triggers is not watched at all")
	void ordinaryDocumentsCostNothing() {
		// The whole of what keeps this free. Nearly every document answers no, and the
		// answer is a field read rather than a search — asked of every loaded document
		// for every player four times a second.
		Dialogue plain = Dialogue.builder("chat")
			.start("end").add(new Node.End("end")).build();
		assertFalse(plain.watchesGround());
		assertTrue(plain.triggers().isEmpty());
	}

	@Test
	@DisplayName("a box bound to a way in makes the document a place")
	void boundDocumentIsWatched() {
		Dialogue tutorial = withTrigger();
		assertTrue(tutorial.watchesGround());
		assertEquals("leaving", tutorial.triggers().get(0).wayIn());
		// And the way in it names is a real one. A trigger pointing at a segment the
		// document has not got is a room where nothing happens, which is the hardest
		// kind of broken to see — there is no error, only silence.
		assertTrue(tutorial.segments().containsKey(tutorial.triggers().get(0).wayIn()));
	}

	@Test
	@DisplayName("a zone's box is measured from the origin, which is what world coordinates are")
	void zonesMeasureFromNothing() {
		// A conversation a place began has no character, so a box written as steps has
		// nothing to hang off. Measured from the origin facing south, a step and a
		// coordinate are the same arithmetic — which is why the editor writes a zone's
		// boxes in coordinates and why this is not a fallback but the definition.
		Area box = withTrigger().area("door");
		assertTrue(box.holds(12.5, 65, 12.5, 0, 0, 0, Route.Facing.SOUTH),
			"the middle of the box is inside it");
		assertFalse(box.holds(20.5, 65, 12.5, 0, 0, 0, Route.Facing.SOUTH),
			"and a place outside it is not");
	}

	@Test
	@DisplayName("a trigger on a box written as steps is refused, because it can never fire")
	void aZoneCannotHangOffACharacter() {
		// The reported failure, written down. A box drawn round a doorway was saved as
		// {"go": [-3, -3, -7]} — steps from the character, which is what the editor
		// wrote by default — and bound to a way in. A place has no character, so those
		// steps were measured from the origin and the box sat in a hole under the world
		// spawn. Drawing it, binding it and walking into it produced nothing whatever:
		// no error, no message, no fired trigger, and no way to tell from inside the
		// editor that anything was wrong.
		Dialogue base = withTrigger();
		Dialogue hangingOffNobody = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), base.segments(),
			Map.of("door", new Area(new Route.Point.Go(-3, -3, -7),
				new Route.Point.Go(-2, 1, -5), Route.From.CHARACTER)),
			base.manner(), base.places(), base.triggers());

		var report = DialogueValidator.validate(hangingOffNobody);
		assertFalse(report.ok(), "a trigger that cannot possibly fire is not a runnable graph");
		assertTrue(report.toString().contains("steps from a character"),
			"and it says which box and why: " + report);

		// Where the box the writer drew actually ended up, which is the whole of the
		// bug in one line: nowhere near the ground they were standing on.
		Area lost = hangingOffNobody.area("door");
		assertFalse(lost.holds(12.5, 65, 12.5, 0, 0, 0, Route.Facing.SOUTH));
		// Seven blocks to the left of south is seven west, three forward is three
		// north: the box the writer drew round a doorway is at x −7…−5, y −3…1,
		// z −3…−2.
		assertTrue(lost.holds(-6.5, -1, -2.5, 0, 0, 0, Route.Facing.SOUTH),
			"it is down by the origin, where nobody will ever stand");

		// And the same document with the same box written down in coordinates runs.
		assertTrue(DialogueValidator.validate(base).ok());
	}

	@Test
	@DisplayName("a trigger naming a box or a way in that does not exist is refused too")
	void triggersMustNameSomethingReal() {
		Dialogue base = withTrigger();
		Dialogue toNowhere = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), java.util.List.of(Trigger.of("porch", "leaving")));
		assertFalse(DialogueValidator.validate(toNowhere).ok(),
			"a box that was never drawn cannot start anything");

		Dialogue toNoWayIn = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(), java.util.List.of(Trigger.of("door", "arriving")));
		assertFalse(DialogueValidator.validate(toNoWayIn).ok(),
			"and a way in that does not exist is a room where nothing happens");
	}

	@Test
	@DisplayName("a trigger written before it could be asked twice still means every time")
	void theOldFormIsStillTheOrdinaryOne() {
		// What is in somebody's world save today: the name of a way in and nothing else.
		// It has to keep meaning what it meant, or every map with a doorway in it changes
		// behaviour on the day this field arrives.
		var read = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.TRIGGERS
			.parse(com.mojang.serialization.JsonOps.INSTANCE,
				com.google.gson.JsonParser.parseString("{\"door\": \"leaving\"}"))
			.getOrThrow(message -> new AssertionError(message));
		assertEquals(java.util.List.of(Trigger.of("door", "leaving")), read);
		assertEquals(Trigger.Again.EVERY_TIME, read.get(0).again());
		assertFalse(read.get(0).onceOnly());
	}

	@Test
	@DisplayName("and it is written back as it was found, so an untouched map does not change")
	void theShortFormSurvivesASave() {
		// Not tidiness. A format that fattened every old trigger into an object on the
		// first save would show up as a change in somebody's file for a field they never
		// went near — and a diff nobody made is a diff nobody can review.
		var plain = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.TRIGGERS
			.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
				java.util.List.of(Trigger.of("door", "leaving")))
			.getOrThrow(message -> new AssertionError(message));
		assertTrue(plain.isJsonObject(), "still the old map: " + plain);
		assertTrue(plain.getAsJsonObject().get("door").isJsonPrimitive(),
			"and still a bare name inside it: " + plain);

		// One more thing to say, and it grows a shape — but only inside the map, which
		// the rest of the file still reads exactly as it did.
		var once = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.TRIGGERS
			.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, java.util.List.of(
				Trigger.of("door", "leaving").asking(Trigger.Again.ONCE_A_VISIT)))
			.getOrThrow(message -> new AssertionError(message));
		assertTrue(once.getAsJsonObject().get("door").isJsonObject(),
			"the longer form when there is more to say: " + once);
	}

	@Test
	@DisplayName("how often a box fires survives the file it is saved in")
	void onceAVisitIsRemembered() {
		Dialogue base = withTrigger();
		Dialogue once = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), base.segments(), base.areas(), base.manner(),
			base.places(),
			java.util.List.of(Trigger.of("door", "leaving").asking(Trigger.Again.ONCE_A_VISIT)));

		var written = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.DIALOGUE
			.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, once)
			.getOrThrow(message -> new AssertionError(message));
		Dialogue back = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.DIALOGUE
			.parse(com.mojang.serialization.JsonOps.INSTANCE, written)
			.getOrThrow(message -> new AssertionError(message));

		assertEquals(once, back);
		assertTrue(back.triggers().get(0).onceOnly());
		// And it is still a runnable graph: how often it fires is not a second way of
		// naming a way in, so nothing the validator checks has moved.
		assertTrue(DialogueValidator.validate(back).ok());
	}

	@Test
	@DisplayName("a place keeps its own bookmark and its own memory, separately from every other")
	void twoZonesAreTwoThreads() {
		// The name is the document and the way in, both. Keyed by the document alone,
		// two rooms of one map would share a conversation, and walking into the second
		// would read as it being ignored.
		var one = com.mopicmp.npcstudio.dialogue.runtime.Cast.Nobody.named("tutorial/leaving");
		var other = com.mopicmp.npcstudio.dialogue.runtime.Cast.Nobody.named("tutorial/arriving");
		assertFalse(one.equals(other));
		// And it is the same name tomorrow: the bookmark is written into the world's
		// save, and it has to still mean this place after a restart.
		assertEquals(one,
			com.mopicmp.npcstudio.dialogue.runtime.Cast.Nobody.named("tutorial/leaving"));
	}
}
