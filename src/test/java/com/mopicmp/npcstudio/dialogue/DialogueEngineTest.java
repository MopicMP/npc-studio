package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The engine, driven with no Minecraft anywhere in sight.
 *
 * Every test here is a conversation played out from the outside: give it a
 * graph, press the buttons a player would press, and look at what came back.
 * If these pass, a wrong line on screen is the presentation layer's fault, not
 * the engine's — which is the whole reason the two are separate.
 */
class DialogueEngineTest {

	/** A world where the player owns nothing, unless a test says otherwise. */
	private static final Condition.World EMPTY_POCKETS = (item, count) -> false;

	private static Node.Line line(String id, String text, String next) {
		return new Node.Line(id, "", text, Presentation.SUBTITLE, null, next);
	}

	private static DialogueEngine.Step begin(Dialogue d) {
		return DialogueEngine.step(d, DialogueState.start(d, Map.of()),
			new DialogueEngine.Input.Begin(), EMPTY_POCKETS);
	}

	@Nested
	@DisplayName("Walking through a conversation")
	class Walking {

		@Test
		@DisplayName("a line is shown, and advancing moves to the next one")
		void linesFollowOneAnother() {
			Dialogue d = Dialogue.builder("greeting")
				.add(line("hello", "Good evening.", "weather"))
				.add(line("weather", "Cold, is it not?", "bye"))
				.add(new Node.End("bye"))
				.build();

			var first = begin(d);
			assertEquals(new DialogueEngine.Screen.Line("", "Good evening.", Presentation.SUBTITLE, null),
				first.screen());

			var second = DialogueEngine.step(d, first.state(), new DialogueEngine.Input.Advance(), EMPTY_POCKETS);
			assertInstanceOf(DialogueEngine.Screen.Line.class, second.screen());
			assertEquals("Cold, is it not?", ((DialogueEngine.Screen.Line) second.screen()).text());

			var third = DialogueEngine.step(d, second.state(), new DialogueEngine.Input.Advance(), EMPTY_POCKETS);
			assertInstanceOf(DialogueEngine.Screen.Finished.class, third.screen());
		}

		@Test
		@DisplayName("nodes that do not wait are run through before the next line")
		void silentNodesDoNotNeedAClick() {
			// The writer means "set the flag and say the line", not "set the flag,
			// wait for a click, then say the line".
			Dialogue d = Dialogue.builder("gift")
				.variable("met", "flag")
				.add(new Node.Set("remember", "met", Scope.PLAYER, Value.of(true), "give"))
				.add(new Node.Act("give", new Effect.GiveItem("minecraft:bread", 3), "thanks"))
				.add(line("thanks", "Take this.", "done"))
				.add(new Node.End("done"))
				.build();

			var step = begin(d);

			assertEquals("Take this.", ((DialogueEngine.Screen.Line) step.screen()).text());
			assertEquals(List.of(new Effect.GiveItem("minecraft:bread", 3)), step.effects());
			assertEquals(Value.of(true), step.state().get("met", Scope.PLAYER));
		}

		@Test
		@DisplayName("effects come out in the order they were reached")
		void effectsKeepTheirOrder() {
			Dialogue d = Dialogue.builder("ceremony")
				.add(new Node.Act("a", new Effect.PlaySound("bell", 1f, 1f), "b"))
				.add(new Node.Act("b", new Effect.PlaceStructure("gate", "here", 40), "c"))
				.add(new Node.Act("c", new Effect.RunCommand("say opened"), "d"))
				.add(line("d", "It is done.", "end"))
				.add(new Node.End("end"))
				.build();

			var step = begin(d);

			assertEquals(List.of(
				new Effect.PlaySound("bell", 1f, 1f),
				new Effect.PlaceStructure("gate", "here", 40),
				new Effect.RunCommand("say opened")), step.effects());
		}

		@Test
		@DisplayName("a line with an animation asks for it to be played")
		void animationBecomesAnEffect() {
			Dialogue d = Dialogue.builder("bow")
				.add(new Node.Line("greet", "Guard", "Welcome.", Presentation.SUBTITLE, "bow", "end"))
				.add(new Node.End("end"))
				.build();

			// No length. A line's animation lasts as long as the line is on screen,
			// and how long that is depends on how fast somebody reads.
			assertEquals(List.of(new Effect.PlayAnimation("bow", 0)), begin(d).effects());
		}
	}

	@Nested
	@DisplayName("Choices")
	class Choices {

		private Dialogue toll() {
			return Dialogue.builder("toll")
				.variable("paid", "flag")
				.add(new Node.Choice("ask", "The bridge is closed.", List.of(
					new Node.Option("Pay the toll.", "gold",
						new Condition.HasItem("minecraft:emerald", 5), "pay"),
					new Node.Option("Turn back.", "leave"))))
				.add(new Node.Set("pay", "paid", Scope.PLAYER, Value.of(true), "through"))
				.add(line("through", "Go on, then.", "end"))
				.add(line("leave", "Suit yourself.", "end"))
				.add(new Node.End("end"))
				.build();
		}

		@Test
		@DisplayName("an option the player cannot afford is not shown")
		void conditionsHideOptions() {
			var poor = DialogueEngine.step(toll(), DialogueState.start(toll(), Map.of()),
				new DialogueEngine.Input.Begin(), EMPTY_POCKETS);

			var shown = ((DialogueEngine.Screen.Choice) poor.screen()).options();
			assertEquals(1, shown.size());
			assertEquals("Turn back.", shown.get(0).label());
		}

		@Test
		@DisplayName("a shown option keeps its original number even when others are hidden")
		void indexesSurviveFiltering() {
			// If the engine renumbered what it shows, picking "Turn back" would take
			// the branch belonging to "Pay the toll" as soon as the first option was
			// hidden — a wrong branch that only appears for poor players.
			var poor = begin(toll());
			var shown = ((DialogueEngine.Screen.Choice) poor.screen()).options();

			assertEquals(1, shown.get(0).index());

			var after = DialogueEngine.step(toll(), poor.state(),
				new DialogueEngine.Input.Pick(shown.get(0).index()), EMPTY_POCKETS);
			assertEquals("Suit yourself.", ((DialogueEngine.Screen.Line) after.screen()).text());
		}

		@Test
		@DisplayName("an option that is hidden cannot be picked anyway")
		void hiddenOptionsAreRefused() {
			var poor = begin(toll());

			var fault = assertThrows(DialogueEngine.DialogueFault.class, () ->
				DialogueEngine.step(toll(), poor.state(), new DialogueEngine.Input.Pick(0), EMPTY_POCKETS));
			assertTrue(fault.getMessage().contains("not available"), fault.getMessage());
		}

		@Test
		@DisplayName("with the emeralds in hand, both options appear")
		void richPlayerSeesBoth() {
			Condition.World rich = (item, count) -> item.equals("minecraft:emerald") && count <= 64;
			Dialogue d = toll();

			var step = DialogueEngine.step(d, DialogueState.start(d, Map.of()),
				new DialogueEngine.Input.Begin(), rich);

			assertEquals(2, ((DialogueEngine.Screen.Choice) step.screen()).options().size());
		}
	}

	@Nested
	@DisplayName("The bookmark")
	class Bookmark {

		private Dialogue question() {
			return Dialogue.builder("question")
				.add(line("intro", "I have something to ask.", "ask"))
				.add(new Node.Choice("ask", "Will you help?", List.of(
					new Node.Option("Yes.", "yes"),
					new Node.Option("No.", "no"))))
				.add(line("yes", "Thank you.", "end"))
				.add(line("no", "A pity.", "end"))
				.add(new Node.End("end"))
				.build();
		}

		@Test
		@DisplayName("Esc and coming back resumes on the same node with the same options")
		void suspendingAndResumingChangesNothing() {
			// This is the behaviour the whole design rests on: leaving a choice is a
			// bookmark, not a cancel, and it must not restart the conversation.
			Dialogue d = question();

			var atChoice = DialogueEngine.step(d,
				DialogueEngine.step(d, DialogueState.start(d, Map.of()),
					new DialogueEngine.Input.Begin(), EMPTY_POCKETS).state(),
				new DialogueEngine.Input.Advance(), EMPTY_POCKETS);

			// The player presses Esc — nothing is called at all — and comes back later.
			var resumed = DialogueEngine.step(d, atChoice.state(),
				new DialogueEngine.Input.Begin(), EMPTY_POCKETS);

			assertEquals(atChoice.state().currentNode(), resumed.state().currentNode());
			assertEquals(atChoice.screen(), resumed.screen());
			assertNotEquals(d.start(), resumed.state().currentNode(),
				"coming back must not start the dialogue over");
		}

		@Test
		@DisplayName("a bookmark survives being saved and loaded")
		void stateIsPlainData() {
			Dialogue d = question();
			var atChoice = DialogueEngine.step(d,
				DialogueEngine.step(d, DialogueState.start(d, Map.of()),
					new DialogueEngine.Input.Begin(), EMPTY_POCKETS).state(),
				new DialogueEngine.Input.Advance(), EMPTY_POCKETS);

			// Rebuilt from its parts, the way the server will after a restart.
			var reloaded = new DialogueState(
				atChoice.state().currentNode(),
				atChoice.state().playerVars(),
				atChoice.state().worldVars(),
				atChoice.state().visited(),
				d.variableTypes());

			assertEquals(atChoice.screen(),
				DialogueEngine.step(d, reloaded, new DialogueEngine.Input.Begin(), EMPTY_POCKETS).screen());
		}

		@Test
		@DisplayName("visiting is remembered, so a second meeting can differ")
		void visitedIsRecorded() {
			Dialogue d = question();
			var step = begin(d);
			assertTrue(step.state().visited().contains("intro"));
		}
	}

	@Nested
	@DisplayName("Variables and conditions")
	class Variables {

		@Test
		@DisplayName("an unwritten variable reads as its type's default, not as an error")
		void defaultsAreQuiet() {
			// "Have we met before?" is asked before anything has ever been written,
			// and that has to be an ordinary false rather than a crash.
			Dialogue d = Dialogue.builder("memory")
				.variable("met", "flag")
				.variable("gold", "number")
				.add(new Node.Branch("check",
					List.of(new Node.Arm(
						new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ, Value.of(true)), "again")),
					"first"))
				.add(line("first", "I do not believe we have met.", "end"))
				.add(line("again", "You are back.", "end"))
				.add(new Node.End("end"))
				.build();

			assertEquals("I do not believe we have met.",
				((DialogueEngine.Screen.Line) begin(d).screen()).text());
		}

		@Test
		@DisplayName("world variables are shared, player variables are not")
		void scopesAreSeparate() {
			Dialogue d = Dialogue.builder("gate")
				.variable("open", "flag")
				.add(new Node.Set("openIt", "open", Scope.WORLD, Value.of(true), "say"))
				.add(line("say", "The gate is open.", "end"))
				.add(new Node.End("end"))
				.build();

			var step = begin(d);

			assertEquals(Value.of(true), step.state().get("open", Scope.WORLD));
			assertEquals(Value.of(false), step.state().get("open", Scope.PLAYER),
				"writing a world variable must not touch the player's copy");
		}

		@Test
		@DisplayName("comparing different types is false rather than a crash")
		void mismatchedTypesDoNotThrow() {
			var state = new DialogueState("n", Map.of("gold", Value.of(10)), Map.of(), Set.of(),
				Map.of("gold", "number"));

			var wrong = new Condition.Compare("gold", Scope.PLAYER, Condition.Op.GT, Value.of("many"));
			assertTrue(!wrong.test(state, EMPTY_POCKETS));

			// Inequality still has an honest answer: a number is not a piece of text.
			var notEqual = new Condition.Compare("gold", Scope.PLAYER, Condition.Op.NE, Value.of("many"));
			assertTrue(notEqual.test(state, EMPTY_POCKETS));
		}

		@Test
		@DisplayName("and, or and not combine as expected")
		void logicWorks() {
			var state = new DialogueState("n", Map.of("gold", Value.of(10)), Map.of(), Set.of("shop"),
				Map.of("gold", "number"));

			var rich = new Condition.Compare("gold", Scope.PLAYER, Condition.Op.GE, Value.of(5));
			var beenToShop = new Condition.Visited("shop");
			var beenToInn = new Condition.Visited("inn");

			assertTrue(new Condition.All(List.of(rich, beenToShop)).test(state, EMPTY_POCKETS));
			assertTrue(!new Condition.All(List.of(rich, beenToInn)).test(state, EMPTY_POCKETS));
			assertTrue(new Condition.Any(List.of(beenToInn, rich)).test(state, EMPTY_POCKETS));
			assertTrue(new Condition.Not(beenToInn).test(state, EMPTY_POCKETS));
			assertTrue(new Condition.All(List.of()).test(state, EMPTY_POCKETS), "empty 'all' is true");
			assertTrue(!new Condition.Any(List.of()).test(state, EMPTY_POCKETS), "empty 'any' is false");
		}
	}

	@Nested
	@DisplayName("Refusing to hang")
	class Hanging {

		@Test
		@DisplayName("a loop with no waiting node is stopped instead of freezing the game")
		void runawayLoopIsCaught() {
			Dialogue d = Dialogue.builder("spin")
				.variable("n", "number")
				.add(new Node.Set("a", "n", Scope.PLAYER, Value.of(1), "b"))
				.add(new Node.Set("b", "n", Scope.PLAYER, Value.of(2), "a"))
				.build();

			var fault = assertThrows(DialogueEngine.DialogueFault.class, () -> begin(d));
			assertTrue(fault.getMessage().contains("looping"), fault.getMessage());
		}

		@Test
		@DisplayName("a missing node is named, not swallowed")
		void missingNodeIsReported() {
			Dialogue d = Dialogue.builder("broken")
				.add(line("start", "Follow me.", "nowhere"))
				.build();

			var first = begin(d);
			var fault = assertThrows(DialogueEngine.DialogueFault.class, () ->
				DialogueEngine.step(d, first.state(), new DialogueEngine.Input.Advance(), EMPTY_POCKETS));
			assertTrue(fault.getMessage().contains("nowhere"), fault.getMessage());
		}

		@Test
		@DisplayName("a choice where nothing is available is refused, not shown empty")
		void emptyChoiceIsRefused() {
			Dialogue d = Dialogue.builder("locked")
				.add(new Node.Choice("ask", "Well?", List.of(
					new Node.Option("Only with a key.", null,
						new Condition.HasItem("minecraft:tripwire_hook", 1), "in"))))
				.add(line("in", "Come in.", "end"))
				.add(new Node.End("end"))
				.build();

			assertThrows(DialogueEngine.DialogueFault.class, () -> begin(d));
		}

		@Test
		@DisplayName("a long chain of silent nodes is allowed through")
		void honestChainsAreNotMistakenForLoops() {
			// The guard must catch runaway loops without tripping on a dialogue that
			// legitimately sets a lot of flags in a row.
			var b = Dialogue.builder("many").variable("n", "number");
			int chain = 100;
			for (int i = 0; i < chain; i++) {
				b.add(new Node.Set("s" + i, "n", Scope.PLAYER, Value.of(i), i == chain - 1 ? "done" : "s" + (i + 1)));
			}
			b.add(line("done", "Finally.", "end")).add(new Node.End("end"));

			assertEquals("Finally.", ((DialogueEngine.Screen.Line) begin(b.build()).screen()).text());
		}
	}
}
