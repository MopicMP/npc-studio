package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the second catalogue, from the shape its documentation states.
 *
 * <b>Said plainly: unlike {@link ModrinthTest}, these samples were not trimmed
 * from a live reply.</b> Answering at all needs a key belonging to a person, and
 * there is none in this repository — so what is tested here is that the code
 * reads the documented shape correctly and refuses safely, and not that the shape
 * is still the one being served. The first person to paste a key is the first
 * proof of that, and the messages are written so that a mismatch says which field
 * was missing rather than "search failed".
 *
 * What the tests are really guarding is the two things that differ from the
 * catalogue we knew first, because both are easy to get quietly wrong: a file
 * that has no address, and a project id that goes into a URL path.
 */
class CurseForgeTest {

	private static final String SEARCH = """
		{
		  "data": [
		    {
		      "id": 238222,
		      "name": "Just Enough Items",
		      "slug": "jei",
		      "summary": "View items and recipes.",
		      "downloadCount": 385122334.0,
		      "classId": 6,
		      "logo": {"url": "https://media.forgecdn.net/avatars/1/2/jei.png"},
		      "latestFilesIndexes": [
		        {"gameVersion": "26.2", "modLoader": 4},
		        {"gameVersion": "26.2", "modLoader": 1}
		      ]
		    },
		    {
		      "id": 310111,
		      "name": "A plugin with no picture",
		      "slug": "quiet",
		      "summary": "",
		      "downloadCount": 12,
		      "classId": 5
		    }
		  ],
		  "pagination": {"index": 40, "pageSize": 20, "resultCount": 20, "totalCount": 4321}
		}
		""";

	@Test
	@DisplayName("A search gives the name, the summary and the loaders its files name")
	void search() {
		Catalogue.Page page = CurseForge.parseSearch(SEARCH);
		assertEquals(2, page.hits().size());
		assertEquals(4321, page.total());

		Catalogue.Found jei = page.hits().getFirst();
		assertEquals("238222", jei.id());
		assertEquals("Just Enough Items", jei.title());
		assertEquals(385122334L, jei.downloads());
		// The project itself says no loader; the index of its latest files does.
		assertTrue(jei.loaders().contains("fabric"));
		assertTrue(jei.loaders().contains("forge"));
		assertTrue(jei.icon().startsWith("https://media.forgecdn.net/"));

		// Nothing there is a blank, never a crash: half the fields are optional in
		// their answers and a row with no picture is a row with a letter in it.
		assertEquals("", page.hits().get(1).icon());
		assertTrue(page.hits().get(1).loaders().isEmpty());
	}

	private static final String NULLS = """
		{
		  "data": [
		    {
		      "id": 501,
		      "name": "A plugin nobody drew a logo for",
		      "slug": "plain",
		      "summary": null,
		      "downloadCount": 4,
		      "logo": null,
		      "latestFilesIndexes": null,
		      "links": null
		    }
		  ],
		  "pagination": {"index": 0, "pageSize": 20, "resultCount": 1, "totalCount": 1}
		}
		""";

	@Test
	@DisplayName("A field that is present and null is not a field that is missing")
	void nullsAreNotAbsences() {
		// This is the whole of the fault that put a stack trace where the list of
		// plugins should have been. The sample this parser was written against
		// simply left "logo" out; the live answer writes "logo": null, and Gson's
		// getAsJsonObject returns null for the first and throws a class cast for
		// the second. Bukkit plugins are exactly the shelf where most projects
		// have no picture, so the fault waited for the one press that would find
		// it.
		Catalogue.Page page = CurseForge.parseSearch(NULLS);
		assertEquals(1, page.hits().size());
		Catalogue.Found plain = page.hits().getFirst();
		assertEquals("501", plain.id());
		assertEquals("", plain.icon());
		assertEquals("", plain.summary());
		assertTrue(plain.loaders().isEmpty());
	}

	@Test
	@DisplayName("It cannot say whether something runs on a server, so it does not say")
	void doesNotGuess() {
		// Modrinth states this and it is the reason a browser for a server is worth
		// having. CurseForge has no such field — so every row is "unknown" and
		// nothing is filtered out on a guess. Inventing an answer here would either
		// hide things that work or offer things that stop the server starting.
		Catalogue.Found jei = CurseForge.parseSearch(SEARCH).hits().getFirst();
		assertEquals("unknown", jei.serverSide());
		assertFalse(jei.serverless());
	}

	@Test
	@DisplayName("The next page is counted in what was sent, as everywhere else")
	void paging() {
		Catalogue.Page page = CurseForge.parseSearch(SEARCH);
		assertEquals(40, page.offset());
		assertEquals(60, page.next());
		assertTrue(page.more());
	}

	private static final String FILES = """
		{
		  "data": [
		    {
		      "id": 4001,
		      "displayName": "jei-26.2-fabric-19.0.0.jar",
		      "fileName": "jei-26.2-fabric-19.0.0.jar",
		      "releaseType": 1,
		      "fileLength": 1234567,
		      "downloadUrl": "https://edge.forgecdn.net/files/4/1/jei.jar",
		      "hashes": [
		        {"value": "d41d8cd98f00b204e9800998ecf8427e", "algo": 2},
		        {"value": "435ad0289209bb72195e7a423756a03f5dfa5a9a", "algo": 1}
		      ]
		    },
		    {
		      "id": 4002,
		      "displayName": "jei-26.2-fabric-19.1.0-beta.jar",
		      "fileName": "jei-26.2-fabric-19.1.0-beta.jar",
		      "releaseType": 2,
		      "fileLength": 22,
		      "downloadUrl": null,
		      "hashes": []
		    }
		  ]
		}
		""";

	@Test
	@DisplayName("A file carries its SHA-1 and not its MD5")
	void files() {
		List<Catalogue.Release> releases = CurseForge.parseFiles(FILES);
		assertEquals(2, releases.size());
		Catalogue.Release first = releases.getFirst();
		assertEquals("jei-26.2-fabric-19.0.0.jar", first.filename());
		// Their hash list names its algorithm with a number, and MD5 comes first
		// here on purpose: it says a file arrived whole and nothing about it being
		// the file that was published.
		assertEquals("435ad0289209bb72195e7a423756a03f5dfa5a9a", first.sha1());
		assertEquals(1234567L, first.size());
		assertTrue(first.finished());
		assertFalse(releases.get(1).finished());
	}

	@Test
	@DisplayName("A file whose author turned off downloads says so instead of failing oddly")
	void noAddress() throws Exception {
		// This is a decision by a person, not a fault to retry, and it has no
		// counterpart on the other catalogue — so the message has to name it. A
		// generic "download failed" would send somebody looking at their network.
		Catalogue.Release blocked = CurseForge.parseFiles(FILES).get(1);
		assertNull(blocked.url() == null || blocked.url().isBlank() ? null : blocked.url());

		CurseForge site = new CurseForge("https://api.curseforge.com/v1",
			new Downloads(java.util.Set.of("api.curseforge.com")), "not-a-real-key");
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.directory = java.nio.file.Files.createTempDirectory("npc-cf").toString();
		server.core = "fabric";
		Exception refused = assertThrows(java.io.IOException.class,
			() -> site.install(server, blocked, Downloads.Progress.IGNORED));
		assertTrue(refused.getMessage().contains("third-party downloads"), refused.getMessage());
	}

	@Test
	@DisplayName("The shipped catalogue says CurseForge has mods and not plugins")
	void oneShelf() {
		// A statement about a website, kept as data. Their Bukkit plugins section
		// exists in the numbering and was left behind years ago — the authors
		// publish on SpigotMC, Hangar and Modrinth. Offering it would be offering
		// a shop with an empty shelf, and an empty answer reads as "there is
		// nothing like that" rather than "you are asking in the wrong shop".
		var source = CoreCatalog.bundled().source("curseforge").orElseThrow();
		assertTrue(source.has("mods"));
		assertFalse(source.has("plugins"));
		assertFalse(source.bothKinds());

		var modrinth = CoreCatalog.bundled().source("modrinth").orElseThrow();
		assertTrue(modrinth.bothKinds());
	}

	@Test
	@DisplayName("A project id that is not a number never becomes part of an address")
	void idIsANumber() {
		// It arrives from outside and goes into a URL path, which is the shape the
		// security notes are about. Checked rather than escaped: their ids are
		// numbers, so anything else is a sign something is wrong, not a string to
		// be made safe.
		assertEquals("238222", CurseForge.encodeId(" 238222 "));
		assertThrows(IllegalArgumentException.class, () -> CurseForge.encodeId("../../mods"));
		assertThrows(IllegalArgumentException.class, () -> CurseForge.encodeId(""));
	}

	@Test
	@DisplayName("A project page keeps its pictures and loses its markup")
	void project() {
		String mod = """
			{"data": {
			  "id": 238222, "name": "Just Enough Items", "summary": "View items.",
			  "screenshots": [
			    {"id": 1, "url": "https://media.forgecdn.net/attachments/1/one.png"},
			    {"id": 2, "url": null}
			  ],
			  "links": {"websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/jei"}
			}}
			""";
		String described = "<h2>What it does</h2><p>Shows <b>recipes</b>.</p>"
			+ "<ul><li>Search</li><li>Bookmarks</li></ul>";

		Catalogue.Details page = CurseForge.parseProject(mod, described, List.of());
		assertEquals("Just Enough Items", page.title());
		assertEquals(1, page.gallery().size(), page.gallery().toString());
		assertTrue(page.page().contains("curseforge.com"), page.page());
		// The description is kept exactly as they wrote it. Reading the markup is
		// the drawing code's business, because what can be shown of a heading or a
		// link depends on what is doing the showing — see MarkupTest.
		assertEquals(described, page.body());
		String words = Markup.plain(page.body());
		assertTrue(words.contains("Shows recipes."), words);
		assertTrue(words.contains("Search"), words);
	}

	@Test
	@DisplayName("The address carries their numbering for the shelf, the loader and the order")
	void addresses() {
		String mods = CurseForge.searchUrl("https://api.curseforge.com/v1",
			"world edit", "fabric", "26.2", 20, 20, "downloads");
		assertTrue(mods.contains("gameId=432"), mods);
		assertTrue(mods.contains("classId=6"), mods);
		assertTrue(mods.contains("modLoaderType=4"), mods);
		assertTrue(mods.contains("sortField=6"), mods);
		assertTrue(mods.contains("index=20"), mods);

		// A Paper server wants their Bukkit shelf, which has no loader at all —
		// asking for one there returns nothing rather than an error, which is the
		// worst way to be wrong.
		String plugins = CurseForge.searchUrl("https://api.curseforge.com/v1",
			"", "paper", "26.2", 0, 20, "relevance");
		assertTrue(plugins.contains("classId=5"), plugins);
		assertFalse(plugins.contains("modLoaderType"), plugins);
		// Their sort has no "relevance", and a number invented for it would sort by
		// something else without saying so.
		assertFalse(plugins.contains("sortField"), plugins);
	}
}
