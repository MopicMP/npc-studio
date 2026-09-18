package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * A scene written to disk and read back, field for field.
 *
 * <h2>Why this is the riskiest saving in the mod</h2>
 *
 * The dialogue documents are saved by codecs, where one description does both
 * directions and the compiler watches half the seam. A scene is not: {@link
 * SceneIO} is two hand-written functions, two hundred-odd lines that have to
 * mirror each other exactly, and nothing at all checks that they do.
 *
 * Every failure here is silent and none of them is a crash. A field {@code write}
 * emits and {@code read} does not look for is a setting that survives until the
 * world is closed. A field {@code read} defaults is a setting that comes back
 * wrong rather than missing, which is worse — the scene loads, plays, and is
 * subtly not the one that was built. The note at the top of {@code Scene} says
 * the same thing about its arithmetic: <em>"a mistake does not crash, it produces
 * an animation that is slightly wrong in a way nobody can point at."</em>
 *
 * <h2>Why every value here is deliberately not a default</h2>
 *
 * This is the whole trick of the test and it is easy to get wrong. Records compare
 * by their fields, so a round trip that loses a field is only caught if that field
 * held something other than what it would come back as. A scene built with zeroes,
 * empty strings and the first constant of each enum would round-trip perfectly
 * through a reader that ignores half of it.
 *
 * So: no zero, no empty string, no first enum constant, no false where a boolean
 * could be true. There is a test below that checks this test obeys that.
 */
class SceneIORoundTripTest {

	/** A scene with something unusual in every field there is. */
	private static Scene rich() {
		Look look = new Look("npc_studio:handwriting", 2.5f, 0xFF3366CC, 0.25f, 0.75f,
			Look.Align.RIGHT, true, true, false);

		List<Role> cast = List.of(
			new Role("Guard", Role.Kind.CHARACTER, "42", 3),
			new Role("ворота", Role.Kind.OBJECT, "gate_left", 2),
			new Role("watcher", Role.Kind.CAMERA, "7", 5),
			new Role("weather", Role.Kind.WORLD, "sky", 4));

		List<Track> tracks = List.of(
			new Track("Guard", "head.yaw", List.of(
				new Key(3, 12.5f, Key.Ease.HOLD),
				new Key(9, -4.25f, Key.Ease.LINEAR),
				new Key(21, 90f, Key.Ease.SMOOTH))),
			new Track("watcher", "camera.fov", List.of(
				new Key(1, 65f, Key.Ease.SMOOTH))));

		List<Cue> cues = List.of(
			new Cue(4, Cue.Kind.SOUND, "minecraft:block.anvil.land", 11, look),
			new Cue(17, Cue.Kind.TEXT, "Кто идёт?", 40, look),
			new Cue(29, Cue.Kind.MUSIC, "npc_studio:march", 200, look));

		return new Scene("the gate", 240, cast, tracks, cues);
	}

	@Test
	@DisplayName("a scene comes back exactly as it was written")
	void roundTrip() {
		Scene was = rich();
		JsonObject written = SceneIO.write(was);
		Scene back = SceneIO.read(written);
		// One comparison rather than field by field: records compare by their contents
		// all the way down, so this catches a lost field, a defaulted one, and two
		// fields written into each other's places.
		assertEquals(was, back, "written as: " + written);
	}

	@Test
	@DisplayName("writing what was read gives the same file again")
	void writingIsStable() {
		// The other direction, and it catches what the first cannot: a reader that
		// quietly repairs something on the way in would still hand back an equal scene
		// while the file on disk kept changing every time it was opened and saved.
		JsonObject once = SceneIO.write(rich());
		JsonObject twice = SceneIO.write(SceneIO.read(once));
		assertEquals(once, twice);
	}

	@Test
	@DisplayName("an empty scene is a scene, not a hole")
	void emptyRoundTrips() {
		// The shape a scene has the moment it is made, which is the one every author
		// meets first and the one a reader is most likely to have opinions about.
		Scene bare = new Scene("fresh", 1, List.of(), List.of(), List.of());
		assertEquals(bare, SceneIO.read(SceneIO.write(bare)));
	}

	@Test
	@DisplayName("the scene above really does use an unusual value everywhere")
	void theTestItselfIsHonest() {
		// A guard on the test rather than on the code, and the most important assertion
		// in the file. If any field here held the value a reader would default it to,
		// the round trip would pass while losing it — so this test would go green while
		// saying nothing, which is worse than not existing.
		List<String> lazy = new ArrayList<>();
		check(rich(), "Scene", lazy);
		for (Role role : rich().cast()) check(role, "Role", lazy);
		for (Track track : rich().tracks()) {
			check(track, "Track", lazy);
			for (Key key : track.keys()) check(key, "Key", lazy);
		}
		int looks = 0;
		for (Cue cue : rich().cues()) {
			check(cue, "Cue", lazy);
			// Only a line has a look: the cue's own constructor drops one from anything
			// else, so that "a sound with a font" cannot exist to be read back wrongly.
			// Skipping the nulls here is not a hole, because the check below insists at
			// least one look is actually carried.
			if (cue.look() == null) continue;
			looks++;
			check(cue.look(), "Look", lazy);
		}
		assertTrue(looks > 0, "no cue carries a look, so nothing tests that a look survives");
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

			// Lists are allowed to be whatever length they are; their contents are
			// checked in their own right by the caller.
			if (value instanceof List<?>) continue;
			// A field the record itself insists is empty proves nothing about the
			// reader, and it is not this test's business to argue with a constructor.
			if (value == null) continue;

			if (value instanceof Number number && number.doubleValue() == 0) lazy.add(where + " = 0");
			if ("".equals(value)) lazy.add(where + " = \"\"");
			if (Boolean.FALSE.equals(value) && !"shadow".equals(field.getName())) {
				// One exception, named rather than waved through: a Look has three flags
				// and one of them has to be false somewhere or nothing checks that false
				// survives the trip either.
				lazy.add(where + " = false");
			}
			if (value instanceof Enum<?> constant && constant.ordinal() == 0
					&& !firstIsUsedOnPurpose(where)) {
				lazy.add(where + " = the first constant (" + constant + ")");
			}
		}
	}

	/** The enums where the first constant genuinely appears in the scene above. */
	private static boolean firstIsUsedOnPurpose(String where) {
		// Role.kind and Cue.kind each use their first constant on one member of a list
		// whose other members use later ones, so the first is covered without being the
		// only thing covered.
		return where.equals("Role.kind") || where.equals("Cue.kind") || where.equals("Key.ease");
	}
}
