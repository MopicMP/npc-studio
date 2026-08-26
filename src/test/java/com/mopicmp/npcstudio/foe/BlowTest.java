package com.mopicmp.npcstudio.foe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.foe.Blow.Phase;
import com.mopicmp.npcstudio.foe.Blow.Shape;

/**
 * A swing as an interval, which is the whole of what was missing.
 *
 * <h2>What is worth pinning down</h2>
 *
 * The order and the lengths, because they are what a fight is made of and they
 * are invisible from outside — a blow that lands on the wrong tick still looks
 * like a blow, right up until somebody notices they are being hit before the
 * blade moves.
 *
 * Nothing here touches the world. Whether the blade reaches anybody is a
 * question about the world; when to ask it is arithmetic.
 */
class BlowTest {

	private static final Shape SWING = new Shape(8, 4, 12);

	/** Runs a blow to its end and hands back what each tick was. */
	private static List<Phase> through(Blow blow, int ticks) {
		List<Phase> seen = new ArrayList<>();
		for (int i = 0; i < ticks; i++) seen.add(blow.tick());
		return seen;
	}

	@Test
	@DisplayName("a swing winds up, is briefly dangerous, and takes time to come back from")
	void theThreeParts() {
		Blow blow = new Blow();
		assertTrue(blow.begin(SWING));

		List<Phase> seen = through(blow, SWING.length());
		assertEquals(8, seen.stream().filter(p -> p == Phase.WIND_UP).count());
		assertEquals(4, seen.stream().filter(p -> p == Phase.CONTACT).count());
		assertEquals(12, seen.stream().filter(p -> p == Phase.RECOVERY).count());

		// And in that order, which matters more than the counts: a blade that is
		// dangerous before it moves is a character who hits you during her wind-up.
		assertEquals(Phase.WIND_UP, seen.get(0));
		assertEquals(Phase.CONTACT, seen.get(8));
		assertEquals(Phase.RECOVERY, seen.get(12));
		assertEquals(Phase.RECOVERY, seen.get(SWING.length() - 1));
	}

	@Test
	@DisplayName("the blade is dangerous for a window, not for an instant")
	void contactIsAnInterval() {
		// A sweep passes through a space. One tick of contact would mean a target
		// walking through the arc between two ticks is never touched — and from
		// outside that is a blow that went straight through somebody.
		assertTrue(SWING.contact() > 1);
	}

	@Test
	@DisplayName("she is committed from the first tick to the last")
	void committedThroughout() {
		Blow blow = new Blow();
		blow.begin(SWING);
		for (int i = 0; i < SWING.length(); i++) {
			assertTrue(blow.committed(), "let go at tick " + i);
			blow.tick();
		}
		assertFalse(blow.committed(), "and free once it is over");
	}

	@Test
	@DisplayName("a blow already going cannot be started again")
	void oneAtATime() {
		// The graph asks every time round its loop, which is right — it should not
		// have to know how long a swing takes. What it must not do is restart one.
		Blow blow = new Blow();
		assertTrue(blow.begin(SWING));
		blow.tick();
		assertFalse(blow.begin(SWING), "that would have reset the swing to its start");
	}

	@Test
	@DisplayName("after a blow, the chain moves on")
	void theChainAdvances() {
		Blow blow = new Blow();
		assertEquals(0, blow.link(3));
		blow.begin(SWING);
		through(blow, SWING.length());
		assertEquals(1, blow.link(3), "the next swing is a different swing");
	}

	@Test
	@DisplayName("the chain is counted on ending, so the animation matches the blow")
	void theChainDoesNotMoveMidSwing() {
		// Counted on beginning, the blow that lands would be numbered for the next
		// one — and the picture and the damage would belong to different swings.
		Blow blow = new Blow();
		blow.begin(SWING);
		for (int i = 0; i < SWING.length() - 1; i++) {
			assertEquals(0, blow.link(3), "changed at tick " + i);
			blow.tick();
		}
	}

	@Test
	@DisplayName("the chain wraps rather than running off the end")
	void theChainWraps() {
		Blow blow = new Blow();
		for (int swing = 0; swing < 7; swing++) {
			blow.begin(SWING);
			through(blow, SWING.length());
		}
		assertTrue(blow.link(3) >= 0 && blow.link(3) < 3, "off the end: " + blow.link(3));
	}

	@Test
	@DisplayName("stopping for a moment starts the next fight from the first blow")
	void theChainForgets() {
		// Keep hitting and the swings run into one another; walk away and come back,
		// and it starts again — rather than halfway through somebody else's
		// combination, which is where it would otherwise resume.
		Blow blow = new Blow();
		blow.begin(SWING);
		through(blow, SWING.length());
		assertEquals(1, blow.link(3));

		through(blow, Blow.CHAIN_HOLDS + 1);
		assertEquals(0, blow.link(3), "it should have forgotten by now");
	}

	@Test
	@DisplayName("a long recovery does not by itself break a combination")
	void recoveryIsNotAPause() {
		// The chain forgets only while she is not swinging. Otherwise a heavy
		// weapon — whose recovery is longer than the memory — could never chain at
		// all, and its combination would be dead data.
		assertTrue(Style.HEAVY.shape().recovery() < Blow.CHAIN_HOLDS
			|| new Blow().link(3) == 0);

		Blow blow = new Blow();
		blow.begin(Style.HEAVY.shape());
		through(blow, Style.HEAVY.shape().length());
		assertEquals(1, blow.link(3), "the heavy chain broke on its own recovery");
	}

	// ------------------------------------------------------------------ styles

	@Test
	@DisplayName("no style commits her for longer than its own shortest swing")
	void nothingOutlastsItsPicture() {
		// Read out of the pack rather than written down here, because a number
		// written down here is a number that drifts the moment somebody swaps an
		// animation — and the symptom is a character frozen at the end of a swing,
		// which nobody would think to blame on a constant in a test.
		for (Style style : styles()) {
			for (String swing : style.chain()) {
				int length = lengthOf(swing);
				assertTrue(style.shape().length() <= length,
					style.stance() + " commits for " + style.shape().length()
						+ " ticks but " + swing + " lasts " + length);
			}
		}
	}

	@Test
	@DisplayName("no swing loops, because a loop never ends")
	void aBlowHasToFinish() {
		// spe_swing_the_sword is the obvious choice by its name and the wrong one:
		// it loops, and a blow whose animation never ends is a character stuck
		// mid-swing for ever.
		for (Style style : styles()) {
			for (String swing : style.chain()) {
				assertFalse(loops(swing), swing + " loops and cannot be a blow");
			}
		}
	}

	@Test
	@DisplayName("every stance loops, because a stance is held")
	void aStanceHasToHold() {
		for (Style style : styles()) {
			assertTrue(loops(style.stance()),
				style.stance() + " does not loop, so she would settle out of it");
		}
	}

	@Test
	@DisplayName("every animation a style names is one the mod actually ships")
	void nothingIsNamedThatIsNotThere() {
		// A misspelt animation is silence: nothing plays, and from outside that is
		// the wooden character this whole piece of work is about.
		for (Style style : styles()) {
			assertTrue(emote(style.stance()) != null, "no such animation: " + style.stance());
			for (String swing : style.chain()) {
				assertTrue(emote(swing) != null, "no such animation: " + swing);
			}
		}
	}

	private static List<Style> styles() {
		return List.of(Style.SWORD, Style.HEAVY, Style.SPEAR,
			Style.FIST, Style.GUARDED, Style.RANGED);
	}

	/** The shipped emote file, straight off the classpath. */
	private static com.google.gson.JsonObject emote(String id) {
		try (var in = Style.class.getResourceAsStream(
				"/assets/npc_studio/emotes/" + id + ".json")) {
			if (in == null) return null;
			var root = com.google.gson.JsonParser
				.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
				.getAsJsonObject();
			return root.has("emote") ? root.getAsJsonObject("emote") : root;
		} catch (java.io.IOException cannotRead) {
			return null;
		}
	}

	private static int lengthOf(String id) {
		var e = emote(id);
		if (e == null) return 0;
		return e.get("stopTick").getAsInt() - e.get("beginTick").getAsInt();
	}

	private static boolean loops(String id) {
		var e = emote(id);
		// Written as the string "true" in every file in the pack, which is why it
		// is read as text rather than as a boolean.
		return e != null && Boolean.parseBoolean(e.get("isLoop").getAsString());
	}

	@Test
	@DisplayName("every style has a stance and at least one swing")
	void nothingIsHalfWritten() {
		for (Style style : List.of(Style.SWORD, Style.HEAVY, Style.SPEAR,
				Style.FIST, Style.GUARDED, Style.RANGED)) {
			assertFalse(style.stance().isEmpty());
			assertFalse(style.chain().isEmpty());
			for (int link = 0; link < 5; link++) {
				assertFalse(style.swing(link).isEmpty(), "no swing for link " + link);
			}
		}
	}

	@Test
	@DisplayName("every weapon there is has a style")
	void noWeaponIsLeftWithout() {
		// A weapon with no style would be a character who fights in her walking
		// pose — the exact complaint this is answering, hiding in one branch.
		for (Arms.Kind kind : Arms.Kind.values()) {
			assertTrue(Style.forWeapon(kind) != null, kind + " has no style");
		}
	}

	@Test
	@DisplayName("bare hands fight faster than a heavy sword")
	void weightReadsInTheNumbers() {
		assertTrue(Style.FIST.shape().length() < Style.HEAVY.shape().length());
	}
}
