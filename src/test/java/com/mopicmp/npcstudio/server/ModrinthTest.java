package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the catalogue, from cut-down copies of what it really answers.
 *
 * The samples are trimmed from live replies rather than from documentation, and
 * the fields kept are the ones the browser turns on: which loaders a project
 * offers, <b>whether it runs on a server</b>, and where its file is with its
 * checksum. {@code gradlew checkBrowse} asks the real service whether this is
 * still the shape.
 */
class ModrinthTest {

	private static final String SEARCH = """
		{
		  "hits": [
		    {
		      "project_id": "gvQqBUqZ",
		      "project_type": "mod",
		      "slug": "lithium",
		      "title": "Lithium",
		      "description": "No-compromises game logic optimization mod.",
		      "categories": ["fabric", "quilt", "optimization"],
		      "downloads": 12345678,
		      "client_side": "optional",
		      "server_side": "optional"
		    },
		    {
		      "project_id": "AANobbMI",
		      "project_type": "mod",
		      "slug": "sodium",
		      "title": "Sodium",
		      "description": "A rendering engine.",
		      "categories": ["fabric", "optimization"],
		      "downloads": 40000000,
		      "client_side": "required",
		      "server_side": "unsupported"
		    }
		  ],
		  "offset": 0, "limit": 20, "total_hits": 2
		}
		""";

	@Test
	@DisplayName("A search gives the title, the summary and which side it runs on")
	void search() {
		Catalogue.Page page = Modrinth.parseSearch(SEARCH);
		List<Catalogue.Found> found = page.hits();
		assertEquals(2, found.size());
		assertEquals(2, page.total());
		// Everything is here, so there is no next page to ask for.
		assertFalse(page.more());

		Catalogue.Found lithium = found.getFirst();
		assertEquals("gvQqBUqZ", lithium.id());
		assertEquals("Lithium", lithium.title());
		assertEquals(12345678L, lithium.downloads());
		assertTrue(lithium.loaders().contains("fabric"));
		assertFalse(lithium.serverless());
	}

	@Test
	@DisplayName("Something that cannot run on a server is known before it is offered")
	void serverless() {
		// The whole reason for using a catalogue rather than a search engine: a
		// browser for a server that offers client mods is a browser that breaks
		// servers.
		Catalogue.Found sodium = Modrinth.parseSearch(SEARCH).hits().get(1);
		assertEquals("unsupported", sodium.serverSide());
		assertTrue(sodium.serverless());
	}

	private static final String VERSIONS = """
		[
		  {
		    "id": "f7vZ0VWU",
		    "name": "Lithium 0.25.3 for Fabric",
		    "version_number": "mc26.2-0.25.3-fabric",
		    "version_type": "release",
		    "game_versions": ["26.2"],
		    "loaders": ["fabric", "quilt"],
		    "files": [
		      {"filename": "lithium-sources.jar", "primary": false, "size": 10,
		       "url": "https://cdn.modrinth.com/data/x/versions/y/lithium-sources.jar",
		       "hashes": {"sha1": "aaa"}},
		      {"filename": "lithium-fabric-0.25.3+mc26.2.jar", "primary": true, "size": 912850,
		       "url": "https://cdn.modrinth.com/data/x/versions/y/lithium.jar",
		       "hashes": {"sha1": "435ad0289209bb72195e7a423756a03f5dfa5a9a"}}
		    ]
		  },
		  {
		    "id": "beta1", "name": "Lithium beta", "version_number": "0.26.0-beta",
		    "version_type": "beta", "files": [
		      {"filename": "beta.jar", "primary": true, "size": 1,
		       "url": "https://cdn.modrinth.com/beta.jar", "hashes": {"sha1": "bbb"}}]
		  }
		]
		""";

	@Test
	@DisplayName("The file taken is the primary one, with its published checksum")
	void files() {
		Catalogue.Release release = Modrinth.parseVersions(VERSIONS).getFirst();
		// Not the sources jar that comes first in the list.
		assertEquals("lithium-fabric-0.25.3+mc26.2.jar", release.filename());
		assertEquals("435ad0289209bb72195e7a423756a03f5dfa5a9a", release.sha1());
		assertEquals(912850L, release.size());
		assertTrue(release.url().startsWith("https://cdn.modrinth.com/"));
	}

	@Test
	@DisplayName("A finished release is preferred to a newer beta")
	void best() throws IOException {
		assertEquals("f7vZ0VWU", Catalogue.best(Modrinth.parseVersions(VERSIONS)).id());
	}

	@Test
	@DisplayName("Nothing published for this version and loader is an error that says so")
	void nothing() {
		assertThrows(IOException.class, () -> Catalogue.best(List.of()));
	}

	@Test
	@DisplayName("The search address carries the loader and the version as facets")
	void addresses() {
		String url = Modrinth.searchUrl("https://api.modrinth.com/v2",
			"world edit", "paper", "1.21.4", 0, 20, "downloads");
		assertTrue(url.contains("query=world+edit") || url.contains("query=world%20edit"), url);
		assertTrue(url.contains("categories%3Apaper"), url);
		assertTrue(url.contains("versions%3A1.21.4"), url);
		assertTrue(url.contains("index=downloads"), url);
		// A plugin loader asks for plugins. This is the whole of the fix for a
		// Paper shelf that ended at about forty rows: with project_type:mod the
		// live catalogue answers 96 projects for paper, and with project_type:plugin
		// it answers 3405 for this game version — the number on the website.
		assertTrue(url.contains("project_type%3Aplugin"), url);
		assertFalse(url.contains("project_type%3Amod"), url);

		// And a mod loader still asks for mods, without which a modpack appears in
		// the list — "Essential Sodium" did — and its published file is a .mrpack,
		// so installing it answered "that file is not a jar": a true sentence about
		// a row that was never installable here.
		String mods = Modrinth.searchUrl("https://api.modrinth.com/v2",
			"", "fabric", "26.2", 0, 20, "downloads");
		assertTrue(mods.contains("project_type%3Amod"), mods);
		// Proxies count as plugins here too, and by a wide margin: velocity has 858
		// plugins against 30 projects filed as mods for it.
		assertTrue(Modrinth.searchUrl("https://api.modrinth.com/v2",
			"", "velocity", "", 0, 20, "downloads").contains("project_type%3Aplugin"));
		// A datapack is a type of its own and takes no loader at all.
		String packs = Modrinth.searchUrl("https://api.modrinth.com/v2",
			"", Catalogue.DATAPACK, "26.2", 0, 20, "downloads");
		assertTrue(packs.contains("project_type%3Adatapack"), packs);
		assertFalse(packs.contains("categories%3Adatapack"), packs);
		// An order the catalogue does not know is left off rather than sent: it
		// answers 400 to anything else, and a browser that goes blank because of
		// a spelling mistake is worse than one that sorts by relevance.
		assertFalse(Modrinth.searchUrl("https://api.modrinth.com/v2",
			"", "paper", "", 0, 20, "nonsense").contains("index="));

		String versions = Modrinth.versionsUrl("https://api.modrinth.com/v2",
			"lithium", "fabric", "26.2");
		assertTrue(versions.startsWith("https://api.modrinth.com/v2/project/lithium/version"),
			versions);
		assertTrue(versions.contains("loaders=") && versions.contains("game_versions="), versions);
	}

	@Test
	@DisplayName("A page knows whether there is another one behind it")
	void paging() {
		// The count is the catalogue's own and not the number of rows shown: a
		// page with client mods taken out of it still has a page after it.
		Catalogue.Page page = new Catalogue.Page(Modrinth.parseSearch(SEARCH).hits(), 57, 0);
		assertTrue(page.more());
		assertFalse(new Catalogue.Page(List.of(), 0, 0).more());
	}

	@Test
	@DisplayName("The next page starts after what was sent, not after what was kept")
	void nextPage() {
		// Twenty came, twelve survived the filter. Asking for everything after
		// the twelve fetches eight of them a second time — which is how one mod
		// ended up in the list ten times.
		Catalogue.Page page = new Catalogue.Page(List.of(), 500, 0, 20);
		assertEquals(20, page.next());
		assertTrue(page.more());

		Catalogue.Page second = new Catalogue.Page(List.of(), 500, 20, 20);
		assertEquals(40, second.next());

		// And a page that was the end of it says so, however many it kept.
		assertFalse(new Catalogue.Page(List.of(), 20, 0, 20).more());
	}

	@Test
	@DisplayName("A project page keeps its pictures and loses its markup")
	void project() {
		String json = """
			{
			  "id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium",
			  "description": "No-compromises game logic optimization mod.",
			  "body": "# Lithium\\n\\nSee the [wiki](https://example.invalid) for more.\\n\\n![banner](https://cdn.modrinth.com/b.png)\\n\\n- makes ticks **faster**\\n- changes no behaviour\\n\\n<p>Also html.</p>",
			  "gallery": [
			    {"url": "https://cdn.modrinth.com/data/x/images/one_350.webp",
			     "raw_url": "https://cdn.modrinth.com/data/x/images/one.webp",
			     "title": "The lighting underwater", "featured": true},
			    {"url": null}
			  ]
			}
			""";
		Catalogue.Details page = Modrinth.parseProject(json, List.of());
		assertEquals("Lithium", page.title());
		assertEquals("https://modrinth.com/project/lithium", page.page());
		// A null url in the gallery is skipped rather than kept as a blank row.
		assertEquals(1, page.gallery().size(), page.gallery().toString());
		// The full-size one, not the three-hundred-and-fifty pixel thumbnail their
		// "url" field holds: that is narrower than the panel it would be drawn in.
		assertEquals("https://cdn.modrinth.com/data/x/images/one.webp",
			page.gallery().getFirst().url());
		assertEquals("The lighting underwater", page.gallery().getFirst().caption());

		// The description is kept as they wrote it, markup and all: what can be
		// made of a heading or a link is the drawing code's business, and reading
		// it here would throw away the emphasis somebody put in on purpose.
		assertTrue(page.body().startsWith("# Lithium"), page.body());

		// What comes of it is MarkupTest's subject; this only checks that the two
		// are joined up.
		String words = Markup.plain(page.body());
		assertTrue(words.startsWith("Lithium"), words);
		assertTrue(words.contains("See the wiki for more."), words);
		assertFalse(words.contains("example.invalid"), words);
		assertFalse(words.contains("**"), words);
		assertFalse(words.contains("<p>"), words);
		assertTrue(words.contains("· makes ticks faster"), words);
	}

	@Test
	@DisplayName("A core that takes neither plugins nor mods asks for no loader")
	void loaders() {
		assertEquals("paper", Catalogue.loaderFor("paper"));
		assertEquals("fabric", Catalogue.loaderFor("fabric"));
		// Vanilla takes neither, and a browser offered on one would be a shop
		// with nothing on the shelves that fits.
		assertEquals("", Catalogue.loaderFor("vanilla"));
	}
}
