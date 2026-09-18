package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The first datapack files this mod has ever shipped, checked against the game's own.
 *
 * <h2>Why this test exists and what it stands in for</h2>
 *
 * A dimension is described by JSON the game reads at load. A field spelt wrong, or an id
 * that names nothing, does not produce a small fault in one corner: the pack fails to
 * load, and a pack that fails to load takes everything in it with it. So the cost of a
 * typo here is the whole mod, on somebody else's machine, at a moment when nothing in
 * the game says which letter it was.
 *
 * None of it can be tried without running a server, which is the one thing these tests
 * do not do. What they can do is read the game's own dimension types — they are on the
 * classpath, in the jar, right beside ours — and insist that every field we use is a
 * field they use, and every id we name is a file that is there.
 *
 * That is not the same as knowing the dimension works. It is the difference between a
 * mistake found here and a mistake found by somebody whose game will not start.
 */
class LessonDimensionTest {

	private static final List<String> VANILLA = List.of(
		"overworld", "the_nether", "the_end", "overworld_caves");

	private static JsonObject read(String path) {
		try (InputStream stream = LessonDimensionTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, "not on the classpath: " + path);
			return JsonParser.parseReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception broken) {
			throw new AssertionError(path + " would not read: " + broken, broken);
		}
	}

	private static boolean there(String path) {
		try (InputStream stream = LessonDimensionTest.class.getResourceAsStream(path)) {
			return stream != null;
		} catch (Exception broken) {
			return false;
		}
	}

	private static JsonObject ours() {
		return read("/data/npc_studio/dimension_type/lesson.json");
	}

	@Test
	@DisplayName("every field the lessons' world uses is one the game's own worlds use")
	void noInventedFields() {
		// A misspelt field is the failure this is here for. The codec either refuses the
		// file — the pack does not load — or, for an optional one, quietly takes the
		// default, which is the worse of the two because everything starts and one thing
		// is wrong for ever.
		var known = new TreeSet<String>();
		for (String name : VANILLA) {
			known.addAll(read("/data/minecraft/dimension_type/" + name + ".json").keySet());
		}
		var unknown = new ArrayList<String>();
		for (String field : ours().keySet()) {
			if (!known.contains(field)) unknown.add(field);
		}
		assertTrue(unknown.isEmpty(),
			"fields the game's own dimension types do not have: " + unknown
				+ " (it has " + known + ")");
	}

	@Test
	@DisplayName("every environment attribute is one the game's own worlds set")
	void noInventedAttributes() {
		var known = new TreeSet<String>();
		for (String name : VANILLA) {
			JsonElement had = read("/data/minecraft/dimension_type/" + name + ".json")
				.get("attributes");
			if (had != null) known.addAll(had.getAsJsonObject().keySet());
		}
		JsonElement mine = ours().get("attributes");
		assertNotNull(mine, "the lessons' world sets some, or this test is watching nothing");
		for (String attribute : mine.getAsJsonObject().keySet()) {
			assertTrue(known.contains(attribute),
				attribute + " is not an attribute the game's own worlds set: " + known);
		}
	}

	@Test
	@DisplayName("every id the lessons' world names is a file that is there")
	void everyIdResolves() {
		// The other half of the same worry. A field spelt right holding an id that names
		// nothing fails at load in exactly the same way, and is exactly as invisible.
		Map<String, String> where = Map.of(
			"#minecraft:infiniburn_overworld", "/data/minecraft/tags/block/infiniburn_overworld.json",
			"#minecraft:in_overworld", "/data/minecraft/tags/timeline/in_overworld.json",
			"minecraft:overworld", "/data/minecraft/world_clock/overworld.json",
			"minecraft:the_void", "/data/minecraft/worldgen/biome/the_void.json");

		JsonObject type = ours();
		for (String field : List.of("infiniburn", "timelines", "default_clock")) {
			String named = type.get(field).getAsString();
			assertTrue(where.containsKey(named), field + " names " + named
				+ ", which this test does not know where to look for — add it here");
			assertTrue(there(where.get(named)), named + " names nothing: " + where.get(named));
		}

		String biome = read("/data/npc_studio/dimension/lesson.json")
			.getAsJsonObject("generator").getAsJsonObject("settings")
			.get("biome").getAsString();
		assertTrue(there(where.get(biome)), biome + " names nothing");
	}

	@Test
	@DisplayName("the world points at our own type, and the type file is there")
	void theWorldPointsAtTheType() {
		JsonObject world = read("/data/npc_studio/dimension/lesson.json");
		assertEquals("npc_studio:lesson", world.get("type").getAsString());
		assertTrue(there("/data/npc_studio/dimension_type/lesson.json"));
		// Both halves have to agree with the key the code uses, or the code looks up a
		// world that exists under another name and finds nothing — which shows up as
		// locations having nowhere to be, with no error anywhere.
		assertEquals("npc_studio:lesson", Lesson.LEVEL.identifier().toString());
	}

	@Test
	@DisplayName("the ground is nothing at all, which is the whole point of it")
	void theGroundIsVoid() {
		JsonObject settings = read("/data/npc_studio/dimension/lesson.json")
			.getAsJsonObject("generator").getAsJsonObject("settings");
		assertEquals("minecraft:flat", read("/data/npc_studio/dimension/lesson.json")
			.getAsJsonObject("generator").get("type").getAsString());
		assertEquals(0, settings.getAsJsonArray("layers").size(),
			"one layer of anything and it is no longer the clearest background there is");
		assertFalse(settings.get("features").getAsBoolean());
		assertFalse(settings.get("lakes").getAsBoolean());
		assertEquals(0, settings.getAsJsonArray("structure_overrides").size(),
			"a village generated through somebody's lesson");
	}

	@Test
	@DisplayName("there is a sky, because without one the place is pitch black")
	void thereIsDaylight() {
		// Written after the first person stood in it, and the report was three words:
		// absolute, utter darkness.
		//
		// The version before this had no skylight and ambient_light at 1.0, on the
		// reasoning that ambient light is light from nowhere at full strength. It is not.
		// It is a floor on the client's light map — Level never reads it at all — and the
		// thing that lights a world with no sky is the environment attribute
		// gameplay/sky_light_level, which is why the Nether is gloomy rather than black.
		// Neither of those was set, so nothing was lit by anything.
		assertTrue(ours().get("has_skylight").getAsBoolean(),
			"no skylight and no sky_light_level attribute is a black room");
		assertFalse(ours().get("has_fixed_time").getAsBoolean(),
			"time that cannot move is time /time cannot set");
	}

	@Test
	@DisplayName("the world names a clock, or /time refuses to work in it")
	void thereIsAClock() {
		// The second fault behind the first: /time set day answers "no default clock" in
		// a world that names none — the Nether is like this too. So somebody standing in
		// the dark could not even ask for daylight.
		//
		// The cost is stated rather than hidden. Clocks in 26.2 belong to the server, so
		// setting the time here sets it in the overworld as well. A clock of its own
		// would mean shipping a copy of the game's own day timeline bound to it — nine
		// kilobytes of keyframes that go stale on the next version — and that is a trade
		// worth making once there is a way to try it, not before.
		assertEquals("minecraft:overworld", ours().get("default_clock").getAsString());
		assertEquals("#minecraft:in_overworld", ours().get("timelines").getAsString(),
			"the timelines have to be the ones bound to that clock");
	}

	@Test
	@DisplayName("it is lit the way the overworld is lit, by the same parts")
	void litLikeTheOverworld() {
		// Compared against the overworld rather than asserted as numbers, because the
		// claim being made is "this is an ordinary lit world", and what proves it is the
		// game's own ordinary lit world.
		JsonObject overworld = read("/data/minecraft/dimension_type/overworld.json");
		for (String field : List.of("has_skylight", "default_clock", "timelines")) {
			assertEquals(overworld.get(field), ours().get(field), field);
		}
		assertFalse(overworld.has("has_fixed_time"), "absent there means false");
		assertFalse(ours().get("has_fixed_time").getAsBoolean(), "and false here");
	}

	@Test
	@DisplayName("night is dim rather than blind")
	void nightHasAFloor() {
		// A small floor under the light map, so a build being looked at after dark is dim
		// and not invisible. Not a light source — that was the mistake this replaced —
		// just a floor, and a smaller one than the Nether's.
		double floor = ours().get("ambient_light").getAsDouble();
		assertTrue(floor > 0 && floor < 0.25, "a floor, not a lamp: " + floor);
	}

	@Test
	@DisplayName("nothing hostile spawns in a place people are meant to be reading in")
	void nothingSpawns() {
		assertEquals(0, ours().get("monster_spawn_light_level").getAsInt());
		assertEquals(0, ours().get("monster_spawn_block_light_limit").getAsInt());
	}
}
