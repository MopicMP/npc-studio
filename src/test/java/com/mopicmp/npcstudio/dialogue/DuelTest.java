package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two characters with one graph, deciding what to do about each other.
 *
 * <h2>What these can and cannot show</h2>
 *
 * They drive the graph the way the server does and check what came out. Whether
 * the arrow then hits, whether the legs get there — those belong to the bow and
 * the pathfinder and are tested where they live.
 *
 * What is worth pinning down here is the branching, because it is the part
 * somebody will change: the distance at which shooting stops being sensible,
 * the distance at which hitting starts, and the fact that neither of them needs
 * a weapon for something to happen.
 */
class DuelTest {

	@BeforeEach
	void loadTheBuiltIns() {
		com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.registerBuiltIn();
	}

	private static Dialogue duel() {
		return com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get("duel").orElseThrow();
	}

	/** Somebody of her own kind, this far off, with this in her hand. */
	private static Condition.World facing(double away, String weapon) {
		Map<String, Value> readings = Map.of(
			Sense.KIN, new Value.Flag(true),
			Sense.KIN_DISTANCE, new Value.Num(away),
			Sense.WEAPON, new Value.Text(weapon));
		return new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
			@Override public Value sense(String name) {
				return readings.getOrDefault(name, new Value.Flag(false));
			}
		};
	}

	private static List<Effect> whatSheDoes(Condition.World world) {
		Dialogue graph = duel();
		return DialogueEngine.step(graph, DialogueState.start(graph, Map.of()),
			new DialogueEngine.Input.Begin(), world).effects();
	}

	@Test
	@DisplayName("alone, she does nothing at all")
	void nobodyThere() {
		Condition.World empty = new Condition.World() {
			@Override public boolean hasItem(String item, int count) { return false; }
		};
		assertEquals(List.of(), whatSheDoes(empty));
	}

	@Test
	@DisplayName("a bow and twenty blocks is a shot")
	void shootingFromAfar() {
		assertEquals(
			List.of(new Effect.LookAt(Mark.KIN), new Effect.Fire(Mark.KIN)),
			whatSheDoes(facing(20, "drawn")),
			"facing has to come first: the arrow leaves along the body");
	}

	@Test
	@DisplayName("a bow at arm's length is a club held by the wrong end")
	void tooCloseToShoot() {
		// She stops and hits instead. A character who keeps drawing a bow into
		// somebody's chest looks like one who has not noticed them.
		assertTrue(whatSheDoes(facing(2, "drawn")).contains(new Effect.Strike(Mark.KIN)));
	}

	@Test
	@DisplayName("she stops before she swings, rather than walking through them")
	void stoppingFirst() {
		List<Effect> did = whatSheDoes(facing(2, "melee"));
		assertEquals(new Effect.Halt(), did.get(0),
			"otherwise the two of them shuffle across the floor together: " + did);
		assertTrue(did.contains(new Effect.Strike(Mark.KIN)));
	}

	@Test
	@DisplayName("out of reach and no bow means closing the distance, at a run")
	void closingIn() {
		assertEquals(List.of(new Effect.WalkTo(Mark.KIN, 1f)), whatSheDoes(facing(12, "melee")));
	}

	@Test
	@DisplayName("empty hands still start a fight")
	void unarmed() {
		// Worth its own test because it is what makes this usable as a first check:
		// nothing has to be handed out for something to happen. A bare fist is a
		// real attack and she now has the attack damage to make one.
		assertEquals(List.of(new Effect.WalkTo(Mark.KIN, 1f)), whatSheDoes(facing(9, "nothing")));
		assertTrue(whatSheDoes(facing(1.5, "nothing")).contains(new Effect.Strike(Mark.KIN)));
	}

	@Test
	@DisplayName("a rifle from a datapack is not fired, and does not stop the fight either")
	void theWeaponWeCannotUseYet() {
		// It reads as `swung`, which wants the swing rather than the trigger, and
		// whether swinging at empty air sets one off has not been tested. So she
		// falls through to closing and hitting rather than standing still.
		assertEquals(List.of(new Effect.WalkTo(Mark.KIN, 1f)), whatSheDoes(facing(20, "swung")));
	}

	@Test
	@DisplayName("the fight is not settled in one tick")
	void thereIsAPauseBetweenBlows() {
		Dialogue graph = duel();
		var step = DialogueEngine.step(graph, DialogueState.start(graph, Map.of()),
			new DialogueEngine.Input.Begin(), facing(2, "melee"));
		var waiting = step.screen();
		assertTrue(waiting instanceof DialogueEngine.Screen.Waiting w && w.ticks() > 0,
			"she should be standing off for a moment, not looping: " + waiting);
	}
}
