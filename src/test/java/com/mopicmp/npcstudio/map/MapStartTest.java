package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

/**
 * What a map asks of a player, checked with no game anywhere near it.
 *
 * Everything here is arithmetic and bookkeeping, and it is exactly the part that
 * cannot be seen by looking at the panel: a bound that is applied the wrong way
 * round looks like a working slider that happens to give the wrong number, and
 * an entry list that grows a duplicate looks like nothing at all until the map
 * starts meaning whichever copy came last.
 */
class MapStartTest {

	@Nested
	@DisplayName("a bound")
	class Bounds {

		@Test
		@DisplayName("exact takes the value whatever it was")
		void exactTakes() {
			assertEquals(0.2, Bound.EXACT.applied(0.9, 0.2));
			assertEquals(0.9, Bound.EXACT.applied(0.2, 0.9));
		}

		@Test
		@DisplayName("a floor leaves somebody who is already above it alone")
		void floorLeavesAlone() {
			assertEquals(24, Bound.AT_LEAST.applied(24, 12));
			assertEquals(12, Bound.AT_LEAST.applied(8, 12));
		}

		@Test
		@DisplayName("a ceiling leaves somebody who is already below it alone")
		void ceilingLeavesAlone() {
			assertEquals(4, Bound.AT_MOST.applied(4, 12));
			assertEquals(12, Bound.AT_MOST.applied(30, 12));
		}

		@Test
		@DisplayName("applying twice changes nothing the second time")
		void idempotent() {
			// The author edits these while standing on the map, so every keystroke
			// runs the whole thing again. A bound that crept on each pass would walk
			// the player's settings away from them one edit at a time.
			for (Bound bound : Bound.values()) {
				double once = bound.applied(20, 12);
				assertEquals(once, bound.applied(once, 12), bound + " moved on the second pass");
			}
		}

		@Test
		@DisplayName("what a held setting still allows")
		void allows() {
			assertTrue(Bound.AT_LEAST.allows(30, 12));
			assertTrue(Bound.AT_LEAST.allows(12, 12));
			assertFalse(Bound.AT_LEAST.allows(11, 12));

			assertTrue(Bound.AT_MOST.allows(4, 12));
			assertFalse(Bound.AT_MOST.allows(13, 12));

			assertTrue(Bound.EXACT.allows(12, 12));
			assertFalse(Bound.EXACT.allows(12.5, 12));
		}

		@Test
		@DisplayName("what is applied is what is afterwards allowed")
		void agreesWithItself() {
			// The two halves are used in different places — one puts the value on, the
			// other refuses to let it move — and a map where they disagree is a map
			// that sets a value and then complains about it.
			for (Bound bound : Bound.values()) {
				double landed = bound.applied(20, 12);
				assertTrue(bound.allows(landed, 12),
					bound + " applied " + landed + " and then would not allow it");
			}
		}
	}

	@Nested
	@DisplayName("a setting")
	class Settings {

		@Test
		@DisplayName("a value outside the game's range is brought back inside it")
		void sane() {
			// Not fussiness: OptionInstance.set does not clamp, it substitutes the
			// option's default. A brightness of 1.5 would silently become 0.5.
			assertEquals(1.0, Setting.BRIGHTNESS.sane(1.5));
			assertEquals(0.0, Setting.BRIGHTNESS.sane(-3));
			assertEquals(32, Setting.RENDER_DISTANCE.sane(64));
			assertEquals(2, Setting.RENDER_DISTANCE.sane(0));
		}

		@Test
		@DisplayName("an entry clamps on the way in, wherever it came from")
		void entryClamps() {
			// Entries arrive off the wire, so this is the guard as well as the tidy-up.
			assertEquals(1.0, new MapStart.Entry(
				Setting.BRIGHTNESS, 9, Firmness.HELD, Bound.EXACT).value());
		}

		@Test
		@DisplayName("every setting is findable by the name it is stored under")
		void named() {
			for (Setting setting : Setting.values()) {
				assertEquals(setting, Setting.named(setting.getSerializedName()));
			}
			assertNull(Setting.named("gamma"));
		}

		@Test
		@DisplayName("the usual value is one the game would accept")
		void usualIsSane() {
			for (Setting setting : Setting.values()) {
				assertEquals(setting.usual(), setting.sane(setting.usual()), setting.name());
			}
		}
	}

	@Nested
	@DisplayName("the list of settings")
	class Listing {

		@Test
		@DisplayName("setting the same one twice replaces rather than adds")
		void replaces() {
			MapStart start = MapStart.NOTHING
				.with(new MapStart.Entry(Setting.BRIGHTNESS, 0.2, Firmness.HELD, Bound.EXACT))
				.with(new MapStart.Entry(Setting.BRIGHTNESS, 0.4, Firmness.HELD, Bound.EXACT));

			assertEquals(1, start.settings().size());
			assertEquals(0.4, start.entry(Setting.BRIGHTNESS).value());
		}

		@Test
		@DisplayName("order is kept when one in the middle changes")
		void keepsOrder() {
			// The panel draws them in list order. A replacement that moved its entry to
			// the end would make the row somebody is editing jump under the mouse.
			MapStart start = MapStart.NOTHING
				.with(MapStart.Entry.starting(Setting.RENDER_DISTANCE))
				.with(MapStart.Entry.starting(Setting.BRIGHTNESS))
				.with(MapStart.Entry.starting(Setting.FOV))
				.with(new MapStart.Entry(Setting.BRIGHTNESS, 0.1, Firmness.HELD, Bound.EXACT));

			assertEquals(List.of(Setting.RENDER_DISTANCE, Setting.BRIGHTNESS, Setting.FOV),
				start.settings().stream().map(MapStart.Entry::what).toList());
		}

		@Test
		@DisplayName("removing one leaves the rest")
		void removes() {
			MapStart start = MapStart.NOTHING
				.with(MapStart.Entry.starting(Setting.FOV))
				.with(MapStart.Entry.starting(Setting.BRIGHTNESS))
				.without(Setting.FOV);

			assertNull(start.entry(Setting.FOV));
			assertNotNull(start.entry(Setting.BRIGHTNESS));
		}

		@Test
		@DisplayName("only suggestions are worth asking about")
		void suggests() {
			assertFalse(MapStart.NOTHING
				.with(new MapStart.Entry(Setting.FOV, 70, Firmness.HELD, Bound.EXACT))
				.suggestsAnything());
			assertTrue(MapStart.NOTHING
				.with(new MapStart.Entry(Setting.FOV, 70, Firmness.SUGGESTED, Bound.EXACT))
				.suggestsAnything());
		}

		@Test
		@DisplayName("a suggestion changes nothing on arrival, the other two do")
		void applies() {
			assertFalse(Firmness.SUGGESTED.applies());
			assertTrue(Firmness.WHILE_HERE.applies());
			assertTrue(Firmness.HELD.applies());
		}

		@Test
		@DisplayName("a shader on its own is worth asking about")
		void shaderAlone() {
			// It is never applied without being asked, so a map recommending only a
			// shader still has something to say. Checking the settings alone left it
			// silent, which is the map saying nothing about the one thing it wanted.
			MapStart onlyShader = MapStart.NOTHING.withShader("BSL");
			assertFalse(onlyShader.suggestsAnything());
			assertTrue(onlyShader.worthAsking());

			assertFalse(MapStart.NOTHING
				.with(new MapStart.Entry(Setting.FOV, 70, Firmness.HELD, Bound.EXACT))
				.worthAsking());
		}
	}

	@Nested
	@DisplayName("what the panel will say out loud")
	class Words {

		/**
		 * Every name these enums build is a name somebody sees.
		 *
		 * A missing one does not fail to compile and does not throw: it draws the key
		 * itself, so the dropdown offers "npc_studio.start.bound.at_least" and the
		 * only way to find out is to open the panel and read it. Two languages
		 * because the second one is the one that gets forgotten.
		 */
		@Test
		@DisplayName("every setting, firmness and bound is translated in both languages")
		void translated() throws java.io.IOException {
			for (String language : List.of("ru_ru", "en_us")) {
				com.google.gson.JsonObject words = com.google.gson.JsonParser.parseString(
					java.nio.file.Files.readString(java.nio.file.Path.of(
						"src/main/resources/assets/npc_studio/lang/" + language + ".json"),
						java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();

				for (Setting setting : Setting.values()) {
					assertTrue(words.has(setting.key()),
						language + " has no name for " + setting.key());
				}
				for (Firmness firmness : Firmness.values()) {
					assertTrue(words.has(firmness.key()),
						language + " has no name for " + firmness.key());
				}
				for (Bound bound : Bound.values()) {
					assertTrue(words.has(bound.key()),
						language + " has no name for " + bound.key());
				}
			}
		}
	}

	@Nested
	@DisplayName("written down and read back")
	class Storage {

		@Test
		@DisplayName("a map survives the trip through its codec")
		void roundTrip() {
			// The same codec is both the world save and the wire, so this one test
			// covers a map handed to somebody as a folder and a map arriving on join.
			MapStart start = MapStart.NOTHING
				.with(new MapStart.Entry(Setting.BRIGHTNESS, 0.15, Firmness.HELD, Bound.EXACT))
				.with(new MapStart.Entry(Setting.RENDER_DISTANCE, 16, Firmness.WHILE_HERE, Bound.AT_LEAST))
				.withShader("BSL")
				.withPlayerSkin("a1b2c3");

			var written = MapStart.CODEC.encodeStart(JsonOps.INSTANCE, start)
				.getOrThrow(problem -> new AssertionError(problem));
			MapStart read = MapStart.CODEC.parse(JsonOps.INSTANCE, written)
				.getOrThrow(problem -> new AssertionError(problem));

			assertEquals(start, read);
		}

		@Test
		@DisplayName("an empty map is written and read as one")
		void emptyRoundTrip() {
			var written = MapStart.CODEC.encodeStart(JsonOps.INSTANCE, MapStart.NOTHING)
				.getOrThrow(problem -> new AssertionError(problem));
			MapStart read = MapStart.CODEC.parse(JsonOps.INSTANCE, written)
				.getOrThrow(problem -> new AssertionError(problem));

			assertEquals(MapStart.NOTHING, read);
		}

		@Test
		@DisplayName("a map written before it could dress anybody still reads, with nobody dressed")
		void olderMapsWearTheirOwnClothes() {
			// The promise every optional field makes, and here it is the one that would
			// be loudest to break: a map saved last week has no player_skin key, and
			// absent has to go on meaning "their own skin" rather than "no skin".
			var written = MapStart.CODEC.encodeStart(JsonOps.INSTANCE,
					new MapStart(List.of(), "BSL"))
				.getOrThrow(problem -> new AssertionError(problem));
			written.getAsJsonObject().remove("player_skin");

			MapStart read = MapStart.CODEC.parse(JsonOps.INSTANCE, written)
				.getOrThrow(problem -> new AssertionError(problem));
			assertEquals("", read.playerSkin());
			assertEquals("BSL", read.shaderPack(), "and nothing else moved");
		}

		@Test
		@DisplayName("changing one part of the start leaves the others alone")
		void thewithersCarryTheRest() {
			// Every wither here rebuilds the whole record by hand, which is the shape
			// that quietly drops a field the next time one is added — it did exactly
			// that to a line's timer, from eleven places. Three fields, three withers,
			// and each of them checked against the other two.
			MapStart start = MapStart.NOTHING
				.with(new MapStart.Entry(Setting.BRIGHTNESS, 0.15, Firmness.HELD, Bound.EXACT))
				.withShader("BSL")
				.withPlayerSkin("a1b2c3");

			assertEquals("a1b2c3", start.withShader("Complementary").playerSkin());
			assertEquals("BSL", start.withPlayerSkin("ffffff").shaderPack());
			assertEquals(1, start.withPlayerSkin("").settings().size());
			assertEquals("a1b2c3",
				start.with(new MapStart.Entry(Setting.RENDER_DISTANCE, 16,
					Firmness.WHILE_HERE, Bound.AT_LEAST)).playerSkin());
			assertEquals("a1b2c3", start.without(Setting.BRIGHTNESS).playerSkin());
		}
	}
}
