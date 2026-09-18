package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which marks a graph points anything at.
 *
 * <h2>What the answer is used for</h2>
 *
 * Telling an author, at the moment they take a named place away, which of their
 * graphs were walking to it. Removing a place is allowed — a doorway moves, a
 * scene is rewritten — and it cannot be made an error afterwards, because a mark
 * naming nothing has to be survivable: a lead that is not there behaves the same
 * way and every graph already copes.
 *
 * Which means the failure it causes is a character who simply does not walk. That
 * is the single hardest symptom in this mod to attribute, and the only person who
 * can connect it back to a deleted place is the one who deleted it, in the moment
 * they did.
 *
 * <h2>Why every kind of pointing has to be counted</h2>
 *
 * Missing one makes the warning worse than none: it would name two graphs out of
 * three and let somebody stop looking. The call is the one usually forgotten,
 * because it does not look like aiming — it names the thing a skill is to act on,
 * and that name is a mark like any other.
 */
class MarksUsedTest {

	private static Dialogue graph(Node... nodes) {
		Dialogue.Builder building = Dialogue.builder("test").start(nodes[0].id());
		for (Node node : nodes) building.add(node);
		return building.build();
	}

	@Test
	@DisplayName("all four verbs that aim are counted")
	void everyAimingVerb() {
		Dialogue graph = graph(
			new Node.Act("a", new Effect.WalkTo(Mark.at("gate"), 0.5f), "b"),
			new Node.Act("b", new Effect.LookAt(Mark.at("window")), "c"),
			new Node.Act("c", new Effect.Fire(Mark.PLAYER), "d"),
			new Node.Act("d", new Effect.Strike(Mark.KIN), "e"),
			new Node.End("e"));

		assertEquals(Set.of(Mark.at("gate"), Mark.at("window"), Mark.PLAYER, Mark.KIN),
			graph.marksUsed());
	}

	@Test
	@DisplayName("a call names a mark too, and it is the one people forget")
	void aCallCounts() {
		// It does not read as aiming — it says which skill to run — but the name beside
		// it is what the skill will act on, and it is a mark like any other.
		Dialogue graph = graph(
			new Node.Do("a", "fighting:fight", Mark.at("gate"), "b"),
			new Node.End("b"));
		assertTrue(graph.marksUsed().contains(Mark.at("gate")));
	}

	@Test
	@DisplayName("verbs that do not aim contribute nothing")
	void otherVerbsAreQuiet() {
		// Not merely uninteresting: an effect wrongly counted would name a graph in a
		// warning it has nothing to do with, and a warning that cries wolf is one
		// nobody reads the third time.
		Dialogue graph = graph(
			new Node.Act("a", new Effect.Express("cross", 20), "b"),
			new Node.Act("b", new Effect.PlaySound("thud", 1f, 1f), "c"),
			new Node.Act("c", new Effect.Halt(), "d"),
			new Node.Act("d", new Effect.GiveItem("bread", 2), "e"),
			new Node.End("e"));
		assertTrue(graph.marksUsed().isEmpty(), "found " + graph.marksUsed());
	}

	@Test
	@DisplayName("a place is told from a place of another name")
	void placesAreDistinct() {
		Dialogue graph = graph(
			new Node.Act("a", new Effect.WalkTo(Mark.at("gate"), 0.5f), "b"),
			new Node.End("b"));
		assertTrue(graph.marksUsed().contains(Mark.at("gate")));
		assertFalse(graph.marksUsed().contains(Mark.at("gates")),
			"a longer name must not be read as containing a shorter one");
		assertFalse(graph.marksUsed().contains("gate"),
			"the bare word is not the mark — the form is the whole point");
	}

	@Test
	@DisplayName("a graph that points at nothing says so")
	void nothingPointedAt() {
		assertTrue(graph(new Node.End("a")).marksUsed().isEmpty());
	}

	@Test
	@DisplayName("the places among a set of marks are picked out and the rest left")
	void placesArePickedOut() {
		// Read from both ends of the same problem: taking a place away asks which
		// graphs pointed here, saving a graph asks which of its places the world has
		// not got. The second is what catches a typo, and a typo in a place name is the
		// commonest way to end up with a character who simply does not walk.
		assertEquals(java.util.List.of("gate", "ворота"),
			Mark.placesIn(java.util.List.of(
				Mark.PLAYER, Mark.at("gate"), Mark.KIN, Mark.at("ворота"), Mark.NOTHING)));

		// Order is kept because a person reads it: the same question answered twice in
		// two orders reads as two different answers.
		assertEquals(java.util.List.of("b", "a"),
			Mark.placesIn(java.util.List.of(Mark.at("b"), Mark.at("a"))));

		// And named twice is named once, for the same reason.
		assertEquals(java.util.List.of("gate"),
			Mark.placesIn(java.util.List.of(Mark.at("gate"), Mark.at("gate"))));

		assertTrue(Mark.placesIn(Mark.KNOWN).isEmpty(),
			"not one of the relative marks is a place");
		assertTrue(Mark.placesIn(java.util.List.of("at:")).isEmpty(),
			"the prefix with nothing after it names nothing");
	}

	@Test
	@DisplayName("the same mark twice is one answer")
	void namedTwiceCountsOnce() {
		Dialogue graph = graph(
			new Node.Act("a", new Effect.WalkTo(Mark.at("gate"), 0.5f), "b"),
			new Node.Act("b", new Effect.LookAt(Mark.at("gate")), "c"),
			new Node.End("c"));
		assertEquals(1, graph.marksUsed().size(), "found " + graph.marksUsed());
	}
}
