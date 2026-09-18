package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * How one kind of core is found and installed.
 *
 * Two questions, because those are the two a person asks: which versions can I
 * have, and put one here. Everything peculiar to a core — that Fabric needs a
 * loader version as well as a game version, that Paper is identified by a build
 * number, that vanilla publishes a checksum and Fabric does not — lives behind
 * these two and does not leak into the screen that calls them.
 *
 * Both talk to the network, so neither belongs on the thread that draws frames.
 */
public interface CoreProvider {

	/** Game versions this core offers, newest first. */
	List<String> versions() throws IOException;

	/**
	 * Put a launch jar for that version in the folder.
	 *
	 * @return what the jar is called, to be stored and launched
	 */
	String install(Path directory, String gameVersion, Downloads.Progress progress)
		throws IOException;

	/**
	 * Whether the installed server needs the internet the first time it starts.
	 *
	 * True for Fabric and Paper, and it is not a detail: both put a small
	 * launcher in the folder that fetches the real server on first run. Somebody
	 * who installs a core, goes offline and then starts the server gets a failure
	 * that mentions neither the network nor the core, and the interface is the
	 * only place that can warn them.
	 */
	default boolean fetchesOnFirstStart() {
		return false;
	}

	/** The provider for a catalogue entry, or empty if we do not know that kind. */
	static java.util.Optional<CoreProvider> of(CoreCatalog.Core core, Downloads downloads) {
		return switch (core.provider()) {
			case "mojang" -> java.util.Optional.of(new MojangCore(core.endpoint(), downloads));
			case "fabric" -> java.util.Optional.of(new FabricCore(core.endpoint(), downloads));
			case "paper" -> java.util.Optional.of(new PaperCore(core.endpoint(), downloads));
			default -> java.util.Optional.empty();
		};
	}
}
