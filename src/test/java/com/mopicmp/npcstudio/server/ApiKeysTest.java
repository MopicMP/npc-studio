package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeping somebody else's credential, and the parts of that which can be checked.
 *
 * Two of them can. The value never appears in the interface — {@link
 * ApiKeys#masked} is the only way out and it keeps four characters. And the file
 * is written with permissions naming one person, which is testable wherever the
 * filesystem has a vocabulary for saying so.
 *
 * The third thing is not a test but a statement, and it belongs beside these:
 * <b>a key that this program can use is a key this program can read</b>, so
 * anything running as this user can read it too. Nothing here pretends otherwise,
 * and the interface says the same in as many words.
 */
class ApiKeysTest {

	@Test
	@DisplayName("A key is kept, read back, replaced and forgotten")
	void roundTrip(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("keys.properties");
		ApiKeys keys = new ApiKeys(file);
		assertFalse(keys.has("curseforge"));
		assertEquals("", keys.get("curseforge"));

		keys.put("curseforge", "  $2a$10$abcdefghijkl  ");
		// Trimmed, because a key pasted from a web page brings whitespace with it
		// and a header with a space in it is a header that is refused.
		assertEquals("$2a$10$abcdefghijkl", keys.get("curseforge"));
		assertTrue(keys.has("curseforge"));
		assertEquals(java.util.Set.of("curseforge"), keys.present());

		// A second reader sees what the first wrote: the point of the file.
		assertEquals("$2a$10$abcdefghijkl", new ApiKeys(file).get("curseforge"));

		keys.put("curseforge", "second-key");
		assertEquals("second-key", new ApiKeys(file).get("curseforge"));

		keys.drop("curseforge");
		assertFalse(new ApiKeys(file).has("curseforge"));
		assertTrue(new ApiKeys(file).present().isEmpty());
	}

	@Test
	@DisplayName("An empty key is a deletion, not a stored blank")
	void emptyIsGone(@TempDir Path folder) throws IOException {
		ApiKeys keys = new ApiKeys(folder.resolve("keys.properties"));
		keys.put("curseforge", "something");
		keys.put("curseforge", "   ");
		assertFalse(keys.has("curseforge"));
	}

	@Test
	@DisplayName("Nothing in the file is the key as it was typed")
	void notLyingAround(@TempDir Path folder) throws IOException {
		// Not a security claim and must not be read as one — the encoding is there
		// so a key holding a newline or an equals sign cannot break the file. It is
		// worth a test only because a parser that loses a key silently is worse
		// than one that fails.
		Path file = folder.resolve("keys.properties");
		new ApiKeys(file).put("curseforge", "line=with=equals");
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertFalse(text.contains("line=with=equals"), text);
		assertEquals("line=with=equals", new ApiKeys(file).get("curseforge"));
	}

	@Test
	@DisplayName("The file is written so that only its owner may read it")
	void onlyOwner(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("keys.properties");
		new ApiKeys(file).put("curseforge", "a-key");

		PosixFileAttributeView posix =
			Files.getFileAttributeView(file, PosixFileAttributeView.class);
		if (posix == null) {
			// Windows, where this is an access list rather than a mode. That the
			// list was set is checked by it having exactly the owner on it, which
			// is more than this test can portably ask — so here it only has to have
			// been written and be readable by us.
			assertTrue(Files.isReadable(file));
			return;
		}
		var permissions = posix.readAttributes().permissions();
		assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
		assertFalse(permissions.contains(PosixFilePermission.GROUP_READ), permissions.toString());
		assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ), permissions.toString());
	}

	@Test
	@DisplayName("What a screen may know is four characters and not the length")
	void masking() {
		// Four is enough to answer the only question an interface has about a key:
		// is this the one I pasted. The mask is a fixed width on purpose — one as
		// long as the key states the key's length, which is a fact about the key.
		assertEquals("************cdef", ApiKeys.masked("abcdef-0123456789-abcdef"));
		assertEquals("************cdef", ApiKeys.masked("abcdef"));
		assertEquals("****", ApiKeys.masked("abcd"));
		assertEquals("", ApiKeys.masked(""));
		assertEquals("", ApiKeys.masked(null));
	}
}
