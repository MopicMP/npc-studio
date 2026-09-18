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
 * A rule about using a block: the campfire, end to end.
 *
 * <h2>What this is answering</h2>
 *
 * A campfire cannot be lit or put out by hand — it wants a flint and steel one way and
 * a shovel the other. So "use it to toggle it" is not a rule the game has, and giving
 * the player that rule is the plainest form of the whole idea: a map's own rules live
 * in its graphs.
 *
 * <h2>The piece that was missing and nearly stayed missing</h2>
 *
 * Every part of the mechanism had been designed and costed before anybody noticed that
 * the sentence it was for could not be spelled. A rule fires because somebody clicked a
 * block, and that block has no name: every other way of naming a place in this language
 * is a name written down in advance. {@link Mark#IT} is that gap, and half the tests
 * here are about it.
 */
class RuleTest {

	/** A world where one place holds one block and the player holds one item. */
	private static Condition.World with(String mark, String block, String held) {
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public boolean blockAt(String asked, String wanted) {
				return asked.equals(mark) && wanted.equals(block);
			}
			@Override public boolean holding(String item) { return item.equals(held); }
		};
	}

	/** The rule: click a fire that is alight, and it goes out. */
	private static Dialogue toggling(Trigger rule) {
		Dialogue base = Dialogue.builder("fire")
			.start("end")
			.add(new Node.Branch("douse", List.of(new Node.Arm(
					new Condition.Block(Mark.IT, "minecraft:campfire[lit=true]"), "out")),
				"light"))
			.add(new Node.Act("out",
				new Effect.PutBlock(Mark.IT, "minecraft:campfire[lit=false]"), "end"))
			.add(new Node.Act("light",
				new Effect.PutBlock(Mark.IT, "minecraft:campfire[lit=true]"), "end"))
			.add(new Node.End("end"))
			.build();
		return new Dialogue(base.id(), base.formatVersion(), base.start(), base.nodes(),
			base.variableTypes(), Map.of("щёлкнули", "douse"), base.areas(), base.manner(),
			base.places(), List.of(rule));
	}

	@Test
	@DisplayName("a rule can name the block it fired about, which nothing else could")
	void theRuleCanNameItsOwnBlock() {
		// The gap. Every other mark is a name somebody wrote down in advance; none of
		// them can say "the thing that was just touched", and without that the plainest
		// rule there is cannot be written at all.
		assertTrue(Mark.known(Mark.IT));
		assertTrue(Mark.KNOWN.contains(Mark.IT));
		// And it is not a place: nothing goes looking for it in the world's book of
		// marked points, where it would find nothing and read as a typo.
		assertEquals(null, Mark.place(Mark.IT));
	}

	@Test
	@DisplayName("the branch reads the block under the cursor, not a place on the map")
	void theBranchAsksAboutIt() {
		Dialogue graph = toggling(Trigger.onUse(new Trigger.Cause.Used("костёр", ""), new Condition.Always(), "щёлкнули"));
		DialogueState at = new DialogueState("douse", Map.of(), Map.of(), Map.of(),
			java.util.Set.of(), graph.variableTypes());

		var lit = DialogueEngine.step(graph, at, new DialogueEngine.Input.Begin(),
			with(Mark.IT, "minecraft:campfire[lit=true]", ""));
		assertEquals(List.of(new Effect.PutBlock(Mark.IT, "minecraft:campfire[lit=false]")),
			lit.effects(), "alight, so it goes out");

		var dark = DialogueEngine.step(graph, at, new DialogueEngine.Input.Begin(),
			with(Mark.IT, "minecraft:campfire[lit=false]", ""));
		assertEquals(List.of(new Effect.PutBlock(Mark.IT, "minecraft:campfire[lit=true]")),
			dark.effects(), "out, so it lights");
	}

	@Test
	@DisplayName("what is in hand is a different question from what is in the bag")
	void holdingIsNotHaving() {
		// The whole of "light it with a flint and steel". Somebody carrying one in their
		// bag has not lit anything; somebody holding one has, and there was no way to
		// ask the second.
		Condition steel = new Condition.Holding("minecraft:flint_and_steel");
		DialogueState nowhere = DialogueState.start(
			Dialogue.builder("x").start("e").add(new Node.End("e")).build(), Map.of());

		assertTrue(steel.test(nowhere, with("", "", "minecraft:flint_and_steel")));
		assertFalse(steel.test(nowhere, with("", "", "minecraft:stone")));
		// And an empty hand is air, which is an item id like any other rather than a
		// field whose emptiness means a different question.
		assertTrue(new Condition.Holding("minecraft:air")
			.test(nowhere, with("", "", "minecraft:air")));
	}

	@Test
	@DisplayName("a rule is a rule and a scene is a scene, and it is written down")
	void mannerIsWrittenAndNotGuessed() {
		// The tempting shortcut is "no waiting nodes, therefore a rule". It is wrong: a
		// rule may say a line — "you burn your hand" — and still be a rule.
		Trigger rule = Trigger.onUse(new Trigger.Cause.Used("костёр", ""), new Condition.Always(), "щёлкнули");
		assertTrue(rule.isRule());
		assertTrue(rule.onUse());
		assertTrue(rule.instead(), "answering instead of the game is what one usually means");

		assertFalse(Trigger.of("дверь", "greeting").isRule(), "a doorway is still a scene");
		assertFalse(Trigger.onBlock("костёр", "minecraft:campfire", new Condition.Always(), "x").isRule(),
			"watching for a change is still a scene");
	}

	@Test
	@DisplayName("an old file still means what it meant, and a rule still writes itself out")
	void bothFormsSurviveTheFile() {
		// The old map with a bare name in it, which is what every file written before
		// any of this holds. It has to go on meaning a doorway that waits its turn.
		var old = DialogueCodecs.TRIGGERS
			.parse(JsonOps.INSTANCE,
				com.google.gson.JsonParser.parseString("{\"дверь\": \"greeting\"}"))
			.getOrThrow(IllegalStateException::new);
		assertEquals(1, old.size());
		assertEquals(new Trigger.Cause.Inside("дверь"), old.get(0).cause());
		assertEquals(Trigger.Manner.SCENE, old.get(0).manner());
		assertEquals("greeting", old.get(0).wayIn());

		// And it is written back as a map, because nothing more has been said about it.
		// A format that fattened every old trigger on the first save would show up as a
		// diff in somebody's map for a field they never touched.
		var back = DialogueCodecs.TRIGGERS
			.encodeStart(JsonOps.INSTANCE, old).getOrThrow(IllegalStateException::new);
		assertTrue(back.isJsonObject(), "an old trigger still writes itself out old: " + back);

		// A rule cannot be said in the map at all, so it takes the list — and survives it.
		List<Trigger> rules = List.of(
			Trigger.onUse(new Trigger.Cause.UsedAny("minecraft:campfire[lit=true]"),
				new Condition.Holding("minecraft:air"), "щёлкнули"));
		var written = DialogueCodecs.TRIGGERS
			.encodeStart(JsonOps.INSTANCE, rules).getOrThrow(IllegalStateException::new);
		assertTrue(written.isJsonArray(), "a rule needs the list: " + written);
		assertEquals(rules, DialogueCodecs.TRIGGERS.parse(JsonOps.INSTANCE, written)
			.getOrThrow(IllegalStateException::new));
	}

	@Test
	@DisplayName("a rule about a place counts as a place the document points at")
	void theWatchedPlaceIsCollected() {
		Dialogue graph = toggling(Trigger.onUse(new Trigger.Cause.Used("костёр", ""), new Condition.Always(), "щёлкнули"));
		assertTrue(graph.marksUsed().contains(Mark.at("костёр")), graph.marksUsed().toString());
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("a rule can be about a kind of block rather than about one spot")
	void aRuleCanBeAboutAKind() {
		// What the whole change from a map to a list was for. Filed under a place, a
		// rule is about one campfire; about a kind, it is about every campfire on the
		// map, including the ones built after the graph was written.
		Trigger any = Trigger.onUse(new Trigger.Cause.UsedAny("minecraft:campfire"),
			new Condition.Always(), "щёлкнули");
		assertEquals("", any.place(), "a kind names no place, and that is not a typo");
		assertEquals("minecraft:campfire", any.block());
		assertTrue(any.onUse());

		Dialogue graph = toggling(any);
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
		assertFalse(graph.marksUsed().contains(Mark.at("")),
			"and nothing goes looking for a place it never named");
	}

	@Test
	@DisplayName("a rule about any block whatever is refused")
	void aRuleAboutEverythingIsRefused() {
		// It would run a graph on every right click of every player. Not a rule anybody
		// means, and the one shape of this that is worth refusing outright rather than
		// leaving to be discovered on a server.
		Dialogue graph = toggling(Trigger.onUse(new Trigger.Cause.UsedAny(""),
			new Condition.Always(), "щёлкнули"));
		var report = DialogueValidator.validate(graph);
		assertFalse(report.ok());
		assertTrue(report.errors().stream()
			.anyMatch(problem -> problem.message().contains("every click in the world")),
			report.errors().toString());
	}

	@Test
	@DisplayName("one place can carry two rules, which a map could never have said")
	void onePlaceCanCarryTwo() {
		// "If it is alight, put it out" and "if it is out, light it" are two rules about
		// one fire, and that is how anybody would write them. Under a map keyed by the
		// place, the second would have replaced the first silently.
		Dialogue base = Dialogue.builder("fire")
			.start("end")
			.add(new Node.Act("out",
				new Effect.PutBlock(Mark.IT, "minecraft:campfire[lit=false]"), "end"))
			.add(new Node.Act("light",
				new Effect.PutBlock(Mark.IT, "minecraft:campfire[lit=true]"), "end"))
			.add(new Node.End("end"))
			.build();
		Dialogue graph = new Dialogue(base.id(), base.formatVersion(), base.start(),
			base.nodes(), base.variableTypes(), Map.of("гасить", "out", "зажечь", "light"),
			base.areas(), base.manner(), base.places(), List.of(
				Trigger.onUse(new Trigger.Cause.Used("костёр", "minecraft:campfire[lit=true]"),
					new Condition.Always(), "гасить"),
				Trigger.onUse(new Trigger.Cause.Used("костёр", "minecraft:campfire[lit=false]"),
					new Condition.Always(), "зажечь")));

		assertEquals(2, graph.triggers().size());
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
		// And the file carries both, which a map keyed by the place could not.
		var written = DialogueCodecs.DIALOGUE
			.encodeStart(JsonOps.INSTANCE, graph).getOrThrow(IllegalStateException::new);
		assertEquals(2, DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE, written)
			.getOrThrow(IllegalStateException::new).triggers().size());
	}

	@Test
	@DisplayName("naming the block the rule fired about is not mistaken for a bad mark")
	void itIsNotRefusedAsAMistake() {
		// The validator refuses a verb pointed at something nobody has heard of, and it
		// would have refused every rule ever written if this had been left out of the
		// vocabulary. Worth its own test because the failure would be a whole feature
		// that never loads.
		Dialogue graph = Dialogue.builder("fire")
			.start("out")
			.add(new Node.Act("out", new Effect.LookAt(Mark.IT), "end"))
			.add(new Node.End("end"))
			.build();
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
	}
}
