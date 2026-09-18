package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing about a flat world that is easy to get backwards.
 *
 * The game lists its layers from the top down and the file wants them from the
 * bottom up. Getting that wrong makes a world that generates perfectly well, with
 * the bedrock in the sky — and nobody finds out until they are standing in it.
 */
class FlatTest {

	@Test
	@DisplayName("Layers are written bottom upwards, however they were listed")
	void bedrockGoesAtTheBottom() {
		String settings = Flat.settings(Flat.preset("classic_flat"));

		assertEquals("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
			+ "{\"block\":\"minecraft:dirt\",\"height\":2},"
			+ "{\"block\":\"minecraft:grass_block\",\"height\":1}],"
			+ "\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}", settings);
	}

	@Test
	@DisplayName("The void is one layer of air, which is what a builder's world is")
	void theVoid() {
		String settings = Flat.settings(Flat.preset("the_void"));

		assertTrue(settings.contains("{\"block\":\"minecraft:air\",\"height\":1}"), settings);
		assertTrue(settings.contains("\"biome\":\"minecraft:the_void\""), settings);
	}

	@Test
	@DisplayName("What somebody types is read bottom upwards, as everybody writes it")
	void written() {
		String settings = Flat.fromWritten("minecraft:bedrock,2*dirt,grass_block", null);

		assertEquals("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
			+ "{\"block\":\"minecraft:dirt\",\"height\":2},"
			+ "{\"block\":\"minecraft:grass_block\",\"height\":1}],"
			+ "\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}", settings);
	}

	@Test
	@DisplayName("Nonsense in the field is refused rather than written into the file")
	void refusals() {
		// It goes into server.properties and from there into the core's parser: a
		// bad value there is a server that will not start, which is a much worse
		// way to find out than a button that stays grey.
		assertThrows(IllegalArgumentException.class, () -> Flat.fromWritten("", null));
		assertThrows(IllegalArgumentException.class, () -> Flat.fromWritten("0*stone", null));
		assertThrows(IllegalArgumentException.class,
			() -> Flat.fromWritten("many*stone", null));
		assertThrows(IllegalArgumentException.class,
			() -> Flat.fromWritten("99999*stone", null));
		assertThrows(IllegalArgumentException.class,
			() -> Flat.fromWritten("minecraft:stone\nmotd=owned", null));
	}

	@Test
	@DisplayName("Nothing written ever holds a newline, because the file is line by line")
	void oneLine() {
		for (Flat.Preset each : Flat.PRESETS) {
			String settings = Flat.settings(each);
			assertTrue(settings.indexOf('\n') < 0, each.id());
			assertTrue(settings.indexOf('\r') < 0, each.id());
		}
	}
}
