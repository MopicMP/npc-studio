package com.mopicmp.npcstudio.emote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.client.emote.Emote;
import com.mopicmp.npcstudio.client.emote.Emote.Bone;
import com.mopicmp.npcstudio.client.emote.Emote.Channel;
import com.mopicmp.npcstudio.client.emote.Emote.Track;
import com.mopicmp.npcstudio.client.emote.EmoteApplier;
import com.mopicmp.npcstudio.client.emote.EmoteParser;

/**
 * Reads the whole pack, because the pack is the specification.
 *
 * There is no written standard for this format that we can hold a parser
 * against — what there is, is seven hundred files somebody else exported. So
 * the test is the same thing the game will do, over every one of them: if a
 * file has a shape this parser has not met, that should stop a build rather
 * than appear as a character standing still in a conversation.
 */
class EmoteParserTest {

	private static final Path PACK =
		Path.of("src/main/resources/assets/npc_studio/emotes");

	private static List<Path> emotes() throws Exception {
		if (!Files.isDirectory(PACK)) return List.of();
		try (var files = Files.list(PACK)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".json"))
				.filter(path -> !path.getFileName().toString().equals("index.json"))
				.toList();
		}
	}

	private static Emote parse(Path path) throws Exception {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			String id = path.getFileName().toString().replace(".json", "");
			return EmoteParser.parse(id, root);
		}
	}

	@Test
	void everyEmoteInThePackParses() throws Exception {
		List<Path> files = emotes();
		assumeTrue(!files.isEmpty(), "the emote pack is not unpacked here");

		List<String> broken = new ArrayList<>();
		int moved = 0;
		for (Path file : files) {
			try {
				Emote emote = parse(file);
				if (!emote.tracks().isEmpty()) moved++;
			} catch (Exception failed) {
				broken.add(file.getFileName() + ": " + failed);
			}
		}
		assertTrue(broken.isEmpty(), "emotes that would not parse: " + broken);
		assertEquals(files.size(), moved, "every emote should move something");
	}

	/**
	 * Keyframes must come out sorted, because sampling binary-searches them.
	 *
	 * They do not arrive sorted, and the same tick arrives more than once — that
	 * is the whole reason the parser rebuilds them rather than copying them.
	 */
	@Test
	void tracksAreSortedAndUnique() throws Exception {
		List<Path> files = emotes();
		assumeTrue(!files.isEmpty(), "the emote pack is not unpacked here");

		for (Path file : files) {
			for (Map<Channel, Track> limb : parse(file).tracks().values()) {
				for (Track track : limb.values()) {
					int[] ticks = track.ticks();
					for (int i = 1; i < ticks.length; i++) {
						assertTrue(ticks[i] > ticks[i - 1],
							file.getFileName() + " has keyframes out of order or repeated");
					}
					assertEquals(ticks.length, track.values().length);
					assertEquals(ticks.length, track.easings().length);
				}
			}
		}
	}

	/**
	 * The T-pose, which is the one emote in the pack whose correct shape is known
	 * in advance.
	 *
	 * That makes it the only end-to-end check available without a running game:
	 * arms out level, right rolled one way and left the other, by exactly a
	 * quarter turn. If the reader ever starts putting values in the wrong channel
	 * or losing their sign, this is where it shows.
	 */
	@Test
	void theTPoseIsAT() throws Exception {
		Path file = PACK.resolve("spe_t-pose.json");
		assumeTrue(Files.exists(file), "the emote pack is not unpacked here");

		Emote emote = parse(file);
		// Late enough that the character has finished getting into position.
		float tick = emote.endTick();

		float right = emote.tracks().get(Bone.RIGHT_ARM).get(Channel.ROLL).sample(tick);
		float left = emote.tracks().get(Bone.LEFT_ARM).get(Channel.ROLL).sample(tick);

		assertEquals(Math.PI / 2, right, 1e-3, "the right arm should be out level");
		assertEquals(-Math.PI / 2, left, 1e-3, "and the left arm the other way");

		// Nothing should be pitched or yawed in a T-pose; if a channel were being
		// read into the wrong slot, this is what would catch it.
		assertEquals(0f, emote.tracks().get(Bone.RIGHT_ARM).get(Channel.PITCH).sample(tick), 1e-3);
		assertEquals(0f, emote.tracks().get(Bone.RIGHT_ARM).get(Channel.YAW).sample(tick), 1e-3);
	}

	/**
	 * Limb tracks hold where a limb is, not how far it moved.
	 *
	 * Checked against the model's own rest positions, which the pack reproduces
	 * to the decimal: right arm at −5, left at +5, legs at ∓1.9 and 12 down. This
	 * is the test that was missing when the first version added these to the rest
	 * pose and doubled every one of them.
	 */
	@Test
	void limbTracksAreAbsolutePositions() throws Exception {
		Path file = PACK.resolve("spe_t-pose.json");
		assumeTrue(Files.exists(file), "the emote pack is not unpacked here");

		Emote emote = parse(file);
		float tick = emote.endTick();

		float rightArmX = emote.tracks().get(Bone.RIGHT_ARM).get(Channel.X).sample(tick);
		float leftArmX = emote.tracks().get(Bone.LEFT_ARM).get(Channel.X).sample(tick);

		// Within a couple of pixels of where the vanilla arm already sits. If these
		// were offsets they would be near zero instead.
		assertTrue(rightArmX < -3f && rightArmX > -8f,
			"the right arm should be placed on the right, was " + rightArmX);
		assertTrue(leftArmX > 3f && leftArmX < 8f,
			"the left arm should be placed on the left, was " + leftArmX);
	}

	/**
	 * A channel the emote never mentions must read as nothing, not as zero.
	 *
	 * Each keyframe here carries one channel of one limb, so an emote that only
	 * rotates an arm has no track for where the arm is. Zero would be a position
	 * — the middle of the character.
	 */
	@Test
	void anUnmentionedChannelIsSilentRatherThanZero() {
		JsonObject move = new JsonObject();
		move.addProperty("tick", 1);
		move.addProperty("easing", "LINEAR");
		JsonObject arm = new JsonObject();
		arm.addProperty("roll", 1.5);
		move.add("rightArm", arm);

		com.google.gson.JsonArray moves = new com.google.gson.JsonArray();
		moves.add(move);
		JsonObject body = new JsonObject();
		body.addProperty("isLoop", "false");
		body.addProperty("endTick", 10);
		body.add("moves", moves);
		JsonObject root = new JsonObject();
		root.add("emote", body);

		Emote.Pose pose = EmoteParser.parse("x", root).poseAt(5f);

		assertEquals(1.5f, pose.or(Bone.RIGHT_ARM, Channel.ROLL, -99f), 1e-4);
		assertEquals(-99f, pose.or(Bone.RIGHT_ARM, Channel.X, -99f), 1e-4,
			"an unmentioned position must fall through to the rest pose");
	}

	/**
	 * Turning the torso about the waist is what puts a lying character on the
	 * floor; turning it about the model's own origin leaves them in the air.
	 *
	 * Worked here rather than through the applier, which needs a game to hold a
	 * model. The arithmetic is the part that can be wrong, and it is the same
	 * arithmetic: swing the waist point, then shift back by however far it moved.
	 */
	@Test
	void turningAboutTheWaistLandsTheBodyOnTheGround() {
		// Standing still, nothing is corrected. This is the case that runs on every
		// character that is not mid-emote, so it had better cost nothing.
		var still = EmoteApplier.waistCorrection(0, 0, 0);
		assertEquals(0f, still.x, 1e-4);
		assertEquals(0f, still.y, 1e-4);
		assertEquals(0f, still.z, 1e-4);

		// A quarter turn forward, which is what lying down is. The waist would
		// otherwise swing out to the front and up; the correction pulls it back by
		// the whole twelve pixels in both directions, which is what drops the body
		// from neck height onto the floor.
		var flat = EmoteApplier.waistCorrection((float) (Math.PI / 2), 0, 0);
		assertEquals(12f, flat.y, 1e-3, "the body has to come down by the length of the waist");
		assertEquals(-12f, flat.z, 1e-3, "and back, or it lies where its head used to be");

		// Turning on the spot moves the waist nowhere, because the waist is on the
		// axis being turned about.
		var spun = EmoteApplier.waistCorrection(0, 1.2f, 0);
		assertEquals(0f, spun.x, 1e-4);
		assertEquals(0f, spun.y, 1e-4);
		assertEquals(0f, spun.z, 1e-4);
	}

	@Test
	void aOneTickLoopIsNotWorthRepeating() {
		Emote holds = new Emote("x", "x", "", "", true, 0, 21, 22, 20, Map.of());
		Emote dances = new Emote("y", "y", "", "", true, 0, 22, 23, 3, Map.of());

		assertEquals(1, holds.cycle());
		assertFalse(holds.loopsVisibly(), "holding the last pose is not a loop to watch");
		assertTrue(dances.loopsVisibly(), "a real loop is");
	}

	@Test
	void samplingStaysInsideTheKeyframes() {
		Track track = new Track(
			new int[] { 0, 10 },
			new float[] { 0f, 1f },
			new Emote.Easing[] { Emote.Easing.LINEAR, Emote.Easing.LINEAR });

		assertEquals(0f, track.sample(-5f), 1e-6, "before the start holds the first value");
		assertEquals(1f, track.sample(50f), 1e-6, "after the end holds the last value");
		assertEquals(0.5f, track.sample(5f), 1e-6, "halfway is halfway");
	}

	/** A held keyframe must not drift towards the next one. */
	@Test
	void constantEasingHolds() {
		Track track = new Track(
			new int[] { 0, 10 },
			new float[] { 0f, 1f },
			new Emote.Easing[] { Emote.Easing.CONSTANT, Emote.Easing.LINEAR });

		assertEquals(0f, track.sample(9.9f), 1e-6);
		assertEquals(1f, track.sample(10f), 1e-6);
	}

	/**
	 * A loop comes back to its return point rather than to zero.
	 *
	 * The opening of an emote is usually the character getting into position, and
	 * replaying it every cycle is what makes a loop look like a stutter.
	 */
	@Test
	void loopingReturnsToTheReturnTick() {
		Emote emote = new Emote("x", "x", "", "", true, 0, 20, 24, 5, Map.of());

		assertEquals(10f, emote.timeAt(10f), 1e-6, "inside the first pass nothing changes");
		// The last frame of a pass is still a frame of the animation, so the wrap
		// happens after it rather than on it.
		assertEquals(20f, emote.timeAt(20f), 1e-6, "the closing frame is shown");
		assertEquals(5.5f, emote.timeAt(20.5f), 1e-6, "and the next moment is back at the return");
		assertEquals(8f, emote.timeAt(23f), 1e-6, "and carries on from there");
	}

	/**
	 * A one-tick loop turns any age at all into its final pose.
	 *
	 * <h2>Why this is worth a test of its own</h2>
	 *
	 * Because it is how the format writes "play once and hold", fifteen files use
	 * it, and it means an emote is never out of range — ask it for tick nine
	 * thousand and it answers with a pose rather than with nothing.
	 *
	 * That turned a small mistake into a loud one. An animation being faded out was
	 * frozen at the wrong number — the age of the one replacing it, which for a
	 * resting animation is the character's whole lifetime — and instead of showing
	 * nothing, it showed the last frame of a death at full strength. The report was
	 * that a punched character lies flat on the floor and gets up again, and every
	 * word of it was accurate.
	 */
	@Test
	void aLoopOfOneTickHoldsItsLastFrameForEver() {
		Emote emote = new Emote("x", "x", "", "", true, 0, 32, 33, 31, Map.of());

		assertEquals(20f, emote.timeAt(20f), 1e-6, "inside the pass it plays");
		assertEquals(31f, emote.timeAt(33f), 1e-6, "one past the end is the held frame");
		assertEquals(31f, emote.timeAt(9000f), 1e-6, "and so is a number off the clock");
	}

	/**
	 * The animation the flinch is cut out of really is shaped the way we think.
	 *
	 * Against the shipped file rather than against a remembered reading of it, so
	 * that a pack update which recuts the death is a failing build here instead of
	 * a character on the floor in a fight.
	 */
	@Test
	void theFlinchIsCutFromAnAnimationThatHoldsItsLastFrame() throws Exception {
		Path file = PACK.resolve("spe_to_die_from_a_severe_blow.json");
		assumeTrue(Files.exists(file), "the emote pack is not unpacked here");

		Emote emote = parse(file);
		assertTrue(emote.loop(), "it loops, which is how it holds");
		assertEquals(31f, emote.timeAt(5000f), 1e-6, "and holds on the body being down");
		// The four ticks the flinch actually uses are inside the animation and
		// nowhere near that, which is the whole reason it is a range and not a name.
		assertEquals(5f, emote.timeAt(5f), 1e-6, "the fold is still the fold");
	}

	/**
	 * How far apart two poses are, which is how long a change between them takes.
	 *
	 * The scale matters more than any single number: a change has to be able to
	 * tell a step from a swing from falling over, because one length for all three
	 * is what "the changes are not smooth enough" turns out to mean.
	 */
	@Test
	void poseTravelIsZeroForAPoseAgainstItselfAndGrowsWithTheMovement() {
		Emote.Pose still = new Emote.Pose(Map.of());
		assertEquals(0d, still.travelTo(still), 1e-6, "nothing has to move");

		// One radian at one shoulder. Twelve pixels, because that is how far a hand
		// on the end of an arm travels when the shoulder turns by a radian.
		float[] turned = new float[Emote.Channel.values().length];
		java.util.Arrays.fill(turned, Float.NaN);
		turned[Emote.Channel.PITCH.ordinal()] = 1f;
		Emote.Pose armUp = new Emote.Pose(Map.of(Emote.Bone.RIGHT_ARM, turned));

		assertEquals(12d, still.travelTo(armUp), 1e-4);
		assertEquals(12d, armUp.travelTo(still), 1e-4, "and it is the same either way");
	}

	/**
	 * The measured spread across a real fight, against the shipped pack.
	 *
	 * Written down as a test rather than only in a document because the numbers are
	 * what the length of every change is drawn from, and a pack update that recuts
	 * these animations should fail here rather than quietly change how the fighting
	 * moves.
	 */
	@Test
	void aStepIsASmallerChangeThanASwingWhichIsSmallerThanFallingOver() throws Exception {
		assumeTrue(Files.exists(PACK.resolve("spe_fighting_stance.json")),
			"the emote pack is not unpacked here");

		double step = travel("spe_gait_with_a_sword_on_belt", 10,
			"spe_fighting_stance_with_swords", 0);
		double punch = travel("spe_fighting_stance", 0, "spe_hand_strike1", 0);
		double swing = travel("spe_fighting_stance_with_swords", 0,
			"spe_strike_with_a_sword1", 0);
		double falling = travel("spe_fighting_stance", 0,
			"spe_to_die_from_a_severe_blow", 31);

		assertTrue(step < punch, "a step is less movement than a punch: " + step + " vs " + punch);
		assertTrue(punch < swing, "a punch is less than a sword swing: " + punch + " vs " + swing);
		assertTrue(swing < falling, "and nothing is further than falling over: " + falling);
		assertTrue(falling < 16 * 12,
			"the whole scale has to fit inside the longest change: " + falling);
	}

	private static double travel(String from, int fromTick, String to, int toTick)
			throws Exception {
		Emote a = parse(PACK.resolve(from + ".json"));
		Emote b = parse(PACK.resolve(to + ".json"));
		return a.poseAt(a.timeAt(fromTick)).travelTo(b.poseAt(b.timeAt(toTick)));
	}

	@Test
	void aNonLoopingEmoteStopsAndSaysSo() {
		Emote emote = new Emote("x", "x", "", "", false, 0, 20, 24, 0, Map.of());

		assertEquals(24f, emote.timeAt(100f), 1e-6, "it holds on the last frame");
		assertFalse(emote.finished(10f));
		assertTrue(emote.finished(30f));
	}

	/** The one file in seven hundred that capitalises its boolean must still loop. */
	@Test
	void loopFlagIsReadAsWrittenNotAsTyped() {
		JsonObject emote = new JsonObject();
		emote.addProperty("isLoop", "True");
		emote.addProperty("beginTick", 0);
		emote.addProperty("endTick", 10);
		emote.addProperty("stopTick", 12);
		emote.addProperty("returnTick", 0);
		emote.add("moves", new com.google.gson.JsonArray());

		JsonObject root = new JsonObject();
		root.addProperty("author", "somebody");
		root.add("emote", emote);

		assertTrue(EmoteParser.parse("x", root).loop());
	}

	/** Bones the parser does not know must be skipped, not fatal. */
	@Test
	void unknownBonesAndEasingsAreSurvivable() {
		JsonObject move = new JsonObject();
		move.addProperty("tick", 1);
		move.addProperty("easing", "EASEOUTELASTICWOBBLE");
		JsonObject tail = new JsonObject();
		tail.addProperty("x", 1.0);
		move.add("tail", tail);
		JsonObject head = new JsonObject();
		head.addProperty("pitch", 0.5);
		move.add("head", head);

		com.google.gson.JsonArray moves = new com.google.gson.JsonArray();
		moves.add(move);

		JsonObject emote = new JsonObject();
		emote.addProperty("isLoop", "false");
		emote.addProperty("endTick", 10);
		emote.add("moves", moves);

		JsonObject root = new JsonObject();
		root.add("emote", emote);

		Emote parsed = EmoteParser.parse("x", root);
		assertTrue(parsed.tracks().containsKey(Bone.HEAD), "the head should have survived");
		assertEquals(1, parsed.tracks().size(), "and the invented limb should not be there");
	}
}
