package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * The datapack that gives a server a builder's world.
 *
 * Two things here are worth a test rather than a careful read. Everything written
 * ends up in files the server runs as itself, so a name from a text field is the
 * one piece of somebody else's text in this mod that becomes code. And the pack's
 * format number decides whether the whole thing loads at all — silently, with one
 * line in a log.
 */
class DimensionsTest {

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static Dimensions.Place place(Dimensions.Sharing sharing, Dimensions.Return back,
			String... who) {
		return new Dimensions.Place("builder", "Стройка", "the_void", "", sharing, back,
			List.of(who), new int[] {8, 64, 8});
	}

	@Test
	@DisplayName("The pack has a dimension, a way in, a way out and the tags that run them")
	void writesTheWholePack(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);

		Dimensions.write(server, "world",
			List.of(place(Dimensions.Sharing.SHARED, Dimensions.Return.EXIT, "Steve")));

		Path pack = root.resolve("world/datapacks/npc_studio");
		assertTrue(Files.isRegularFile(pack.resolve("pack.mcmeta")));
		assertTrue(Files.isRegularFile(
			pack.resolve("data/npc_studio/dimension/builder.json")));
		assertTrue(Files.isRegularFile(
			pack.resolve("data/npc_studio/function/go_builder.mcfunction")));
		assertTrue(Files.isRegularFile(
			pack.resolve("data/npc_studio/function/back.mcfunction")));
		// The tags are what make a function run at all; without them the pack
		// loads and does nothing, which is the hardest kind of nothing to debug.
		assertTrue(Files.isRegularFile(
			pack.resolve("data/minecraft/tags/function/tick.json")));
		assertTrue(Files.isRegularFile(
			pack.resolve("data/minecraft/tags/function/load.json")));

		String dimension = Files.readString(
			pack.resolve("data/npc_studio/dimension/builder.json"));
		assertTrue(dimension.contains("\"minecraft:flat\""), dimension);
		assertTrue(dimension.contains("minecraft:air"), dimension);
	}

	@Test
	@DisplayName("Only the named may go, and the list is rewritten from nothing each time")
	void permissionsComeFromTheList(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);

		Dimensions.write(server, "world", List.of(
			place(Dimensions.Sharing.SHARED, Dimensions.Return.EXIT, "Steve", "Ann")));
		String load = Files.readString(root.resolve(
			"world/datapacks/npc_studio/data/npc_studio/function/load.mcfunction"));

		assertTrue(load.contains("scoreboard players reset * npcok"), load);
		assertTrue(load.contains("scoreboard players set Steve npcok 1"), load);
		assertTrue(load.contains("scoreboard players set Ann npcok 1"), load);

		Dimensions.write(server, "world", List.of(
			place(Dimensions.Sharing.SHARED, Dimensions.Return.EXIT, "Ann")));
		String again = Files.readString(root.resolve(
			"world/datapacks/npc_studio/data/npc_studio/function/load.mcfunction"));

		// Taken off the list in the window is taken off the server, not left
		// behind in a file nobody looks at.
		assertFalse(again.contains("Steve"), again);
	}

	@Test
	@DisplayName("A name that is not a name never reaches a command file")
	void namesAreCheckedBeforeTheyBecomeCommands(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);

		// The one place in this mod where somebody else's text becomes code: a
		// newline here would be a player writing their own commands into a file
		// the server runs as itself, every tick, at level four.
		assertThrows(IOException.class, () -> Dimensions.write(server, "world",
			List.of(place(Dimensions.Sharing.SHARED, Dimensions.Return.EXIT,
				"Steve\nscoreboard players set Steve npcok 1"))));
		assertThrows(IOException.class, () -> Dimensions.write(server, "world",
			List.of(place(Dimensions.Sharing.SHARED, Dimensions.Return.EXIT, "@a"))));
		assertThrows(IOException.class, () -> Dimensions.write(server, "world",
			List.of(new Dimensions.Place("../../escape", "x", "the_void", "",
				Dimensions.Sharing.SHARED, Dimensions.Return.EXIT, List.of(),
				new int[] {0, 0, 0}))));

		assertFalse(Files.exists(root.resolve("world/datapacks/npc_studio/data")),
			"nothing is written until every name in the list has been checked");
	}

	@Test
	@DisplayName("Plots put each builder somewhere of their own")
	void plots(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);

		Dimensions.write(server, "world", List.of(
			place(Dimensions.Sharing.PLOTS, Dimensions.Return.EXIT, "Steve", "Ann")));
		String go = Files.readString(root.resolve(
			"world/datapacks/npc_studio/data/npc_studio/function/go_builder.mcfunction"));

		assertTrue(go.contains("[name=Steve] in npc_studio:builder run tp @s 0 100 0"), go);
		assertTrue(go.contains("[name=Ann] in npc_studio:builder run tp @s 1024 100 0"), go);
	}

	@Test
	@DisplayName("Each place has its own way out, so two of them cannot answer for each other")
	void everyPlaceReturnsItsOwnWay(@TempDir Path folder) throws IOException {
		// Found by reading the generated files: one function did every place's
		// return in turn, so with a "where you left" world and a "fixed point"
		// world, everybody came out of the first one standing in the second.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);

		Dimensions.write(server, "world", List.of(
			new Dimensions.Place("builder", "b", "the_void", "", Dimensions.Sharing.SHARED,
				Dimensions.Return.EXIT, List.of("Steve"), new int[] {0, 100, 0}),
			new Dimensions.Place("arena", "a", "classic_flat", "", Dimensions.Sharing.SHARED,
				Dimensions.Return.POINT, List.of("Steve"), new int[] {12, 70, -30})));

		Path functions = root.resolve("world/datapacks/npc_studio/data/npc_studio/function");
		String back = Files.readString(functions.resolve("back.mcfunction"));
		assertTrue(back.contains("if score @s npcin matches 1 run function npc_studio:back_builder"),
			back);
		assertTrue(back.contains("if score @s npcin matches 2 run function npc_studio:back_arena"),
			back);

		String fromBuilder = Files.readString(functions.resolve("back_builder.mcfunction"));
		assertTrue(fromBuilder.contains("back_overworld with storage"), fromBuilder);
		assertFalse(fromBuilder.contains("12 70 -30"), fromBuilder);

		String fromArena = Files.readString(functions.resolve("back_arena.mcfunction"));
		assertTrue(fromArena.contains("tp @s 12 70 -30"), fromArena);
		assertFalse(fromArena.contains("storage"), fromArena);

		// And the name on two lists is one line, not two.
		String load = Files.readString(functions.resolve("load.mcfunction"));
		assertEquals(1, load.lines().filter(each -> each.contains("Steve")).count(), load);
	}

	@Test
	@DisplayName("With nobody named, everybody may go — and no line can fail on an empty server")
	void emptyListMeansEveryone(@TempDir Path folder) throws IOException {
		// It meant nobody at first, which is the stricter reading and the wrong
		// one: the command answered "you cannot trigger this yet" to the person
		// who had just made the world.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);

		Dimensions.write(server, "world", List.of(place(Dimensions.Sharing.PLOTS,
			Dimensions.Return.EXIT)));
		String tick = Files.readString(root.resolve(
			"world/datapacks/npc_studio/data/npc_studio/function/tick.mcfunction"));

		assertTrue(tick.contains("execute as @a run scoreboard players enable @s npcgo"), tick);
		// And every line is an "execute as", because a selector that matches
		// nobody is a failure, and this file runs twenty times a second on a
		// server that is empty most of the night.
		for (String each : tick.lines().filter(line -> !line.startsWith("#")).toList()) {
			assertTrue(each.startsWith("execute as "), each);
		}

		// The way back is reset only for whoever used it. Resetting a trigger's
		// score throws away the unlocking too, and these functions run before the
		// server reads what people typed — so an unconditional reset locked the
		// command again every tick, and it could never be pressed.
		assertTrue(tick.contains(
			"execute as @a[scores={npcback=1..}] run scoreboard players reset @s npcback"), tick);
		assertFalse(tick.contains("execute as @a run scoreboard players reset @s npcback"), tick);

		// Plots are cut from the list of names; with no names, one place for all.
		String go = Files.readString(root.resolve(
			"world/datapacks/npc_studio/data/npc_studio/function/go_builder.mcfunction"));
		assertTrue(go.contains("execute in npc_studio:builder run tp @s 0 100 0"), go);
	}

	@Test
	@DisplayName("The pack format is read from the server's own jar, not from our version")
	void formatComesFromTheJar(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		server.jar = "core.jar";
		try (ZipOutputStream zip = new ZipOutputStream(
				Files.newOutputStream(root.resolve("core.jar")))) {
			zip.putNextEntry(new ZipEntry("version.json"));
			zip.write(("{\"id\":\"1.21.4\",\"pack_version\":{\"data_major\":61,"
				+ "\"data_minor\":0}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		assertEquals(61, Dimensions.format(server)[0]);
		assertTrue(Dimensions.meta(server).contains("\"pack_format\": 61"),
			Dimensions.meta(server));
	}

	@Test
	@DisplayName("What was written can be read back, so the window shows what is there")
	void readBack(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Dimensions.Place made = place(Dimensions.Sharing.PLOTS, Dimensions.Return.POINT, "Ann");

		Dimensions.write(server, "world", List.of(made));
		List<Dimensions.Place> read = Dimensions.read(server, "world");

		assertEquals(1, read.size());
		assertEquals("builder", read.getFirst().id());
		assertEquals("Стройка", read.getFirst().name());
		assertEquals(Dimensions.Sharing.PLOTS, read.getFirst().sharing());
		assertEquals(Dimensions.Return.POINT, read.getFirst().back());
		assertEquals(List.of("Ann"), read.getFirst().who());
		assertEquals(64, read.getFirst().point()[1]);
	}
}
