package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the saves a server has, on the two arrangements that exist.
 *
 * The thing worth guarding is the word: a world is a whole save, a dimension is
 * one part of one, and the two cores put the parts in different places. Vanilla
 * keeps them inside one folder; Paper puts them in folders beside each other. So
 * the same three dimensions are one row on both, and getting that wrong tells
 * somebody they have three worlds when they have one.
 */
class WorldsTest {

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static void save(Path root, String name, String... insideFolders)
		throws IOException {
		Path folder = root.resolve(name);
		Files.createDirectories(folder);
		Files.writeString(folder.resolve("level.dat"), "x");
		for (String inside : insideFolders) {
			Files.createDirectories(folder.resolve(inside));
			Files.writeString(folder.resolve(inside).resolve("chunk"), "y");
		}
	}

	@Test
	@DisplayName("On Paper, three folders beside each other are one world")
	void paperSplitsThem(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world");
		save(root, "world_nether");
		save(root, "world_the_end");

		List<Worlds.World> found = Worlds.found(server);

		assertEquals(1, found.size(), found.toString());
		Worlds.World world = found.getFirst();
		assertEquals("world", world.name());
		assertEquals(3, world.folders().size());
		// No region folder anywhere in these, so the only dimensions are the two
		// folders beside it — named by what they are, not by where they sit.
		assertEquals(List.of("minecraft:the_nether", "minecraft:the_end"),
			world.dimensions().stream().map(Worlds.Dimension::name).toList());
	}

	@Test
	@DisplayName("On vanilla and Fabric, the same three live inside one folder")
	void vanillaKeepsThemInside(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world", "region", "DIM-1", "DIM1");
		Files.createDirectories(root.resolve("world/dimensions/mymod/moon"));
		Files.writeString(root.resolve("world/dimensions/mymod/moon/chunk"), "z");

		Worlds.World world = Worlds.found(server).getFirst();

		assertEquals(1, world.folders().size());
		assertEquals(List.of("minecraft:overworld", "minecraft:the_nether",
			"minecraft:the_end", "mymod:moon"),
			world.dimensions().stream().map(Worlds.Dimension::name).toList());
	}

	@Test
	@DisplayName("A dimension keeps its chunks a folder deeper on Paper than on vanilla")
	void whereTheChunksAre(@TempDir Path folder) throws IOException {
		Path vanilla = folder.resolve("world/DIM-1");
		Files.createDirectories(vanilla.resolve("region"));
		assertEquals(vanilla, Worlds.chunks(vanilla));

		// Bukkit gives the nether a save folder of its own and then puts the
		// chunks in DIM-1 inside it.
		Path paper = folder.resolve("world_nether");
		Files.createDirectories(paper.resolve("DIM-1/region"));
		assertEquals(paper.resolve("DIM-1"), Worlds.chunks(paper));
	}

	@Test
	@DisplayName("Replacing a dimension changes its chunks and nothing that belongs to the world")
	void replaceKeepsTheWorld(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world", "region", "DIM-1");
		Files.createDirectories(root.resolve("world/DIM-1/region"));
		Files.writeString(root.resolve("world/DIM-1/region/r.0.0.mca"), "old");
		Files.writeString(root.resolve("world/playerdata"), "mine");

		Path elsewhere = folder.resolve("somebody-elses-nether");
		Files.createDirectories(elsewhere.resolve("region"));
		Files.writeString(elsewhere.resolve("region/r.0.0.mca"), "new");

		Worlds.World world = Worlds.found(server).getFirst();
		Worlds.Dimension nether = world.dimensions().stream()
			.filter(each -> each.name().equals(Worlds.NETHER)).findFirst().orElseThrow();
		Worlds.replace(nether, elsewhere);

		assertEquals("new", Files.readString(root.resolve("world/DIM-1/region/r.0.0.mca")));
		// What makes it this world is untouched: the same seed, the same people.
		assertTrue(Files.isRegularFile(root.resolve("world/level.dat")));
		assertEquals("mine", Files.readString(root.resolve("world/playerdata")));
	}

	@Test
	@DisplayName("Replacing the overworld leaves level.dat where it is")
	void replaceOverworld(@TempDir Path folder) throws IOException {
		// The overworld is the world folder, so a replacement that swapped folders
		// would take the seed, the spawn and everybody's inventory with it.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world", "region");
		Files.writeString(root.resolve("world/level.dat"), "seed and spawn");

		Path elsewhere = folder.resolve("downloaded-map");
		Files.createDirectories(elsewhere.resolve("region"));
		Files.writeString(elsewhere.resolve("region/r.0.0.mca"), "new");

		Worlds.Dimension overworld = Worlds.found(server).getFirst().dimensions().getFirst();
		assertTrue(overworld.overworld());
		Worlds.replace(overworld, elsewhere);

		assertEquals("new", Files.readString(root.resolve("world/region/r.0.0.mca")));
		assertEquals("seed and spawn", Files.readString(root.resolve("world/level.dat")));
	}

	@Test
	@DisplayName("A folder with no chunks in it is refused before anything is deleted")
	void refusesAFolderThatIsNotADimension(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world", "region");
		Files.writeString(root.resolve("world/region/r.0.0.mca"), "mine");

		Path pictures = folder.resolve("holiday");
		Files.createDirectories(pictures);
		Files.writeString(pictures.resolve("beach.png"), "not a world");

		Worlds.Dimension overworld = Worlds.found(server).getFirst().dimensions().getFirst();
		IOException refused = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
			() -> Worlds.replace(overworld, pictures));

		assertTrue(refused.getMessage().contains("region"), refused.getMessage());
		assertEquals("mine", Files.readString(root.resolve("world/region/r.0.0.mca")));
	}

	@Test
	@DisplayName("The world being played comes first, however small it is")
	void currentFirst(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "big", "region");
		Files.write(root.resolve("big/region/r.0.0.mca"), new byte[4096]);
		save(root, "tiny");
		PropertiesFile.parse("level-name=tiny\n").write(server.propertiesPath());

		assertEquals(List.of("tiny", "big"), Worlds.found(server).stream()
			.map(Worlds.World::name).toList());
	}

	@Test
	@DisplayName("A dimension found twice is one dimension, and an absent one is not invented")
	void oneEntryPerDimension(@TempDir Path folder) throws IOException {
		// A real save, sent in: its chunks all live under dimensions/, including
		// the overworld's, and the nether is in both places at once. The list
		// showed an invented overworld of 0 B above the real one.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world", "DIM-1");
		Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld/region"));
		Files.writeString(root.resolve("world/dimensions/minecraft/overworld/region/r.mca"), "o");
		Files.createDirectories(root.resolve("world/dimensions/minecraft/the_nether"));

		List<String> parts = Worlds.found(server).getFirst().dimensions().stream()
			.map(Worlds.Dimension::name).toList();

		assertEquals(List.of("minecraft:the_nether", "minecraft:overworld"), parts);
		// DIM-1 came first and is where the chunks have always been, so that is
		// the one a replacement would land in.
		assertEquals(root.resolve("world/DIM-1"), Worlds.found(server).getFirst()
			.dimensions().getFirst().folder());
	}

	@Test
	@DisplayName("Resetting a dimension throws its chunks away and leaves the world alone")
	void resetGivesItBack(@TempDir Path folder) throws IOException {
		// There is no making a dimension: the server generates one the first time
		// somebody walks into it. So making it again is deleting the chunks.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world", "region", "DIM-1");
		Files.createDirectories(root.resolve("world/DIM-1/region"));
		Files.writeString(root.resolve("world/DIM-1/region/r.0.0.mca"), "walked");
		Files.writeString(root.resolve("world/level.dat"), "seed and spawn");

		Worlds.Dimension nether = Worlds.found(server).getFirst().dimensions().stream()
			.filter(each -> each.name().equals(Worlds.NETHER)).findFirst().orElseThrow();
		Worlds.reset(nether);

		assertFalse(Files.exists(root.resolve("world/DIM-1/region")));
		assertEquals("seed and spawn", Files.readString(root.resolve("world/level.dat")));
		assertTrue(Files.isDirectory(root.resolve("world/region")));
	}

	@Test
	@DisplayName("A folder without level.dat is not a save, whatever it is called")
	void needsALevelDat(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "world");
		Files.createDirectories(root.resolve("backups"));
		Files.createDirectories(root.resolve("mods"));
		Files.createDirectories(root.resolve("world_nether_backup_old"));

		assertEquals(List.of("world"), Worlds.found(server).stream()
			.map(Worlds.World::name).toList());
	}

	@Test
	@DisplayName("A world of its own that happens to end in _nether stays its own")
	void suffixWithoutAParent(@TempDir Path folder) throws IOException {
		// The grouping rule is "a folder whose name is another folder's name plus
		// a suffix". Without the other folder there is nothing to group with, and
		// a save called this is somebody's save.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		save(root, "spooky_nether");

		assertEquals(List.of("spooky_nether"), Worlds.found(server).stream()
			.map(Worlds.World::name).toList());
	}

	@Test
	@DisplayName("Which world is current comes from the properties file, not from a guess")
	void current(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		assertEquals("world", Worlds.currentName(server));

		PropertiesFile properties = PropertiesFile.parse("level-name=another\nmotd=hi\n");
		properties.write(server.propertiesPath());
		assertEquals("another", Worlds.currentName(server));
	}

	@Test
	@DisplayName("A name that goes into a path is checked before it gets there")
	void names() {
		// It is typed into a field, written into server.properties and then
		// resolved by the server as a folder — which is the shape the security
		// notes are about.
		assertTrue(Worlds.validName("world"));
		assertTrue(Worlds.validName("my-world_2"));
		assertFalse(Worlds.validName("../escape"));
		assertFalse(Worlds.validName(".hidden"));
		assertFalse(Worlds.validName("a/b"));
		assertFalse(Worlds.validName(""));
		assertFalse(Worlds.validName(null));
	}
}
