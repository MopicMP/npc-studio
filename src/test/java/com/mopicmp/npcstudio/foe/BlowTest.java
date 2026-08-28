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
	@DisplayName("being interrupted does not lose the combination")
	void anInterruptionIsNotAStop() {
		// This was reported as "the same cycle over and over". Two evenly matched
		// fighters interrupt each other on every exchange, so forgetting the chain
		// on interruption meant both were permanently on swing one and a fight was
		// one movement repeated.
		Blow blow = new Blow();
		blow.begin(SWING);
		through(blow, SWING.length());
		assertEquals(1, blow.link(3));

		blow.begin(SWING);
		blow.tick();
		blow.drop();
		assertEquals(1, blow.link(3), "the interrupted swing should still be next in line");
	}

	@Test
	@DisplayName("standing down does forget it, because the fight is over")
	void standingDownForgets() {
		Blow blow = new Blow();
		blow.begin(SWING);
		through(blow, SWING.length());
		assertEquals(1, blow.link(3));
		blow.standDown();
		assertEquals(0, blow.link(3), "a new fight starts from the first blow");
	}

	@Test
	@DisplayName("the chain outlives a real exchange, not just a swing")
	void theChainSpansTakingTurns() {
		// A blow commits her for its own length and costs the other one a shorter
		// beat, so two fighters take turns roughly every twenty-five ticks. A memory
		// shorter than that expires between every pair of blows.
		assertTrue(Blow.CHAIN_HOLDS > 2 * Style.SWORD.shape(0).length(),
			"a chain of " + Blow.CHAIN_HOLDS + " ticks cannot survive taking turns");
	}

	@Test
	@DisplayName("a long recovery does not by itself break a combination")
	void recoveryIsNotAPause() {
		// The chain forgets only while she is not swinging. Otherwise a heavy
		// weapon — whose recovery is longer than the memory — could never chain at
		// all, and its combination would be dead data.
		Blow blow = new Blow();
		blow.begin(Style.HEAVY.shape(0));
		through(blow, Style.HEAVY.shape(0).length());
		assertEquals(1, blow.link(3), "the heavy chain broke on its own recovery");
	}

	// ------------------------------------------------------------------ styles

	@Test
	@DisplayName("no blow commits her for longer than its own picture")
	void nothingOutlastsItsPicture() {
		// Read out of the pack rather than written down here, because a number
		// written down here is a number that drifts the moment somebody swaps an
		// animation — and the symptom is a character frozen at the end of a swing,
		// which nobody would think to blame on a constant in a test.
		for (Style style : styles()) {
			for (int link = 0; link < style.chain().size(); link++) {
				String swing = style.swing(link);
				int length = lengthOf(swing);
				int commits = style.shape(link).length();
				assertTrue(commits <= length,
					swing + " commits for " + commits + " ticks but lasts " + length);
			}
		}
	}

	@Test
	@DisplayName("every blow a style can throw has been measured, not guessed at")
	void nothingShippedIsAGuess() {
		// The guess exists for somebody else's animation, not for ours. A style
		// naming a swing with no numbers behind it is a blow landing on a tick
		// nobody chose, and it would look exactly like a blow landing correctly.
		for (Style style : styles()) {
			for (String swing : style.chain()) {
				assertTrue(Swings.known(swing),
					swing + " is thrown by a built-in style and has no timing");
			}
		}
	}

	@Test
	@DisplayName("the edge is dangerous inside the film, not before or after it")
	void contactIsWithinThePicture() {
		for (String name : Swings.names()) {
			Swing swing = Swings.of(name);
			int length = lengthOf(name);
			if (length == 0) continue;
			assertTrue(swing.contact() > 0,
				name + " is dangerous on its very first tick, before anything has moved");
			assertTrue(swing.contact() + swing.through() <= length,
				name + " is still dangerous after it has finished: contact "
					+ swing.contact() + "+" + swing.through() + " of " + length);
			assertTrue(swing.cancel() <= length,
				name + " holds her past its own end: cancel " + swing.cancel()
					+ " of " + length);
		}
	}

	@Test
	@DisplayName("a blow reaches about as far as a person does, not as far as a mouse")
	void everySwingReachesLikeALimb() {
		// The bound this is really about is the top one. The fighting used to ask
		// ENTITY_INTERACTION_RANGE, which is three blocks because that is how far a
		// player can click on a mob, and characters swung at each other from three
		// and a half blocks apart with a metre of air between the fists.
		//
		// A person with a weapon reaches something between half a metre and about
		// two and a half. Anything outside that is not a reading of an animation,
		// it is somebody's idea of one — which is the whole failure being fenced off.
		for (String name : Swings.names()) {
			Swing swing = Swings.of(name);
			assertTrue(swing.reach() >= 0.5,
				name + " reaches " + swing.reach() + " blocks, which is inside her own body");
			assertTrue(swing.reach() <= 3.0,
				name + " reaches " + swing.reach() + " blocks, which is not an arm");
		}
	}

	@Test
	@DisplayName("a spear outreaches a fist, because the animations say so")
	void aLongWeaponReachesFurtherThanAShortOne() {
		// Not asserted as a rule the code enforces — nothing enforces it. It is a
		// consequence of having measured, and it is worth a test precisely because
		// it used to be false: every weapon reached three and a half blocks, so a
		// spear and a bare hand were the same length.
		assertTrue(Swings.of("spe_spear_strike1").reach()
				> Swings.of("spe_strike_with_a_sword1").reach(),
			"a spear should outreach a sword");
		assertTrue(Swings.of("spe_strike_with_a_sword1").reach()
				> Swings.of("spe_hand_strike1").reach(),
			"a sword should outreach a fist");
	}

	@Test
	@DisplayName("an animation nobody measured still swings, and says it was a guess")
	void anUnknownAnimationIsAGuessAndSaysSo() {
		// A modded pack's own strike. Refusing to swing would read as a character
		// who decided not to fight, which is worse than landing in the middle of
		// somebody else's animation — but it has to be findable, so it is flagged.
		assertFalse(Swings.known("nothing_anybody_ships"));
		assertEquals(Swings.UNKNOWN, Swings.of("nothing_anybody_ships"));
		assertFalse(Swings.of("nothing_anybody_ships").checked());
	}

	@Test
	@DisplayName("an override wins, and letting it go puts back what ships")
	void anOverrideCanBeTakenBack() {
		// The way out of a change matters as much as the change. Without it the
		// only way back to the shipped numbers is to remember what they were.
		String name = "spe_strike_with_a_sword1";
		Swing was = Swings.of(name);
		Swings.override(name, new Swing(3, 1, 9, 1.5, false));
		assertEquals(3, Swings.of(name).contact());
		assertEquals(was, Swings.shipped(name).orElseThrow(), "the file is not touched");
		Swings.forget(name);
		assertEquals(was, Swings.of(name));
	}

	@Test
	@DisplayName("she is free to swing again before the animation has finished")
	void theCancelIsNotTheEnd() {
		// This is what stops three blows taking four seconds. spe_zweihander_strike
		// runs for sixty-one ticks and has finished swinging by its thirty-fifth;
		// committing for the film rather than the blow is a slideshow.
		Swing heavy = Swings.of("spe_zweihander_strike");
		assertTrue(heavy.cancel() < lengthOf("spe_zweihander_strike"),
			"it commits for the whole film: " + heavy);
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

	/**
	 * Where a limb rests, in model pixels, so how far one has been flung can be read.
	 *
	 * The vanilla numbers, and they are here rather than asked of the model because
	 * a model needs a game running and these six numbers have not moved since 2011.
	 */
	private static final java.util.Map<String, float[]> RESTS = java.util.Map.of(
		"head", new float[] { 0, 0, 0 },
		"rightArm", new float[] { -5, 2, 0 },
		"leftArm", new float[] { 5, 2, 0 },
		"rightLeg", new float[] { -2, 12, 0 },
		"leftLeg", new float[] { 2, 12, 0 });

	@Test
	@DisplayName("no blow flings a limb far enough to be hidden mid-swing")
	void nothingAFighterDoesTripsTheLimbHider() {
		// The format has no way to say "do not draw this bone", so authors say it
		// with distance: a limb sent far enough away is treated as gone and stops
		// being drawn. Forty-eight pixels is where that starts.
		//
		// A fighting animation that crossed it would make an arm blink out and back
		// in mid-swing, which from outside is a limb teleporting — the complaint this
		// is written against, and the one thing checked here that was <em>not</em> the
		// cause. Nothing a fighter does goes past ten pixels. It should stay that way,
		// and a pack update is the thing that would change it.
		for (Style style : styles()) {
			java.util.List<String> all = new java.util.ArrayList<>(style.chain());
			all.add(style.stance());
			all.add(style.flinch().animation());
			for (String name : all) {
				if (name.isEmpty()) continue;
				var e = emote(name);
				if (e == null) continue;
				for (var move : e.getAsJsonArray("moves")) {
					var frame = move.getAsJsonObject();
					for (var bone : RESTS.entrySet()) {
						if (!frame.has(bone.getKey())
							|| !frame.get(bone.getKey()).isJsonObject()) continue;
						var at = frame.getAsJsonObject(bone.getKey());
						String[] axes = { "x", "y", "z" };
						for (int i = 0; i < axes.length; i++) {
							if (!at.has(axes[i])) continue;
							float off = Math.abs(at.get(axes[i]).getAsFloat() - bone.getValue()[i]);
							assertTrue(off < 48,
								name + " flings " + bone.getKey() + "." + axes[i] + " by " + off
									+ " pixels, which is far enough to be hidden");
						}
					}
				}
			}
		}
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
		// It was the other way round while the fists were kicking. A kick is nearly
		// twice as long as a punch, so bare hands were the slowest thing in the game
		// — which nobody would report as a bug, and everybody would feel.
		assertTrue(Style.FIST.shape(0).length() < Style.HEAVY.shape(0).length(),
			"fists " + Style.FIST.shape(0) + " against heavy " + Style.HEAVY.shape(0));
	}

	@Test
	@DisplayName("a chain of three is quicker than three whole films")
	void theChainDoesNotWaitForThePicture() {
		// The other half of what makes a fight not a slideshow. Three sword swings
		// run for seventy-three ticks of animation between them; committing for all
		// of it would be three and a half seconds for three blows, which reads as a
		// character taking turns rather than fighting.
		Style style = Style.SWORD;
		int committed = 0;
		int filmed = 0;
		for (int link = 0; link < style.chain().size(); link++) {
			committed += style.shape(link).length();
			filmed += lengthOf(style.swing(link));
		}
		assertTrue(committed < filmed,
			"she is held for " + committed + " ticks of " + filmed + " filmed");
	}

	@Test
	@DisplayName("weight is the wind-up, not the length")
	void weightIsTelegraph() {
		// The thing established in the docs and never actually held to: a heavy
		// weapon is one you see coming. Held by the length instead, the only way to
		// make something feel heavy is to freeze the character at the end of its
		// own animation.
		assertTrue(Style.HEAVY.timing(0).contact() > Style.FIST.timing(0).contact(),
			"a heavy blow should be seen coming for longer than a punch");
	}
}
