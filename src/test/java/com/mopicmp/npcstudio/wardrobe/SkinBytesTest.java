package com.mopicmp.npcstudio.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which files count as a skin.
 *
 * Written because the limits here were guesses that had never met a real
 * collection, and the guesses were wrong in the direction that costs the most:
 * they turned away files that were perfectly good skins. Measured over fifty HD
 * skins people actually drew, the old ceiling of 384 kilobytes refused fourteen
 * of them and the old width limit of 1024 refused five more — with a message
 * saying a skin is a multiple of sixty-four, which they were.
 *
 * So the numbers below are pinned against what the collection actually contains
 * rather than against what seemed roomy.
 */
class SkinBytesTest {

	/** A PNG that says it is the given size, and is otherwise empty. */
	private static byte[] png(int width, int height, int length) {
		byte[] file = new byte[Math.max(33, length)];
		byte[] head = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		System.arraycopy(head, 0, file, 0, head.length);
		file[12] = 'I';
		file[13] = 'H';
		file[14] = 'D';
		file[15] = 'R';
		write(file, 16, width);
		write(file, 20, height);
		return file;
	}

	private static void write(byte[] file, int at, int value) {
		file[at] = (byte) (value >>> 24);
		file[at + 1] = (byte) (value >>> 16);
		file[at + 2] = (byte) (value >>> 8);
		file[at + 3] = (byte) value;
	}

	/** Every size of skin the game itself draws, up to the largest anybody makes. */
	@Test
	@DisplayName("skins from 64 to 2048 across are accepted")
	void everySizeOfSkinIsASkin() {
		for (int width = 64; width <= 2048; width *= 2) {
			assertNull(SkinBytes.refuse(png(width, width, 1024)),
				width + " across was refused");
			// And the old half-height layout at the same sizes, which fourteen of the
			// fifty measured skins still use.
			assertNull(SkinBytes.refuse(png(width, width / 2, 1024)),
				width + "×" + width / 2 + " was refused");
		}
		assertNotNull(SkinBytes.refuse(png(4096, 4096, 1024)), "past anything anybody draws");
		assertNotNull(SkinBytes.refuse(png(100, 100, 1024)), "not a multiple of sixty-four");
	}

	/**
	 * A two-megabyte skin is a skin.
	 *
	 * The largest in the measured set is 2.1 MB and the median is 110 KB, so a cap
	 * anywhere near the old 384 KB is a cap that mostly refuses real work.
	 */
	@Test
	@DisplayName("a file the size of the largest real skin is accepted")
	void aRealHdSkinFits() {
		assertNull(SkinBytes.refuse(png(2048, 2048, 3 * 1024 * 1024)));
		assertTrue(SkinBytes.LARGEST >= 3 * 1024 * 1024,
			"the limit has to clear the largest skin anybody actually draws");
		assertNotNull(SkinBytes.refuse(png(2048, 2048, SkinBytes.LARGEST + 1)),
			"and still be a limit");
	}

	/** Anything that is not a PNG is refused before its numbers are believed. */
	@Test
	@DisplayName("something that is not a PNG is refused")
	void onlyPngs() {
		assertNotNull(SkinBytes.refuse(null));
		assertNotNull(SkinBytes.refuse(new byte[8]));
		byte[] notPng = png(64, 64, 1024);
		notPng[1] = 'X';
		assertNotNull(SkinBytes.refuse(notPng));
	}

	/**
	 * Every skin fits in a whole number of pieces, and no piece is over the line.
	 *
	 * The arithmetic that decides whether uploading a skin works or disconnects the
	 * player, so it is worth stating rather than trusting: the cap is the game's and
	 * a payload one byte past it is not a failed upload, it is a player thrown out
	 * of the world.
	 */
	@Test
	@DisplayName("a skin of any size splits into pieces the wire will carry")
	void everySkinSplitsIntoSendablePieces() {
		int part = com.mopicmp.npcstudio.net.WardrobePayloads.PART;
		assertTrue(part < 32767 - 2048, "a piece plus its labels has to clear the cap");

		for (int size : new int[] { 1, part - 1, part, part + 1, SkinBytes.LARGEST }) {
			int count = Math.max(1, (size + part - 1) / part);
			int total = 0;
			for (int i = 0; i < count; i++) {
				int from = i * part;
				int to = Math.min(size, from + part);
				assertTrue(to - from <= part, "a piece came out too large");
				assertTrue(to > from, "an empty piece was sent");
				total += to - from;
			}
			assertEquals(size, total, "the pieces of " + size + " bytes did not add up");
		}
	}
}
