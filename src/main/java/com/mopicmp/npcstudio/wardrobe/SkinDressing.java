package com.mopicmp.npcstudio.wardrobe;

/**
 * Putting a costume on a face.
 *
 * A costume is a body: the same person in work clothes, party clothes and
 * everyday clothes is one head and three bodies. So wearing one means keeping
 * the head that is there and replacing everything below it.
 *
 * Except when it does not. A costume can come with spectacles, or a hat, or an
 * earring — and those live on the head. A rule that never touches the head is
 * right for most costumes and wrong for exactly the ones somebody took trouble
 * over.
 *
 * <h2>The mask is the picture</h2>
 *
 * So nothing is declared and no setting has to be understood. A purpose-made
 * costume is transparent where it does not apply, and what it paints is what it
 * claims: a body left transparent above the neck touches no head, and a pair of
 * spectacles painted across the eyes replaces exactly those pixels.
 *
 * That leaves one case the mask cannot answer. A whole skin used as a costume is
 * opaque everywhere, head included, so read literally it would replace the face
 * as well — which is the one thing a costume must not do. It is told apart by
 * looking: a head that is almost entirely painted belongs to a skin, and a head
 * that is mostly empty belongs to a costume. Nobody has to say which they have.
 */
public final class SkinDressing {

	/**
	 * The head and its hat: the top quarter of a skin.
	 *
	 * Exact rather than approximate. In the modern layout everything above row
	 * sixteen is the head — its six sides on the left half, its hat on the right
	 * — and everything below is body, arms and legs.
	 */
	public static final int HEAD_ROWS = 16;

	/** A pixel counts as painted at this alpha. Below it, the costume is not claiming it. */
	private static final int PAINTED = 16;

	/**
	 * How much of the head has to be painted before the picture is a whole skin
	 * rather than a costume.
	 *
	 * Well clear of both cases: a costume with spectacles paints a handful of
	 * pixels up there, and a skin paints nearly all of them.
	 */
	private static final double A_WHOLE_SKIN = 0.5;

	/** What to do about the head, when somebody wants to override the reading. */
	public enum Head { AUTOMATIC, KEEP, REPLACE }

	private SkinDressing() { }

	/**
	 * Puts a costume on, whatever sizes the two happen to be.
	 *
	 * Skins come at 64 across and at whole multiples of it, and there is no
	 * reason a character in a detailed costume should have a detailed face — so
	 * the two will not always match. The smaller is enlarged to meet the larger
	 * rather than the larger reduced, because throwing away detail somebody drew
	 * is the one outcome nobody asked for.
	 *
	 * Enlarging is pixel doubling and nothing cleverer. Blending would turn crisp
	 * pixel art into mush, and it is exact anyway: doubling a 64 into a 128 loses
	 * nothing and invents nothing.
	 */
	public static Worn wear(int[] base, int baseWidth, int[] costume, int costumeWidth, Head head) {
		// Half-height skins are a different layout, not a different size, and
		// pretending otherwise would line the arms up with the legs. Refused rather
		// than composited into nonsense.
		if (!squareish(base, baseWidth) || !squareish(costume, costumeWidth)) {
			return new Worn(base, baseWidth, "one of these is an old half-height skin");
		}

		int common = Math.max(baseWidth, costumeWidth);
		int[] under = enlarge(base, baseWidth, common);
		int[] over = enlarge(costume, costumeWidth, common);
		return new Worn(wear(under, over, common, head), common, null);
	}

	/** The result, its size, and a reason if nothing could be done. */
	public record Worn(int[] pixels, int width, String refused) { }

	private static boolean squareish(int[] pixels, int width) {
		return width > 0 && pixels.length == width * width;
	}

	/**
	 * Doubles a picture up to a larger size by repeating pixels.
	 *
	 * Only whole multiples, which is all a skin ever is. Anything else comes back
	 * untouched rather than being stretched into something between two sizes.
	 */
	public static int[] enlarge(int[] pixels, int from, int to) {
		if (to == from || from <= 0 || to % from != 0) return pixels;
		int step = to / from;
		int[] bigger = new int[to * to];
		for (int y = 0; y < to; y++) {
			int source = (y / step) * from;
			for (int x = 0; x < to; x++) {
				bigger[y * to + x] = pixels[source + x / step];
			}
		}
		return bigger;
	}

	/**
	 * Lays a costume over a base skin.
	 *
	 * @param base    the character as it is now; its head is what is being kept
	 * @param costume the clothes, in the same layout and size
	 * @param width   how wide both are, in pixels
	 * @return a new picture; neither argument is touched
	 */
	public static int[] wear(int[] base, int[] costume, int width, Head head) {
		int[] worn = base.clone();
		int scale = Math.max(1, width / 64);
		int headRows = HEAD_ROWS * scale;
		boolean keepHead = switch (head) {
			case KEEP -> true;
			case REPLACE -> false;
			case AUTOMATIC -> looksLikeAWholeSkin(costume, width);
		};

		for (int i = 0; i < worn.length && i < costume.length; i++) {
			if (keepHead && i / width < headRows) continue;
			int colour = costume[i];
			// Transparent means "not mine". Anything painted is claimed, wherever it
			// is — which is how spectacles reach the face without a setting for it.
			if ((colour >>> 24) >= PAINTED) worn[i] = colour;
		}
		return worn;
	}

	/**
	 * Whether a picture is a whole skin rather than a costume.
	 *
	 * Decided by how much of the head is painted, because that is the one place
	 * the two differ reliably. A costume may well have something up there; it
	 * will not have a whole face.
	 */
	public static boolean looksLikeAWholeSkin(int[] pixels, int width) {
		int scale = Math.max(1, width / 64);
		int headRows = HEAD_ROWS * scale;
		// Only the left half of those rows: the right half is the hat layer, which
		// is empty on a great many perfectly ordinary skins and would drag the
		// count down for no reason.
		int half = width / 2;

		int painted = 0;
		int counted = 0;
		for (int y = 0; y < headRows; y++) {
			for (int x = 0; x < half; x++) {
				int at = y * width + x;
				if (at >= pixels.length) continue;
				counted++;
				if ((pixels[at] >>> 24) >= PAINTED) painted++;
			}
		}
		return counted > 0 && (double) painted / counted > A_WHOLE_SKIN;
	}
}
