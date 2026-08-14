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
