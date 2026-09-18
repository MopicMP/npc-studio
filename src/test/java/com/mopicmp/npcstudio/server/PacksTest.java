package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unpacking an archive somebody else made.
 *
 * The tests that matter here are the ones about refusing, not the ones about
 * succeeding. An archive is a file format built to be small and become large, and
 * every bound in {@link Packs} is there because its absence is a documented way
 * to fill a disk or write outside a folder. A name like
 * {@code ../../server.properties} is not a hypothetical: archives contain it, and
 * a reader that resolves entry names is a reader that will one day overwrite
 * something it was never pointed at.
 */
class PacksTest {

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "fabric";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static Path zip(Path where, String... namesAndBodies) throws IOException {
		try (OutputStream out = Files.newOutputStream(where);
			 ZipOutputStream zip = new ZipOutputStream(out)) {
			for (int at = 0; at + 1 < namesAndBodies.length; at += 2) {
				zip.putNextEntry(new ZipEntry(namesAndBodies[at]));
				zip.write(namesAndBodies[at + 1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return where;
	}

	@Test
	@DisplayName("Jars at the top and jars under mods go in; everything else stays out")
	void unpacks(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder.resolve("server"));
		Path archive = zip(folder.resolve("pack.zip"),
			"lithium.jar", "one",
			"overrides/mods/ferritecore.jar", "two",
			"overrides/config/lithium.toml", "not a mod",
			"shaderpacks/complementary.zip", "not a mod either",
			"resources/something.jar", "a jar in the wrong place");

		Packs.Result result = Packs.install(server, archive, null, Downloads.Progress.IGNORED);

		assertEquals(2, result.added().size(), result.added().toString());
		assertTrue(result.added().contains("lithium.jar"));
		assertTrue(result.added().contains("ferritecore.jar"));
		assertTrue(Files.isRegularFile(Addons.folder(server).resolve("ferritecore.jar")));
		// A jar somewhere else in the archive is not a mod because it ends in the
		// right four letters — it is a library, or a shader, or a mistake.
		assertFalse(Files.exists(Addons.folder(server).resolve("something.jar")));
		assertFalse(Files.exists(Addons.folder(server).resolve("lithium.toml")));
	}

	@Test
	@DisplayName("A name that climbs out of the folder is refused, not resolved")
	void doesNotClimb(@TempDir Path folder) throws IOException {
		// Written as an entry name, this is a real thing archives contain. The
		// only safe answer is to never turn an entry name into a path: the last
		// segment is used and the result is checked afterwards as well.
		ManagedServer server = server(folder.resolve("server"));
		Path archive = zip(folder.resolve("bad.zip"),
			"../../escaped.jar", "no",
			"mods/../../../also-escaped.jar", "no",
			"mods/fine.jar", "yes");

		Packs.Result result = Packs.install(server, archive, null, Downloads.Progress.IGNORED);

		assertTrue(result.added().contains("fine.jar"), result.added().toString());
		assertFalse(Files.exists(folder.resolve("escaped.jar")));
		assertFalse(Files.exists(folder.resolve("also-escaped.jar")));
		// And whatever did land, landed inside the addons folder.
		for (String name : result.added()) {
			assertTrue(Files.isRegularFile(Addons.folder(server).resolve(name)), name);
		}
	}

	@Test
	@DisplayName("A file that is not an archive says so rather than failing oddly")
	void notAnArchive(@TempDir Path folder) throws IOException {
		ManagedServer server = server(folder.resolve("server"));
		Path fake = Files.writeString(folder.resolve("pack.zip"), "this is not a zip");
		IOException refused = assertThrows(IOException.class,
			() -> Packs.install(server, fake, null, Downloads.Progress.IGNORED));
		assertTrue(refused.getMessage().contains("not an archive"), refused.getMessage());
	}

	@Test
	@DisplayName("A pack's list of downloads is read, and its client-only files are left out")
	void index(@TempDir Path folder) throws IOException {
		// A Modrinth pack names files rather than holding them, and says which end
		// of the game each is for. Putting a client-only mod on a server is the
		// same mistake the browser exists to prevent, made forty times at once.
		ManagedServer server = server(folder.resolve("server"));
		String index = """
			{"formatVersion": 1, "name": "A pack", "files": [
			  {"path": "mods/sodium.jar",
			   "hashes": {"sha1": "aaa"},
			   "env": {"client": "required", "server": "unsupported"},
			   "downloads": ["https://cdn.modrinth.com/sodium.jar"]},
			  {"path": "mods/lithium.jar",
			   "hashes": {"sha1": "bbb"},
			   "env": {"client": "optional", "server": "optional"},
			   "downloads": []},
			  {"path": "config/lithium.toml",
			   "downloads": ["https://cdn.modrinth.com/x.toml"]}
			]}
			""";
		Path archive = zip(folder.resolve("pack.mrpack"), "modrinth.index.json", index);

		Packs.Result result = Packs.install(server, archive, null, Downloads.Progress.IGNORED);

		assertTrue(result.added().isEmpty(), result.added().toString());
		assertTrue(result.skipped().stream().anyMatch(said -> said.startsWith("sodium.jar")
			&& said.contains("client")), result.skipped().toString());
		// The one with no address is named too, rather than quietly missing.
		assertTrue(result.skipped().stream().anyMatch(said -> said.startsWith("lithium.jar")),
			result.skipped().toString());
		// A config file is not a mod, whoever listed it.
		assertFalse(result.skipped().stream().anyMatch(said -> said.contains("toml")),
			result.skipped().toString());
	}

	@Test
	@DisplayName("Only a zip or a pack is treated as an archive")
	void whatIsAnArchive() {
		assertTrue(Packs.isArchive(Path.of("pack.zip")));
		assertTrue(Packs.isArchive(Path.of("Essential Sodium 26.2 1.0.0.mrpack")));
		assertFalse(Packs.isArchive(Path.of("lithium.jar")));
	}

	@Test
	@DisplayName("Which entries count as mods")
	void wanted() {
		assertTrue(Packs.wanted("lithium.jar"));
		assertTrue(Packs.wanted("mods/lithium.jar"));
		assertTrue(Packs.wanted("overrides/mods/lithium.jar"));
		assertTrue(Packs.wanted("MODS/Lithium.JAR"));
		assertFalse(Packs.wanted("config/lithium.toml"));
		assertFalse(Packs.wanted("libraries/asm.jar"));
	}
}
