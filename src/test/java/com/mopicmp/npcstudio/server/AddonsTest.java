package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading what is installed on a server out of the jars themselves.
 *
 * The field worth all of this is where a mod is meant to run. A client mod on a
 * server does nothing at best and stops it starting at worst, and the two are
 * the same kind of file with the same name on the same download page — so the
 * one place it can be caught is before it is copied in.
 */
class AddonsTest {

	@TempDir
	Path folder;

	/** A jar with one file in it, which is all a descriptor needs to be found. */
	private Path jar(String name, String path, String content) throws IOException {
		Path file = folder.resolve(name);
		try (OutputStream out = Files.newOutputStream(file);
			 ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(path));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return file;
	}

	@Test
	@DisplayName("A Fabric mod gives its name, version and the side it runs on")
	void fabric() throws IOException {
		Path file = jar("lithium.jar", "fabric.mod.json", """
			{
			  "schemaVersion": 1,
			  "id": "lithium",
			  "name": "Lithium",
			  "version": "0.14.1",
			  "environment": "*",
			  "depends": {"minecraft": ">=1.21", "fabricloader": ">=0.15", "fabric-api": "*"}
			}
			""");
		Addon addon = Addons.read(file, Addon.Kind.MOD);
		assertEquals("lithium", addon.id());
		assertEquals("Lithium", addon.name());
		assertEquals("0.14.1", addon.version());
		assertEquals(Addon.Side.BOTH, addon.side());
		assertTrue(addon.described());
		// The two everything depends on are not worth saying.
		assertEquals(List.of("fabric-api"), addon.needs());
	}

	@Test
	@DisplayName("A mod meant for the client says so, and that is the point")
	void clientOnly() throws IOException {
		Path file = jar("sodium.jar", "fabric.mod.json", """
			{"id": "sodium", "name": "Sodium", "version": "0.5.0", "environment": "client"}
			""");
		Addon addon = Addons.read(file, Addon.Kind.MOD);
		assertEquals(Addon.Side.CLIENT_ONLY, addon.side());
		assertTrue(addon.wrongSide());
	}

	@Test
	@DisplayName("A plugin gives its name and what it needs, from its own descriptor")
	void plugin() throws IOException {
		Path file = jar("EssentialsX.jar", "plugin.yml", """
			name: Essentials
			main: com.earth2me.essentials.Essentials
			version: 2.20.1
			api-version: "1.20"
			depend: [Vault]
			commands:
			  home:
			    description: teleports home
			""");
		Addon addon = Addons.read(file, Addon.Kind.PLUGIN);
		assertEquals("Essentials", addon.name());
		assertEquals("2.20.1", addon.version());
		assertEquals(Addon.Kind.PLUGIN, addon.kind());
		assertEquals(List.of("Vault"), addon.needs());
		// Nested lines belong to the command above them and are not settings of
		// the plugin.
		assertFalse(addon.needs().contains("description"));
	}

	@Test
	@DisplayName("Only the top level of a descriptor is read")
	void nesting() {
		var read = Addons.topLevel("""
			name: Thing
			commands:
			  fly:
			    aliases: [f]
			version: 1.0
			""");
		assertEquals("Thing", read.get("name"));
		assertEquals("1.0", read.get("version"));
		assertFalse(read.containsKey("aliases"));
		assertFalse(read.containsKey("fly"));
	}

	@Test
	@DisplayName("A jar that says nothing about itself is still listed")
	void silent() throws IOException {
		Path file = jar("mystery-1.2.3.jar", "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
		Addon addon = Addons.read(file, Addon.Kind.PLUGIN);
		assertFalse(addon.described());
		assertEquals("mystery-1.2.3.jar", addon.file());
		// Named after its file, because a list with a blank line in it is worse
		// than a list with a file name in it.
		assertEquals("mystery", addon.name());
	}

	@Test
	@DisplayName("Which folder a core keeps its additions in")
	void folders() {
		assertEquals("plugins", Addons.folderFor("paper"));
		assertEquals("mods", Addons.folderFor("fabric"));
		assertEquals("mods", Addons.folderFor("vanilla"));
		assertEquals(Addon.Kind.PLUGIN, Addons.kindFor("paper"));
		assertEquals(Addon.Kind.MOD, Addons.kindFor("fabric"));
	}

	private ManagedServer server() {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "fabric";
		server.directory = folder.resolve("server").toString();
		return server;
	}

	@Test
	@DisplayName("A client mod is refused rather than copied onto a server")
	void refusesClientMod() throws IOException {
		Path file = jar("sodium.jar", "fabric.mod.json", """
			{"id": "sodium", "name": "Sodium", "version": "0.5.0", "environment": "client"}
			""");
		String said = Addons.install(server(), file);
		assertTrue(said.contains("client"), said);
		assertFalse(Files.exists(folder.resolve("server").resolve("mods").resolve("sodium.jar")));
	}

	@Test
	@DisplayName("A plugin is refused by a core that takes mods")
	void refusesWrongKind() throws IOException {
		Path file = jar("Essentials.jar", "plugin.yml", "name: Essentials\nversion: 1.0\n");
		String said = Addons.install(server(), file);
		assertTrue(said.contains("plugin"), said);
	}

	@Test
	@DisplayName("Something that belongs goes in and comes back out of the listing")
	void installs() throws IOException {
		Path file = jar("lithium.jar", "fabric.mod.json", """
			{"id": "lithium", "name": "Lithium", "version": "0.14.1", "environment": "*"}
			""");
		ManagedServer server = server();
		assertEquals("", Addons.install(server, file));

		List<Addon> installed = Addons.installed(server);
		assertEquals(1, installed.size());
		assertEquals("Lithium", installed.getFirst().name());

		assertEquals("", Addons.remove(server, "lithium.jar"));
		assertEquals(0, Addons.installed(server).size());
	}

	@Test
	@DisplayName("A name that tries to leave the folder deletes nothing")
	void cannotEscape() throws IOException {
		ManagedServer server = server();
		Path outside = folder.resolve("precious.jar");
		Files.writeString(outside, "keep me");
		Files.createDirectories(Addons.folder(server));

		assertFalse(Addons.remove(server, "../precious.jar").isEmpty());
		assertFalse(Addons.remove(server, "..\\precious.jar").isEmpty());
		assertTrue(Files.exists(outside), "a file outside the folder was deleted");
	}

	@Test
	@DisplayName("A server with no folder of its own has nothing installed, not an error")
	void nothingYet() {
		assertEquals(0, Addons.installed(server()).size());
	}
}
