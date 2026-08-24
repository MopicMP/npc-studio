package com.mopicmp.npcstudio.client.scene;

/**
 * A colour used as a filter over something else, rather than in place of it.
 *
 * <h2>Why the sky's colour cannot simply be painted on</h2>
 *
 * Because the sky is not one thing. The dome overhead is a flat disc drawn in
 * one colour, and painting that is exactly as simple as it sounds — but the band
 * along the horizon, and the haze that distant land fades into, are the
 * <em>fog</em>, and the fog is also what carries the time of day and the weather.
 * Replace it with a colour and midnight is as bright as noon, and rain stops
 * making anything grey. That was the whole of "the sky colour only changes part
 * of the sky": one of the two was being set and the other was not.
 *
 * So the chosen colour is turned into a filter — its own shape with its
 * brightest channel taken as one — and the fog is multiplied by it. A green sky
 * makes a green horizon that is still dark at night, still pale at distance and
 * still washed out in a storm. White changes nothing at all, which is the
 * property that matters most: a scene that has said nothing about the sky must
 * look exactly as it did.
 *
 * <h2>The sun is filtered the same way, and was not</h2>
 *
 * It was multiplied by the plain colour over two hundred and fifty-five, on the
 * argument that a sun is a light and dimming a light is a thing somebody means.
 * That argument is wrong, and the way it was wrong is instructive: an ordinary
 * green of {@code 65, 150, 67} — bright in the swatch it was picked from — is a
 * multiplier of a quarter, three fifths, a quarter. The sun went to a fifth of
 * its brightness against a sky the same hand had just made green, and vanished.
 *
 * A colour taken from a swatch is a hue. The three sliders say nothing about
 * brightness and the swatch beside them shows none, so a control that quietly
 * halves the light is a control that lies about what it is. Brightness has its
 * own control — where the sun is put — and it is the one that means it.
 */
public final class Filter {

	/** The filter that changes nothing. */
	public static final float[] NONE = { 1, 1, 1 };

	private Filter() { }

	/**
	 * A colour as a filter: its shape, with its brightest channel taken as one.
	 *
	 * Black is the one input with no shape to take. It comes out as a filter that
	 * multiplies everything to nothing, which is the honest reading of "the sky is
	 * black" and is what somebody asking for it wants.
	 */
	public static float[] of(int rgb) {
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;
		int most = Math.max(red, Math.max(green, blue));
		if (most == 0) return new float[] { 0, 0, 0 };
		return new float[] { red / (float) most, green / (float) most, blue / (float) most };
	}

	/** Whether this filter would do anything, so that the ordinary case costs nothing. */
	public static boolean changes(float[] filter) {
		return filter[0] != 1 || filter[1] != 1 || filter[2] != 1;
	}

	/** One colour seen through the filter. The alpha is not a colour and is left alone. */
	public static int over(int argb, float[] filter) {
		int red = channel(((argb >> 16) & 0xFF) * filter[0]);
		int green = channel(((argb >> 8) & 0xFF) * filter[1]);
		int blue = channel((argb & 0xFF) * filter[2]);
		return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	private static int channel(float value) {
		return (int) Math.clamp(Math.round(value), 0, 255);
	}
}
