package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.foe.Draw.Order;

/**
 * Drawing a weapon and letting it go.
 *
 * <h2>What is being tested and what is not</h2>
 *
 * Not the shot. The shot belongs to the item and is the item's business — that
 * is the whole design. What is here is the timing that decides when to hand the
 * item its moment, which is arithmetic and therefore worth pinning down without
 * a running game.
 */
class DrawTest {

	/**
	 * A pair of hands: the machine, and the count the entity would be keeping.
	 *
	 * Stateful across calls on purpose. The first version of this was a static
	 * helper that started the count afresh every time, so a test which drew, then
	 * withdrew the order, was really testing a character who had nothing in her
	 * hands — and it passed while saying nothing at all.
	 */
	private static final class Hands {
		final Draw draw = new Draw();
		int drawnFor = -1;

		java.util.List<Order> run(int ticks) {
			java.util.List<Order> happened = new java.util.ArrayList<>();
			for (int i = 0; i < ticks; i++) {
				// The entity's count moves first, exactly as it does in
				// LivingEntity.tick, which runs the item before anything of ours
				// gets a turn.
				if (drawnFor >= 0) drawnFor++;
				Order order = draw.tick(drawnFor);
				switch (order) {
					case START -> drawnFor = 0;
					case LOOSE, LET_GO -> drawnFor = -1;
					case NOTHING -> { }
				}
				happened.add(order);
			}
			return happened;
		}
	}

	@Test
	@DisplayName("nothing happens until somebody orders it")
	void idle() {
		assertTrue(new Hands().run(40).stream().allMatch(order -> order == Order.NOTHING));
	}

	@Test
	@DisplayName("an order starts a draw and looses it at the length asked for")
	void oneShot() {
		Hands hands = new Hands();
		hands.draw.pull(15);
		var happened = hands.run(30);

		assertEquals(Order.START, happened.get(0));
		int loosed = happened.indexOf(Order.LOOSE);
		// Started on the first tick, and the fifteenth tick of holding is the one it
		// goes on — so the string was under tension for exactly as long as asked.
		assertEquals(15, loosed, "loosed on tick " + loosed);
		assertEquals(1, happened.stream().filter(o -> o == Order.LOOSE).count(),
			"one order is one shot");
	}

	@Test
	@DisplayName("a fumble is not a shot")
	void tooShortToBeWorthIt() {
		// Somebody — a graph, eventually — asks for a shot drawn for one tick. That
		// is a bowstring plucked, and vanilla would throw the arrow at her own feet.
		Draw draw = new Draw();
		draw.pull(1);
		assertTrue(draw.wanted() >= Draw.LEAST, "drew for " + draw.wanted());
	}

	@Test
	@DisplayName("two shots do not leave the bow in the same tick")
	void theCooldownIsReal() {
		Hands hands = new Hands();
		hands.draw.pull(Draw.LEAST);
		hands.run(Draw.LEAST + 2);
		assertTrue(hands.draw.cooling() > 0);

		hands.draw.pull(Draw.LEAST);
		var happened = hands.run(3);
		assertEquals(Order.NOTHING, happened.get(0), "she is still recovering");
	}

	@Test
	@DisplayName("an order given during the cooldown is honoured, not thrown away")
	void theOrderWaits() {
		// The alternative is refusing it, which from outside is a character who
		// ignores being told to fire — and that is indistinguishable from a bug.
		Hands hands = new Hands();
		hands.draw.pull(Draw.LEAST);
		hands.run(Draw.LEAST + 2);

		hands.draw.pull(Draw.LEAST);
		var happened = hands.run(Draw.BETWEEN + Draw.LEAST + 4);
		assertTrue(happened.contains(Order.START), "it never started");
		assertTrue(happened.contains(Order.LOOSE), "it never fired");
	}

	@Test
	@DisplayName("withdrawing the order lowers the bow instead of loosing it")
	void easingOff() {
		Hands hands = new Hands();
		hands.draw.pull(40);
		hands.run(10);
		hands.draw.ease();

		var happened = hands.run(5);
		assertEquals(Order.LET_GO, happened.get(0));
		assertTrue(!happened.contains(Order.LOOSE), "nobody shot at nothing");
	}

	@Test
	@DisplayName("a weapon taken out of her hands mid-draw does not leave her stuck")
	void theWorldInterferes() {
		Draw draw = new Draw();
		draw.pull(15);
		draw.tick(-1);        // ordered, not started
		draw.tick(5);         // drawing
		// And now the item is gone: the entity is no longer using anything.
		assertEquals(Order.START, draw.tick(-1), "she starts again rather than waiting for ever");
	}

	// ------------------------------------------------------------------ the power

	@Test
	@DisplayName("the draw curve agrees with the one in the game")
	void power() {
		// Spot values from BowItem.getPowerForTime, which this mirrors so that
		// deciding how long to hold can be worked out without a running game.
		assertEquals(0f, Draw.powerOf(0), 1e-6);
		assertEquals(1f, Draw.powerOf(Draw.FULLY), 1e-6);
		assertEquals(1f, Draw.powerOf(Draw.FULLY * 3), 1e-6, "it does not keep growing");
		assertTrue(Draw.powerOf(10) < 0.5f, "half the time is well under half the shot");
	}

	@Test
	@DisplayName("holding for a wanted power is the shortest hold that reaches it")
	void howLongToHold() {
		int held = Draw.longEnoughFor(0.8f);
		assertTrue(Draw.powerOf(held) >= 0.8f, "held " + held + " for " + Draw.powerOf(held));
		assertTrue(Draw.powerOf(held - 1) < 0.8f, "and not a tick longer than needed");
		assertTrue(held < Draw.FULLY, "eight tenths does not cost the whole draw");
	}

	@Test
	@DisplayName("but a strong shot costs very nearly the whole draw")
	void theCurveIsSteepAtTheEnd() {
		// Worth pinning down because it is not obvious and it decides the default:
		// the last tenth of the power is in the last tick or two of the pull, so
		// there is no cheap strong shot to be had by letting go early.
		assertEquals(Draw.FULLY, Draw.longEnoughFor(0.95f));
		assertTrue(Draw.powerOf(Draw.FULLY - 3) < 0.9f,
			"three ticks short is " + Draw.powerOf(Draw.FULLY - 3));
	}

	@Test
	@DisplayName("a shot that cannot be had is not held for ever")
	void beyondFull() {
		assertEquals(Draw.FULLY, Draw.longEnoughFor(2f));
	}
}
