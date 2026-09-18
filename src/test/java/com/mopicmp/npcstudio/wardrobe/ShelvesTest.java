package com.mopicmp.npcstudio.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mopicmp.npcstudio.net.WardrobePayloads;

/**
 * Two shelves in one cupboard.
 *
 * <h2>What this is guarding</h2>
 *
 * The portraits went onto the machinery the costumes already had — pictures named by
 * their own fingerprint, never deleted, a versioned list beside them, delivery to any
 * player who asks. That was the whole argument for putting them there: the part of
 * the plan that said "work out how a portrait reaches another player" turned out to
 * be already written.
 *
 * What could go wrong with reusing it is exactly one thing: the two shelves quietly
 * becoming one. A portrait in the costume picker, a costume in the portrait picker,
 * or worse — a folder deleted on one shelf taking records off the other.
 */
class ShelvesTest {

	private static final byte[] PICTURE = pngOf(1);
	private static final byte[] OTHER = pngOf(2);

	/**
	 * A file that is long enough to be accepted and different from its neighbour.
	 *
	 * The shelf refuses anything under thirty-three bytes as too small to be a
	 * picture, and files with the same bytes are one file by design — so a test that
	 * wants two entries has to hand it two different ones.
	 */
	private static byte[] pngOf(int seed) {
		byte[] bytes = new byte[64];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * seed + seed);
		return bytes;
	}

	@Test
	@DisplayName("the two shelves keep separate lists and separate folders")
	void neitherShelfSeesTheOther(@TempDir Path root) {
		PictureLibrary costumes = PictureLibrary.wardrobeShelf(root);
		PictureLibrary portraits = PictureLibrary.portraitShelf(root);

		costumes.add("guard", "town", "", PICTURE);
		portraits.add("Lona calm", "town", "", OTHER);

		assertEquals(1, costumes.entries().size(), "one costume, and not the portrait");
		assertEquals(1, portraits.entries().size(), "one portrait, and not the costume");
		assertEquals("guard", costumes.entries().get(0).label());
		assertEquals("Lona calm", portraits.entries().get(0).label());

		// The same folder name on both, which is the case a shared pile would get
		// wrong: taking the town folder off the portraits must not touch the town
		// folder of the costumes.
		portraits.dropCategory("town", "");
		assertEquals(1, portraits.entries().size(),
			"taking a folder away unfiles its pictures rather than deleting them");
		assertEquals("", portraits.entries().get(0).category(),
			"they come out to the top level, where they can be seen and refiled");
		assertEquals("town", costumes.entries().get(0).category(),
			"and the costumes filed under the same word are untouched");
	}

	@Test
	@DisplayName("each shelf writes its own files, and neither treads on the other")
	void separateFilesOnDisk(@TempDir Path root) {
		PictureLibrary.wardrobeShelf(root).add("guard", "", "", PICTURE);
		PictureLibrary.portraitShelf(root).add("Lona", "", "", OTHER);

		assertTrue(Files.isDirectory(root.resolve("skins")));
		assertTrue(Files.isDirectory(root.resolve("portraits")));
		assertTrue(Files.isRegularFile(root.resolve("wardrobe.json")));
		assertTrue(Files.isRegularFile(root.resolve("portraits.json")));

		// Reopened from disk rather than trusted in memory: what the map is handed to
		// somebody as is the folder, and the folder is what has to say the right thing.
		assertEquals(List.of("guard"),
			PictureLibrary.wardrobeShelf(root).entries().stream().map(PictureLibrary.Entry::label).toList());
		assertEquals(List.of("Lona"),
			PictureLibrary.portraitShelf(root).entries().stream().map(PictureLibrary.Entry::label).toList());
	}

	@Test
	@DisplayName("a picture is still found by fingerprint on whichever shelf it is on")
	void fetchingCrossesShelves(@TempDir Path root) {
		// The delivery packet does not name a shelf, and should not: it asks for a
		// picture by its fingerprint, and a portrait is exactly as much a picture with
		// a fingerprint as a costume is. What has to hold is that both are findable.
		PictureLibrary costumes = PictureLibrary.wardrobeShelf(root);
		PictureLibrary portraits = PictureLibrary.portraitShelf(root);
		String one = costumes.add("guard", "", "", PICTURE).fingerprint();
		String other = portraits.add("Lona", "", "", OTHER).fingerprint();

		assertNotNull(costumes.picture(one));
		assertNotNull(portraits.picture(other));
		// And neither shelf hands out the other's, which is what makes looking on both
		// a wider book rather than a wider door.
		assertNull(costumes.picture(other));
		assertNull(portraits.picture(one));
	}

	@Test
	@DisplayName("an unknown shelf name means the costumes, which is what every old packet meant")
	void anUnknownShelfIsTheOldOne() {
		// The name arrives from a client. A word has a safe reading and a number does
		// not — an unknown number would be an index into an array.
		assertTrue(WardrobePayloads.Shelves.portraits(WardrobePayloads.Shelves.PORTRAITS));
		assertFalse(WardrobePayloads.Shelves.portraits(WardrobePayloads.Shelves.WARDROBE));
		assertFalse(WardrobePayloads.Shelves.portraits("nonsense"));
		assertFalse(WardrobePayloads.Shelves.portraits(""));
	}

	@Test
	@DisplayName("deleting a portrait deletes the record and never the picture")
	void thePictureSurvivesTheRecord(@TempDir Path root) {
		// The shelf's oldest promise, and the reason a folder is safe to delete at all:
		// somebody with a grudge and permissions must not be able to end a project on
		// their way out.
		PictureLibrary portraits = PictureLibrary.portraitShelf(root);
		var entry = portraits.add("Lona", "", "", PICTURE);
		Path file = root.resolve("portraits").resolve(entry.fingerprint() + ".png");
		assertTrue(Files.isRegularFile(file));

		portraits.remove(List.of(entry.id()));
		assertTrue(portraits.entries().isEmpty(), "the record is gone");
		assertTrue(Files.isRegularFile(file), "and the picture is still there");
	}
}
