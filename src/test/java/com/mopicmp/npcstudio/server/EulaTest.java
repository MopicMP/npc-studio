package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agreement file, and what counts as one.
 *
 * Small, and here because the failure it guards against is not technical. This
 * file <em>is</em> a person saying they agreed; everything the code does with it
 * has to keep that true, which is why there is no method that writes it with a
 * value, only one that records an agreement that has happened.
 */
class EulaTest {

	@TempDir
	Path folder;

	@Test
	@DisplayName("A folder with no agreement in it has not agreed")
	void nothing() {
		assertFalse(Eula.accepted(folder));
	}

	@Test
	@DisplayName("Accepting writes a file the server will read as an agreement")
	void accepted() throws IOException {
		Eula.accept(folder);
		assertTrue(Eula.accepted(folder));

		String written = Files.readString(folder.resolve("eula.txt"), StandardCharsets.UTF_8);
		assertTrue(written.contains("eula=true"));
		// The terms are linked, not copied: the text is theirs.
		assertTrue(written.contains(Eula.URL));
	}

	@Test
	@DisplayName("Somebody who turned it off has not agreed, whatever we wrote before")
	void turnedOff() throws IOException {
		Eula.accept(folder);
		Files.writeString(folder.resolve("eula.txt"), "eula=false\n", StandardCharsets.UTF_8);
		assertFalse(Eula.accepted(folder));
	}

	@Test
	@DisplayName("A file that is not an agreement is not read as one")
	void nonsense() throws IOException {
		Files.writeString(folder.resolve("eula.txt"), "I agree, honestly\n", StandardCharsets.UTF_8);
		assertFalse(Eula.accepted(folder));
	}
}
