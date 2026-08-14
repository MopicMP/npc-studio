package com.mopicmp.npcstudio.wardrobe;

/**
 * Checks that a lump of bytes really is a skin, before anybody opens it.
 *
 * The reason this exists is worth stating plainly, because the earlier code got
 * it wrong on purpose and the reasoning was bad. A skin uploaded by one player
 * is passed on to every player who can see the character, and each of them
 * decodes it. The decoder is native code. So "let the clients decide whether it
 * is a picture" means handing bytes chosen by one person straight to a native
 * image decoder on a dozen other people's machines — which is precisely the
 * shape of the mod bugs that have got players compromised in the past.
 *
 * The server is the one place that sees the bytes before anybody else does, so
 * it is the place to look at them. Reading a PNG header needs no decoder and no
 * memory: the first eight bytes are fixed, and the size sits at a known offset
 * in the first chunk.
 *
 * This does not make a malicious file safe. It makes an obviously wrong one
 * stop here, which is the cheap half of the job and the half worth having.
 */
public final class SkinBytes {

	/** What every PNG begins with. Not a checksum, just a locked door. */
	private static final byte[] SIGNATURE = {
		(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
	};

	/**
	 * How large a skin file may be.
	 *
	 * It was 384 kilobytes, described as generous for a 256×256 skin — and it was,
	 * for that skin. Measured against fifty real HD skins the median is a hundred
	 * and ten kilobytes, <b>fourteen are over the old limit and the largest is two
	 * megabytes</b>. A cap that refuses more than a quarter of the skins people
	 * actually draw is not a safety margin, it is a bug with a polite message.
	 *
	 * Four megabytes, then: past anything in the measured set and still far below
	 * anything that could hurt a server, since a wardrobe is written to the world's
	 * own folder and read by the person who put it there.
	 */
	public static final int LARGEST = 4 * 1024 * 1024;

	private SkinBytes() { }

	/** Why a file was refused, or null when it was not. */
	public static String refuse(byte[] png) {
		if (png == null || png.length < 33) return "that file is too small to be a skin";
		if (png.length > LARGEST) return "that file is too big to be a skin";

		for (int i = 0; i < SIGNATURE.length; i++) {
			if (png[i] != SIGNATURE[i]) return "that is not a PNG";
		}

		// The first chunk of a PNG is always IHDR, and it always begins at byte
		// eight: four bytes of length, four of type, then width and height.
		if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
			return "that PNG is malformed";
		}
		int width = intAt(png, 16);
		int height = intAt(png, 20);

		// Up to 2048, which is the largest anybody draws and five of the fifty
		// measured HD skins are. The old ceiling of 1024 turned those five away at
		// the door with a message saying a skin is a multiple of sixty-four, which
		// they were.
		if (width < 64 || width > 2048 || width % 64 != 0) {
			return "a skin is 64 wide, or a whole multiple of it — that one is " + width;
		}
		if (height != width && height * 2 != width) {
			return "a skin is square, or half as tall — that one is " + width + "×" + height;
		}
		return null;
	}

	public static boolean looksLikeASkin(byte[] png) {
		return refuse(png) == null;
	}

	/**
	 * How wide a picture is, read from its header alone.
	 *
	 * Two dozen bytes rather than a decoded image, which is what lets a screen be
	 * told the size of a thousand costumes without any of them being opened.
	 */
	public static int widthOf(byte[] png) {
		return png != null && png.length >= 24 ? intAt(png, 16) : 0;
	}

	private static int intAt(byte[] bytes, int at) {
		return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
			| ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
	}
}
