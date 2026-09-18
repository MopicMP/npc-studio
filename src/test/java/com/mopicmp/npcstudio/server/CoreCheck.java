package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.util.List;

/**
 * Asks the real version APIs whether we still read them correctly.
 *
 * Not a test, and deliberately not one: it needs the internet, it depends on
 * three services nobody here controls, and a build that fails because somebody
 * else's API is down teaches nothing. {@code CoreParsingTest} proves we read the
 * documents correctly; this proves the documents still look like that.
 *
 * Run it with {@code gradlew checkCores}, and run it whenever the creation
 * screen comes up with an empty list of versions.
 */
public final class CoreCheck {

	public static void main(String[] arguments) {
		CoreCatalog catalog = CoreCatalog.bundled();
		Downloads downloads = catalog.downloads();

		// -Pinstall=fabric:26.2 downloads one for real, into build/core-check.
		// The version lists prove we can read; only this proves we can fetch —
		// the address built from three separate answers, the checksum, and the
		// file landing where it was asked for.
		String install = System.getProperty("install", "");
		if (!install.isBlank()) {
			installOne(catalog, install);
			return;
		}

		// -Pbrowse=paper:1.21.4:essentials asks the real catalogue whether the
		// browser still reads it correctly.
		String browse = System.getProperty("browse", "");
		if (!browse.isBlank()) {
			browseFor(catalog, browse);
			return;
		}

		int failures = 0;

		System.out.println("Cores in the catalogue: " + catalog.cores().size());
		for (CoreCatalog.Core core : catalog.cores()) {
			System.out.println();
			System.out.println("--- " + core.name() + " (" + core.endpoint() + ")");
			CoreProvider provider = CoreProvider.of(core, downloads).orElse(null);
			if (provider == null) {
				System.out.println("  no provider knows how to install this");
				failures++;
				continue;
			}
			try {
				long began = System.currentTimeMillis();
				List<String> versions = provider.versions();
				long took = System.currentTimeMillis() - began;
				if (versions.isEmpty()) {
					System.out.println("  answered, but with no versions at all — the shape has"
						+ " probably changed");
					failures++;
					continue;
				}
				System.out.println("  " + versions.size() + " versions in " + took + " ms");
				System.out.println("  newest: " + String.join(", ",
					versions.subList(0, Math.min(6, versions.size()))));
				System.out.println("  oldest listed: " + versions.getLast());
				if (provider.fetchesOnFirstStart()) {
					System.out.println("  needs the internet on its first start");
				}
			} catch (IOException failed) {
				System.out.println("  " + failed);
				failures++;
			}
		}

		System.out.println();
		System.out.println(failures == 0
			? "All cores answered as expected."
			: failures + " core(s) did not answer as expected.");
		if (failures > 0) System.exit(1);
	}

	/**
	 * Ask the real catalogue, and resolve one result down to a file.
	 *
	 * The search alone would not prove much: the shape that matters is the one
	 * two requests deep, where a project becomes a version and a version becomes
	 * a jar with a checksum beside it.
	 */
	private static void browseFor(CoreCatalog catalog, String what) {
		String[] parts = what.split(":", 3);
		String loaderCore = parts.length > 0 ? parts[0] : "paper";
		String version = parts.length > 1 ? parts[1] : "";
		String query = parts.length > 2 ? parts[2] : "";

		Modrinth modrinth = new Modrinth(catalog.browse(), catalog.downloads());
		String loader = Catalogue.loaderFor(loaderCore);
		System.out.println("Catalogue: " + catalog.browse());
		System.out.println("Looking for '" + query + "' — " + loader + " " + version);
		try {
			// How many there are altogether, said out loud. This is the number that
			// caught the Paper shelf ending at about forty rows: it was asking for
			// project_type:mod, which on this catalogue means 96 projects for paper
			// against 3405 filed as plugins. A count printed here is a count
			// somebody can compare with the website in ten seconds.
			Catalogue.Page page = modrinth.search(query, loader, version, 0, "relevance");
			System.out.println("  the catalogue says there are " + page.total()
				+ " altogether, and sent " + page.hits().size());
			List<Catalogue.Found> found = page.hits();
			if (found.isEmpty()) {
				System.out.println("  nothing came back — the shape may have changed");
				System.exit(1);
			}
			for (Catalogue.Found each : found.subList(0, Math.min(5, found.size()))) {
				System.out.println("  " + each.title() + "  (" + each.downloads()
					+ " downloads, server: " + each.serverSide() + ")");
			}
			Catalogue.Found first = found.getFirst();
			List<Catalogue.Release> releases = modrinth.releases(first.id(), loader, version);
			System.out.println("  " + first.title() + ": " + releases.size() + " versions");
			Catalogue.Release release = Catalogue.best(releases);
			System.out.println("  newest finished: " + release.number()
				+ " -> " + release.filename() + " (" + release.size() + " bytes)");
			System.out.println("  from " + release.url());
			System.out.println("  sha1 " + release.sha1());
			System.out.println();
			System.out.println("The catalogue answered as expected.");
		} catch (IOException failed) {
			System.out.println("  " + failed);
			System.exit(1);
		}
	}

	/**
	 * Actually install one, to prove the second half of a provider works.
	 *
	 * Installing is not running: no server starts here and no agreement is
	 * needed. What it proves is the part the version lists cannot — that the
	 * download address is built correctly, that the published checksum matches
	 * what arrives, and that the jar lands under the name we then store.
	 */
	private static void installOne(CoreCatalog catalog, String what) {
		int colon = what.indexOf(':');
		String coreId = colon < 0 ? what : what.substring(0, colon);
		String version = colon < 0 ? "" : what.substring(colon + 1);

		CoreCatalog.Core core = catalog.byId(coreId).orElse(null);
		if (core == null) {
			System.out.println("No such core as '" + coreId + "'");
			System.exit(1);
			return;
		}
		CoreProvider provider = CoreProvider.of(core, catalog.downloads()).orElseThrow();
		java.nio.file.Path into = java.nio.file.Path.of("build", "core-check", coreId);
		try {
			if (version.isBlank()) version = provider.versions().getFirst();
			System.out.println("Installing " + core.name() + " " + version + " into " + into);

			long began = System.currentTimeMillis();
			long[] last = {-1};
			String jar = provider.install(into, version, (done, total) -> {
				long percent = total > 0 ? done * 100 / total : -1;
				if (percent != last[0] && (percent < 0 || percent % 20 == 0)) {
					last[0] = percent;
					System.out.println("  " + done + (total > 0 ? " of " + total : "") + " bytes");
				}
			});
			long size = java.nio.file.Files.size(into.resolve(jar));
			System.out.println("  " + jar + ", " + size + " bytes, in "
				+ (System.currentTimeMillis() - began) + " ms");
			// Not "the checksum matched": Fabric publishes none, and a line that
			// claims a check nobody performed is worse than no line.
			System.out.println("The jar is in place. Where a checksum is published,"
				+ " it was checked before this file existed.");
		} catch (IOException failed) {
			System.out.println("  " + failed);
			System.exit(1);
		}
	}
}
