package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Not believing a reading until it has said the same thing for a while.
 *
 * <h2>What went wrong without this</h2>
 *
 * A character's animation was picked from her speed every tick. A step down a slab is a
 * tick or two of falling and a nudge is two ticks of walking, so a character standing
 * about was constantly, briefly, reported as jumping or walking — and a change of
 * animation starts it again from its first frame.
 *
 * Reported as an animation that "starts over" and "sometimes does not show at all".
 */
class SettledTest {

	private static Settled<String> after(int ticks) {
		return new Settled<>("idle", ticks);
	}

	@Test
	@DisplayName("a reading has to say the same thing four times before it is believed")
	void oneTickIsNotAChange() {
		Settled<String> settled = after(4);
		assertEquals("idle", settled.saw("jump"));
		assertEquals("idle", settled.saw("jump"));
		assertEquals("idle", settled.saw("jump"));
		assertEquals("jump", settled.saw("jump"), "and on the fourth it is");
	}

	@Test
	@DisplayName("a blip of one or two ticks changes nothing at all")
	void theStepDownASlab() {
		// The reported case, in the numbers it actually happens in: a path of slabs is
		// half a block down at every other step, which is one or two ticks of falling.
		Settled<String> settled = after(4);
		settled.saw("jump");
		settled.saw("jump");
		assertEquals("idle", settled.saw("idle"));
		// And the count is gone rather than banked. Two more later must not finish what
		// those two started.
		settled.saw("jump");
		settled.saw("jump");
		assertEquals("idle", settled.believed());
	}

	@Test
	@DisplayName("alternating every other tick never settles on the wrong thing")
	void flickeringIsNotEvidence() {
		// The flicker arriving by a slower road: without clearing the count on agreement,
		// four walking readings spread over eight ticks would add up and switch.
		Settled<String> settled = after(4);
		for (int i = 0; i < 20; i++) {
			settled.saw(i % 2 == 0 ? "walk" : "idle");
		}
		assertEquals("idle", settled.believed());
	}

	@Test
	@DisplayName("a reading that changes its mind starts its own count over")
	void athirdAnswerResetsTheCount() {
		Settled<String> settled = after(4);
		settled.saw("walk");
		settled.saw("walk");
		settled.saw("walk");
		// Three walks and then a run: the run has to earn its own four, not inherit them.
		assertEquals("idle", settled.saw("run"));
		assertEquals("idle", settled.saw("run"));
		assertEquals("idle", settled.saw("run"));
		assertEquals("run", settled.saw("run"));
	}

	@Test
	@DisplayName("a real change gets through, and quickly")
	void walkingStillStartsWalking() {
		// The cost of the whole thing, stated: a fifth of a second before a character
		// who starts walking looks like she is walking. Worth naming, because if this is
		// ever too long it is this number that is wrong.
		Settled<String> settled = after(4);
		for (int i = 0; i < 4; i++) settled.saw("walk");
		assertEquals("walk", settled.believed());
	}

	@Test
	@DisplayName("waiting for one reading is the honest way to switch the waiting off")
	void oneMeansNoWaiting() {
		Settled<String> settled = after(1);
		assertEquals("jump", settled.saw("jump"));

		// And something known rather than read is believed at once — being placed, being
		// teleported — because waiting there is waiting for evidence already given.
		settled.settleOn("idle");
		assertEquals("idle", settled.believed());
	}
}
