package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.Vec3i;

/**
 * Copies of the ground, and the two names each of them has.
 *
 * <h2>What is being protected</h2>
 *
 * A region has a name a person reads and a name the disk gets, and they are different
 * because an identifier's path may hold only {@code [a-z0-9_.-]} — so «поляна» cannot be
 * a file. Everything here is about those two not being confused for one another, because
 * the way that failure shows up is one region overwriting another and nothing saying so.
 *
 * <h2>What is not here</h2>
 *
 * Blocks. Filling a template from a world needs a world, and a test that built a fake one
 * would be a test of the fake. What can be checked without a world is the bookkeeping,
 * and the bookkeeping is where the silent mistakes are.
 */
class RegionTest {

	private static WorldRegions.Region region(String file, int x, int y, int z) {
		return new WorldRegions.Region(file, new Vec3i(x, y, z), List.of());
	}

	@Test
	@DisplayName("a region is found by the name a person gave it, in any letters")
	void foundByItsOwnName() {
		WorldRegions regions = new WorldRegions();
		assertTrue(regions.put("поляна", region("region1", 8, 4, 8)));
		assertEquals("region1", regions.at("поляна").file());
		assertNull(regions.at("region1"), "the file name is not a way of finding it");
		assertNull(regions.at(null));
	}

	@Test
	@DisplayName("the file name is counted, never derived from what a person typed")
	void fileNamesAreCountedAndUnique() {
		// The failure this rules out: two names that turn into the same letters — and
		// «поляна» and «Поляна» would — quietly becoming one file, so taking a copy of
		// the second build destroys the first.
		WorldRegions regions = new WorldRegions();
		String first = regions.freshFile();
		regions.put("поляна", region(first, 8, 4, 8));
		String second = regions.freshFile();
		assertNotEquals(first, second);
		regions.put("Поляна", region(second, 8, 4, 8));
		assertEquals(2, regions.count(), "two names are two regions");
		assertNotEquals(regions.at("поляна").file(), regions.at("Поляна").file());
	}

	@Test
	@DisplayName("every counted file name is one an identifier will accept")
	void countedNamesAreLegalPaths() {
		// The point of counting them instead of deriving them is that they are always
		// legal. Asserted rather than assumed, because Identifier throws on a bad path
		// and the throw would happen at capture, on somebody's build.
		WorldRegions regions = new WorldRegions();
		for (int n = 0; n < 5; n++) {
			String file = regions.freshFile();
			assertEquals("npc_studio", Regions.file(file).getNamespace());
			assertTrue(Regions.file(file).getPath().startsWith("region/"),
				Regions.file(file).toString());
			regions.put("место" + n, region(file, 1, 1, 1));
		}
	}

	@Test
	@DisplayName("taking a copy again replaces it rather than adding a second")
	void retakingReplaces() {
		// What somebody does after changing the build. Adding a second copy instead
		// would leave the old one laid out by everything already pointing at it, with
		// no sign that the new one exists.
		WorldRegions regions = new WorldRegions();
		regions.put("поляна", region("region1", 8, 4, 8));
		regions.put("поляна", region("region1", 16, 8, 16));
		assertEquals(1, regions.count());
		assertEquals(new Vec3i(16, 8, 16), regions.at("поляна").size());
	}

	@Test
	@DisplayName("a full world can still correct the copies it already has")
	void theCeilingDoesNotBlockReplacement() {
		// The same trap the named places have, and it only shows up on a map somebody
		// has spent a long time on: a ceiling that turns a correction into a refusal
		// makes a full map one whose existing regions can never be retaken.
		WorldRegions regions = new WorldRegions();
		for (int n = 0; n < WorldRegions.MOST; n++) {
			assertTrue(regions.put("место" + n, region("region" + n, 1, 1, 1)));
		}
		assertFalse(regions.put("ещё одно", region("regionX", 1, 1, 1)),
			"a full world takes no more");
		assertTrue(regions.put("место0", region("region0", 2, 2, 2)),
			"but the ones it has can still be corrected");
		assertEquals(WorldRegions.MOST, regions.count());
	}

	@Test
	@DisplayName("how many blocks a region comes to is counted wide enough not to wrap")
	void theVolumeDoesNotOverflow() {
		// A region 2000 blocks on a side is more than an int holds, and an overflowed
		// volume comes out negative — which reads as "well under the ceiling" and lays
		// the whole thing down.
		WorldRegions.Region wide = region("region1", 2000, 320, 2000);
		assertEquals(1_280_000_000L, wide.blocks());
		assertTrue(wide.blocks() > Integer.MAX_VALUE / 2, "the point of counting it long");
	}

	@Test
	@DisplayName("a nameless region is refused before anything is written")
	void namesAreCheckedFirst() {
		assertFalse(Regions.nameable(null));
		assertFalse(Regions.nameable(""));
		assertFalse(Regions.nameable("   "));
		assertFalse(Regions.nameable("п".repeat(WorldRegions.LONGEST + 1)));
		assertTrue(Regions.nameable("поляна"));
		assertTrue(Regions.nameable("п".repeat(WorldRegions.LONGEST)));
	}

	@Test
	@DisplayName("a region survives the file, documents and all")
	void throughTheFile() {
		// The documents field is empty today and written into the format anyway: a field
		// added to a file later is the expensive kind of change, and an empty list is
		// the cheap kind.
		WorldRegions.Region was = new WorldRegions.Region("region1", new Vec3i(8, 4, 8),
			List.of("урок1", "урок2"));
		WorldRegions.Region back = WorldRegions.Region.CODEC
			.parse(JsonOps.INSTANCE, WorldRegions.Region.CODEC
				.encodeStart(JsonOps.INSTANCE, was).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(was, back);
		assertEquals(List.of("урок1", "урок2"), back.documents());
	}

	@Test
	@DisplayName("a whole world of regions survives the file with its order")
	void theStoreSurvivesTheFile() {
		WorldRegions regions = new WorldRegions();
		regions.put("поляна", region("region1", 8, 4, 8));
		regions.put("пещера", region("region2", 16, 8, 16));
		WorldRegions back = WorldRegions.CODEC
			.parse(JsonOps.INSTANCE, WorldRegions.CODEC
				.encodeStart(JsonOps.INSTANCE, regions).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(List.of("поляна", "пещера"), back.names(),
			"the order they were taken in is the order the list shows");
		assertEquals("region2", back.at("пещера").file());
	}
}
