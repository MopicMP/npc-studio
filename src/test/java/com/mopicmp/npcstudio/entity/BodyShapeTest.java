package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape survives the journey to every client that can see the character.
 *
 * Worth testing because the journey is a bit-packing scheme, and a bit-packing
 * scheme that is one shift out is wrong in a way that looks like a rendering
 * bug: the belly setting turns up on the arms.
 */
class BodyShapeTest {

	/** How close two scales may be and still count as the same to an eye. */
	private static final float STEP = (BodyShape.MAX_SCALE - BodyShape.MIN_SCALE) / 255f;

	@Test
	@DisplayName("an ordinary character packs to nothing to do")
	void defaultIsRecognised() {
		assertTrue(BodyShape.DEFAULT.isDefault());
		assertTrue(BodyShape.unpack(BodyShape.DEFAULT.packed(),
			BodyShape.DEFAULT.packedPosture()).isDefault(),
			"and still says so after a round trip, or every character pays for the slow path");
	}

	@Test
	@DisplayName("every field comes back as itself and in its own place")
	void eachFieldKeepsItsSlot() {
		// All different, and none of them one of the others, so a shift by one
		// byte cannot pass unnoticed.
		BodyShape sent = new BodyShape(1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 0.8f, 0.4f);

		BodyShape got = BodyShape.unpack(sent.packed(), sent.packedPosture());

		assertEquals(sent.shoulders(), got.shoulders(), STEP);
		assertEquals(sent.hips(), got.hips(), STEP);
		assertEquals(sent.belly(), got.belly(), STEP);
		assertEquals(sent.arms(), got.arms(), STEP);
		assertEquals(sent.legs(), got.legs(), STEP);
		assertEquals(sent.chest(), got.chest(), STEP);
		assertEquals(sent.head(), got.head(), STEP);
		assertEquals(sent.softness(), got.softness(), 1f / 255f);
		assertEquals(sent.stoop(), got.stoop(), 2f * BodyShape.MAX_STOOP / 255f,
			"the bend of the back travels in its own number and still comes back");
	}

	/**
	 * The ends of the range are exact, not nearly.
	 *
	 * They are where presets and reset buttons land, so a smallest value that
	 * came back as very slightly more than smallest would leave every "back to
	 * normal" a hair off normal — and {@code isDefault} would stop being true,
	 * quietly putting every character on the slow path forever.
	 */
	@Test
	@DisplayName("the ends of the range survive exactly")
	void endsAreExact() {
		BodyShape ends = new BodyShape(BodyShape.MIN_SCALE, BodyShape.MAX_SCALE,
			1f, 1f, 1f, 1f, 1f, 1f, BodyShape.MAX_STOOP);

		BodyShape got = BodyShape.unpack(ends.packed(), ends.packedPosture());

		assertEquals(BodyShape.MIN_SCALE, got.shoulders());
		assertEquals(BodyShape.MAX_SCALE, got.hips());
		assertEquals(1f, got.softness());
		assertEquals(BodyShape.MAX_STOOP, got.stoop());
	}

	/**
	 * Standing up straight comes back as standing up straight, to the bit.
	 *
	 * Its own test because it is its own arithmetic, and because getting it wrong
	 * is invisible until it is expensive. A range spread evenly end to end has 255
	 * gaps in it, so its middle lands between two steps: upright packed to a hair
	 * of a stoop, {@code isDefault} became false for every character alive, and
	 * every one of them would have been drawn the slow way for the rest of time.
	 * Nothing on screen would have looked wrong.
	 */
	@Test
	@DisplayName("standing straight survives the round trip exactly")
	void uprightIsExactlyUpright() {
		BodyShape upright = BodyShape.DEFAULT;

		assertEquals(0f, BodyShape.unpack(upright.packed(), upright.packedPosture()).stoop(), 0f);
		// And both ways off it, since a signed encoding is where sign errors live.
		for (float stoop : new float[] { -BodyShape.MAX_STOOP, -0.2f, 0.2f, BodyShape.MAX_STOOP }) {
			BodyShape bent = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0, stoop);
			assertEquals(stoop, BodyShape.unpack(bent.packed(), bent.packedPosture()).stoop(),
				BodyShape.MAX_STOOP / 127f, "at " + stoop);
		}
	}

	/** Every preset has to be one somebody could have reached with the sliders. */
	@Test
	@DisplayName("the presets survive being sent like anything else")
	void presetsTravel() {
		for (BodyShape.Preset preset : BodyShape.PRESETS) {
			BodyShape got = BodyShape.unpack(
				preset.shape().packed(), preset.shape().packedPosture());
			assertEquals(preset.shape().shoulders(), got.shoulders(), STEP, preset.name());
			assertEquals(preset.shape().stoop(), got.stoop(), BodyShape.MAX_STOOP / 127f, preset.name());
		}
		assertTrue(BodyShape.PRESETS.get(0).shape().isDefault(),
			"and the first one is the way out of all the others");
	}

	@Test
	@DisplayName("a value outside the range is brought back rather than wrapped")
	void nonsenseIsClamped() {
		// Not idle: the numbers arrive from a text field somebody can type into,
		// and a wrap would turn a fat-fingered 20 into a character inside out.
		BodyShape silly = new BodyShape(20f, -5f, Float.NaN, 1, 1, 1, 1, 9f, -80f);

		assertEquals(BodyShape.MAX_SCALE, silly.shoulders());
		assertEquals(BodyShape.MIN_SCALE, silly.hips());
		assertEquals(1f, silly.belly(), "not a number means no opinion, not zero width");
		assertEquals(1f, silly.softness());
		assertEquals(-BodyShape.MAX_STOOP, silly.stoop());
	}

	@Test
	@DisplayName("a file keeps only what was changed, and reads back the same")
	void jsonIsSparseAndFaithful() {
		BodyShape smith = new BodyShape(1.3f, 1, 1.2f, 1.15f, 1, 1, 1, 0.5f, -0.1f);

		var json = smith.toJson();

		// Everything left alone is left out, so a hand-written file stays short
		// enough to read.
		assertFalse(json.has("hips"));
		assertFalse(json.has("head"));
		assertEquals(smith, BodyShape.fromJson(json));
		assertEquals(BodyShape.DEFAULT, BodyShape.fromJson(BodyShape.DEFAULT.toJson()));
	}

	@Test
	@DisplayName("a typo in a hand-edited file costs one setting, not the character")
	void badJsonDegradesGently() {
		var json = com.google.gson.JsonParser.parseString(
			"{\"belly\": \"вот столько\", \"shoulders\": 1.4}").getAsJsonObject();

		BodyShape read = BodyShape.fromJson(json);

		assertEquals(1f, read.belly());
		assertEquals(1.4f, read.shoulders(), "and the rest of the line still applies");
	}

	@Test
	@DisplayName("a character saved before height existed comes back ordinary, not a dwarf")
	void oldPostureIsNotAMinimum() {
		// What was written when this long held nothing but the stoop: the low byte
		// says the back is bent, and the two bytes above it had not been invented.
		long asWritten = BodyShape.DEFAULT.packedPosture() & 0xFFL;
		long stooped = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0, 0.3f).packedPosture() & 0xFFL;

		BodyShape read = BodyShape.unpack(
			BodyShape.DEFAULT.packed(), BodyShape.readPosture(asWritten));
		BodyShape bent = BodyShape.unpack(
			BodyShape.DEFAULT.packed(), BodyShape.readPosture(stooped));

		// Read literally these would both be MIN_HEIGHT, which is what was happening:
		// every character placed before the change was two thirds of its own height,
		// and everything measured from the body — the eyes among them — moved with it.
		assertEquals(1f, read.height(), 0.01f);
		assertEquals(1f, bent.height(), 0.01f, "and the stoop it did have survives");
		assertEquals(0.3f, bent.stoop(), 0.02f);
	}

	@Test
	@DisplayName("a posture written since is left exactly alone")
	void newPostureIsUntouched() {
		BodyShape tall = new BodyShape(1, 1, 1, 1, 1, 1, 1, 0, 0, 1.4f, 0.5f);
		long written = tall.packedPosture();

		assertEquals(written, BodyShape.readPosture(written));
		assertEquals(1.4f, BodyShape.unpack(tall.packed(),
			BodyShape.readPosture(written)).height(), 0.01f);
	}
}
