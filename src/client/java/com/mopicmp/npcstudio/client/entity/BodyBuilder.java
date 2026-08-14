package com.mopicmp.npcstudio.client.entity;

import com.mopicmp.npcstudio.client.emote.BendablePart;
import com.mopicmp.npcstudio.client.emote.EmoteApplier;
import com.mopicmp.npcstudio.entity.BodyShape;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

/**
 * Puts a build onto the vanilla player model.
 *
 * Two jobs, and the second is the one that is easy to forget. Widening a part is
 * the obvious half; keeping the rest of the body attached to it is the half that
 * decides whether the result looks like a person or like a kit of parts. A torso
 * a third wider with the arms left where they were is a character with its arms
 * sunk into its chest.
 *
 * <h2>Why this is not the gender mod's approach</h2>
 *
 * Their renderer builds its own boxes and draws them as a layer over the vanilla
 * model — {@code ModelBox}, {@code OverlayModelBox}, and a {@code GenderLayer} to
 * hang them on. That is a sound design and it has a ceiling: a layer can only
 * <em>add</em>. It cannot make a torso wider, taper a forearm, or round a
 * cross-section, because the vanilla boxes are not its to touch.
 *
 * That ceiling is why people describe the result as square. It is not a style
 * anybody chose; it is what boxes glued to boxes can express. We deform the
 * model's own vertices instead, which is more delicate and can say all three.
 */
public final class BodyBuilder {

	/**
	 * Half-widths of the vanilla parts, in model pixels.
	 *
	 * Written down rather than measured, because they are the numbers the rest of
	 * the model was built around and a measurement would only tell us what we
	 * already know — while quietly changing meaning if somebody hands us a model
	 * with a different rig.
	 */
	private static final float BODY_HALF = 4f;
	private static final float LIMB_HALF = 2f;

	private BodyBuilder() { }

	/**
	 * Shapes the model, and moves what has to move with it.
	 *
	 * Called after the pose, never before. An emote writes absolute positions for
	 * every limb it mentions, so a nudge applied first would simply be overwritten
	 * — the character would come out broad-shouldered while standing and snap back
	 * to ordinary the moment it waved.
	 */
	public static void apply(PlayerModel model, BodyShape shape) {
		BodyShape build = shape == null ? BodyShape.DEFAULT : shape;

		// Every part is told, every frame, even when there is nothing to say — and
		// especially then. The alternative is to return early on an ordinary build,
		// which leaks: a part clears itself after it draws, and a part that is not
		// drawn never gets the chance. Hidden parts are ordinary, not exotic —
		// players switch skin layers off — so a hidden jacket would keep the last
		// character's width and hand it to the next one wearing the same model.
		//
		// This is the third time this family of bug has come up here, after the
		// elbow that followed a model out and the fold that reached the player's
		// own hand. The cheap answer costs a few field writes and closes it.

		// The second layer is handed the very same build rather than working one out
		// for itself. It is an inflated copy — a jacket is a quarter-pixel bigger all
		// round — so the same multipliers keep that quarter-pixel in proportion, and
		// the same push forward moves both by the same distance. Letting each измерить
		// itself is what made the two cross and shimmer, on bends, once already.
		//
		// The torso is the only part that is different at its two ends. Its top is
		// as wide as the shoulders and its bottom as wide as the hips — which is
		// what hips are. Making the legs move apart was a symptom of hips dressed
		// up as the cause; the legs follow the pelvis, below.
		float roundness = build.roundness();
		set(model.body, model.jacket, new PartBuild(
			build.shoulders(), build.hips(), 1,
			(build.chest() - 1f) * PartBuild.PUSH,
			(build.belly() - 1f) * PartBuild.PUSH,
			roundness));

		set(model.rightArm, model.rightSleeve, limb(build.arms(), roundness));
		set(model.leftArm, model.leftSleeve, limb(build.arms(), roundness));
		set(model.rightLeg, model.rightPants, limb(build.legs(), roundness));
		set(model.leftLeg, model.leftPants, limb(build.legs(), roundness));

		// The head is its own setting rather than something the body drags along
		// with it, and it is one by default. A face is drawn on that cube, and
		// stretching it moves somebody's eyes off where they put them — including
		// off where our own blink goes looking for them. It is never rounded: the
		// face is the part nobody wants reinterpreted.
		set(model.head, model.hat, new PartBuild(
			build.head(), build.head(), build.head(), 0, 0, 0));

		// Nothing to reattach when nothing moved, and the offsets would all be zero
		// anyway; skipped so that the ordinary case touches no positions at all.
		if (!build.isDefault()) {
			attach(model, build);
			stoop(model, build.stoop());
		}
	}

	/**
	 * How far forward the front of a body is pushed at a point on it.
	 *
	 * This is what a chest and a stomach are, and saying it as a push rather than
	 * as a width is the whole correction. Two differences from the old profile,
	 * and both of them are the point:
	 *
	 * <ul>
	 * <li>it moves the front and leaves the flanks alone, because a stomach is not
	 *     a character who has got wider, and because the sides are where the arms
	 *     expect to find the body;
	 * <li>the chest is two lobes and the stomach is one. That is why the gender mod
	 *     draws its chest as two boxes: one bulge across the whole front is a
	 *     barrel, and no amount of tuning its size makes it read as a chest.
	 * </ul>
	 *
	 * @param t     nought at the top of the torso, one at the waist
	 * @param side  minus one at the character's right, plus one at its left
	 * @param chest how far the chest pushes, in blocks; may be negative for flat
	 * @param belly the same for the stomach
	 */
	public static float forward(float t, float side, float chest, float belly) {
		float at = Math.clamp(t, 0f, 1f);
		float out = 0;
		if (chest != 0) {
			out += chest * hump(at, CHEST_AT, CHEST_REACH) * lobes(side);
		}
		if (belly != 0) {
			// One bulge, centred, and reaching wider than it is tall — a stomach is
			// broader across than a chest is, even though it sticks out less sharply.
			out += belly * hump(at, BELLY_AT, BELLY_REACH) * hump(side, 0f, 1.3f);
		}
		return out;
	}

	/**
	 * Where down the torso each bulge is fullest, and how far it carries.
	 *
	 * The chest's reach is exactly its distance from the top, and that is not a
	 * rounded number — it is the requirement. The torso's top is the shoulder
	 * line, and {@link #attach} moves the arms out by the shoulder setting alone,
	 * so anything that changed the body at that height would push it out from
	 * under arms that had not been told to move.
	 *
	 * The belly is the other way about: it is still most of itself at the very
	 * bottom, because a stomach does not stop politely above the belt.
	 */
	private static final float CHEST_AT = 0.35f;
	private static final float CHEST_REACH = 0.35f;
	private static final float BELLY_AT = 0.85f;
	private static final float BELLY_REACH = 0.45f;

	/**
	 * Where the two halves of a chest sit, and how far each reaches.
	 *
	 * The reach is wide enough that the two overlap well before the breastbone.
	 * Narrower, and there is a canyon between them rather than a cleft — measured
	 * at first, the middle came out at eight per cent of the lobes, which drawn on
	 * a handful of columns is two pyramids with a trench between.
	 */
	private static final float LOBE_AT = 0.45f;
	private static final float LOBE_REACH = 0.8f;

	/**
	 * How many columns the front of a torso is cut into when it has a chest.
	 *
	 * Far more than anything else needs, and for a plain reason: two lobes cannot
	 * be drawn on four columns. The samples land at the two peaks and the valley
	 * between them and nowhere else, so what comes out is two pyramids. Ten
	 * columns is eleven points across the chest, which is enough for it to read as
	 * curved.
	 *
	 * Only the front and back faces pay this, and only when there is a bulge to
	 * draw. The flanks are cut four ways as before, since nothing varies across
	 * them except a straight ramp.
	 */
	public static final int LOBE_COLUMNS = 10;

	/** Two of them, side by side, joined in the middle rather than added there. */
	private static float lobes(float side) {
		// Taken as the larger of the two rather than the sum: added, they would
		// pile up in the middle and give one bulge on the breastbone, which is the
		// barrel this exists to avoid.
		return Math.max(hump(side, -LOBE_AT, LOBE_REACH), hump(side, LOBE_AT, LOBE_REACH));
	}

	/** One at the centre, nothing beyond the reach, smooth the whole way. */
	private static float hump(float t, float centre, float reach) {
		float away = Math.abs(t - centre) / reach;
		if (away >= 1f) return 0f;
		return 0.5f * (1f + (float) Math.cos(away * Math.PI));
	}

	/**
	 * How far in or out to move a point to make a square cross-section round.
	 *
	 * Measured against the part's own half-width and half-depth, every point on a
	 * limb's surface sits on the boundary of a unit square. The same direction on
	 * a unit circle is that point divided by its own length — so sliding between
	 * the two is a single radial scale, and no corner ever has to be recognised as
	 * a corner.
	 *
	 * The middle of a face has length one and does not move at all; a corner has
	 * length root two and is drawn in the furthest.
	 *
	 * Dividing by the square radius keeps points <em>inside</em> the outline
	 * honest — the grid across a palm, the top of a head. Without it they would be
	 * flung out onto the surface with the edges.
	 *
	 * @param u        across, in units of half the part's width
	 * @param w        front to back, in units of half its depth
	 * @param roundness nought for the box exactly as it was, one for the ellipse
	 *                  that fits inside it
	 * @return what to multiply both u and w by
	 */
	public static float round(float u, float w, float roundness) {
		if (roundness <= 0) return 1f;
		float length = (float) Math.sqrt(u * u + w * w);
		if (length < 1e-5f) return 1f;
		float square = Math.max(Math.abs(u), Math.abs(w));
		return (1f - roundness) + roundness * square / length;
	}

	/** An arm or a leg: the same all the way down, and no bulges of its own. */
	private static PartBuild limb(float thickness, float roundness) {
		return new PartBuild(thickness, thickness, thickness, 0, 0, roundness);
	}

	private static void set(ModelPart part, ModelPart layer, PartBuild build) {
		shape(part, build);
		if (layer == null) return;
		shape(layer, build);
		// And by the same measurements, not its own. See
		// BendablePart#npcStudio$followExtent: an inflated copy that measures itself
		// creases differently from what it covers, and the limb underneath shows
		// through along the crease.
		((BendablePart) (Object) layer).npcStudio$followExtent(
			((BendablePart) (Object) part).npcStudio$extent());
	}

	private static void shape(ModelPart part, PartBuild build) {
		// Cast through Object because ModelPart is final and knows nothing of this
		// interface until the mixin puts them together, well after the compiler has
		// stopped looking.
		((BendablePart) (Object) part).npcStudio$setBuild(build);
	}

	/**
	 * Bends the back, and keeps the head looking where it was looking.
	 *
	 * Added to whatever the pose left rather than assigned, so a stooped character
	 * still bows and still waves — the emote says where the body is and this says
	 * how much further over.
	 *
	 * The head is turned back by the same amount because a stoop is in the spine,
	 * not the neck. Without it an old man walks along studying his own boots,
	 * which is a different character and not the one anybody asked for.
	 *
	 * <h2>The torso, not the character</h2>
	 *
	 * The first version turned the whole model at the root, which tipped the legs
	 * with everything else — a character leaning like a plank rather than one with
	 * a bent back. A stoop happens in the spine: the legs stay under the body and
	 * the shoulders come forward over them.
	 *
	 * So only the torso turns, and it turns about the waist rather than about its
	 * own origin, which sits up at the neck. Turning it about the neck would swing
	 * the hips forward instead and leave the shoulders where they were, which is
	 * the same mistake upside down.
	 *
	 * The correction that keeps the waist still is the one the emote engine
	 * already works out, for exactly this reason — borrowed rather than derived a
	 * second time. It also happens to be exactly how far the neck moves, since the
	 * neck is the part's own origin: rotate about the origin, translate by the
	 * correction, and the origin has travelled by the correction. So the head and
	 * the arms follow by the same amount and stay attached.
	 *
	 * They follow without turning, though. Arms hang and a head looks ahead; an
	 * old man leaning on a stick does not study his own boots, and his hands do
	 * not swing out in front of him.
	 */
	private static void stoop(PlayerModel model, float radians) {
		if (radians == 0) return;
		ModelPart body = model.body;

		var before = EmoteApplier.waistCorrection(body.xRot, body.yRot, body.zRot);
		body.xRot += radians;
		var after = EmoteApplier.waistCorrection(body.xRot, body.yRot, body.zRot);

		float downwards = after.y - before.y;
		float forwards = after.z - before.z;
		body.y += downwards;
		body.z += forwards;

		// Everything that hangs off the top of the torso goes with it. Not the hat
		// or the sleeves: they are children of the head and the arms — the model
		// builds them as head.getChild("hat") — so they have moved already, and
		// moving them again puts them twice as far. Confirmed against the class
		// rather than remembered; the same trap took the sleeves once before.
		for (ModelPart carried : new ModelPart[] { model.head, model.rightArm, model.leftArm }) {
			carried.y += downwards;
			carried.z += forwards;
		}
	}

	/**
	 * Moves the limbs out to meet a body that has changed size.
	 *
	 * Added to where the pose left them rather than assigned, so this survives an
	 * emote: the emote says where the arm is, and this says how much further out
	 * that is now. Assigning would throw the emote away and leave a character
	 * whose arms ignored every animation it played.
	 */
	private static void attach(PlayerModel model, BodyShape shape) {
		// How much wider each side of the torso got, and how much thicker each arm.
		// Both push the arm outwards: the first because the shoulder it hangs from
		// has moved, the second so the arm's inner face still meets the body rather
		// than growing into it.
		//
		// The limbs only, never their layers. A sleeve is a child of the arm and a
		// trouser leg a child of the leg, so both have already moved by the time
		// this is read — moving them again puts them twice as far out, which is a
		// sleeve floating beside a bare arm. The vertex thickening above is the
		// opposite case and is set on both, because that is not a transform and is
		// not inherited.
		float shoulder = BODY_HALF * (shape.shoulders() - 1f)
			+ LIMB_HALF * (shape.arms() - 1f);
		model.rightArm.x -= shoulder;
		model.leftArm.x += shoulder;

		// The legs follow the pelvis rather than being the pelvis. Hips widen the
		// bottom of the torso — that is done in the build above — and the legs move
		// out from under it by however far their own resting position has been
		// carried, so they stay under the body instead of beside it.
		//
		// This was the wrong way round before: the slider moved the legs apart and
		// left the torso alone, which is a character doing the splits rather than
		// one with hips.
		float hip = Math.abs(model.rightLeg.getInitialPose().x()) * (shape.hips() - 1f)
			+ LIMB_HALF * (shape.legs() - 1f);
		model.rightLeg.x -= hip;
		model.leftLeg.x += hip;
	}
}
