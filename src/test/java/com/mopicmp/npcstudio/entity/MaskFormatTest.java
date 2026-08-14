package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * That this side of the mask format agrees with the written contract.
 *
 * <h2>Why a file of strings rather than a test of its own</h2>
 *
 * Because there are two implementations of this format and there will be more: the
 * mod encodes it in Java and the website decodes it in TypeScript, and the two
 * never run in the same process, never see each other's tests, and never fail
 * together.
 *
 * Two encoders of one format do not diverge loudly. Nothing throws and nothing
 * complains — one day a face marked in a browser simply appears in game a pixel
 * out, and by then there is no way to find the week it started. So both sides are
 * held to the same file of reference strings, {@code test/Eyes/mask-fixtures.json},
 * and a case added there is a case both sides must answer.
 *
 * See {@code docs/face-mask-format.md} for what the strings mean.
 */
class MaskFormatTest {

	private static final Path FIXTURES = Path.of("../test/Eyes/mask-fixtures.json");

	private static JsonObject fixtures() throws Exception {
		Assumptions.assumeTrue(Files.exists(FIXTURES), "no fixture file");
		return JsonParser.parseString(Files.readString(FIXTURES)).getAsJsonObject();
	}

	/** A mask built from the marks a case lists, so encoding can be checked against it. */
	private static FaceMask built(int size, JsonObject marks) {
		long[][] masks = new long[FaceMask.PARTS][FaceMask.words(size)];
		for (String key : marks.keySet()) {
			String[] parts = key.split("/");
			int at = FaceMask.part(FaceMask.Kind.valueOf(parts[0]),
				FaceMask.Layer.valueOf(parts[1]));
			for (var cell : marks.getAsJsonArray(key)) {
				JsonArray xy = cell.getAsJsonArray();
				int bit = xy.get(1).getAsInt() * size + xy.get(0).getAsInt();
				masks[at][bit >>> 6] |= 1L << (bit & 63);
			}
		}
		return new FaceMask(size, masks, true);
	}

	/** Every cell a mask marks, as "KIND/LAYER x,y", sorted so two can be compared. */
	private static List<String> spelled(FaceMask mask) {
		List<String> out = new ArrayList<>();
		for (FaceMask.Kind kind : FaceMask.Kind.values()) {
			for (FaceMask.Layer layer : FaceMask.Layer.values()) {
				for (int y = 0; y < mask.size(); y++) {
					for (int x = 0; x < mask.size(); x++) {
						if (mask.is(kind, layer, x, y)) out.add(kind + "/" + layer + " " + x + "," + y);
					}
				}
			}
		}
		java.util.Collections.sort(out);
		return out;
	}

	private static List<String> spelled(int size, JsonObject marks) {
		return spelled(built(size, marks));
	}

	/** The reference strings decode to exactly the cells the file says they do. */
	@Test
	@DisplayName("every reference string means what the contract says it means")
	void theReferenceStringsDecodeAsWritten() throws Exception {
		JsonArray cases = fixtures().getAsJsonArray("cases");
		for (var each : cases) {
			JsonObject one = each.getAsJsonObject();
			String name = one.get("name").getAsString();
			int size = one.get("size").getAsInt();
			FaceMask read = FaceMask.decode(one.get("encoded").getAsString(), true);

			assertEquals(spelled(size, one.getAsJsonObject("marks")), spelled(read),
				"«" + name + "» decoded to different cells than the contract says");
		}
	}

	/** And this side writes exactly those strings, so the two never drift apart. */
	@Test
	@DisplayName("this side encodes to the reference strings, character for character")
	void thisSideEncodesTheSame() throws Exception {
		JsonArray cases = fixtures().getAsJsonArray("cases");
		for (var each : cases) {
			JsonObject one = each.getAsJsonObject();
			String name = one.get("name").getAsString();
			FaceMask made = built(one.get("size").getAsInt(), one.getAsJsonObject("marks"));

			assertEquals(one.get("encoded").getAsString(), made.encode(),
				"«" + name + "» was written differently here than in the contract");
		}
	}

	/**
	 * A string nobody can read is an unmarked face.
	 *
	 * The rule that keeps a hand-edited save, or one written by a later version, from
	 * costing a world its wardrobe over a pair of eyes.
	 */
	@Test
	@DisplayName("an unreadable string costs one costume, not a world")
	void rubbishIsAnUnmarkedFace() throws Exception {
		for (var each : fixtures().getAsJsonArray("unreadable")) {
			JsonObject one = each.getAsJsonObject();
			assertTrue(FaceMask.decode(one.get("encoded").getAsString(), true).isNone(),
				"«" + one.get("encoded").getAsString() + "» should have been refused: "
					+ one.get("why").getAsString());
		}
	}

	/** Three groups is the shape from before lashes and layers, and it still reads. */
	@Test
	@DisplayName("a mask written by the old build still means what it meant")
	void theOldFormStillReads() throws Exception {
		for (var each : fixtures().getAsJsonArray("old_form")) {
			JsonObject one = each.getAsJsonObject();
			FaceMask read = FaceMask.decode(one.get("encoded").getAsString(), true);
			assertEquals(spelled(one.get("size").getAsInt(), one.getAsJsonObject("marks")),
				spelled(read), one.get("why").getAsString());
		}
	}

	/** Runs that claim more bits than the face has stop at its edge. */
	@Test
	@DisplayName("a run past the end of the face is cut off rather than overflowing")
	void anOverrunIsCutOff() throws Exception {
		JsonObject one = fixtures().getAsJsonObject("overrun");
		FaceMask read = FaceMask.decode(one.get("encoded").getAsString(), true);

		assertFalse(read.isNone(), "the bits that do fit are still set");
		assertEquals(spelled(one.get("size").getAsInt(), one.getAsJsonObject("marks")),
			spelled(read), one.get("why").getAsString());
	}
}
