package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A graph that hangs a menu in the air and waits to be told which part was pressed.
 *
 * <h2>What these are really checking</h2>
 *
 * That a press is an input like any other and not a second way of running a graph. The
 * menu is one document: it shows, it waits, it branches, it goes on — and everything about
 * that happens inside the engine, with no world and no player in the room.
 *
 * The one that matters most is the press nobody here is waiting for. A press is delivered
 * to every graph the presser has running, so "this is not for me" is the ordinary case
 * rather than the odd one, and a graph that moved on it would be a scene interrupted by
 * somebody pressing a button in the next room.
 */
class PressedTest {

	/** Nothing is true of the world; none of these ask it anything. */
	private static final Condition.World NOTHING = (_, _) -> false;

	private static DialogueEngine.Step press(Dialogue graph, DialogueState state, String what) {
		return DialogueEngine.step(graph, state, new DialogueEngine.Input.Pressed(what), NOTHING);
	}

	private static DialogueEngine.Step resume(Dialogue graph, DialogueState state) {
		return DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), NOTHING);
	}

	/** A menu of two labels and a wait that answers for both. */
	private static Dialogue menu() {
		return Dialogue.builder("menu")
			.start("hang_play")
			.add(new Node.Act("hang_play", new Effect.Show("menu.play",
				Shown.of("Play"), new Route.Point.Go(2, 1, 0)), "hang_quit"))
			.add(new Node.Act("hang_quit", new Effect.Show("menu.quit",
				Shown.of("Quit"), new Route.Point.Go(2, 0, 0)), "waiting"))
			.add(new Node.Pressed("waiting", List.of(
				new Node.Press("menu.play", "played"),
				new Node.Press("menu.quit", "quit"))))
			.add(new Node.Act("played", new Effect.Unshow("menu.play"), "done"))
			.add(new Node.Act("quit", new Effect.Unshow("menu.quit"), "done"))
			.add(new Node.End("done"))
			.build();
	}

	@Test
	@DisplayName("the menu goes up in one run and then the graph stands still")
	void itShowsThenWaits() {
		Dialogue graph = menu();
		DialogueEngine.Step step = resume(graph, DialogueState.start(graph, Map.of()));

		assertEquals(2, step.effects().size(), "both labels went up in one step");
		assertInstanceOf(Effect.Show.class, step.effects().get(0));
		assertEquals("waiting", step.state().currentNode());
		DialogueEngine.Screen.Waiting waiting =
			assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen());
		// Nought, not a rest: the graph is not waiting on a clock. What wakes it is the
		// press, and the beat is only what keeps the thread alive to receive one.
		assertEquals(0, waiting.ticks());
	}

	@Test
	@DisplayName("pressing a label takes the way out that names it")
	void aPressTakesItsOwnWay() {
		Dialogue graph = menu();
		DialogueState waiting = resume(graph, DialogueState.start(graph, Map.of())).state();

		DialogueEngine.Step step = press(graph, waiting, "menu.quit");
		assertEquals(List.of(new Effect.Unshow("menu.quit")), step.effects());
		assertInstanceOf(DialogueEngine.Screen.Finished.class, step.screen());
	}

	@Test
	@DisplayName("a press this wait does not name leaves it exactly where it was")
	void someoneElsesPress() {
		Dialogue graph = menu();
		DialogueState waiting = resume(graph, DialogueState.start(graph, Map.of())).state();

		DialogueEngine.Step step = press(graph, waiting, "shop.open");
		assertEquals("waiting", step.state().currentNode(), "it did not move");
		assertTrue(step.effects().isEmpty(), "and it did nothing");
		assertInstanceOf(DialogueEngine.Screen.Waiting.class, step.screen());
	}

	@Test
	@DisplayName("a press arriving at a graph that is not waiting at all changes nothing")
	void aPressAtSomethingElse() {
		// The same case one step earlier: the press lands on a graph in the middle of a
		// line. It has to read as "carry on", because every graph the presser has running
		// is asked and most of them are busy.
		Dialogue graph = Dialogue.builder("talking")
			.start("hello")
			.add(new Node.Line("hello", "", "Good day", Presentation.SUBTITLE, null, "done"))
			.add(new Node.End("done"))
			.build();

		DialogueEngine.Step step = press(graph, DialogueState.start(graph, Map.of()), "menu.play");
		assertEquals("hello", step.state().currentNode());
		assertInstanceOf(DialogueEngine.Screen.Line.class, step.screen());
	}

	@Test
	@DisplayName("coming back without a press shows the wait again rather than moving on")
	void beginningDoesNotPress() {
		Dialogue graph = menu();
		DialogueState waiting = resume(graph, DialogueState.start(graph, Map.of())).state();

		DialogueEngine.Step step = resume(graph, waiting);
		assertEquals("waiting", step.state().currentNode());
		assertTrue(step.effects().isEmpty(),
			"the labels are not hung a second time by looking at the wait");
	}

	@Test
	@DisplayName("the ways out are what the canvas draws wires from, in the order written")
	void itsExitsAreItsRows() {
		Node.Pressed waiting = new Node.Pressed("waiting", List.of(
			new Node.Press("menu.play", "played"),
			new Node.Press("menu.quit", "quit")));
		assertEquals(List.of("played", "quit"), waiting.exits());
		assertTrue(waiting.waits());
		assertEquals("quit", waiting.next("menu.quit"));
		assertEquals(null, waiting.next("menu.nothing"));
	}

	// ------------------------------------------------------------------ what is refused

	private static List<String> complaints(Dialogue graph, DialogueValidator.Severity how) {
		return DialogueValidator.validate(graph).problems().stream()
			.filter(problem -> problem.severity() == how)
			.map(DialogueValidator.Problem::message)
			.toList();
	}

	@Test
	@DisplayName("a wait with nothing to wait for is refused")
	void aWaitForNothing() {
		Dialogue graph = Dialogue.builder("stuck")
			.start("waiting")
			.add(new Node.Pressed("waiting", List.of()))
			.build();
		assertTrue(complaints(graph, DialogueValidator.Severity.ERROR).stream()
				.anyMatch(said -> said.contains("names nothing to be pressed")),
			"a graph that can never move on says so at the door");
	}

	@Test
	@DisplayName("waiting twice for the same thing is refused, because the second never happens")
	void twoWaysForOnePress() {
		Dialogue graph = Dialogue.builder("twice")
			.start("hang")
			.add(new Node.Act("hang", new Effect.Show("menu.play",
				Shown.of("Play"), new Route.Point.Go(2, 1, 0)), "waiting"))
			.add(new Node.Pressed("waiting", List.of(
				new Node.Press("menu.play", "one"),
				new Node.Press("menu.play", "two"))))
			.add(new Node.End("one"))
			.add(new Node.End("two"))
			.build();
		assertTrue(complaints(graph, DialogueValidator.Severity.ERROR).stream()
				.anyMatch(said -> said.contains("can never be taken")));
	}

	@Test
	@DisplayName("showing something with no name is refused, because nothing could reach it")
	void aThingWithNoName() {
		Dialogue graph = Dialogue.builder("nameless")
			.start("hang")
			.add(new Node.Act("hang", new Effect.Show("",
				Shown.of("Play"), new Route.Point.Go(2, 1, 0)), "done"))
			.add(new Node.End("done"))
			.build();
		assertTrue(complaints(graph, DialogueValidator.Severity.ERROR).stream()
				.anyMatch(said -> said.contains("no name")));
	}

	@Test
	@DisplayName("a block shown without saying which block is refused; empty words are not")
	void emptyIsOnlyAllowedForWords() {
		Dialogue blocks = Dialogue.builder("blank_block")
			.start("hang")
			.add(new Node.Act("hang", new Effect.Show("wall",
				new Shown(Shown.Kind.BLOCK,
					com.mopicmp.npcstudio.dialogue.text.Words.EMPTY, 1.0f, false),
				new Route.Point.Go(2, 1, 0)), "done"))
			.add(new Node.End("done"))
			.build();
		assertTrue(complaints(blocks, DialogueValidator.Severity.ERROR).stream()
			.anyMatch(said -> said.contains("without saying which one")));

		Dialogue words = Dialogue.builder("blank_words")
			.start("hang")
			.add(new Node.Act("hang", new Effect.Show("label",
				Shown.of(""), new Route.Point.Go(2, 1, 0)), "done"))
			.add(new Node.End("done"))
			.build();
		assertTrue(complaints(words, DialogueValidator.Severity.ERROR).isEmpty(),
			"an empty label filled in by a later show is an ordinary way to write a menu");
	}

	@Test
	@DisplayName("waiting for something this document never shows is warned about, not refused")
	void waitingForSomethingElsesLabel() {
		Dialogue graph = Dialogue.builder("borrowed")
			.start("waiting")
			.add(new Node.Pressed("waiting", List.of(new Node.Press("room.door", "done"))))
			.add(new Node.End("done"))
			.build();
		assertTrue(complaints(graph, DialogueValidator.Severity.ERROR).isEmpty(),
			"the room may well have hung it, so this is not a refusal");
		assertTrue(complaints(graph, DialogueValidator.Severity.WARNING).stream()
				.anyMatch(said -> said.contains("room.door")));
	}
}
