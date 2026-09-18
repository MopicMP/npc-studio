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
 * Which file belongs to which plugin or mod.
 *
 * The claim being tested is a modest one, and that is the point: a plugin's own
 * folder is close to a guarantee and is trusted, a mod's file name is a
 * convention and is matched carefully, and anything that fits neither is put
 * under "the rest" rather than guessed at. A file filed under the wrong plugin is
 * worse than a file filed under nothing, because the wrong heading is a claim
 * somebody will act on.
 */
class ConfigsTest {

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "paper";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static Addon plugin(String file, String name) {
		return new Addon(file, name.toLowerCase(java.util.Locale.ROOT), name, "1.0",
			Addon.Kind.PLUGIN, Addon.Side.SERVER_ONLY, List.of(), true);
	}

	private static Addon mod(String file, String id, String name) {
		return new Addon(file, id, name, "1.0", Addon.Kind.MOD, Addon.Side.BOTH, List.of(), true);
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	@Test
	@DisplayName("A plugin's folder is its own, and the core's files stand apart")
	void pluginFolders(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder);
		write(folder.resolve("bukkit.yml"), "settings: {}\n");
		write(folder.resolve("spigot.yml"), "settings: {}\n");
		write(folder.resolve("config").resolve("paper-global.yml"), "_version: 31\n");
		write(folder.resolve("plugins").resolve("Chunky").resolve("config.yml"), "a: 1\n");
		write(folder.resolve("plugins").resolve("Chunky").resolve("tasks.yml"), "b: 2\n");
		write(folder.resolve("plugins").resolve("Nobody").resolve("config.yml"), "c: 3\n");

		List<Configs.Group> groups = Configs.of(server,
			List.of(plugin("Chunky-Bukkit-1.5.3.jar", "Chunky")));

		Configs.Group core = groups.getFirst();
		assertEquals("core", core.id());
		assertEquals(List.of("bukkit.yml", "config/paper-global.yml", "spigot.yml"),
			core.files().stream().map(Configs.Entry::name).sorted().toList());

		Configs.Group chunky = one(groups, "Chunky-Bukkit-1.5.3.jar");
		assertEquals(2, chunky.files().size());
		assertEquals("Chunky", chunky.name());

		// A folder belonging to no installed plugin is still shown, under the name
		// of the folder: the plugin may have been removed and its settings kept,
		// and hiding them would be hiding somebody's work.
		Configs.Group orphan = one(groups, "Nobody");
		assertEquals(1, orphan.files().size());
	}

	@Test
	@DisplayName("A mod's config is matched by its id, longest first")
	void modConfigsByName(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder);
		write(folder.resolve("config").resolve("lithium.properties"), "# a\nx = 1\n");
		write(folder.resolve("config").resolve("ferritecore.mixin.properties"), "# b\ny = 2\n");
		write(folder.resolve("config").resolve("sodium-extra.json"), "{}\n");
		write(folder.resolve("config").resolve("nobody-at-all.toml"), "z = 3\n");

		List<Configs.Group> groups = Configs.of(server, List.of(
			mod("lithium.jar", "lithium", "Lithium"),
			mod("ferritecore.jar", "ferritecore", "FerriteCore"),
			mod("sodium.jar", "sodium", "Sodium"),
			mod("sodium-extra.jar", "sodium-extra", "Sodium Extra")));

		assertEquals("config/lithium.properties",
			one(groups, "lithium.jar").files().getFirst().name());
		// The suffix after the id is part of the convention: ferritecore writes
		// ferritecore.mixin.properties and means its own.
		assertEquals("config/ferritecore.mixin.properties",
			one(groups, "ferritecore.jar").files().getFirst().name());
		// And the longer id wins, or Sodium Extra's file would be handed to Sodium.
		assertEquals("config/sodium-extra.json",
			one(groups, "sodium-extra.jar").files().getFirst().name());
		// Sodium is still listed — every installed thing is — but with nothing under
		// it, because the file that could have been mistaken for its own is not.
		assertTrue(has(groups, "sodium.jar"));
		assertTrue(one(groups, "sodium.jar").files().isEmpty());

		// What nothing claims goes under the rest, where it can still be found.
		Configs.Group rest = one(groups, "rest");
		assertEquals(List.of("config/nobody-at-all.toml"),
			rest.files().stream().map(Configs.Entry::name).toList());
	}

	@Test
	@DisplayName("Data files and the server's own properties are left out")
	void leavesOutWhatIsNotSettings(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder);
		// The one file with a tab of its own. Two editors for one file is two
		// answers to one question.
		write(folder.resolve("server.properties"), "motd=hi\n");
		write(folder.resolve("plugins").resolve("spark").resolve("config.yml"), "a: 1\n");
		write(folder.resolve("plugins").resolve("spark").resolve("data.db"), "binary");
		write(folder.resolve("plugins").resolve("spark").resolve("logs").resolve("x.log"), "log");

		List<Configs.Group> groups = Configs.of(server, List.of());
		List<String> all = groups.stream().flatMap(group -> group.files().stream())
			.map(Configs.Entry::name).toList();
		assertEquals(List.of("plugins/spark/config.yml"), all);
	}

	/**
	 * One folder that cannot be read must not cost the rest of the list.
	 *
	 * This is why the walk is a visitor and not a stream: the stream form throws in
	 * the middle of the iteration, so a plugin keeping a locked file or an
	 * unreadable folder beside its settings took every file after it out of the
	 * list — silently, and the symptom is a plugin whose configs are simply not
	 * there.
	 */
	@Test
	@DisplayName("A folder that will not open does not take the rest of the list with it")
	void oneBadFolder(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder);
		write(folder.resolve("plugins").resolve("Aaa").resolve("config.yml"), "a: 1\n");
		write(folder.resolve("plugins").resolve("Geyser-Spigot").resolve("config.yml"), "b: 2\n");
		write(folder.resolve("plugins").resolve("Zzz").resolve("config.yml"), "c: 3\n");
		// A path that walks as a folder and will not list: a file where a folder is
		// expected does exactly that, on every system, without needing permissions
		// this test cannot ask for.
		Path pretend = folder.resolve("plugins").resolve("Mmm");
		Files.createDirectories(pretend);
		Files.writeString(pretend.resolve("nested"), "not a folder");

		List<String> all = Configs.of(server, List.of()).stream()
			.flatMap(group -> group.files().stream()).map(Configs.Entry::name).sorted().toList();
		assertTrue(all.contains("plugins/Aaa/config.yml"), all.toString());
		assertTrue(all.contains("plugins/Geyser-Spigot/config.yml"), all.toString());
		assertTrue(all.contains("plugins/Zzz/config.yml"), all.toString());
	}

	/**
	 * A plugin that has never run is named, with nothing under it.
	 *
	 * The case that sent me looking: Geyser, LuckPerms and SkinsRestorer sitting in
	 * a plugins folder whose server had not been started since they were put there.
	 * A plugin writes its folder on its first run, so there was nothing on disk —
	 * and a list that simply left them out looked like a list that had lost them.
	 */
	@Test
	@DisplayName("A plugin with no files yet is still listed, so its absence can be explained")
	void pluginsWithNothingYet(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder);
		write(folder.resolve("plugins").resolve("spark").resolve("config.json"), "{}\n");
		Files.createDirectories(folder.resolve("plugins"));
		Files.writeString(folder.resolve("plugins").resolve("Geyser-Spigot.jar"), "jar");

		List<Configs.Group> groups = Configs.of(server, List.of(
			plugin("Geyser-Spigot.jar", "Geyser-Spigot"),
			plugin("spark.jar", "spark")));

		Configs.Group geyser = one(groups, "Geyser-Spigot.jar");
		assertEquals("Geyser-Spigot", geyser.name());
		assertTrue(geyser.files().isEmpty());
		assertEquals(1, one(groups, "spark.jar").files().size());
	}

	private static boolean has(List<Configs.Group> groups, String id) {
		return groups.stream().anyMatch(group -> group.id().equals(id));
	}

	private static Configs.Group one(List<Configs.Group> groups, String id) {
		for (Configs.Group group : groups) {
			if (group.id().equals(id)) return group;
		}
		assertTrue(false, "no group " + id + " in "
			+ groups.stream().map(Configs.Group::id).toList());
		return null;
	}
}
