package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeping the player still, and getting them going again.
 *
 * <h2>Why this one is worth insisting on</h2>
 *
 * Because it is the only thing in this mod that can take the game away from
 * somebody. Everything else that goes wrong here shows up as a character behaving
 * oddly; this shows up as a person who cannot move, and a person who cannot move
 * cannot go and read a log about why.
 *
 * The parts that can be tested without a game are tested here: that it is a thing
 * the graph says, that it comes out of the engine in the order it was written, and
 * that it survives being saved. The three ways out live on the client and are named
 * in the last test so that removing one is a decision somebody makes rather than a
 * line somebody deletes.
 */
class HoldTest {

	private static DialogueState fresh(Dialogue graph) {
		return new DialogueState(graph.start(), new java.util.HashMap<>(),
			new java.util.HashMap<>(), new java.util.HashMap<>(),
			new java.util.HashSet<>(), graph.variableTypes());
	}

	/** A world that answers nothing, because none of these ask it anything. */
	private static final Condition.World NOTHING = new Condition.World() {
		@Override public boolean hasItem(String item, int count) { return false; }

		@Override public Value sense(String name) { return Value.of(false); }
	};

	@Test
	@DisplayName("taking the keys and handing them back are one verb with a flag")
	void oneVerbTwoWays() {
		// The same bargain the wall struck, for the same reason: two records naming the
		// same thing are two places for the name to be wrong in, and on the canvas the
		// two boxes that hold and release read as one decision said twice.
		assertTrue(new Effect.Hold(true).up());
		assertFalse(new Effect.Hold(false).up());
	}

	@Test
	@DisplayName("a scene that holds and releases hands both orders out in the order written")
	void bothComeOutInOrder() {
		// The shape the report asked for: speak, hold, walk off, release. Before this
		// verb existed the middle two could not be said at all — holding was a property
		// of the whole document, so a character who spoke and then left held the player
		// for as long as her line stayed on the screen.
		Dialogue graph = Dialogue.builder("scene")
			.start("hold")
			.add(new Node.Act("hold", new Effect.Hold(true), "say"))
			.add(new Node.Line("say", "", "Wait here.", Presentation.SUBTITLE, null, "free"))
			.add(new Node.Act("free", new Effect.Hold(false), "end"))
			.add(new Node.End("end"))
			.build();

		DialogueState state = fresh(graph);
		var first = DialogueEngine.step(graph, state, new DialogueEngine.Input.Begin(), NOTHING);
		assertEquals(List.of(new Effect.Hold(true)), first.effects(),
			"the hold goes out before the line it is holding for");

		var then = DialogueEngine.step(graph, first.state(),
			new DialogueEngine.Input.Advance(), NOTHING);
		assertEquals(List.of(new Effect.Hold(false)), then.effects(),
			"and the release goes out after it, on its own");
	}

	@Test
	@DisplayName("a hold survives being written down and read back")
	void throughAFile() {
		// It has to, or a scene that holds the player is a scene that holds them only
		// until the world is reloaded — and the failure would be the good way round
		// only by luck. The other way round is somebody permanently stuck.
		for (boolean up : new boolean[] { true, false }) {
			Effect was = new Effect.Hold(up);
			var written = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.EFFECT
				.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, was)
				.getOrThrow(message -> new AssertionError(message));
			Effect back = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.EFFECT
				.parse(com.mojang.serialization.JsonOps.INSTANCE, written)
				.getOrThrow(message -> new AssertionError(message));
			assertEquals(was, back);
		}
	}

	@Test
	@DisplayName("the engine never holds anybody by itself")
	void onlyWhenAsked() {
		// Nothing implicit. A graph that says nothing about it produces no order about
		// it, so the only way anybody is ever held is that somebody wrote it down —
		// which is what makes the verb searchable when a scene goes wrong.
		Dialogue graph = Dialogue.builder("plain")
			.start("say")
			.add(new Node.Line("say", "", "Hello.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
		var step = DialogueEngine.step(graph, fresh(graph),
			new DialogueEngine.Input.Begin(), NOTHING);
		assertTrue(step.effects().stream().noneMatch(effect -> effect instanceof Effect.Hold),
			"a line is not an instruction to stand still");
	}
}
