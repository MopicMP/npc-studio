package com.mopicmp.npcstudio.scene;

/**
 * The names of the numbers a scene knows how to move.
 *
 * Gathered here rather than spelled out at each use, because a channel name is a
 * string that has to match one written somewhere else entirely — the panel that
 * makes it, the playback that reads it, the file it was saved in — and a typo
 * between two of those is a track that silently animates nothing.
 *
 * Nothing here is a closed set. A channel this class has never heard of is a
 * perfectly good channel; it simply has nobody to apply it yet.
 */
public final class Channels {

	/**
	 * The part the sky and the weather are played by.
	 *
	 * A name nobody will type: the objects panel names characters, and a leading
	 * mark keeps it out of the way of anything a person would call somebody. Its
	 * kind is what actually identifies it — see {@link Role.Kind#WORLD} — and this
	 * is only so that the two ends agree on where to look.
	 */
	public static final String WORLD = "@world";

	/**
	 * Where the sun is, in degrees round the sky.
	 *
	 * A whole turn, and not a time of day. Those are the same thing in the game and
	 * are not the same thing in a scene: a time of day drifts, and a shot that took
	 * three takes to get right had the sun in three places. This one is where it is
	 * put, and it stays there until somebody keys it somewhere else.
	 *
	 * Nought is noon, which is where the game puts the sun at nought.
	 */
	public static final String SUN = "sun";

	/**
	 * Which way round the horizon the sun's whole arc runs, in degrees.
	 *
	 * The other half of placing a sun, and the half that was missing. {@link #SUN}
	 * moves it <em>along</em> its arc — up out of the east, over, down into the west
	 * — which is one number and cannot say "behind the mast" when the mast is to the
	 * north. This one turns the arc itself about the vertical, so the sun rises from
	 * whichever compass direction the shot needs.
	 *
	 * Nought is where the game puts it, which is the east. The moon, the stars and
	 * the glow at the horizon all come with it: they are one sky, and a sun that
	 * moved without them would be a sun rising in the north with its own dawn still
	 * happening in the east.
	 */
	public static final String SUN_TURN = "sunTurn";

	/**
	 * Whether it is raining, and whether it is a storm. Nought or one.
	 *
	 * Held rather than started. Vanilla weather is a countdown — it begins, it runs
	 * for some minutes and it stops, and it stops in the middle of takes. A scene
	 * says what the weather <em>is</em>, every frame, for as long as the scene is
	 * open, so there is nothing left to run out.
	 */
	public static final String RAIN = "rain";
	public static final String STORM = "storm";

	/**
	 * How dark the whole picture is drawn over, nought to one.
	 *
	 * Not a light and not the weather: a sheet of black laid over the finished
	 * frame, which is what a scene opening out of darkness or closing into it
	 * actually is. Turning the sun off would be the other way to do it and it is
	 * the wrong one — it darkens the world, so the water still glitters and the
	 * torches still burn, and what you get is night rather than a fade.
	 *
	 * A channel rather than a switch, because the whole point is the middle: the
	 * scene eases from one to nought over as many ticks as the two keys are apart,
	 * and that easing is what makes it a transition rather than a cut.
	 */
	public static final String FADE = "fade";

	/**
	 * The colours, as three numbers each rather than one packed one.
	 *
	 * Because a packed colour cannot be interpolated: halfway between two of them
	 * is a number, and the number is not halfway between the two colours. Three
	 * ordinary channels fade properly, thin properly, and show up in the timeline
	 * as three rows anybody can key — and nothing downstream had to be told that
	 * colours exist.
	 */
	public static final String SKY_R = "skyR";
	public static final String SKY_G = "skyG";
	public static final String SKY_B = "skyB";
	public static final String SUN_R = "sunR";
	public static final String SUN_G = "sunG";
	public static final String SUN_B = "sunB";
	public static final String RAIN_R = "rainR";
	public static final String RAIN_G = "rainG";
	public static final String RAIN_B = "rainB";

	/** Where in the world, in blocks. */
	public static final String X = "at.x";
	public static final String Y = "at.y";
	public static final String Z = "at.z";

	/** Which way it faces, in degrees. */
	public static final String YAW = "yaw";
	public static final String PITCH = "pitch";

	/** How big, as a multiplier. */
	public static final String SCALE = "scale";

	/** The camera's, in degrees across. */
	public static final String FOV = "fov";

	/** Turning a bone, in degrees. */
	public static final String TURN_X = "turnX";
	public static final String TURN_Y = "turnY";
	public static final String TURN_Z = "turnZ";

	/**
	 * Folding a limb at its middle, in degrees.
	 *
	 * The same fold the emotes use and drawn by the same code — a limb sliced into
	 * bands, each turned by its share, so the surface curves rather than hinging
	 * open. It has been in the mod since limbs learned to bend and a scene has
	 * never been able to ask for one.
	 */
	public static final String BEND = "bend";

	/** Moving a bone away from where it sits, in model pixels. */
	public static final String SHIFT_X = "shiftX";
	public static final String SHIFT_Y = "shiftY";
	public static final String SHIFT_Z = "shiftZ";

	private static final String BONE = "bone.";

	private Channels() { }

	/**
	 * A bone's own channel: {@code bone.<name>.<field>}.
	 *
	 * The bone's name sits in the middle rather than being a field of the track,
	 * so that a track is still one string and one list of keys. It costs a split
	 * to read back, which happens when a bone is renamed and at no other time.
	 */
	public static String of(String bone, String field) {
		return BONE + bone + "." + field;
	}

	public static boolean isBone(String channel) {
		return channel.startsWith(BONE) && channel.lastIndexOf('.') > BONE.length();
	}

	/** Which bone a channel belongs to, or empty when it belongs to the whole thing. */
	public static String boneOf(String channel) {
		if (!isBone(channel)) return "";
		return channel.substring(BONE.length(), channel.lastIndexOf('.'));
	}

	/** The field at the end: {@code turnX} for {@code bone.wheel.turnX}. */
	public static String fieldOf(String channel) {
		if (!isBone(channel)) return channel;
		return channel.substring(channel.lastIndexOf('.') + 1);
	}

	/**
	 * Whether a channel is measured in degrees, and so may pass through a full
	 * turn.
	 *
	 * Asked by recording rather than by playback — see {@link Turning}. Playback
	 * has nothing to decide: by then the numbers are already continuous.
	 */
	public static boolean isAngle(String channel) {
		String field = fieldOf(channel);
		return field.equals(YAW) || field.equals(PITCH)
			|| field.equals(TURN_X) || field.equals(TURN_Y) || field.equals(TURN_Z);
	}
}
