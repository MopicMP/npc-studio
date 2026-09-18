package com.mopicmp.npcstudio.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * A model written to a Blockbench file and read back, part for part.
 *
 * <h2>What is riding on this pair</h2>
 *
 * Everything that was built. A model is the hull, the masts, the deck — hours of
 * placing boxes — and {@link ModelIO} is two hand-written functions with nothing
 * comparing them. A field written and not read comes back as its default: a mast
 * at the wrong angle, a box the wrong size, a face pointing somewhere else. None
 * of it throws and none of it is obvious in a list of numbers; it is obvious only
 * by looking at the ship and knowing it used to be right.
 *
 * <h2>Why a round trip is the honest test for a foreign format</h2>
 *
 * These functions read and write somebody else's file, so there is no
 * specification here to check them against. What can be checked is that they
 * agree with each other, which is the property the mod actually depends on:
 * everything the editor saves it must be able to open again.
 *
 * <h2>Why every number here is odd</h2>
 *
 * The same trick as the scene test, and it is the whole of what makes this mean
 * anything. Records compare by their fields, so a lost field is only caught if it
 * held something other than what it comes back as. A model of zeroes and empty
 * strings would round-trip perfectly through a reader that ignored half of it, so
 * nothing here is nought, empty, or a whole number where a fraction would tell
 * more. There is a test below that checks this one has kept that promise.
 */
class ModelIORoundTripTest {

	/** A model with something unusual in every field of every part. */
	private static Model rich() {
		Cube plank = new Cube(
			-3.5f, 1.25f, -0.75f,
			4.5f, 2.75f, 6.25f,
			0.5f, 1.5f, 2.5f,
			15f, -30f, 45f,
			0.25f,
			12, 20,
			"minecraft:oak_planks");
		Cube trim = new Cube(
			1.5f, 2.5f, 3.5f,
			4.25f, 5.25f, 6.25f,
			7.5f, 8.5f, 9.5f,
			-11f, 22f, -33f,
			0.5f,
			3, 7,
			"minecraft:stone");

		Bone keel = new Bone("keel", "", 1.5f, 2.5f, 3.5f, 10f, 20f, 30f, List.of(plank));
		Bone mast = new Bone("mast", "keel", 4.5f, 5.5f, 6.5f, -5f, -15f, -25f, List.of(trim, plank));

		return new Model("ship", 128, 64, List.of(keel, mast));
	}

	@Test
	@DisplayName("a model comes back exactly as it was written")
	void roundTrip() {
		Model was = rich();
		JsonObject written = ModelIO.writeBlockbench(was);
		Model back = ModelIO.readBlockbench(written);
		// One comparison rather than field by field: records compare all the way down,
		// so this catches a lost number, a defaulted one, and two written into each
		// other's places — which for coordinates is a shape nobody would recognise.
		assertEquals(was, back, "written as: " + written);
	}

	@Test
	@DisplayName("writing what was read gives the same file again")
	void writingIsStable() {
		// Catches what the first cannot: a reader that quietly repairs something on the
		// way in hands back an equal model while the file on disk changes every time it
		// is opened and saved — which turns a diff of a model into noise.
		JsonObject once = ModelIO.writeBlockbench(rich());
		JsonObject twice = ModelIO.writeBlockbench(ModelIO.readBlockbench(once));
		assertEquals(once, twice);
	}

	@Test
	@DisplayName("a bone with no boxes on it is still a bone")
	void anEmptyBoneSurvives() {
		// The shape a bone has the moment it is made, and the one a reader is most
		// likely to have opinions about — dropping empty groups is a plausible thing
		// for a loader to do and would lose the joint a limb turns on.
		Model bare = new Model("frame", 64, 64,
			List.of(new Bone("hinge", "", 1.5f, 2.5f, 3.5f, 5f, 10f, 15f, List.of())));
		assertEquals(bare, ModelIO.readBlockbench(ModelIO.writeBlockbench(bare)));
	}

	@Test
	@DisplayName("the model above really does use an unusual value everywhere")
	void theTestItselfIsHonest() {
		// The most important assertion here. If a field held what a reader would
		// default it to, the round trip would pass while losing it — green, and saying
		// nothing, which is worse than no test at all.
		List<String> lazy = new ArrayList<>();
		check(rich(), "Model", lazy);
		for (Bone bone : rich().bones()) {
			check(bone, "Bone", lazy);
			for (Cube cube : bone.cubes()) check(cube, "Cube", lazy);
		}
		assertTrue(lazy.isEmpty(), "these would survive a reader that ignored them: " + lazy);
	}

	/** Complains about any field holding what a careless reader would default it to. */
	private static void check(Record made, String what, List<String> lazy) {
		for (RecordComponent field : made.getClass().getRecordComponents()) {
			Object value;
			try {
				field.getAccessor().setAccessible(true);
				value = field.getAccessor().invoke(made);
			} catch (ReflectiveOperationException unreachable) {
				throw new AssertionError(unreachable);
			}
			String where = what + "." + field.getName();

			if (value instanceof List<?>) continue;
			if (value == null) continue;
			// One exception, named rather than waved through: a bone at the root of the
			// model has no parent, and "hangs from nothing" is a real state that has to
			// survive the trip as much as any other. The second bone names a parent, so
			// both cases are covered.
			if ("Bone.parent".equals(where)) continue;

			if (value instanceof Number number && number.doubleValue() == 0) lazy.add(where + " = 0");
			if ("".equals(value)) lazy.add(where + " = \"\"");
		}
	}
}
