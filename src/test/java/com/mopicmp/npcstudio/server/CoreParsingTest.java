package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading what the three version APIs answer.
 *
 * The samples below are cut-down copies of real replies, kept because the
 * interesting parts of each are the parts that differ: Mojang lists thousands of
 * versions of which most have no server at all; Fabric answers three separate
 * lists and marks what is stable; Paper answers oldest-first and its builds are
 * not equally finished.
 *
 * These prove that we read those documents correctly, not that the documents
 * still look like this. The second thing cannot be tested from here — an API
 * that changes shape is found by the first person whose creation screen comes up
 * empty, which is why parsing is forgiving about fields it does not need and
 * loud about the ones it does.
 */
class CoreParsingTest {

	@Nested
	@DisplayName("Mojang")
	class Mojang {

		private static final String MANIFEST = """
			{
			  "latest": {"release": "1.21.1", "snapshot": "24w37a"},
			  "versions": [
			    {"id": "24w37a", "type": "snapshot",
			     "url": "https://piston-meta.mojang.com/v1/packages/aaa/24w37a.json"},
			    {"id": "1.21.1", "type": "release",
			     "url": "https://piston-meta.mojang.com/v1/packages/bbb/1.21.1.json"},
			    {"id": "1.21", "type": "release",
			     "url": "https://piston-meta.mojang.com/v1/packages/ccc/1.21.json"},
			    {"id": "rd-132211", "type": "old_alpha",
			     "url": "https://piston-meta.mojang.com/v1/packages/ddd/rd-132211.json"}
			  ]
			}
			""";

		@Test
		@DisplayName("Releases come out newest first, and snapshots do not come out at all")
		void versions() {
			List<MojangCore.Version> all = MojangCore.parseManifest(MANIFEST);
			assertEquals(4, all.size());
			assertEquals("24w37a", all.getFirst().id());

			// What the screen shows: the manifest's own order, releases only.
			List<String> released = all.stream()
				.filter(version -> version.type().equals("release"))
				.map(MojangCore.Version::id)
				.toList();
			assertEquals(List.of("1.21.1", "1.21"), released);
		}

		@Test
		@DisplayName("A version file gives the address and the checksum of the server")
		void serverJar() {
			MojangCore.ServerJar jar = MojangCore.parseServerJar("""
				{
				  "id": "1.21.1",
				  "downloads": {
				    "client": {"sha1": "1111", "size": 26000000,
				               "url": "https://piston-data.mojang.com/v1/objects/1111/client.jar"},
				    "server": {"sha1": "2222", "size": 51000000,
				               "url": "https://piston-data.mojang.com/v1/objects/2222/server.jar"}
				  }
				}
				""").orElseThrow();
			assertEquals("https://piston-data.mojang.com/v1/objects/2222/server.jar", jar.url());
			assertEquals("2222", jar.sha1());
			assertEquals(51000000L, jar.size());
		}

		@Test
		@DisplayName("A version with no server is empty, not an exception")
		void noServer() {
			// Everything before 2010 is like this, and the manifest lists all of it.
			assertTrue(MojangCore.parseServerJar("""
				{"id": "rd-132211", "downloads": {"client": {"sha1": "3333", "size": 1,
				 "url": "https://piston-data.mojang.com/v1/objects/3333/client.jar"}}}
				""").isEmpty());
		}
	}

	@Nested
	@DisplayName("Fabric")
	class Fabric {

		@Test
		@DisplayName("Only stable versions are offered, in the order given")
		void versions() {
			List<FabricCore.Release> releases = FabricCore.parseReleases("""
				[
				  {"version": "24w37a", "stable": false},
				  {"version": "1.21.1", "stable": true},
				  {"version": "1.21", "stable": true}
				]
				""");
			assertEquals(3, releases.size());
			assertFalse(releases.getFirst().stable());
			assertEquals(List.of("1.21.1", "1.21"),
				releases.stream().filter(FabricCore.Release::stable)
					.map(FabricCore.Release::version).toList());
		}

		@Test
		@DisplayName("The loader chosen is the newest stable one, not simply the newest")
		void loader() throws IOException {
			assertEquals("0.16.5", FabricCore.newestStable(FabricCore.parseReleases("""
				[
				  {"version": "0.16.6", "stable": false},
				  {"version": "0.16.5", "stable": true},
				  {"version": "0.16.4", "stable": true}
				]
				""")));
		}

		@Test
		@DisplayName("With nothing marked stable, the newest is taken rather than nothing")
		void noneStable() throws IOException {
			assertEquals("1.0.0", FabricCore.newestStable(FabricCore.parseReleases("""
				[{"version": "1.0.0", "stable": false}, {"version": "0.9.0", "stable": false}]
				""")));
		}

		@Test
		@DisplayName("An empty list is an error and not a blank version number")
		void empty() {
			assertThrows(IOException.class, () -> FabricCore.newestStable(List.of()));
		}

		@Test
		@DisplayName("The server jar address is the four values in their fixed order")
		void address() {
			FabricCore fabric = new FabricCore("https://meta.fabricmc.net/v2/", null);
			assertEquals(
				"https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.5/1.0.1/server/jar",
				fabric.serverJarUrl("1.21.1", "0.16.5", "1.0.1"));
		}
	}

	@Nested
	@DisplayName("Paper")
	class Paper {

		@Test
		@DisplayName("Grouped versions are flattened in the order given, releases only")
		void versions() {
			// Both the groups and the versions inside them already run newest
			// first, so nothing is sorted: the order comes from the document, which
			// is the only place it is correct. Release candidates are not versions
			// anybody means when they create a server.
			assertEquals(List.of("26.2", "26.1.2", "26.1.1", "1.21.11", "1.21.10"),
				PaperCore.parseVersions("""
					{
					  "project": {"id": "paper", "name": "Paper"},
					  "versions": {
					    "26.2": ["26.2", "26.2-rc-2"],
					    "26.1": ["26.1.2", "26.1.1"],
					    "1.21": ["1.21.11", "1.21.11-rc3", "1.21.11-pre5", "1.21.10"]
					  }
					}
					"""));
		}

		private static final String BUILDS = """
			[
			  {"id": 122, "channel": "ALPHA",
			   "downloads": {"server:default": {
			     "name": "paper-26.2-122.jar", "size": 64522205,
			     "checksums": {"sha256": "ccc"},
			     "url": "https://fill-data.papermc.io/v1/objects/ccc/paper-26.2-122.jar"}}},
			  {"id": 121, "channel": "STABLE",
			   "downloads": {"server:default": {
			     "name": "paper-26.2-121.jar", "size": 64522205,
			     "checksums": {"sha256": "bbb"},
			     "url": "https://fill-data.papermc.io/v1/objects/bbb/paper-26.2-121.jar"}}},
			  {"id": 120, "channel": "STABLE",
			   "downloads": {"server:default": {
			     "name": "paper-26.2-120.jar", "size": 64000000,
			     "checksums": {"sha256": "aaa"},
			     "url": "https://fill-data.papermc.io/v1/objects/aaa/paper-26.2-120.jar"}}}
			]
			""";

		@Test
		@DisplayName("The build taken is the newest finished one, not the newest")
		void best() throws IOException {
			PaperCore.Build build = PaperCore.best(PaperCore.parseBuilds(BUILDS));
			assertEquals(121, build.number());
			assertEquals("paper-26.2-121.jar", build.file());
			assertEquals("bbb", build.sha256());
			assertEquals("https://fill-data.papermc.io/v1/objects/bbb/paper-26.2-121.jar",
				build.url());
			assertTrue(build.ready());
		}

		@Test
		@DisplayName("The address to download from comes from the build, not from a template")
		void addressIsGiven() {
			// It points at a different host from the API, and at a path made of the
			// checksum. Building it ourselves would be inventing it.
			PaperCore.Build build = PaperCore.parseBuilds(BUILDS).getFirst();
			assertTrue(build.url().startsWith("https://fill-data.papermc.io/"), build.url());
		}

		@Test
		@DisplayName("When nothing is finished, the newest is taken rather than nothing")
		void onlyExperimental() throws IOException {
			PaperCore.Build build = PaperCore.best(PaperCore.parseBuilds("""
				[
				  {"id": 2, "channel": "ALPHA", "downloads": {"server:default":
				    {"name": "b.jar", "checksums": {"sha256": "bbb"}, "url": "https://x/b.jar"}}},
				  {"id": 1, "channel": "ALPHA", "downloads": {"server:default":
				    {"name": "a.jar", "checksums": {"sha256": "aaa"}, "url": "https://x/a.jar"}}}
				]
				"""));
			assertEquals(2, build.number());
			assertFalse(build.ready());
		}

		@Test
		@DisplayName("A version with no builds is an error that says so")
		void noBuilds() {
			assertThrows(IOException.class, () -> PaperCore.best(PaperCore.parseBuilds("[]")));
		}

		@Test
		@DisplayName("A build with no server download is skipped rather than half-read")
		void noServerDownload() {
			assertEquals(1, PaperCore.parseBuilds("""
				[
				  {"id": 5, "channel": "STABLE", "downloads": {}},
				  {"id": 4, "channel": "STABLE", "downloads": {"server:default":
				    {"name": "a.jar", "checksums": {"sha256": "aaa"}, "url": "https://x/a.jar"}}}
				]
				""").size());
		}
	}
}
