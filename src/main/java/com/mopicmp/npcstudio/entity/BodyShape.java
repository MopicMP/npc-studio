package com.mopicmp.npcstudio.entity;

/**
 * How a character is built, apart from how tall it is.
 *
 * A fat innkeeper, a broad blacksmith, a thin youth, a stooped old man. The
 * wardrobe already gives characters different clothes; without this, twenty NPCs
 * are twenty identical mannequins in different shirts.
 *
 * <h2>Why these numbers and not a set of body types</h2>
 *
 * Because a list of types is somebody else's guess at what a map maker wants,
 * and it runs out exactly when they need the one that is not on it. Sliders run
 * out never, and the types come back as presets on top — which is the same list,
 * without the ceiling.
 *
 * <h2>Why it is one long</h2>
 *
 * Because it has to reach every client that can see the character, and entity
 * data is how that is done. Minecraft has no serializer for a handful of floats;
 * registering one is possible and unwise, since serializer ids are handed out in
 * registration order, so a client running one more mod than the server reads a
 * different id and gets somebody else's data.
 *
 * A string would work and invites a different bug: somebody writes a formatter
 * one day, and on a machine whose locale uses a comma the numbers stop parsing.
 * That failure only appears on other people's computers, which is the worst kind.
 *
 * So eight bytes in a long. Each value is quantised to a two-hundred-and-
 * fifty-sixth of its range, which is a fortieth of a pixel on a torso — far
 * below anything an eye or a screen can tell apart.
 */
public record BodyShape(
		float shoulders,
		float hips,
		float belly,
		float arms,
		float legs,
		float chest,
		float head,
		float softness,
		float stoop,
		float height,
		float taper) {

	/**
	 * A build described by its girths alone, as everything was before limbs had
	 * segments.
	 *
	 * The two that are missing come out at their vanilla values, which is the whole
	 * point: a character nobody has stretched or stepped is the player model, and
	 * stays on the fast path that hands it straight back to Minecraft.
	 */
	public BodyShape(float shoulders, float hips, float belly, float arms, float legs,
			float chest, float head, float softness, float stoop) {
		this(shoulders, hips, belly, arms, legs, chest, head, softness, stoop, 1f, 0f);
	}

	/** What a scale may be. One is the vanilla model, untouched. */
	public static final float MIN_SCALE = 0.5f;
	public static final float MAX_SCALE = 2.0f;

	/**
	 * How far the back may be bent, in radians either way.
	 *
	 * <h2>Doubled when the stoop stopped being a hinge</h2>
	 *
	 * A half used to be right, because the whole torso turned by it at once: a rigid
	 * rod twelve pixels long tipped by a half radian carries its top 5.75 pixels
	 * forward. The bend is spread along the back now, and a spread bend is a shorter
	 * reach for the same angle — the same half radian, spread, carries the top only
	 * 2.94, because every part of the spine below the top has turned less than the
	 * top has.
	 *
	 * So the number is the total turn of the spine rather than the tilt of a plank,
	 * and it takes twice as much of it to arrive in the same place. The two happen to
	 * agree closely across the whole range — a quarter as a hinge reaches 2.97 and a
	 * half as an arc reaches 2.94 — which means a character saved before this looks
	 * very nearly the same after it, and that is worth more here than a round number.
	 *
	 * Beyond one radian a character is not stooped, it is folded, and the arms start
	 * arriving before the face.
	 */
	public static final float MAX_STOOP = 1f;

	/**
	 * How much taller or shorter a character may be built.
	 *
	 * This is length, not size. It stretches the segments a body is made of and
	 * leaves the head alone, which is what being tall actually looks like — and it
	 * is why a tall character reads as small-headed without anybody shrinking a
	 * face. {@code Attributes.SCALE} is the other thing and stays separate: it
	 * multiplies everything at once, head included, so it makes the same person
	 * bigger rather than a different person taller.
	 */
	public static final float MIN_HEIGHT = 0.6f;
	public static final float MAX_HEIGHT = 1.6f;

	/**
	 * How much narrower the lower half of a limb is than the upper, in pixels.
	 *
	 * A whole pixel is what the reference model uses — thigh three and a bit,
	 * calf one less — and a whole pixel is the smallest step this art has. The
	 * measurement that replaced two rounds of invented sub-pixel tapering.
	 */
	public static final float MAX_TAPER = 1.5f;

	/** An ordinary player-shaped character. */
	public static final BodyShape DEFAULT =
		new BodyShape(1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0);

	/**
	 * A few builds worth having without doing the work.
	 *
	 * Nine sliders is a workshop, and a workshop is the right thing to have — but
	 * nobody wants to visit one to place a villager. These are the starting points
	 * people actually reach for, and every one of them is only a set of slider
	 * positions, so a preset is somewhere to begin rather than somewhere to end.
	 *
	 * Kept here rather than in the screen because the shape is the server's to
	 * store, and a preset is just a shape somebody has given a name.
	 */
	public record Preset(String name, BodyShape shape) { }

	// None of them stretches or steps anything yet: they were written for girths, and
	// height and taper are new. They are worth revisiting once a segmented body has
	// been seen in the game — a blacksmith is short and thick, an old man is short
	// and thin, and neither of those is expressible by width alone.
	public static final java.util.List<Preset> PRESETS = java.util.List.of(
		new Preset("обычный", DEFAULT),
		// Shoulders and arms, and a slight lean back: the weight is in the top half.
		new Preset("кузнец", new BodyShape(1.35f, 1.05f, 1.1f, 1.3f, 1.1f, 1.3f, 1, 0f, -0.06f)),
		// The weight is in the bottom half instead, and the back gives a little.
		new Preset("трактирщик", new BodyShape(1.05f, 1.25f, 1.8f, 1.1f, 1.1f, 1.1f, 1, 0f, 0.08f)),
		// Narrow everywhere and a head still a size too big for the body.
		new Preset("подросток", new BodyShape(0.85f, 0.85f, 0.9f, 0.8f, 0.85f, 0.9f, 1.08f, 0f, 0.04f)),
		// The stoop is the whole character; the thin arms only agree with it.
		new Preset("старик", new BodyShape(0.9f, 1f, 1.25f, 0.8f, 0.85f, 0.85f, 1, 0f, 0.3f)),
		// Upright, square, and deliberately the least softened of them.
		new Preset("стражник", new BodyShape(1.25f, 1, 0.95f, 1.15f, 1.1f, 1.15f, 1, 0f, -0.1f)),
		// Not a shape so much as an answer to "what does a woman look like here",
		// which is the case the mod this grew out of exists for. One preset among
		// several, which is the whole argument for building the general thing.
		new Preset("женский", new BodyShape(0.92f, 1.15f, 0.95f, 0.9f, 1f, 1.45f, 1, 0f, 0f)));

	public BodyShape {
		shoulders = clampScale(shoulders);
		hips = clampScale(hips);
		belly = clampScale(belly);
		arms = clampScale(arms);
		legs = clampScale(legs);
		chest = clampScale(chest);
		head = clampScale(head);
		softness = clampShare(softness);
		stoop = Float.isFinite(stoop) ? Math.clamp(stoop, -MAX_STOOP, MAX_STOOP) : 0f;
		height = Float.isFinite(height) ? Math.clamp(height, MIN_HEIGHT, MAX_HEIGHT) : 1f;
		taper = Float.isFinite(taper) ? Math.clamp(taper, 0f, MAX_TAPER) : 0f;
	}

	private static float clampShare(float value) {
		return Float.isFinite(value) ? Math.clamp(value, 0f, 1f) : 0f;
	}

	private static float clampScale(float value) {
		return Float.isFinite(value) ? Math.clamp(value, MIN_SCALE, MAX_SCALE) : 1f;
	}

	/**
	 * Whether this is an ordinary character after all.
	 *
	 * Asked before anything is drawn. A model with nothing to change is handed
	 * back to vanilla untouched, which matters more than it sounds: shaping a
	 * part means slicing it into bands and emitting it a vertex at a time, and
	 * most characters in most worlds are shaped like everybody else.
	 */
	public boolean isDefault() {
		return equals(DEFAULT);
	}

	/** Whether the head is to be left alone, which is the usual answer. */
	public boolean keepsHead() {
		return head == 1f;
	}

	// ------------------------------------------------------------ on the wire

	/**
	 * The bend of the back, and room for seven more settings after it.
	 *
	 * A second number rather than a wider first one, because the first was already
	 * eight values in eight bytes and there was nowhere left to put this. Splitting
	 * is honest about that; squeezing everything down to six bits each to make room
	 * would have been clever, and would have put visible steps on every slider to
	 * buy space for one more.
	 */
	public long packedPosture() {
		return signedByteOf(stoop, MAX_STOOP)
			| (long) byteOf(height, MIN_HEIGHT, MAX_HEIGHT) << 8
			| (long) byteOf(taper, 0f, MAX_TAPER) << 16;
	}

	public long packed() {
		return (long) (byteOf(shoulders, MIN_SCALE, MAX_SCALE))
			| (long) byteOf(hips, MIN_SCALE, MAX_SCALE) << 8
			| (long) byteOf(belly, MIN_SCALE, MAX_SCALE) << 16
			| (long) byteOf(arms, MIN_SCALE, MAX_SCALE) << 24
			| (long) byteOf(legs, MIN_SCALE, MAX_SCALE) << 32
			| (long) byteOf(chest, MIN_SCALE, MAX_SCALE) << 40
			| (long) byteOf(head, MIN_SCALE, MAX_SCALE) << 48
			| (long) byteOf(softness, 0f, 1f) << 56;
	}

	/**
	 * A stored posture as it has to be read back.
	 *
	 * Height and taper were added to this long after characters had already been
	 * saved with it, and both sit in bytes that were simply zero before they
	 * existed. Zero here is not "unset" — it is the bottom of the range — so an
	 * old character read back literally came out at {@link #MIN_HEIGHT}, which is
	 * to say a dwarf. Everything measured from the body followed it down, which
	 * is why the eyes were in the wrong place too: they are placed on the head,
	 * and the head had moved.
	 *
	 * So a stored value with nothing in either of those bytes is taken to predate
	 * them. The one build this reads wrongly is a deliberate shortest-possible
	 * character with no taper at all, which comes back at ordinary height — a
	 * setting its author can put back in one drag. Being shrunk without asking is
	 * not.
	 */
	public static long readPosture(long stored) {
		boolean beforeHeightExisted = ((stored >>> 8) & 0xFFFF) == 0;
		return beforeHeightExisted
			? (stored & 0xFFL) | (DEFAULT.packedPosture() & ~0xFFL)
			: stored;
	}

	public static BodyShape unpack(long packed, long posture) {
		return new BodyShape(
			floatOf(packed, 0, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 8, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 16, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 24, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 32, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 40, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 48, MIN_SCALE, MAX_SCALE),
			floatOf(packed, 56, 0f, 1f),
			signedFloatOf(posture, 0, MAX_STOOP),
			floatOf(posture, 8, MIN_HEIGHT, MAX_HEIGHT),
			floatOf(posture, 16, 0f, MAX_TAPER));
	}

	private static int byteOf(float value, float low, float high) {
		// Rounded rather than truncated, so that a value packed and unpacked comes
		// back as itself. Truncation drifts one step down every trip, and settings
		// are read and written far more often than they are changed.
		return Math.round((Math.clamp(value, low, high) - low) / (high - low) * 255f);
	}

	private static float floatOf(long packed, int shift, float low, float high) {
		return low + ((packed >>> shift) & 0xFF) / 255f * (high - low);
	}

	/**
	 * A quantity that can be negative, stored as a signed byte.
	 *
	 * Not the same arithmetic as the rest, and it has to be different. A range
	 * spread evenly from one end to the other has 255 gaps in it, so its middle
	 * falls between two steps — and for a setting whose middle is "leave it
	 * alone", that is fatal. Straight upright came back as very slightly stooped,
	 * every character stopped counting as ordinary, and every one of them would
	 * have been drawn the slow way for ever. A test said so before anybody saw it.
	 *
	 * Counting out from zero instead gives zero exactly, both ends exactly, and
	 * one unused value at the far end of the byte that nobody will miss.
	 */
	private static int signedByteOf(float value, float extent) {
		return Math.round(Math.clamp(value, -extent, extent) / extent * 127f) & 0xFF;
	}

	private static float signedFloatOf(long packed, int shift, float extent) {
		return (byte) ((packed >>> shift) & 0xFF) / 127f * extent;
	}

	// ------------------------------------------------------------- on disk

	/**
	 * Written as separate named numbers rather than as the packed long.
	 *
	 * A file somebody may open and edit says {@code "belly": 1.4}; it does not say
	 * {@code "shape": 4703919738795935744}. The packing exists for the wire, where
	 * nobody reads, and has no business in a file where they do.
	 */
	public com.google.gson.JsonObject toJson() {
		com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		if (shoulders != 1) json.addProperty("shoulders", shoulders);
		if (hips != 1) json.addProperty("hips", hips);
		if (belly != 1) json.addProperty("belly", belly);
		if (arms != 1) json.addProperty("arms", arms);
		if (legs != 1) json.addProperty("legs", legs);
		if (chest != 1) json.addProperty("chest", chest);
		if (head != 1) json.addProperty("head", head);
		if (softness != 0) json.addProperty("softness", softness);
		if (stoop != 0) json.addProperty("stoop", stoop);
		if (height != 1) json.addProperty("height", height);
		if (taper != 0) json.addProperty("taper", taper);
		return json;
	}

	public static BodyShape fromJson(com.google.gson.JsonObject json) {
		if (json == null) return DEFAULT;
		return new BodyShape(
			number(json, "shoulders", 1), number(json, "hips", 1),
			number(json, "belly", 1), number(json, "arms", 1),
			number(json, "legs", 1), number(json, "chest", 1),
			number(json, "head", 1),
			// Read under its old name as well: a costume saved before limbs had a
			// profile says "roundness", and losing somebody's setting on an update is
			// worse than carrying one dead key.
			json.has("softness") ? number(json, "softness", 0) : number(json, "roundness", 0),
			number(json, "stoop", 0),
			number(json, "height", 1),
			number(json, "taper", 0));
	}

	private static float number(com.google.gson.JsonObject json, String key, float fallback) {
		try {
			return json.has(key) ? json.get(key).getAsFloat() : fallback;
		} catch (RuntimeException notANumber) {
			// A hand-edited file with a typo in it should lose one setting, not the
			// character it belongs to.
			return fallback;
		}
	}
}
