package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a server can be told to generate, which depends on what is installed.
 *
 * The fixed list of vanilla types was right for a vanilla server and wrong for
 * every other one: a mod's world type is a file in the mod's own jar, so the
 * types a server has are a fact about its mods folder.
 */
class WorldTypesTest {

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static void jar(Path file, String... entries) throws IOException {
		Files.createDirectories(file.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			for (String each : entries) {
				zip.putNextEntry(new ZipEntry(each));
				zip.write(new byte[] {'{', '}'});
				zip.closeEntry();
			}
		}
	}

	@Test
	@DisplayName("A mod's world type is found in the mod's own jar")
	void fromAMod(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		jar(root.resolve("mods/biomesoplenty.jar"),
			"fabric.mod.json",
			"data/biomesoplenty/worldgen/world_preset/biomesoplenty.json",
			"data/biomesoplenty/worldgen/biome/lavender_field.json");

		List<String> types = WorldTypes.of(server);

		assertTrue(types.contains("biomesoplenty:biomesoplenty"), types.toString());
		assertEquals(List.of("biomesoplenty:biomesoplenty"), WorldTypes.added(types));
		// And the game's own are still first, because they are what most servers use.
		assertEquals("minecraft:normal", types.getFirst());
	}

	@Test
	@DisplayName("A datapack in the world counts too, zipped or unpacked")
	void fromADatapack(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		PropertiesFile.parse("level-name=world\n").write(server.propertiesPath());
		Files.createDirectories(
			root.resolve("world/datapacks/open/data/mypack/worldgen/world_preset"));
		Files.writeString(root.resolve(
			"world/datapacks/open/data/mypack/worldgen/world_preset/islands.json"), "{}");
		jar(root.resolve("world/datapacks/zipped.zip"),
			"data/zippack/worldgen/world_preset/caves.json");

		List<String> types = WorldTypes.of(server);

		assertTrue(types.contains("mypack:islands"), types.toString());
		assertTrue(types.contains("zippack:caves"), types.toString());
	}

	@Test
	@DisplayName("Only paths that really are presets become types")
	void onlyPresets() {
		assertEquals("mymod:sky",
			WorldTypes.fromPath("data/mymod/worldgen/world_preset/sky.json"));
		assertEquals("mymod:deep/sky",
			WorldTypes.fromPath("data/mymod/worldgen/world_preset/deep/sky.json"));
		assertNull(WorldTypes.fromPath("data/mymod/worldgen/biome/sky.json"));
		assertNull(WorldTypes.fromPath("assets/mymod/worldgen/world_preset/sky.json"));
		assertNull(WorldTypes.fromPath("data/mymod/worldgen/world_preset/sky.txt"));
		// A name from inside somebody else's archive goes into server.properties,
		// so it is held to the same shape as everything else that gets written.
		assertNull(WorldTypes.fromPath("data/../../etc/worldgen/world_preset/sky.json"));
		assertNull(WorldTypes.fromPath("data/Mod Name/worldgen/world_preset/sky.json"));
	}

	@Test
	@DisplayName("A jar that is not a jar leaves the list alone")
	void brokenJar(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Files.createDirectories(root.resolve("mods"));
		Files.writeString(root.resolve("mods/half-downloaded.jar"), "not a zip at all");

		List<String> types = WorldTypes.of(server);

		assertEquals(WorldTypes.VANILLA, types);
		assertFalse(types.isEmpty());
	}
}
