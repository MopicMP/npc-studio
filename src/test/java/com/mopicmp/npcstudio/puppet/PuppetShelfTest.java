package com.mopicmp.npcstudio.puppet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Layout sheets on disk: what survives a save, and what a person opening the file sees. */
class PuppetShelfTest {

	private static Puppet lona() {
		return new Puppet("Lona", 380, 637, List.of(
			new Puppet.Slot("body", 0, 0, List.of(new Puppet.Part("plain", "aaa"))),
			new Puppet.Slot("eyes", 140, 96, List.of(
				new Puppet.Part("calm", "ddd"),
				new Puppet.Part("sad", "eee", 3, 2)))));
	}

	@Test
	@DisplayName("a sheet written to a world and read back is the same sheet")
	void throughTheFile(@TempDir Path root) {
		new PuppetShelf(root).put(lona());

		// Reopened from disk rather than trusted in memory: what an author hands somebody
		// is the folder, and the folder is what has to say the right thing.
		Puppet back = new PuppetShelf(root).named("Lona");
		assertEquals(lona(), back);
		assertEquals(3, back.slot("eyes").part("sad").nudgeX(),
			"including the nudge a variant needed, which is the fiddliest thing in it");
	}

	@Test
	@DisplayName("a sheet is a few kilobytes of names, whatever the pictures weigh")
	void sheetsAreText(@TempDir Path root) throws Exception {
		new PuppetShelf(root).put(lona());
		String written = Files.readString(root.resolve("puppets.json"));

		// Fingerprints, not pixels. This is the property that lets a world hold thousands
		// of pictures and still have a sheet somebody can open and read.
		assertTrue(written.contains("\"picture\": \"aaa\""));
		assertTrue(written.length() < 4096, "a sheet of two slots is small: " + written.length());
		// And nothing that was not said. A nudge of zero is left out, so the file reads as
		// what somebody did rather than as what the record happens to hold.
		assertFalse(written.contains("nudge_x\": 0"));
	}

	@Test
	@DisplayName("saving twice keeps the version before it")
	void changingIsUndoable(@TempDir Path root) {
		PuppetShelf shelf = new PuppetShelf(root);
		shelf.put(lona());
		assertTrue(shelf.history().isEmpty(), "the first save has nothing to keep aside");

		shelf.put(lona().with(lona().slot("eyes").moved(0, 0)));
		assertEquals(1, shelf.history().size(),
			"and the second keeps what the first wrote");
		assertEquals(0, shelf.named("Lona").slot("eyes").x());

		assertTrue(shelf.restore(shelf.history().get(0)));
		assertEquals(140, shelf.named("Lona").slot("eyes").x(),
			"putting a version back is what the whole business is for");
	}

	@Test
	@DisplayName("a version name is never allowed near the filesystem unchecked")
	void namesFromElsewhereAreRefused(@TempDir Path root) {
		// The name arrives from whoever clicked, over the network. This one resolved
		// perfectly happily before the check existed.
		PuppetShelf shelf = new PuppetShelf(root);
		shelf.put(lona());
		assertFalse(shelf.restore("../../../server"));
		assertFalse(shelf.restore("2026-01-01 00-00-00-000"), "and a version that is not there");
	}

	@Test
	@DisplayName("a sheet with no name is refused, because a scene points at one by name")
	void anUnnamedSheetIsNoSheet(@TempDir Path root) {
		PuppetShelf shelf = new PuppetShelf(root);
		assertFalse(shelf.put(new Puppet("", 10, 10, List.of())));
		assertTrue(shelf.all().isEmpty());
		assertNull(shelf.named(""));
	}
}
