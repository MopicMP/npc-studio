package com.mopicmp.npcstudio.client.skin;

/**
 * A skin's face at the size it was actually drawn, and in the layers it was
 * drawn in.
 *
 * <h2>Why the second layer is not an afterthought</h2>
 *
 * A head is drawn twice: the face itself at u 8..16, and an outer layer over it
 * at u 40..48 which the game inflates and draws on top. Everything here used to
 * read the first and ignore the second, on the reasonable-sounding grounds that
 * the outer layer is a hat.
 *
 * It is not a hat. Measured over fifty HD skins, <b>forty-nine draw something on
 * it over the face and thirty-three cover most of the face with it</b> — fringes,
 * hoods, ears, glasses, whole faces. On several of them the eyes underneath are
 * completely hidden, so the old reading was finding eyes nobody can see and
 * would have blinked them behind somebody's hair.
 *
 * So the face is the two composited, which is what a player is looking at. Both
 * layers are kept separately as well, because somebody marking a face by hand
 * needs to be able to see what is under the fringe to decide whether it counts.
 *
 * <h2>And it is native, not sampled</h2>
 *
 * No reduction to eight by eight. See {@link com.mopicmp.npcstudio.entity.FaceMask}
 * for the measurement that settles it: there is no fixed grid that describes
 * these faces, only the grid the artist used.
 *
 * <h2>The old half-height layout</h2>
 *
 * A skin is 64 wide and either 64 or <b>32</b> tall — the layout from before 1.8,
 * which is still perfectly legal and which fourteen of the fifty measured HD skins
 * use. The face sits at the same place in both, so reading it needs no special
 * case; <em>drawing</em> it does, because a texture is addressed as a fraction of
 * its own height and half the height means twice the fraction. Getting that wrong
 * put the wrong rows of seven skins on the screen and made them look like broken
 * pictures. So the layout travels with the face.
 *
 * @param size  how many pixels the face is across — eight on an ordinary skin,
 *              up to two hundred and fifty-six on a 2048-wide one
 * @param tall  how tall the skin is in sixty-fourths: 64, or 32 for the old
 *              layout. What a blit has to be told, and nothing else cares
 * @param both  what a player sees: the outer layer over the face
 * @param base  the face alone
 * @param hat   the outer layer alone, transparent where there is none
 */
public record FacePicture(int size, int tall, int[] both, int[] base, int[] hat) {

	/** Where the face and its outer layer sit, in sixty-fourths. */
	public static final int FACE_LEFT = 8;
	public static final int FACE_TOP = 8;
	public static final int HAT_LEFT = 40;
	public static final int HAT_TOP = 8;
	public static final int SPAN = 8;

	/** Which of the three a person is looking at while marking. */
	public enum Layer { BOTH, BASE, HAT }

	public int[] of(Layer layer) {
		return switch (layer) {
			case BOTH -> both;
			case BASE -> base;
			case HAT -> hat;
		};
	}

	public int at(Layer layer, int x, int y) {
		if (x < 0 || y < 0 || x >= size || y >= size) return 0;
		return of(layer)[y * size + x];
	}

	/**
	 * Reads both layers out of a skin of any size.
	 *
	 * A skin is 64 wide times a power of two, so the face is eight times the same
	 * power and no sampling is needed anywhere — every pixel of the picture that
	 * belongs to the face is kept.
	 *
	 * The one thing capped is how fine it may get: see
	 * {@link com.mopicmp.npcstudio.entity.FaceMask#LARGEST}. Past that a grid is
	 * finer than a person can aim at, and a mask made on it would not be something
	 * anybody made.
	 */
	public static FacePicture read(com.mojang.blaze3d.platform.NativeImage image) {
		if (image == null) return null;
		int width = image.getWidth();
		if (width < 64 || width % 64 != 0) return null;

		int scale = width / 64;
		// 32 for the old half-height layout, 64 for everything since. Worked out
		// from the picture rather than assumed, and carried out of here because a
		// blit cannot tell the difference and has to be told.
		int tall = image.getHeight() * 2 <= width ? 32 : 64;
		int size = Math.min(SPAN * scale, com.mopicmp.npcstudio.entity.FaceMask.LARGEST);
		// If the skin is finer than we mark at, take the block's middle rather than
		// its corner: on a picture this detailed a corner is as likely to be the
		// outline round a feature as the feature.
		int step = Math.max(1, SPAN * scale / size);

		int[] base = new int[size * size];
		int[] hat = new int[size * size];
		int[] both = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int sx = x * step + step / 2;
				int sy = y * step + step / 2;
				int under = image.getPixel(FACE_LEFT * scale + sx, FACE_TOP * scale + sy);
				int over = inside(image, HAT_LEFT * scale + sx, HAT_TOP * scale + sy)
					? image.getPixel(HAT_LEFT * scale + sx, HAT_TOP * scale + sy) : 0;
				base[y * size + x] = under;
				hat[y * size + x] = over;
				both[y * size + x] = over(under, over);
			}
		}
		return new FacePicture(size, tall, both, base, hat);
	}

	/**
	 * The outer layer laid over the face, the way the game draws it.
	 *
	 * Straight alpha compositing rather than a threshold, because an outer layer
	 * drawn with a soft edge — which several of the measured skins have — reads as
	 * a hard cut-out otherwise, and somebody marking the eye under it would be
	 * marking against a picture the game never shows.
	 */
	private static int over(int under, int above) {
		int alpha = (above >>> 24) & 0xFF;
		if (alpha == 0) return under;
		if (alpha == 255) return above;
		int back = (under >>> 24) & 0xFF;
		int out = alpha + back * (255 - alpha) / 255;
		if (out == 0) return 0;
		return (out << 24)
			| mix(above, under, alpha, back, out, 16) << 16
			| mix(above, under, alpha, back, out, 8) << 8
			| mix(above, under, alpha, back, out, 0);
	}

	private static int mix(int above, int under, int alpha, int back, int out, int shift) {
		int top = (above >>> shift) & 0xFF;
		int bottom = (under >>> shift) & 0xFF;
		return Math.clamp((top * alpha + bottom * back * (255 - alpha) / 255) / out, 0, 255);
	}

	private static boolean inside(com.mojang.blaze3d.platform.NativeImage image, int x, int y) {
		return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight();
	}

	/** The face reduced to the eight-by-eight everything else still speaks. */
	public int[] eighths() {
		return eighths(true);
	}

	/**
	 * The same, of one layer or of both.
	 *
	 * <h2>Why this is a choice rather than a fact</h2>
	 *
	 * Both is what a player sees and is therefore what anybody marking a face
	 * should be looking at. It is <em>not</em> what the reading is any good at, and
	 * that was measured rather than assumed: against eighty-one faces marked by
	 * hand, the composite takes the rows a blink lands on from six of twenty-five up
	 * to nine on HD skins and from forty-one of fifty-six down to twenty on ordinary
	 * ones.
	 *
	 * The reason is not that a fringe hides the eyes — on every one of the
	 * twenty-one faces it broke, the marked eyes are entirely in the clear. It is
	 * that this reading chooses its band of rows by comparing the whole face, and a
	 * fringe changes the rest of the face. Which is a reading that needs building
	 * for the composite rather than talked into it.
	 *
	 * @param over whether to lay the outer layer over the face
	 */
	public int[] eighths(boolean over) {
		int span = com.mopicmp.npcstudio.entity.EyeMap.SIZE;
		int[] from = over ? both : base;
		if (size == span) return from.clone();

		int step = size / span;
		int[] face = new int[span * span];
		// Whichever pixel of the block is least like the face's own colour, the same
		// rule the reading uses — an eye drawn as a one-pixel line on a detailed skin
		// lives in a corner of its block, and the middle is the cheek beside it.
		int[] middles = new int[span * span];
		for (int y = 0; y < span; y++) {
			for (int x = 0; x < span; x++) {
				middles[y * span + x] = both[(y * step + step / 2) * size + x * step + step / 2];
			}
		}
		int skin = com.mopicmp.npcstudio.skin.FaceLook.complexion(middles);
		for (int y = 0; y < span; y++) {
			for (int x = 0; x < span; x++) {
				int furthest = middles[y * span + x];
				int worst = -1;
				for (int dy = 0; dy < step; dy++) {
					for (int dx = 0; dx < step; dx++) {
						int colour = both[(y * step + dy) * size + x * step + dx];
						if ((colour >>> 24) < 128) continue;
						int away = com.mopicmp.npcstudio.skin.FaceLook.apart(colour, skin);
						if (away > worst) {
							worst = away;
							furthest = colour;
						}
					}
				}
				face[y * span + x] = furthest;
			}
		}
		return face;
	}
}
