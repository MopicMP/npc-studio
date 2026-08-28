package com.mopicmp.npcstudio.entity;

/**
 * Where the surface of a body is, asked once and answered for everybody.
 *
 * <h2>Why a field and not multipliers on each part</h2>
 *
 * The first telling gave every part its own multipliers and its own box to grow
 * inside, and then moved whole limbs about to make the joins meet. A shift can line
 * up one edge; it cannot line up two surfaces. The moment the top of a thigh and the
 * bottom of a pelvis stopped being the same width there was a cliff between them,
 * and no correction anywhere could close it — the two numbers were arrived at
 * separately and had no reason to agree.
 *
 * So the body is described once, here, in the model's own space. A part brings only
 * two things: which surface a point of it is on, and where the part sits.
 *
 * <h2>What used to be here and is not</h2>
 *
 * A profile along each limb: a knee half a pixel narrower than the thigh, an ankle
 * two thirds narrower again. It was invented rather than measured, twice, and with a
 * skin on it the whole effect came to less than one pixel on a four-pixel limb —
 * invisible where it worked and a ragged outline where it did not. A limb that is
 * not the same width all the way down is now made of {@link SegmentMesh.Segment
 * segments}, in whole pixels, which is how the game itself is drawn.
 *
 * <h2>Everything is in model pixels, measured down from the neck</h2>
 *
 * The torso runs from nought at the shoulders to twelve at the hips, a leg from
 * twelve to twenty-four. Vertices arrive in blocks, a sixteenth of that, and the
 * caller is the one that knows it: the two units meeting by accident is a bug this
 * project has already paid for once.
 */
public final class BodyField {

	// ---------------------------------------------------------------- the rig

	/** The shoulder line, the waist, the hip line, and where a thigh is itself. */
	public static final float SHOULDER_Y = 0f;
	public static final float WAIST_Y = 8f;
	public static final float HIP_Y = 12f;
	public static final float THIGH_Y = 15f;

	/** Where an arm hangs from, which is two below the neck. */
	public static final float SHOULDER_PIVOT_Y = 2f;

	/** Half the vanilla torso across, half a limb across, half the torso through. */
	public static final float BODY_HALF = 4f;
	public static final float LIMB_HALF = 2f;
	public static final float BODY_DEEP = 2f;

	/**
	 * How far the two legs are made to overlap in the middle.
	 *
	 * A tenth of a pixel, and it is vanilla's, not ours: the legs are pitched at 1.9
	 * rather than 2 so that their inner faces never land on the same plane and
	 * flicker against each other. Kept deliberately — this is the kind of number
	 * somebody tidies up and then spends an evening wondering where the flicker came
	 * from.
	 */
	public static final float LEG_OVERLAP = 0.1f;

	private BodyField() { }

	// ---------------------------------------------------------- the invariant

	/**
	 * How wide the pelvis has to be, whatever the sliders were set to.
	 *
	 * Two thighs stand under it and have to fit exactly. Vanilla says the same thing
	 * in numbers — the torso is eight across, the legs are four each — so this is the
	 * model's own invariant kept rather than a rule invented for the occasion.
	 *
	 * It is also the whole answer to "one setting must not move alone": thickening
	 * the legs widens the hips here, and widening the hips is handed straight back to
	 * the top of the thigh by {@link #legHalf}. Neither slider knows the other exists.
	 */
	public static float hipsUsed(BodyShape shape) {
		return Math.max(shape.hips(), shape.legs());
	}

	// ------------------------------------------------------------- the torso

	/**
	 * Half the torso's width at a height.
	 *
	 * Three heights, not one line. A single ramp from the shoulders to the hips is a
	 * wedge, and a wedge has no waist — which is why widening the hips used to widen
	 * everything above them as well.
	 *
	 * The waist has no slider of its own and is the narrower of the two ends. That is
	 * not an omission: at the defaults it comes to four, which is exactly the vanilla
	 * torso, so an untouched character is untouched.
	 */
	public static float torsoHalf(BodyShape shape, float y) {
		float shoulders = BODY_HALF * shape.shoulders();
		float hips = BODY_HALF * hipsUsed(shape);
		float waist = Math.min(shoulders, hips);
		if (y <= SHOULDER_Y) return shoulders;
		if (y >= HIP_Y) return hips;
		return y <= WAIST_Y
			? mix(shoulders, waist, smooth((y - SHOULDER_Y) / (WAIST_Y - SHOULDER_Y)))
			: mix(waist, hips, smooth((y - WAIST_Y) / (HIP_Y - WAIST_Y)));
	}

	/**
	 * How far the back of the torso is behind its middle, at a height.
	 *
	 * <h2>Why the back needs a surface of its own</h2>
	 *
	 * Because until now it did not have one. The torso was as deep at the hips as at
	 * the shoulders and as deep at the back as at the front: widening the hips moved
	 * the sides apart and nothing else, so a wide-hipped character was a wide plank.
	 * Measured on the reference model, the back of the torso stands 0.55 pixels
	 * further back at the seat than at the shoulders, on a torso four pixels deep —
	 * and the front, which the chest and the stomach already own, does something
	 * else entirely.
	 *
	 * <h2>The hollow above it</h2>
	 *
	 * A seat that only bulges reads as a lump. What makes it read as a seat is the
	 * small of the back just above, where the surface comes back <em>in</em> — the
	 * same "turns round twice" that a limb's outline needed, applied front to back
	 * instead of side to side.
	 *
	 * The hollow is authored and the seat is measured, and the difference is worth
	 * stating: the reference wears a waistband exactly where the hollow would be, so
	 * the measurement cannot show one.
	 *
	 * <h2>What it is driven by</h2>
	 *
	 * The hips, which is the same slider that widens them, so the two cannot
	 * disagree — and at the defaults both terms are nought, which is what keeps an
	 * untouched character byte-for-byte the vanilla torso.
	 */
	public static float torsoBack(BodyShape shape, float y) {
		float seat = BODY_DEEP * (hipsUsed(shape) - 1f) * SEAT_GAIN;
		if (seat == 0) return BODY_DEEP;
		return BODY_DEEP + seat * seatShare(y);
	}

	/**
	 * Where the back is, as a share of the seat's full depth: negative in the hollow.
	 *
	 * Nought down to {@link #SMALL_TOP}, because a seat is a thing of the lower back
	 * and the ribcage above it belongs to the chest.
	 */
	private static float seatShare(float y) {
		if (y <= SMALL_TOP) return 0f;
		if (y >= SEAT_Y) return 1f;
		return y <= SMALL_Y
			? mix(0f, -SMALL_SHARE, smooth((y - SMALL_TOP) / (SMALL_Y - SMALL_TOP)))
			: mix(-SMALL_SHARE, 1f, smooth((y - SMALL_Y) / (SEAT_Y - SMALL_Y)));
	}

	/**
	 * Where the lower back begins, where it is hollowest, and where the seat is full.
	 *
	 * Spread rather than chosen for looks. The torso is drawn in sixteen bands, and
	 * the seat has to arrive over enough of them that no one band carries a visible
	 * jump — the same rule the limbs are held to, applied front to back. Four and a
	 * half pixels of rise is what keeps the steepest band under a third of a pixel at
	 * the widest hips the sliders allow.
	 */
	private static final float SMALL_TOP = 4f;
	private static final float SMALL_Y = 6.5f;
	private static final float SEAT_Y = 11f;

	/**
	 * How deep the seat gets, per unit of hip.
	 *
	 * Half. At the widest hips the sliders allow, that puts the back 0.6 pixels
	 * further out, which is what the reference measures at 0.55 — so this is the
	 * measurement rather than a taste, and it is written as a ratio so that it stays
	 * the measurement when the torso is not four deep.
	 */
	private static final float SEAT_GAIN = 0.5f;

	/** How far into the seat the hollow above it cuts, as a share of the seat. */
	private static final float SMALL_SHARE = 0.3f;

	/**
	 * How far forward the spine has leaned by a height, in pixels. Forward is minus.
	 *
	 * <h2>The fault this replaces</h2>
	 *
	 * A stoop used to be one rigid turn of the torso about the waist. Turning a box
	 * turns every face of it, so the torso arrived at the hips as a plank set at an
	 * angle: its bottom face tilted while the legs under it stayed upright, which
	 * left the seat hanging out past the thigh on one side and a wedge of daylight on
	 * the other. Reported as "осанка под прямым углом" — a corner where a bend was
	 * wanted. Turning about the waist rather than the neck was already the second
	 * attempt at the same idea, and the idea is what was wrong.
	 *
	 * <h2>A spine bends, it does not hinge</h2>
	 *
	 * So the lean is spread along the height instead: nought at the hips, so the
	 * torso still meets the legs square and horizontal, and the whole of it by the
	 * shoulders, with {@link #smooth} in between. That curve is the bend — its
	 * steepest part is the middle of the back and it flattens out at both ends,
	 * which is what a spine does and what one rigid angle can never say.
	 *
	 * The reach is the same one the old turn had, so a stoop still looks like the
	 * same amount of stoop: the neck used to travel the waist's height times the sine
	 * of the angle, and it still does.
	 */
	public static float spineLean(BodyShape shape, float y) {
		if (shape.stoop() == 0) return 0f;
		return -WAIST_Y * (float) Math.sin(shape.stoop()) * leaned(y);
	}

	/**
	 * How far that lean has lowered the spine by a height, in pixels. Down is plus.
	 *
	 * A bent back is a shorter back, seen from the side — the distance from hip to
	 * neck is the chord and not the arc. Without this a stooped character keeps its
	 * full height and reads as sliding forwards rather than folding, which is the
	 * failure mode of every shear that forgets it.
	 */
	public static float spineDrop(BodyShape shape, float y) {
		if (shape.stoop() == 0) return 0f;
		return (HIP_Y - SHOULDER_Y) * (1f - (float) Math.cos(shape.stoop())) * leaned(y);
	}

	/** How much of the lean has happened by a height: none at the hips, all at the neck. */
	private static float leaned(float y) {
		return smooth(Math.clamp((HIP_Y - y) / (HIP_Y - SHOULDER_Y), 0f, 1f));
	}

	// --------------------------------------------------------------- the legs

	/** How thick a leg is on its own account, before the pelvis has its say. */
	public static float legGirth(BodyShape shape) {
		return LIMB_HALF * shape.legs();
	}

	/**
	 * Where the thigh has finished becoming itself.
	 *
	 * Not a fixed height, because the distance it has to travel is not fixed. A broad
	 * pelvis over a thin leg has three pixels of width to give up, and giving them up
	 * over three pixels of height is a funnel rather than a thigh — a test measuring
	 * the slope said so before anybody looked at one. So the transition is at least as
	 * long as it is deep, and softness carries it further still.
	 *
	 * The same height ends the joint in every sense: how far the surface is still the
	 * pelvis, and how much of the leg's turn it is spared. Two lengths would be two
	 * things to keep in step, and they would go out of step the day somebody changed
	 * one of them.
	 */
	public static float jointEnd(BodyShape shape) {
		float pelvis = torsoHalf(shape, HIP_Y) / 2f;
		// Half again the difference: a smooth step is steepest in its middle, at half
		// as much again as its average, so this holds the slope to one pixel of width
		// for one pixel of height.
		float needed = 1.5f * Math.abs(pelvis - legGirth(shape));
		float length = Math.clamp(needed, THIGH_Y - HIP_Y, 6f);
		return HIP_Y + length + JOINT_SOFT * shape.softness();
	}

	/** Half a leg's width at a height: the pelvis at the top, its own girth below. */
	public static float legHalf(BodyShape shape, float y) {
		return fromHip(shape, y, torsoHalf(shape, HIP_Y) / 2f, legGirth(shape));
	}

	/** The same for depth, which starts at the torso's and becomes the leg's own. */
	public static float legDepth(BodyShape shape, float y) {
		return fromHip(shape, y, BODY_DEEP, legGirth(shape));
	}

	/**
	 * Where the middle of a leg sits at a height.
	 *
	 * At the hip the two of them fill the pelvis exactly, less the overlap vanilla
	 * gives them. Lower down each returns to standing under its own share of the
	 * body, so a wide pelvis flares the thighs rather than moving the whole leg out
	 * from under the character.
	 */
	public static float legMiddle(BodyShape shape, float y) {
		return fromHip(shape, y,
			torsoHalf(shape, HIP_Y) / 2f - LEG_OVERLAP,
			Math.max(1.9f, legGirth(shape) - LEG_OVERLAP));
	}

	/** Whatever the pelvis says at the top, whatever the leg says below the joint. */
	private static float fromHip(BodyShape shape, float y, float atHip, float own) {
		float end = jointEnd(shape);
		if (y <= HIP_Y) return atHip;
		if (y >= end) return own;
		return mix(atHip, own, smooth((y - HIP_Y) / (end - HIP_Y)));
	}

	// -------------------------------------------------------------- the arms

	/**
	 * Half an arm's width, given the arm's own half-width rather than assuming it.
	 *
	 * A slim model's arm is three pixels across and a classic one's is four. Assuming
	 * would have gone unnoticed until somebody wore an Alex skin.
	 */
	public static float armHalf(BodyShape shape, float base) {
		return base * shape.arms();
	}

	/** Four deep on every model, slim or not; only the width was ever narrowed. */
	public static float armDepth(BodyShape shape) {
		return LIMB_HALF * shape.arms();
	}

	/**
	 * Where the middle of an arm sits across the body.
	 *
	 * Not a correction any more. The arm's inner face is the torso's side at the
	 * shoulder line, so its middle is that plus its own half-width — which at the
	 * defaults comes to six on a classic model and five and a half on a slim one,
	 * exactly where Minecraft puts them.
	 *
	 * It does not follow the torso below the shoulder, and must not: a waist narrows
	 * and an arm hangs free beside it. The gap that opens under the armpit is not a
	 * fault, it is the armpit.
	 */
	public static float armMiddle(BodyShape shape, float base) {
		return torsoHalf(shape, SHOULDER_Y) + armHalf(shape, base);
	}

	// ------------------------------------------------------------- the joints

	/** How much further down a limb softness carries its joint. */
	public static final float JOINT_SOFT = 3f;

	/**
	 * How far down the arm the shoulder reaches.
	 *
	 * Shorter than the hip's, and it has to be. An arm goes through far bigger angles
	 * than a leg — a wave is seventy-five degrees where a stride is thirty — and a
	 * turn given up over a long band reads as a limb made of rubber. Held to two
	 * pixels the same turn reads as a crease at the shoulder.
	 */
	public static final float SHOULDER_JOINT = 2f;

	/**
	 * How much of the hip a height is still inside: one at the hip, nothing below.
	 *
	 * One weight, two jobs, and that is the reason there is only one. It says how far
	 * the surface still belongs to the pelvis and — the point of the whole thing —
	 * how much of the leg's own turn it is spared. A thigh widened to meet the pelvis
	 * must not swing with the leg: it is part of the pelvis, and swinging it drives it
	 * into the body in front and out of the body behind.
	 */
	public static float atJoint(BodyShape shape, float y) {
		float end = jointEnd(shape);
		if (y <= HIP_Y) return 1f;
		if (y >= end) return 0f;
		return 1f - smooth((y - HIP_Y) / (end - HIP_Y));
	}

	/** The same at the other end of the body: the top of an arm is the body's shoulder. */
	public static float atShoulder(BodyShape shape, float y) {
		float end = SHOULDER_JOINT + JOINT_SOFT * shape.softness();
		if (y <= SHOULDER_Y) return 1f;
		if (y >= end) return 0f;
		return 1f - smooth((y - SHOULDER_Y) / (end - SHOULDER_Y));
	}

	// --------------------------------------------------------- the softness

	/** How far a full softness rounds a corner, in pixels. */
	public static final float SOFT_EDGE = 1.2f;

	/** The radius this build rounds with, never more than the shape can carry. */
	public static float chamferRadius(BodyShape shape, float half, float deep) {
		return Math.min(SOFT_EDGE * shape.softness(), Math.min(half, deep));
	}

	/**
	 * How far to draw a point in to round the corners of its cross-section.
	 *
	 * The outline is the rectangle shrunk by the radius and swept by a disc of that
	 * radius, which is what a chamfer is. A point in the middle of a face is already
	 * on that outline and does not move; a corner comes in by the radius less its
	 * diagonal share.
	 *
	 * Given in pixels rather than in shares of the part's own half-width, which the
	 * first version used and which cannot work: the same number did nothing at all to
	 * a torso eight by four and turned an arm four by four into a sausage.
	 *
	 * Points inside the outline — the grid across a foot — travel by their share of
	 * the same ratio rather than being flung out onto the surface with the edges.
	 *
	 * @param u across, in shares of the half-width
	 * @param w through, in shares of the half-depth
	 */
	public static float chamfer(float u, float w, float half, float deep, float radius) {
		if (radius <= 0 || half <= 0 || deep <= 0) return 1f;
		float reach = Math.max(Math.abs(u), Math.abs(w));
		if (reach < 1e-5f) return 1f;

		float bx = u / reach * half, by = w / reach * deep;
		float cx = Math.clamp(bx, -(half - radius), half - radius);
		float cy = Math.clamp(by, -(deep - radius), deep - radius);
		float dx = bx - cx, dy = by - cy;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 1e-5f) return 1f;

		float qx = cx + dx / length * radius, qy = cy + dy / length * radius;
		float outer = (float) Math.sqrt(bx * bx + by * by);
		return outer < 1e-5f ? 1f : (float) Math.sqrt(qx * qx + qy * qy) / outer;
	}

	// ------------------------------------------------------------ the splay

	/**
	 * How far the arms have to swing out to clear the hips, in radians.
	 *
	 * An arm hangs from a shoulder ten pixels above the hip line, and its inner face
	 * sits at four. A pelvis wider than four reaches in behind it. Sliding the arm
	 * sideways would clear the hip and take the shoulder off the top of the torso —
	 * the shoulder is the one point that must not move — so the arm turns about it
	 * instead, which is also what a person does.
	 */
	public static float armSplay(BodyShape shape) {
		float clearance = torsoHalf(shape, HIP_Y) - BODY_HALF;
		if (clearance <= 0) return 0f;
		float reach = HIP_Y - SHOULDER_PIVOT_Y;
		return (float) Math.asin(Math.min(1f, clearance / reach));
	}

	// ------------------------------------------------------------------ maths

	public static float mix(float from, float to, float t) {
		return from + (to - from) * t;
	}

	/** Nought to one with both ends flat, so nothing arrives at a landmark cornered. */
	public static float smooth(float t) {
		float at = Math.clamp(t, 0f, 1f);
		return at * at * (3f - 2f * at);
	}
}
