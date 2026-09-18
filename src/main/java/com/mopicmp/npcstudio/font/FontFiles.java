package com.mopicmp.npcstudio.font;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A font file as something to identify and to send.
 *
 * <h2>Why a file is known by its contents and not by its name</h2>
 *
 * Because two people will call two different typefaces {@code font.ttf}, and one
 * person will rename the same typeface twice. Named, the first pair silently
 * replace each other and the second pair are downloaded twice; by contents,
 * neither can happen and "have I got this already" is a question with an exact
 * answer.
 *
 * It also settles what is otherwise a nasty little question — what to do when a
 * file changes under the same name — by making it not a case at all: different
 * contents are a different font, and the old one is simply no longer offered.
 *
 * <h2>Why it is cut up</h2>
 *
 * A packet is not elastic. A typeface with Cyrillic in it is a few hundred
 * kilobytes, which is more than one may carry, so it travels in pieces and is put
 * back together at the other end — and put back together <em>and checked</em>,
 * because a piece lost or duplicated gives a file that is almost right, and an
 * almost-right font is a crash inside FreeType rather than a missing letter.
 */
public final class FontFiles {

	private FontFiles() { }

	/**
	 * How much goes in one piece.
	 *
	 * Well under what a packet may hold, because the piece is not the whole packet:
	 * there is a name and a number beside it, and the game wraps the lot. Round and
	 * small enough that being wrong about the overhead cannot matter.
	 */
	public static final int PIECE = 24000;

	/** How many typefaces a server may offer, and how large each may be. */
	public static final int MOST = 32;
	public static final int LARGEST = 2 * 1024 * 1024;

	/**
	 * What a file is called by everyone.
	 *
	 * SHA-256 rather than anything shorter. Not because somebody is expected to
	 * forge a font — though a name that is cheap to collide with is a name that
	 * lets one file be served in place of another — but because it costs nothing
	 * here and removes the question.
	 */
	public static String digest(byte[] file) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
		} catch (NoSuchAlgorithmException impossible) {
			// Every Java implementation is required to have it. If this ever throws,
			// something is wrong that no fallback of ours would improve.
			throw new IllegalStateException("this Java has no SHA-256", impossible);
		}
	}

	/** The file in pieces, in order. An empty file is no pieces rather than one empty one. */
	public static List<byte[]> split(byte[] file) {
		List<byte[]> pieces = new ArrayList<>();
		for (int at = 0; at < file.length; at += PIECE) {
			int end = Math.min(file.length, at + PIECE);
			byte[] piece = new byte[end - at];
			System.arraycopy(file, at, piece, 0, piece.length);
			pieces.add(piece);
		}
		return pieces;
	}

	/**
	 * How many pieces a file of this size makes.
	 *
	 * Told to the far end up front so it knows when it is finished, rather than
	 * waiting for a message that says so — a message that can be the one that goes
	 * missing.
	 */
	public static int pieces(int bytes) {
		return (bytes + PIECE - 1) / PIECE;
	}

	/**
	 * The pieces put back together, or null when they do not make the file expected.
	 *
	 * Checked rather than trusted, and this is the one place in the mod where being
	 * strict about it is not caution but necessity: a font assembled wrong is not a
	 * font with a letter missing, it is a byte stream handed to a C library that
	 * parses it. The digest is the whole reason the transfer can be simple.
	 */
	public static byte[] join(List<byte[]> pieces, String expected) {
		int total = 0;
		for (byte[] piece : pieces) {
			if (piece == null) return null;
			total += piece.length;
		}
		byte[] file = new byte[total];
		int at = 0;
		for (byte[] piece : pieces) {
			System.arraycopy(piece, 0, file, at, piece.length);
			at += piece.length;
		}
		return digest(file).equals(expected) ? file : null;
	}
}
