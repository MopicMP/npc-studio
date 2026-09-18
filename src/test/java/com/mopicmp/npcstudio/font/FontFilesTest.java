package com.mopicmp.npcstudio.font;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cutting a typeface up and putting it back.
 *
 * <h2>What is being protected, and what is not</h2>
 *
 * Not the protocol. A test written against my own idea of the other end proves
 * only that I am consistent with myself — a lesson this project has already
 * written down — and there is no second machine here to talk to. Whether a real
 * client and a real server agree is a thing to find out by running both.
 *
 * What <em>is</em> arithmetic, and therefore worth pinning: that the pieces add
 * back up, that a file whose length lands exactly on a boundary does not gain a
 * phantom empty piece, and above all that a damaged assembly is refused. That
 * last one is not tidiness. A font put back together wrongly is not a letter
 * missing; it is a byte stream handed to a C library that parses it.
 */
class FontFilesTest {

	private static byte[] of(int length) {
		byte[] made = new byte[length];
		for (int at = 0; at < length; at++) made[at] = (byte) (at * 31 + 7);
		return made;
	}

	@Test
	@DisplayName("the pieces add back up to the file")
	void roundTrip() {
		byte[] file = of(FontFiles.PIECE * 2 + 13);
		List<byte[]> pieces = FontFiles.split(file);

		assertEquals(3, pieces.size());
		assertEquals(3, FontFiles.pieces(file.length));
		assertArrayEquals(file, FontFiles.join(pieces, FontFiles.digest(file)));
	}

	@Test
	@DisplayName("a file that ends exactly on a boundary makes no empty piece")
	void exactMultiple() {
		// The off-by-one that would show up as a transfer that never finishes: one
		// more piece expected than will ever be sent.
		byte[] file = of(FontFiles.PIECE * 2);
		assertEquals(2, FontFiles.split(file).size());
		assertEquals(2, FontFiles.pieces(file.length));
	}

	@Test
	@DisplayName("nothing is no pieces, not one empty one")
	void emptiness() {
		assertTrue(FontFiles.split(new byte[0]).isEmpty());
		assertEquals(0, FontFiles.pieces(0));
	}

	@Test
	@DisplayName("a smaller file is one piece")
	void smallFile() {
		byte[] file = of(10);
		assertEquals(1, FontFiles.split(file).size());
		assertArrayEquals(file, FontFiles.join(FontFiles.split(file), FontFiles.digest(file)));
	}

	@Test
	@DisplayName("a damaged file is refused rather than assembled")
	void damageIsCaught() {
		byte[] file = of(FontFiles.PIECE + 5);
		String digest = FontFiles.digest(file);

		List<byte[]> pieces = new ArrayList<>(FontFiles.split(file));
		pieces.get(0)[0]++;
		assertNull(FontFiles.join(pieces, digest), "one byte different is a different file");

		// Out of order is the same fault wearing different clothes.
		List<byte[]> swapped = new ArrayList<>(FontFiles.split(file));
		java.util.Collections.reverse(swapped);
		assertNull(FontFiles.join(swapped, digest));

		// And a piece that never arrived.
		List<byte[]> missing = new ArrayList<>(FontFiles.split(file));
		missing.set(1, null);
		assertNull(FontFiles.join(missing, digest));
	}

	@Test
	@DisplayName("two different files are never the same name")
	void digestsDiffer() {
		assertNotEquals(FontFiles.digest(of(100)), FontFiles.digest(of(101)));
		assertEquals(FontFiles.digest(of(100)), FontFiles.digest(of(100)));
		// Sixty-four hex characters, which is what the wire is sized for.
		assertEquals(64, FontFiles.digest(of(1)).length());
	}

	@Test
	@DisplayName("the largest file allowed still fits the piece count the wire expects")
	void theCapsAgree() {
		// Two numbers that have to be consistent and live apart: the receiver refuses
		// a claim of more pieces than the largest allowed file could make. Chosen
		// wrongly, a legitimate typeface at the size limit would be thrown away.
		assertTrue(FontFiles.pieces(FontFiles.LARGEST) >= FontFiles.split(of(FontFiles.LARGEST)).size());
	}
}
