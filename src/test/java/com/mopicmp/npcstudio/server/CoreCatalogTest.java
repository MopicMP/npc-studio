package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalogue that ships in the jar, read as the mod will read it.
 *
 * Worth a test of its own because it is a resource: a typo in it compiles, ships
 * and fails at the moment somebody opens the creation screen. Here it fails at
 * the moment somebody breaks it.
 *
 * The check that matters most is the last one — that every address in the
 * catalogue is reachable under the catalogue's own list of allowed hosts.
 * Those two lists are edited by hand, separately, and nothing but this notices
 * when they drift apart.
 */
class CoreCatalogTest {

	@Test
	@DisplayName("The bundled catalogue offers the three cores it says it does")
	void bundled() {
		CoreCatalog catalog = CoreCatalog.bundled();
		assertEquals(3, catalog.cores().size());

		CoreCatalog.Core vanilla = catalog.byId("vanilla").orElseThrow();
		assertEquals("mojang", vanilla.provider());
		assertFalse(vanilla.takesMods());
		assertFalse(vanilla.takesPlugins());

		assertTrue(catalog.byId("fabric").orElseThrow().takesMods());
		assertTrue(catalog.byId("paper").orElseThrow().takesPlugins());
		assertTrue(catalog.byId("forge").isEmpty());
	}

	@Test
	@DisplayName("Every core in the catalogue has code that knows how to install it")
	void providers() {
		CoreCatalog catalog = CoreCatalog.bundled();
		Downloads downloads = catalog.downloads();
		for (CoreCatalog.Core core : catalog.cores()) {
			assertTrue(CoreProvider.of(core, downloads).isPresent(),
				"the catalogue offers " + core.id() + " and nothing can install it");
		}
	}

	@Test
	@DisplayName("Every address in the catalogue is one the mod is allowed to reach")
	void addressesAreAllowed() {
		CoreCatalog catalog = CoreCatalog.bundled();
		Downloads downloads = catalog.downloads();
		for (CoreCatalog.Core core : catalog.cores()) {
			URI endpoint = URI.create(core.endpoint());
			assertTrue(downloads.permitted(endpoint),
				core.id() + " points at " + endpoint.getHost()
					+ ", which is not in the allowed list");
		}
	}

	@Test
	@DisplayName("A host that is not on the list is refused, however plausible")
	void notOnTheList() {
		Downloads downloads = CoreCatalog.bundled().downloads();
		assertFalse(downloads.permitted(URI.create("https://mojang.com.example.net/server.jar")));
		assertFalse(downloads.permitted(URI.create("https://192.168.1.1/admin")));
		// Plain HTTP, even to a host that is on the list.
		assertFalse(downloads.permitted(URI.create("http://fill.papermc.io/v3/projects/paper")));
		// The retired Paper API: not on the list, so not reachable even by name.
		assertFalse(downloads.permitted(URI.create("https://api.papermc.io/v2/projects/paper")));
	}

	@Test
	@DisplayName("An unreadable update is ignored and the shipped copy stands")
	void brokenUpdate(@TempDir Path folder) throws IOException {
		Path update = folder.resolve("cores.json");
		Files.writeString(update, "{ not json at all", StandardCharsets.UTF_8);
		assertEquals(3, CoreCatalog.load(update).cores().size());
	}

	@Test
	@DisplayName("A readable update replaces the shipped copy")
	void update(@TempDir Path folder) throws IOException {
		Path update = folder.resolve("cores.json");
		Files.writeString(update, """
			{
			  "version": 2,
			  "cores": [
			    {"id": "purpur", "name": "Purpur", "provider": "paper",
			     "accepts": "plugins", "endpoint": "https://api.purpurmc.org/v2"}
			  ],
			  "allowed": ["api.purpurmc.org"]
			}
			""", StandardCharsets.UTF_8);

		CoreCatalog catalog = CoreCatalog.load(update);
		assertEquals(1, catalog.cores().size());
		assertTrue(catalog.byId("purpur").orElseThrow().takesPlugins());
		assertTrue(catalog.downloads().permitted(URI.create("https://api.purpurmc.org/v2")));
	}

	@Test
	@DisplayName("A missing update file is not an error")
	void noUpdate(@TempDir Path folder) {
		assertEquals(3, CoreCatalog.load(folder.resolve("nothing.json")).cores().size());
	}
}
