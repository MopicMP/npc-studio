package com.mopicmp.npcstudio.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mopicmp.npcstudio.entity.BodyShape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The promise this storage makes, checked.
 *
 * The promise is not "it saves costumes" — that would be worth one test. It is
 * that somebody with permissions and a grudge cannot end a project on their way
 * out, and that is worth several, because it is the reason the storage is
 * shaped the way it is rather than being one file with a list in it.
 */
class PictureLibraryTest {

	private static byte[] png(int seed) {
		byte[] bytes = new byte[64];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed + i);
		return bytes;
	}

	@Test
	void aCostumeSurvivesBeingReopened(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("guard", "medieval", "swordsmen", png(1));

		List<PictureLibrary.Entry> reopened = PictureLibrary.wardrobeShelf(root).entries();

		assertEquals(1, reopened.size());
		assertEquals("guard", reopened.get(0).label());
		assertEquals("swordsmen", reopened.get(0).group());
	}

	/**
	 * A build sticks to its costume through everything done to the costume.
	 *
	 * Every one of these rewrites the record, and each was a separate chance to
	 * drop a field that is not the one being changed. Renaming "blacksmith" would
	 * have quietly made him thin again — which reads as a rendering bug, days
	 * later, with nothing to connect it to the rename.
	 */
	@Test
	void aBuildOutlivesFilingAndRenaming(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		PictureLibrary.Entry smith = library.add("smith", "", "", png(1));
		BodyShape broad = new BodyShape(1.4f, 1.1f, 1.2f, 1.3f, 1, 1, 1, 0.5f, 0.2f);
		library.reshape(smith.id(), broad);

		library.rename(smith.id(), "blacksmith");
		library.refile(List.of(smith.id()), "town", "crafts");
		library.dropCategory("town", "crafts");

		assertEquals(broad, PictureLibrary.wardrobeShelf(root).entries().get(0).shape(),
			"the same build, and it survived being reopened from disk");
	}

	/** A copy of a costume is a copy of how it is built, too. */
	@Test
	void aCopyKeepsTheBuild(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		PictureLibrary.Entry guard = library.add("guard", "", "", png(1));
		BodyShape stout = new BodyShape(1.3f, 1, 1.4f, 1, 1, 1, 1, 0.25f, 0f);
		library.reshape(guard.id(), stout);

		library.copy(List.of(guard.id()), "favourites", "");

		assertEquals(stout, library.entries().get(1).shape());
	}

	/** A wardrobe of ordinary costumes reads exactly as it always did. */
	@Test
	void anOrdinaryBuildIsNotWrittenDown(@TempDir Path root) throws Exception {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("plain", "", "", png(1));

		String written = Files.readString(root.resolve("wardrobe.json"));

		assertFalse(written.contains("shape"), "nothing to say, so nothing is said");
		assertEquals(BodyShape.DEFAULT, PictureLibrary.wardrobeShelf(root).entries().get(0).shape());
	}

	/**
	 * A copy weighs nothing, and is its own costume afterwards.
	 *
	 * Both halves matter. The first is why copying is offered at all: the picture
	 * is stored under its own fingerprint, so a costume on three shelves is three
	 * lines of text and one skin. The second is the trap — records that shared an
	 * id would be one costume wearing two hats, and taking it off one shelf would
	 * take it off the others.
	 */
	@Test
	void aCopyIsFreeAndSeparate(@TempDir Path root) throws Exception {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		PictureLibrary.Entry original = library.add("guard", "medieval", "", png(1));

		library.copy(List.of(original.id()), "favourites", "");
		library.copy(List.of(original.id()), "town", "");

		assertEquals(3, library.entries().size(), "three costumes");
		try (var files = Files.list(root.resolve("skins"))) {
			assertEquals(1, files.count(), "and one picture between them");
		}

		// Every copy has the label and the picture, and an identity of its own.
		assertEquals(3, library.entries().stream()
			.filter(entry -> entry.fingerprint().equals(original.fingerprint())).count());
		assertEquals(3, library.entries().stream().map(PictureLibrary.Entry::id).distinct().count());

		// So throwing one away leaves the others where they were.
		library.remove(List.of(original.id()));
		assertEquals(2, library.entries().size());
		assertTrue(library.entries().stream().anyMatch(entry -> entry.category().equals("favourites")));
	}

	/**
	 * The whole point: wiping the library does not wipe the skins.
	 *
	 * This is what makes the disaster survivable. Deleting every record leaves
	 * every picture on disk, and putting a version back brings the costumes with
	 * it — the records were the only thing ever lost.
	 */
	@Test
	void deletingEverythingDestroysNothing(@TempDir Path root) throws Exception {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("one", "", "", png(1));
		library.add("two", "", "", png(2));
		library.add("three", "", "", png(3));

		List<String> everything = library.entries().stream().map(PictureLibrary.Entry::id).toList();
		library.remove(everything);
		assertTrue(library.entries().isEmpty(), "the records are gone");

		try (var files = Files.list(root.resolve("skins"))) {
			assertEquals(3, files.count(), "and every picture is still there");
		}

		// The most recent version is the list as it was before the wipe.
		String previous = library.history().get(0);
		assertTrue(library.restore(previous));
		assertEquals(3, library.entries().size(), "restoring brought the costumes back");
	}

	/** The picture a restored record points at has to still be readable. */
	@Test
	void restoredCostumesStillHaveTheirPictures(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("one", "", "", png(7));
		String fingerprint = library.entries().get(0).fingerprint();

		library.remove(List.of(library.entries().get(0).id()));
		library.restore(library.history().get(0));

		assertNotNull(library.picture(fingerprint), "the picture was never removed");
		assertEquals(1, library.entries().size());
	}

	/** Restoring the wrong version must itself be undoable. */
	@Test
	void restoringIsItselfUndoable(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("one", "", "", png(1));
		library.add("two", "", "", png(2));

		library.remove(library.entries().stream().map(PictureLibrary.Entry::id).toList());
		int versionsAfterWipe = library.history().size();
		library.restore(library.history().get(0));

		assertTrue(library.history().size() > versionsAfterWipe,
			"the state before the restore was kept too");
	}

	/** The same picture added twice is one file, because its name is its content. */
	@Test
	void twoCopiesOfOnePictureAreOneFile(@TempDir Path root) throws Exception {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("winter", "", "", png(5));
		library.add("summer", "", "", png(5));

		assertEquals(2, library.entries().size(), "two costumes");
		try (var files = Files.list(root.resolve("skins"))) {
			assertEquals(1, files.count(), "one picture between them");
		}
	}

	/** Dropping a category keeps what was in it. */
	@Test
	void droppingACategoryKeepsItsCostumes(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("archer", "medieval", "archers", png(1));
		library.add("swordsman", "medieval", "swordsmen", png(2));

		library.dropCategory("medieval", "archers");

		assertEquals(2, library.entries().size(), "nothing was thrown away with the label");
		assertTrue(library.entries().stream().anyMatch(entry -> entry.group().isEmpty()));
	}

	/**
	 * Renaming changes the name and nothing else.
	 *
	 * The identifier is what an NPC wearing this costume points at, so a rename
	 * that changed it would undress every character using it — and the person
	 * renaming would have no idea why. The picture must stay attached too.
	 */
	@Test
	void renamingKeepsTheIdentifierAndThePicture(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("untitled", "medieval", "guards", png(3));
		PictureLibrary.Entry before = library.entries().get(0);

		library.rename(before.id(), "Стражник");
		PictureLibrary.Entry after = library.entries().get(0);

		assertEquals(before.id(), after.id(), "an NPC pointing at this must still find it");
		assertEquals(before.fingerprint(), after.fingerprint(), "and it is still the same picture");
		assertEquals("Стражник", after.label());
		assertEquals("guards", after.group(), "renaming is not refiling");

		// And it survives a reload, which is where a rename that only lived in
		// memory would quietly come undone.
		PictureLibrary reopened = PictureLibrary.wardrobeShelf(root);
		assertEquals("Стражник", reopened.entries().get(0).label());
		assertEquals(before.id(), reopened.entries().get(0).id());
	}

	/** Two costumes must never share an identifier, however fast they are added. */
	@Test
	void identifiersAreUniqueEvenInABurst(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		for (int i = 0; i < 50; i++) library.add("skin " + i, "", "", png(i));

		long distinct = library.entries().stream().map(PictureLibrary.Entry::id).distinct().count();
		assertEquals(50, distinct, "fifty costumes, fifty identifiers");
	}

	/**
	 * Removing one and adding another must not hand the new one the old name.
	 *
	 * The identifier was built from the time and the size of the list, and the
	 * size goes back down when something is removed — so within one millisecond a
	 * new costume could inherit the identifier of the one just deleted, and every
	 * NPC wearing the old one would silently be wearing the new one.
	 */
	@Test
	void anIdentifierIsNeverHandedOnAfterADeletion(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("first", "", "", png(1));
		String gone = library.entries().get(0).id();

		// A hundred rounds, because the first version of this passed by luck: the
		// clock happened to tick between the delete and the add, and one run of one
		// pair proves only that it can go right.
		for (int round = 0; round < 100; round++) {
			library.remove(List.of(library.entries().get(0).id()));
			library.add("again " + round, "", "", png(round));
			assertFalse(library.entries().get(0).id().equals(gone),
				"a dead identifier must not come back on somebody else");
		}
	}

	@Test
	void costumesCanBeMovedBetweenCategories(@TempDir Path root) {
		PictureLibrary library = PictureLibrary.wardrobeShelf(root);
		library.add("one", "medieval", "archers", png(1));
		library.add("two", "medieval", "archers", png(2));

		library.refile(library.entries().stream().map(PictureLibrary.Entry::id).toList(),
			"modern", "police");

		assertTrue(library.entries().stream().allMatch(entry -> entry.group().equals("police")));
		assertFalse(library.entries().stream().anyMatch(entry -> entry.category().equals("medieval")));
	}
}
