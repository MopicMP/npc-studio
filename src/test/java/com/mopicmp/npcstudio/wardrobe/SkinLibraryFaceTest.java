package com.mopicmp.npcstudio.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mopicmp.npcstudio.entity.EyeMap;

/**
 * That a face marked out by hand survives being written down and read back.
 *
 * Written after it did not. The map was added to the library's own record and
 * the record was assumed to be what gets saved — but the file is built field by
 * field, by hand, and nobody had added a field for it. Marking worked, the file
 * was rewritten every time, and thirty-six faces went into the bin.
 *
 * The failure was not subtle and it was not found by reading the code, because
 * reading the code is what produced it. It was found by opening the file. This
 * test opens the file.
 */
class SkinLibraryFaceTest {

	@TempDir
	Path root;

	private static final EyeMap MARKED = new EyeMap(
		0x0000240000000000L, 0x0000180000000000L, 0x0000000000000042L, true);

	@Test
	@DisplayName("a marked face is still there after the wardrobe is written and read")
	void aMarkedFaceSurvivesTheFile() throws Exception {
		SkinLibrary library = new SkinLibrary(root);
		String id = library.add("a face", "", "", new byte[0]).id();

		library.relook(id, com.mopicmp.npcstudio.entity.FaceMask.of(MARKED));
		assertTrue(Files.exists(root.resolve("wardrobe.json")), "nothing was written");

		SkinLibrary reopened = new SkinLibrary(root);
		EyeMap back = reopened.find(id).orElseThrow().eyes();

		assertEquals(MARKED.eyes(), back.eyes(), "the eyes came back different");
		assertEquals(MARKED.whites(), back.whites(), "the whites came back different");
		assertEquals(MARKED.brows(), back.brows(), "the brows came back different");
		assertTrue(back.authored(), "the file forgot that a person marked this");
	}

	/**
	 * The top bit of a mask is the bottom right pixel of a face, and it survives.
	 *
	 * Sixty-four bits written as a decimal number and read back through a JSON
	 * parser is a thing to check rather than to assume, which is why the file
	 * keeps them as hex.
	 */
	@Test
	@DisplayName("a mask with every bit set comes back with every bit set")
	void theTopBitSurvives() throws Exception {
		SkinLibrary library = new SkinLibrary(root);
		String id = library.add("every pixel", "", "", new byte[0]).id();

		library.relook(id, com.mopicmp.npcstudio.entity.FaceMask.of(
			new EyeMap(-1L, -1L, -1L, true)));
		EyeMap back = new SkinLibrary(root).find(id).orElseThrow().eyes();

		assertEquals(-1L, back.eyes(), "the eyes lost a bit");
		assertEquals(-1L, back.whites(), "the whites lost a bit");
		assertEquals(-1L, back.brows(), "the brows lost a bit");
	}

	/**
	 * And a face marked at the size the skin was drawn survives at that size.
	 *
	 * The same failure as the one this file was written for, one resolution up: the
	 * fine mask is what a person actually made, and reducing it to eighths on the
	 * way into the file would throw away the afternoon they spent making it while
	 * leaving everything looking as though it had worked.
	 */
	@Test
	@DisplayName("a face marked at native size comes back at native size")
	void aFineMaskSurvivesTheFile() throws Exception {
		SkinLibrary library = new SkinLibrary(root);
		String id = library.add("an HD face", "", "", new byte[0]).id();

		int size = 128;
		var kind = com.mopicmp.npcstudio.entity.FaceMask.Kind.EYE;
		// On the outer layer, which is where HD skins commonly put an iris — and the
		// half of the answer that had nowhere to live before.
		var layer = com.mopicmp.npcstudio.entity.FaceMask.Layer.OVER;
		long[][] masks = new long[com.mopicmp.npcstudio.entity.FaceMask.PARTS]
			[com.mopicmp.npcstudio.entity.FaceMask.words(size)];
		long[] eyes = masks[com.mopicmp.npcstudio.entity.FaceMask.part(kind, layer)];
		for (int y = 60; y < 70; y++) {
			for (int x = 20; x < 34; x++) {
				int bit = y * size + x;
				eyes[bit >>> 6] |= 1L << (bit & 63);
			}
		}
		var fine = new com.mopicmp.npcstudio.entity.FaceMask(size, masks, true);

		library.relook(id, fine);
		var back = new SkinLibrary(root).find(id).orElseThrow().face();

		assertEquals(fine, back, "the fine mask did not survive the file");
		assertEquals(size, back.size(), "and it has to come back the size it was made");
		assertTrue(back.is(kind, layer, 20, 60) && back.is(kind, layer, 33, 69),
			"with its own pixels");
		assertTrue(!back.is(kind, layer, 19, 60), "and no others");
		assertTrue(!back.is(kind, com.mopicmp.npcstudio.entity.FaceMask.Layer.FACE, 20, 60),
			"and on the layer it was marked on");
	}

	/**
	 * Everything else a costume can have done to it leaves its face alone.
	 *
	 * Renaming, refiling, copying and emptying a category all rebuild the record,
	 * and all four of them rebuilt it without the face. Nothing about that was
	 * visible at the time — the character carried on blinking with the copy it had
	 * been dressed in — so what it looked like from the outside was the mod having
	 * forgotten the marking some time between one visit and the next, and made up
	 * its own.
	 */
	@Test
	@DisplayName("renaming, refiling and copying a costume keep the face it was marked with")
	void theFaceSurvivesEverythingElse() throws Exception {
		SkinLibrary library = new SkinLibrary(root);
		String id = library.add("before", "shelf", "group", new byte[0]).id();
		var marked = com.mopicmp.npcstudio.entity.FaceMask.of(MARKED);
		library.relook(id, marked);

		library.rename(id, "after");
		assertEquals(marked, library.find(id).orElseThrow().face(), "renaming lost the face");

		library.refile(java.util.List.of(id), "another", "elsewhere");
		assertEquals(marked, library.find(id).orElseThrow().face(), "refiling lost the face");

		library.copy(java.util.List.of(id), "third", "");
		var copy = library.entries().stream()
			.filter(entry -> entry.category().equals("third")).findFirst().orElseThrow();
		assertEquals(marked, copy.face(), "the copy came out with no face");

		library.dropCategory("another", "elsewhere");
		assertEquals(marked, library.find(id).orElseThrow().face(),
			"emptying a category lost the face");

		// And all of it is still true once the file has been round-tripped.
		assertEquals(marked, new SkinLibrary(root).find(id).orElseThrow().face());
	}

	/** A costume nobody has looked at stays as short in the file as it was. */
	@Test
	@DisplayName("a costume nobody marked writes no face at all")
	void anUnmarkedCostumeWritesNothing() throws Exception {
		SkinLibrary library = new SkinLibrary(root);
		library.add("plain", "", "", new byte[0]);
		assertTrue(!Files.readString(root.resolve("wardrobe.json")).contains("\"face\""),
			"an unmarked costume wrote a face anyway");
	}
}
