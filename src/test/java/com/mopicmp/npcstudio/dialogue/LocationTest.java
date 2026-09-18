package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * A document that is a place rather than something happening in one.
 *
 * <h2>Why the two fields arrived together</h2>
 *
 * Because they are two halves of one idea. A document can now <em>be</em> a place, and a
 * document can now be <em>inside</em> one — and neither half is worth anything alone: a
 * location with no documents of its own is scenery, and a document claiming to live
 * somewhere no location exists is filed nowhere.
 *
 * <h2>What is deliberately not tested here</h2>
 *
 * Laying the ground out. None of that exists yet, and the point of doing the language
 * first is that a name in a file format is the expensive thing to change later while the
 * machinery behind it is not. What is tested is what a file can say and what it is
 * refused for saying.
 */
class LocationTest {

	/** A document of the given kind, with a way in and nothing else of interest. */
	private static Dialogue document(Dialogue.Kind kind, Optional<Location> place,
			String within) {
		Dialogue base = Dialogue.builder("пещера").add(new Node.End("end")).build();
		return new Dialogue(base.id(), base.formatVersion(), "", base.nodes(),
			base.variableTypes(), Map.of("вошли", "end"), base.areas(), base.manner(),
			base.places(), List.of(), base.voices(), kind, List.of(), List.of(),
			place, within);
	}

	@Test
	@DisplayName("a place with no settings is refused, because nothing says what ground it is")
	void aPlaceNeedsItsSettings() {
		// The mistake this catches is one gesture: somebody changes what the document is
		// and does not notice there is a second half to change. Left alone it would be a
		// location that lays out nothing, for ever, without complaint.
		var report = DialogueValidator.validate(
			document(Dialogue.Kind.LOCATION, Optional.empty(), ""));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("which ground it lays out")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a conversation carrying a place's settings is refused, not quietly ignored")
	void settingsWithoutAPlaceAreRefused() {
		// The same gesture in the other direction: the kind was changed back and the
		// settings stayed. Ignoring them would leave a file with rules and a region that
		// nothing will ever read, which is the kind of thing somebody finds a year later
		// and cannot explain.
		var report = DialogueValidator.validate(
			document(Dialogue.Kind.SCENE, Optional.of(Location.of("поляна")), ""));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("is not a place")),
			report.errors().toString());
	}

	@Test
	@DisplayName("places do not nest")
	void aPlaceCannotLiveInAPlace() {
		// Refused because "which documents can I see from here" has to have one answer.
		// Nesting is not hard to implement; it is hard to read, and that question is
		// asked by somebody opening a list who needs the answer to be obvious.
		var report = DialogueValidator.validate(
			document(Dialogue.Kind.LOCATION, Optional.of(Location.of("поляна")), "лес"));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("cannot live inside another")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a document cannot live inside itself")
	void nothingLivesInsideItself() {
		Dialogue loop = document(Dialogue.Kind.SCENE, Optional.empty(), "пещера");
		var report = DialogueValidator.validate(loop);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("inside itself")),
			report.errors().toString());
	}

	@Test
	@DisplayName("a place left standing that guests may rewrite is a word, not a refusal")
	void theHubThatCanBeChangedIsWarnedAbout() {
		// The hub is the one thing a map keeps, so a hub guests can edit is a hub that
		// drifts away from what its author built with nothing to put it back. A word
		// rather than a refusal, because it is a legitimate thing to want on purpose —
		// a shared room people are meant to leave marks in.
		Location open = new Location("хаб", false, new Condition.Always(), Map.of());
		var report = DialogueValidator.validate(
			document(Dialogue.Kind.LOCATION, Optional.of(open), ""));
		assertTrue(report.ok(), "still saveable: " + report.errors());
		assertTrue(report.problems().stream()
			.anyMatch(problem -> problem.message().contains("never rebuilt")),
			report.problems().toString());
	}

	@Test
	@DisplayName("a fresh place is rebuilt every visit and cannot be rewritten by guests")
	void theDefaultsAreTheCarefulOnes() {
		Location plain = Location.of("поляна");
		assertTrue(plain.resets(), "a location is a thing that is put back");
		// Written out rather than inferred: the defaults are the two that are bad to get
		// backwards, and a default that nobody asserts is a default nobody will notice
		// changing.
		assertEquals(new Condition.Not(new Condition.Always()), plain.mayEdit());
		assertTrue(Location.hub("хаб").resets() == false);
	}

	@Test
	@DisplayName("a missing may_edit and a may_edit saying never are the same thing")
	void absentMeansForbidden() {
		// The one place where "absent" and "written out" drifting apart would matter:
		// a file that never mentioned editing must not become a file where guests can
		// rewrite the lesson.
		Location back = DialogueCodecs.LOCATION
			.parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(
				"{\"region\":\"поляна\"}"))
			.getOrThrow(IllegalStateException::new);
		assertEquals(Location.of("поляна"), back);
	}

	@Test
	@DisplayName("what a place is and where a document lives both survive the file")
	void bothSurviveTheFile() {
		Location full = new Location("поляна", true,
			new Condition.Compare("урок", Scope.PLAYER, Condition.Op.GE, Value.of(3)),
			Map.of("keepInventory", "true", "doDaylightCycle", "false"));
		Dialogue was = document(Dialogue.Kind.LOCATION, Optional.of(full), "");
		Dialogue back = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, was).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(was, back);
		assertEquals(Optional.of(full), back.location());

		Dialogue inside = document(Dialogue.Kind.SCENE, Optional.empty(), "поляна");
		Dialogue insideBack = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, DialogueCodecs.DIALOGUE
				.encodeStart(JsonOps.INSTANCE, inside).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals("поляна", insideBack.within());
	}

	@Test
	@DisplayName("only the hub may set game rules, because every place shares one world")
	void rulesBelongToTheHub() {
		// The failure this refuses is the worst kind there is: a setting that works
		// perfectly while its author tests it alone, and stops working the moment a
		// second person walks into a different lesson. It is not wrong at any moment
		// anybody is looking at it.
		Location asking = new Location("поляна", true, new Condition.Always(),
			Map.of("keepInventory", "true"));
		var report = DialogueValidator.validate(
			document(Dialogue.Kind.LOCATION, Optional.of(asking), ""));
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("belong to a world")),
			report.errors().toString());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("keepInventory")),
			"the refusal names the rule, or nobody knows which one to move");
	}

	@Test
	@DisplayName("the hub may set them, because there is nobody for it to disagree with")
	void theHubMaySetRules() {
		Location hub = new Location("хаб", false,
			new Condition.Not(new Condition.Always()), Map.of("keepInventory", "true"));
		var report = DialogueValidator.validate(
			document(Dialogue.Kind.LOCATION, Optional.of(hub), ""));
		assertTrue(report.ok(), report.errors().toString());
	}

	@Test
	@DisplayName("a document that is nowhere and is not a place writes neither field")
	void oldFilesDoNotGrow() {
		Dialogue plain = Dialogue.builder("greeting")
			.start("end").add(new Node.End("end")).build();
		var written = DialogueCodecs.DIALOGUE
			.encodeStart(JsonOps.INSTANCE, plain).getOrThrow(IllegalStateException::new);
		assertFalse(written.getAsJsonObject().has("location"), written.toString());
		assertFalse(written.getAsJsonObject().has("within"), written.toString());
	}

	@Test
	@DisplayName("an ordinary document is visible from the map and from nowhere else")
	void visibilityIsTheDefaultAnswer() {
		Dialogue ownMap = Dialogue.builder("greeting").start("end")
			.add(new Node.End("end")).build();
		assertTrue(ownMap.visibleFrom(""), "every document written so far lives on the map");
		assertTrue(ownMap.visibleFrom(null), "and nowhere is the same as the map");
		assertFalse(ownMap.visibleFrom("поляна"),
			"a guest in a lesson must not see the map's own documents");

		Dialogue lesson = document(Dialogue.Kind.SCENE, Optional.empty(), "поляна");
		assertTrue(lesson.visibleFrom("поляна"));
		assertFalse(lesson.visibleFrom(""),
			"and the author on the map must not see the lesson's");
	}
}
