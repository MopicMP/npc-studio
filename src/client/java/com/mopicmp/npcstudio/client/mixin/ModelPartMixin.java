package com.mopicmp.npcstudio.client.mixin;

import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.client.emote.BendablePart;

import net.minecraft.client.model.geom.ModelPart;

/**
 * Lets a limb bend in the middle without coming apart.
 *
 * A vanilla model part is one rigid box, and an emote's {@code bend} channel
 * folds a limb at its elbow or knee. Two earlier attempts got this wrong in the
 * same way, and the way is worth writing down.
 *
 * The first turned the whole box by half the angle and shortened it. That put
 * the hand roughly where an elbow would have put it and never looked like an
 * elbow. The second cut the box in two and turned the far half about the cut —
 * which is a hinge, not a joint: the outside of the fold opens into a wedge and
 * you can see straight through the arm. Capping the two pieces only made them
 * two capped pieces. A real limb has no seam to cap.
 *
 * So nothing is cut. Every face is sliced into thin bands across the limb, and
 * each vertex is turned by an amount that depends on how far down the limb it
 * sits — none at the shoulder, all of it past the elbow, and smoothly in
 * between. Bands meet at shared heights, so neighbouring vertices are turned by
 * exactly the same amount and the surface stays joined the whole way down. The
 * limb curves rather than snapping, which is what an arm does.
 *
 * Every model part in the game passes through here, so the first thing this
 * does is check for a bend of zero and get out of the way.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements BendablePart {

	/** How many bands the limb is sliced into. Enough for the curve to read as one. */
	@Unique private static final int BANDS = 16;

	/**
	 * How many columns each side of a limb is cut into when it is being rounded.
	 *
	 * Four a side is sixteen facets round the whole limb, which at the size a
	 * character is drawn reads as round rather than as a stop sign. It costs what
	 * it says: a rounded part emits four times the geometry of a straight one, so
	 * this is only ever paid by a part somebody has actually asked to round.
	 */
	@Unique private static final int COLUMNS = 4;

	/**
	 * How much of the limb the joint occupies, as a fraction of its length.
	 *
	 * A whole-limb blend would bow the arm like a banana; a blend over nothing
	 * would be the hinge again, with its gap. It also decides where the hand ends
	 * up: the wider the joint, the further along the limb the turn is spread, and
	 * the further out the far end lands. A third was too generous — arms and legs
	 * came to rest slightly beyond where they were aimed — so the joint is narrow
	 * enough to sit close to a real elbow and wide enough to stay in one piece.
	 */
	@Unique private static final float JOINT = 0.18f;

	// Both are final on the class itself, and mixin insists on being told so —
	// without this it loads happily and then throws while drawing the first frame.
	@Shadow @Final private List<ModelPart.Cube> cubes;
	@Shadow @Final private Map<String, ModelPart> children;
	@Shadow public boolean visible;
	@Shadow public boolean skipDraw;

	@Shadow public abstract void translateAndRotate(PoseStack pose);

	@Unique private float npcStudio$bend;
	@Unique private boolean npcStudio$failed;

	@Unique private com.mopicmp.npcstudio.client.skin.Eyes npcStudio$eyes;

	@Override
	public void npcStudio$setEyes(com.mopicmp.npcstudio.client.skin.Eyes eyes) {
		npcStudio$eyes = eyes;
	}

	/** The limb's extent, worked out once — a part's own geometry never moves. */
	@Unique private float npcStudio$top = Float.NaN;
	@Unique private float npcStudio$bottom;

	/**
	 * Where the middle of this part is across and front-to-back.
	 *
	 * Needed because a part is not always centred on its own pivot: an arm hangs
	 * from the shoulder, so its box sits to one side. Scaling about the pivot
	 * would swing the whole limb outwards instead of making it thicker.
	 */
	@Unique private float npcStudio$middleX;
	@Unique private float npcStudio$middleZ;

	/** Half the part's own width and depth, which is what a round one rounds to. */
	@Unique private float npcStudio$halfX;
	@Unique private float npcStudio$halfZ;

	@Unique private float npcStudio$round;

	/**
	 * The part's own extent, which is what its faces are cut by.
	 *
	 * Separate from the fields above because an outer layer borrows those. Where a
	 * surface ends up is the limb's business and the fold borrows accordingly; how
	 * a face is divided into bands is the face's own, and cutting a sleeve to the
	 * arm's length threw away the quarter-pixel lip at each end of it.
	 */
	@Unique private float npcStudio$ownTop;
	@Unique private float npcStudio$ownBottom;
	@Unique private float npcStudio$ownMiddleX;
	@Unique private float npcStudio$ownHalfX;
	@Unique private float npcStudio$ownMiddleZ;
	@Unique private float npcStudio$ownHalfZ;

	@Unique private com.mopicmp.npcstudio.client.entity.PartBuild npcStudio$build =
		com.mopicmp.npcstudio.client.entity.PartBuild.NONE;

	@Override
	public void npcStudio$setBuild(com.mopicmp.npcstudio.client.entity.PartBuild build) {
		npcStudio$build = build == null
			? com.mopicmp.npcstudio.client.entity.PartBuild.NONE : build;
		npcStudio$round = npcStudio$build.roundness();
	}

	/** Which part of a body this is this frame, if the field has taken it over. */
	@Unique private com.mopicmp.npcstudio.client.entity.BodyPlace npcStudio$place;

	@Override
	public void npcStudio$setPlace(com.mopicmp.npcstudio.client.entity.BodyPlace place) {
		npcStudio$place = place;
	}

	@Override
	public boolean npcStudio$built() {
		return npcStudio$place != null || !npcStudio$build.isNone();
	}

	/** Nought at the top of this part, one at its bottom. */
	@Unique
	private float npcStudio$down(float y) {
		float length = npcStudio$bottom - npcStudio$top;
		return length <= 0 ? 0 : Math.clamp((y - npcStudio$top) / length, 0f, 1f);
	}

	/**
	 * Moves a point out from the part's own middle, by however thick it is there,
	 * and then in towards a round cross-section by however round it is.
	 *
	 * <h2>Square to round without a special case</h2>
	 *
	 * Measured against the part's own half-width and half-depth, every point on a
	 * limb's surface sits on the boundary of a unit square. The same direction on
	 * a unit circle is that point divided by its own length — so sliding between
	 * the two is one radial scale, and no corner needs to be recognised as a
	 * corner.
	 *
	 * The middle of each face has length one and does not move at all; a corner
	 * has length root two and is drawn in the furthest. That is exactly what
	 * rounding a box means, and it falls out of the arithmetic rather than being
	 * arranged.
	 *
	 * The square radius is divided out so that points inside the outline — the
	 * grid across a hand or the top of a head — move by their share rather than
	 * being flung out to the surface with everything else.
	 */
	/**
	 * Where the body says this point's surface is.
	 *
	 * Nothing here is a multiplier. The part hands over which surface the point is
	 * on — how far across, how far through, as shares of its own box — and the field
	 * hands back where that is in the body. A pelvis and a thigh therefore agree at
	 * the hip line by construction rather than by correction, which is the whole
	 * reason this exists.
	 *
	 * Heights go up into model pixels first. A vertex is in blocks and a pivot is in
	 * pixels; the field speaks pixels, because that is the space the model was drawn
	 * in.
	 */
	@Unique
	private void npcStudio$byField(org.joml.Vector3f at,
			com.mopicmp.npcstudio.client.entity.BodyPlace place) {
		if (npcStudio$halfX <= 0 || npcStudio$halfZ <= 0) return;
		var shape = place.shape();
		float height = at.y * 16f + place.pivotY();
		float lip = place.lip();

		float u = (at.x - npcStudio$middleX) / npcStudio$halfX;
		float w = (at.z - npcStudio$middleZ) / npcStudio$halfZ;

		float half;
		float deep;
		float middle;
		if (place.leg()) {
			half = com.mopicmp.npcstudio.entity.BodyField.legHalf(shape, height) + lip;
			deep = com.mopicmp.npcstudio.entity.BodyField.legDepth(shape, height) + lip;
			middle = place.side()
				* com.mopicmp.npcstudio.entity.BodyField.legMiddle(shape, height)
				- place.pivotX();
		} else if (place.arm()) {
			half = com.mopicmp.npcstudio.entity.BodyField.armHalf(shape, place.base()) + lip;
			deep = com.mopicmp.npcstudio.entity.BodyField.armDepth(shape) + lip;
			middle = place.side()
				* com.mopicmp.npcstudio.entity.BodyField.armMiddle(shape, place.base())
				- place.pivotX();
		} else {
			half = com.mopicmp.npcstudio.entity.BodyField.torsoHalf(shape, height) + lip;
			// Front and back are two surfaces, not one depth. The back carries the
			// seat and the hollow above it; the front is the chest's and the
			// stomach's, pushed further down this method. They meet at the middle,
			// where both come to nought, so the side of the torso stays one quad
			// with a kink nobody can see because it is inside the character.
			deep = (w >= 0
				? com.mopicmp.npcstudio.entity.BodyField.torsoBack(shape, height)
				: com.mopicmp.npcstudio.entity.BodyField.BODY_DEEP) + lip;
			middle = 0;
		}

		float chamfer = com.mopicmp.npcstudio.entity.BodyField.chamfer(u, w, half, deep,
			com.mopicmp.npcstudio.entity.BodyField.chamferRadius(shape, half, deep));
		float across = u * half * chamfer;
		float through = w * deep * chamfer;

		at.x = (middle + across) / 16f;
		at.z = through / 16f;

		// The torso still carries its chest and its stomach the old way, as a push
		// forwards on top of whatever the width came to. That half has not been
		// rewritten yet, and leaving it working is the point: a node at a time is
		// what the last rewrite failed to be.
		if (place.kind() == com.mopicmp.npcstudio.client.entity.BodyPlace.Kind.TORSO) {
			var build = npcStudio$build;
			if (build.chest() != 0 || build.belly() != 0) {
				float front = -at.z / (deep / 16f);
				if (front > 0) {
					at.z -= front * com.mopicmp.npcstudio.client.entity.BodyBuilder.forward(
						npcStudio$down(at.y), across / half, build.chest(), build.belly());
				}
			}
			// Last, and after the chest, because the chest reads how far across the
			// depth a vertex sits and a spine that has moved would have moved that
			// reading with it. The whole cross-section goes together, front and back
			// alike: a body follows its own back.
			at.z += com.mopicmp.npcstudio.entity.BodyField.spineLean(shape, height) / 16f;
			at.y += com.mopicmp.npcstudio.entity.BodyField.spineDrop(shape, height) / 16f;
		}
	}

	/**
	 * The turn this part is making, ready to be given back at the joint.
	 *
	 * Worked out once a frame rather than once a vertex, and left null when there is
	 * nothing to give back — which is every part of every character that is standing
	 * still, so the common case costs one comparison.
	 */
	@Unique private org.joml.Quaternionf npcStudio$undo;
	@Unique private final org.joml.Quaternionf npcStudio$held = new org.joml.Quaternionf();

	@Unique
	private void npcStudio$prepareJoint() {
		npcStudio$undo = null;
		var place = npcStudio$place;
		if (place == null || !place.turns()) return;
		if (!place.leg() && !place.arm()) return;
		// Minecraft applies a part's rotation as z, then y, then x, so its inverse is
		// what has to be undone — not the angles negated, which is a different
		// rotation the moment more than one axis is in play.
		npcStudio$undo = new org.joml.Quaternionf()
			.rotationZYX(place.turnZ(), place.turnY(), place.turnX())
			.conjugate();
	}

	/**
	 * Holds the top of a limb still while the rest of it swings.
	 *
	 * The band at the top of a thigh belongs to the pelvis: it was widened to meet
	 * the pelvis, and a pelvis does not swing with the leg. Left to travel with the
	 * limb it drives into the body in front and out of it behind — which is exactly
	 * what a raised leg looked like before this.
	 *
	 * The pose already carries the full turn by the time a vertex is drawn, so what
	 * is applied here is a share of the <em>inverse</em>: all of it on the hip line,
	 * none of it by the end of the joint, and slid smoothly between. Interpolating
	 * the rotation rather than the angle keeps that true for a turn about any axis.
	 */
	@Unique
	private void npcStudio$holdJoint(org.joml.Vector3f at) {
		npcStudio$hold(at, npcStudio$jointShare(at.y));
	}

	/** How much of the turn a point at this height, in the part's own space, is spared. */
	@Unique
	private float npcStudio$jointShare(float y) {
		if (npcStudio$undo == null) return 0;
		var place = npcStudio$place;
		float height = y * 16f + place.pivotY();
		// The hip holds the top of a thigh; the shoulder holds the top of an arm. The
		// same idea at the two ends of the body, and the two are asked separately
		// because an arm swings through more than twice the angle a leg does and
		// cannot give its turn up over as long a band.
		return place.leg()
			? com.mopicmp.npcstudio.entity.BodyField.atJoint(place.shape(), height)
			: com.mopicmp.npcstudio.entity.BodyField.atShoulder(place.shape(), height);
	}

	@Unique
	private void npcStudio$hold(org.joml.Vector3f vector, float share) {
		if (npcStudio$undo == null || share <= 0) return;
		npcStudio$held.identity().slerp(npcStudio$undo, share).transform(vector);
	}

	@Unique
	private void npcStudio$thicken(org.joml.Vector3f at) {
		if (npcStudio$place != null) {
			npcStudio$byField(at, npcStudio$place);
			return;
		}
		if (!npcStudio$built()) return;
		var build = npcStudio$build;
		float t = npcStudio$down(at.y);

		// Width first, and it may differ between the two ends of the part: a torso
		// is as wide as the shoulders at the top and as wide as the hips at the
		// bottom, and one number could not have said both.
		float wide = build.wideTop() + (build.wideBottom() - build.wideTop()) * t;
		at.x = npcStudio$middleX + (at.x - npcStudio$middleX) * wide;
		at.z = npcStudio$middleZ + (at.z - npcStudio$middleZ) * build.deep();

		// Then the push forwards, which is a different thing from width and is
		// what a chest and a stomach actually are. It reaches the front of the
		// character and fades to nothing by the sides, so the flanks stay where the
		// arms expect to find them.
		float across = npcStudio$halfX * wide;
		float through = npcStudio$halfZ * build.deep();
		if ((build.chest() != 0 || build.belly() != 0) && across > 0 && through > 0) {
			float side = (at.x - npcStudio$middleX) / across;
			float front = -(at.z - npcStudio$middleZ) / through;
			if (front > 0) {
				at.z -= front * com.mopicmp.npcstudio.client.entity.BodyBuilder.forward(
					t, side, build.chest(), build.belly());
			}
		}
		if (npcStudio$round <= 0) return;
		if (across <= 0 || through <= 0) return;

		float u = (at.x - npcStudio$middleX) / across;
		float w = (at.z - npcStudio$middleZ) / through;
		float factor = com.mopicmp.npcstudio.client.entity.BodyBuilder.round(u, w, npcStudio$round);
		at.x = npcStudio$middleX + u * factor * across;
		at.z = npcStudio$middleZ + w * factor * through;
	}

	/**
	 * Which way a rounded surface faces at a point, in the limb's own space.
	 *
	 * Lit as the shape it has become rather than as the box it came from. Without
	 * this a rounded arm still has four flat highlights on it and reads as a box
	 * that has been dented, which is worse than leaving it square.
	 */
	@Unique
	private void npcStudio$roundNormal(org.joml.Vector3f normal, org.joml.Vector3f at) {
		if (npcStudio$round <= 0) return;
		// A cap faces along the limb whatever the cross-section does.
		if (Math.abs(normal.y) > 0.5f) return;

		float across = npcStudio$halfX * npcStudio$build.wideTop();
		float through = npcStudio$halfZ * npcStudio$build.deep();
		if (across <= 0 || through <= 0) return;

		// The outward direction of an ellipse is not the direction of the point
		// itself unless it happens to be a circle — it leans towards the flatter
		// axis, which is why a squashed limb still lights correctly.
		float nx = (at.x - npcStudio$middleX) / (across * across);
		float nz = (at.z - npcStudio$middleZ) / (through * through);
		float length = (float) Math.sqrt(nx * nx + nz * nz);
		if (length < 1e-5f) return;

		normal.set(
			normal.x * (1f - npcStudio$round) + nx / length * npcStudio$round,
			normal.y * (1f - npcStudio$round),
			normal.z * (1f - npcStudio$round) + nz / length * npcStudio$round);
		if (normal.lengthSquared() > 1e-8f) normal.normalize();
	}

	/**
	 * Whether folding is wanted at all.
	 *
	 * Asked at the point the fold is set rather than while drawing, so switching
	 * it off costs nothing anywhere else: a part with no bend takes the fast path
	 * out and is drawn by vanilla, exactly as every other mod reading these
	 * animations draws it.
	 */
	@Unique
	private static boolean npcStudio$folding() {
		return com.mopicmp.npcstudio.client.NpcStudioConfig.get().smoothBend;
	}

	@Override
	public void npcStudio$setBend(float radians) {
		npcStudio$bend = npcStudio$folding() ? radians : 0;
	}

	/**
	 * The fold as it will actually be drawn.
	 *
	 * Held back to what this shape can be mitred at, which depends on how deep it
	 * is against how long — so it is worked out here, where the extent is known,
	 * rather than when the animation hands the angle over and it is not.
	 */
	@Unique
	private float npcStudio$fold() {
		if (npcStudio$bend == 0) return 0;
		// The extent is what decides how sharp a fold this shape can carry, so it
		// has to exist before the question can be answered. Asked for by a part
		// nobody has measured, it would come back as a not-a-number and quietly
		// stop the limb being drawn at all.
		if (Float.isNaN(npcStudio$top)) npcStudio$measure();
		return com.mopicmp.npcstudio.client.emote.Folding.allowed(npcStudio$bend);
	}

	@Override
	public float npcStudio$bend() {
		return npcStudio$fold();
	}

	@Override
	public void npcStudio$setBendAlong(float radians, float top, float bottom) {
		npcStudio$bend = npcStudio$folding() ? radians : 0;
		// Borrowed rather than measured. See the interface for why: measuring its
		// own inflated box is what made a sleeve fight the arm inside it.
		npcStudio$top = top;
		npcStudio$bottom = bottom;
	}

	@Override
	public Extent npcStudio$extent() {
		if (Float.isNaN(npcStudio$top)) npcStudio$measure();
		return new Extent(npcStudio$top, npcStudio$bottom,
			npcStudio$middleX, npcStudio$halfX, npcStudio$middleZ, npcStudio$halfZ);
	}

	/**
	 * Whether this part is an outer layer covering another.
	 *
	 * Known only because borrowing somebody else's measurements is the one thing
	 * a layer does and a limb never does.
	 */
	/**
	 * Whether this part is an outer layer covering another.
	 *
	 * Kept because it says something true — borrowing somebody else's
	 * measurements is the one thing a layer does and a limb never does — even
	 * though the squeeze no longer treats the two differently. It did once, and
	 * that was the bug: giving the sleeve its own limit is what put it on the
	 * arm's surface at 0.46 radians. Both are squeezed by the same share now, and
	 * that is exactly why they can never meet.
	 */
	@Unique private boolean npcStudio$covering;

	@Override
	public void npcStudio$followExtent(Extent extent) {
		// Its own first: after this the borrowed numbers are in place, and a later
		// measure would be a no-op that leaves the cut with the arm's length.
		if (Float.isNaN(npcStudio$top)) npcStudio$measure();
		npcStudio$covering = true;
		npcStudio$top = extent.top();
		npcStudio$bottom = extent.bottom();
		npcStudio$middleX = extent.middleX();
		npcStudio$halfX = extent.halfX();
		npcStudio$middleZ = extent.middleZ();
		npcStudio$halfZ = extent.halfZ();
	}

	@Override
	public float npcStudio$bendTop() {
		if (Float.isNaN(npcStudio$top)) npcStudio$measure();
		return npcStudio$top;
	}

	@Override
	public float npcStudio$bendBottom() {
		if (Float.isNaN(npcStudio$top)) npcStudio$measure();
		return npcStudio$bottom;
	}

	/**
	 * Draws the eyelids after the head itself.
	 *
	 * At the tail, so the face is already there and the lids go over it. The pose
	 * has been popped by then, so this puts the part's own transform back on
	 * before drawing — cheaper than holding one across the two halves and
	 * impossible to get out of step.
	 */
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At("TAIL"))
	private void npcStudio$renderEyelids(PoseStack pose, VertexConsumer consumer,
			int light, int overlay, int colour, CallbackInfo info) {
		var eyes = npcStudio$eyes;
		if (npcStudio$failed || !visible || eyes == null || !eyes.anything()) return;
		try {
			pose.pushPose();
			translateAndRotate(pose);
			// The glance first and the lid over it, in that order: a lid that has
			// come down covers whatever the eye was doing underneath.
			for (boolean right : new boolean[] { true, false }) {
				npcStudio$glance(pose.last(), consumer, light, overlay, colour, right);
			}
			for (boolean right : new boolean[] { true, false }) {
				npcStudio$eyelid(pose.last(), consumer, light, overlay, colour, right);
			}
			// The brows last of all, because a brow is in front of everything —
			// including a lid that has come down, and including the eye itself when
			// it is a scowl coming down over it.
			for (boolean right : new boolean[] { true, false }) {
				npcStudio$brow(pose.last(), consumer, light, overlay, colour, right);
			}
			pose.popPose();
		} catch (RuntimeException broken) {
			npcStudio$failed = true;
			NpcStudio.LOGGER.warn("Could not draw eyelids: {}", broken.toString());
		}
		// Used once, like the bend, and for the same reason: the model is shared.
		npcStudio$eyes = null;
	}

	/**
	 * One eye turned aside, or with its pupil at some other size than it was drawn.
	 *
	 * <h2>Why this is a handful of small quads rather than three big ones</h2>
	 *
	 * Because an eye is not a rectangle and the rectangle round it contains cheek.
	 * Drawing the box was the single cause of every complaint the eyes attracted:
	 * unmarked skin travelling with the iris, a torn drawing wherever the pupil
	 * narrowed, and a flat grey block where a shaded white belongs. See
	 * {@link com.mopicmp.npcstudio.client.skin.EyeRows}.
	 *
	 * So the shape somebody marked decides the geometry.
	 * {@link com.mopicmp.npcstudio.client.skin.EyePaint} cuts the movement to it
	 * and hands back a list of quads that between them cover the marked opening
	 * exactly once — no overlap, so no depths to arrange and nothing for the depth
	 * buffer to argue with, which is the other thing the old three layers cost.
	 */
	@Unique
	private void npcStudio$glance(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour, boolean rightSide) {
		if (!com.mopicmp.npcstudio.client.NpcStudioConfig.get().eyeGlance) return;

		var eyes = npcStudio$eyes;
		var face = eyes.face();
		// Under a lid that is more than half down there is nothing left to see.
		if (eyes.shut(rightSide) > 0.5f) return;

		var rows = npcStudio$rows(face, rightSide);
		if (rows == null) return;

		// Scaled here rather than inside the glance, because how far an eye ought to
		// travel is a matter of taste about a drawing, and the arithmetic of where it
		// then goes is not.
		var config = com.mopicmp.npcstudio.client.NpcStudioConfig.get();
		var glance = com.mopicmp.npcstudio.client.skin.Glance.of(face,
			eyes.aside() * config.eyeTravel,
			config.eyePupils ? eyes.up() : 0f,
			config.eyePupils ? eyes.pupil() : 0f,
			rightSide);
		if (glance == null) return;

		// A hair in front of the face and behind the lid, which is at −4.02.
		for (var patch : com.mopicmp.npcstudio.client.skin.EyePaint.eye(rows, glance)) {
			npcStudio$quad(pose, consumer, light, overlay, colour, patch, -4.005f);
		}
	}

	/**
	 * The eye's marked shape on the side being drawn.
	 *
	 * The far one is the near one reflected, and the reflection is worked out once
	 * for as long as the reading lasts rather than once for each of the three
	 * things that want it — the lid, the lash and the glance all ask, and a
	 * reading lasts a frame.
	 */
	@Unique private com.mopicmp.npcstudio.client.skin.EyeRows npcStudio$mirrored;
	@Unique private com.mopicmp.npcstudio.client.skin.FaceReading npcStudio$mirroredOf;

	@Unique
	private com.mopicmp.npcstudio.client.skin.EyeRows npcStudio$rows(
			com.mopicmp.npcstudio.client.skin.FaceReading face, boolean rightSide) {
		if (face == null || face.rows() == null) return null;
		if (rightSide) return face.rows();
		if (npcStudio$mirroredOf != face) {
			npcStudio$mirroredOf = face;
			npcStudio$mirrored = face.rows().mirrored();
		}
		return npcStudio$mirrored;
	}

	/**
	 * One quad of the face: a piece of skin drawn somewhere else on the face.
	 *
	 * Both rectangles come in sixty-fourths, which is the space the marking, the
	 * texture and the head's own front all agree in. The two conversions — a model
	 * column is its u less twelve, a model row is its v less sixteen — happen here
	 * and nowhere else.
	 */
	@Unique
	private void npcStudio$quad(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour,
			com.mopicmp.npcstudio.client.skin.EyePaint.Patch patch, float z) {
		// Nothing to do where the skin would land back on itself, which is most of
		// the sclera of an eye that is only breathing.
		if (patch.redundant()) return;

		float x0 = patch.u0() - com.mopicmp.npcstudio.client.skin.EyePaint.MIDDLE;
		float x1 = patch.u1() - com.mopicmp.npcstudio.client.skin.EyePaint.MIDDLE;
		float y0 = patch.v0() - com.mopicmp.npcstudio.client.skin.EyePaint.HEAD;
		float y1 = patch.v1() - com.mopicmp.npcstudio.client.skin.EyePaint.HEAD;
		if (x1 - x0 <= 0.001f || y1 - y0 <= 0.001f) return;

		float uFrom = patch.su0() / 64f;
		float uTo = patch.su1() / 64f;
		float vFrom = patch.sv0() / 64f;
		float vTo = patch.sv1() / 64f;

		Matrix4f matrix = pose.pose();
		Vector3f normal = new Vector3f();
		pose.transformNormal(0, 0, -1, normal);

		npcStudio$corner(consumer, matrix, x0, y0, z, uFrom, vFrom, normal, light, overlay, colour);
		npcStudio$corner(consumer, matrix, x0, y1, z, uFrom, vTo, normal, light, overlay, colour);
		npcStudio$corner(consumer, matrix, x1, y1, z, uTo, vTo, normal, light, overlay, colour);
		npcStudio$corner(consumer, matrix, x1, y0, z, uTo, vFrom, normal, light, overlay, colour);
	}

	/**
	 * One brow, picked up and put down a little higher or lower.
	 *
	 * <h2>Why this is the one worth having</h2>
	 *
	 * Everything else the face does is a fraction of a pixel. A brow moves a whole
	 * one, and it moves against the forehead — a flat field of a single colour, on
	 * nearly every skin — so it is the only part of an expression that survives
	 * being looked at from across a room. Surprise, anger and doubt are all brows.
	 *
	 * <h2>Why it is a trapezoid rather than a rectangle</h2>
	 *
	 * Because a brow tilts, and the tilt is most of what is read from it. The inner
	 * end going up while the outer end goes down is worry; the reverse is a frown.
	 * Both ends of the quad therefore get their own height, and the skin between
	 * them is sheared to match — which costs nothing, a quad's corners being four
	 * separate points already, and which no amount of moving a rectangle up and
	 * down could imitate.
	 *
	 * <h2>Where the old brow goes</h2>
	 *
	 * Painted out first, with the bare column immediately inside the brow itself —
	 * bare by construction, because that is where the reading's brow run stopped.
	 * A column and not a texel, for the reason spelled out on
	 * {@link #npcStudio$column}: a forehead is shaded down its height on any skin
	 * drawn with care, and stretching one texel over two rows of it puts a flat
	 * block where the shading was.
	 */
	@Unique
	private void npcStudio$brow(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour, boolean rightSide) {
		if (!com.mopicmp.npcstudio.client.NpcStudioConfig.get().eyeBrows) return;

		var eyes = npcStudio$eyes;
		var face = eyes.face();
		if (!face.canMoveBrows()) return;

		var brows = eyes.brows();
		if (!brows.moves(rightSide)) return;
		float inner = brows.inner(rightSide);
		float outer = brows.outer(rightSide);

		// The same mapping the lid uses: u 8..16 spans x −4..4, so x = u − 12, and
		// the far side is the near one mirrored about the middle of the face. Which
		// end of that is the inner one swaps with the side, which is the whole reason
		// the two lifts are carried separately rather than as a left and a right.
		float x0 = rightSide ? face.browOuter() - 12f : 12f - face.browInner();
		float x1 = rightSide ? face.browInner() - 12f : 12f - face.browOuter();
		float liftAt0 = rightSide ? outer : inner;
		float liftAt1 = rightSide ? inner : outer;

		float top = face.browTop() - 16f;
		float bottom = face.browBottom() - 16f;
		// In front of the lid, which is at −4.02 with its lash a little ahead of it.
		float z = -4.03f;

		npcStudio$column(pose, consumer, light, overlay, colour,
			x0, x1, top, bottom, face.browInner(), face.texel(),
			face.browTop(), face.browBottom(), z);
		npcStudio$browAt(pose, consumer, light, overlay, colour,
			x0, x1, top, bottom, liftAt0, liftAt1,
			face.browOuter(), face.browTop(), face.browBottom(), z - 0.005f);
	}

	/**
	 * The brow's own pixels, drawn a little above where they were painted, with
	 * each end at its own height.
	 *
	 * A copy one texel for one pixel, the same as {@link #npcStudio$eyeAt} — so
	 * whatever the artist drew comes along, and a character with a heavy black brow
	 * keeps it while a character with a faint one keeps that. Up is a smaller y on
	 * the face, which is why the lift is subtracted.
	 */
	@Unique
	private void npcStudio$browAt(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour,
			float x0, float x1, float y0, float y1, float lift0, float lift1,
			float u, float v0, float v1, float z) {
		if (x1 - x0 <= 0.001f || y1 <= y0) return;

		float uFrom = u / 64f;
		float uTo = (u + (x1 - x0)) / 64f;
		float vFrom = v0 / 64f;
		float vTo = v1 / 64f;

		Matrix4f matrix = pose.pose();
		Vector3f normal = new Vector3f();
		pose.transformNormal(0, 0, -1, normal);

		npcStudio$corner(consumer, matrix, x0, y0 - lift0, z, uFrom, vFrom, normal,
			light, overlay, colour);
		npcStudio$corner(consumer, matrix, x0, y1 - lift0, z, uFrom, vTo, normal,
			light, overlay, colour);
		npcStudio$corner(consumer, matrix, x1, y1 - lift1, z, uTo, vTo, normal,
			light, overlay, colour);
		npcStudio$corner(consumer, matrix, x1, y0 - lift1, z, uTo, vFrom, normal,
			light, overlay, colour);
	}

	/**
	 * The sclera showing behind an eye that has looked away: one column of it,
	 * down all of its own rows.
	 *
	 * A column and not a texel, and that distinction is the whole of this method.
	 * Stretching one texel over the eye's full height gives the lower row of the
	 * white the colour of the upper one — and on any eye drawn with an eyelid
	 * shadow, a lower lash or simply two shades of white, that comes out as a flat
	 * bright block sitting in a drawing that has none. At a fraction of a pixel it
	 * passes; at a full glance the strip is a whole pixel wide and it is the first
	 * thing the eye lands on.
	 *
	 * Drawn this way, a full glance is exactly what an artist would have drawn: the
	 * eye's columns swapped over, every pixel of both taken from the skin itself.
	 */
	@Unique
	private void npcStudio$column(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour,
			float x0, float x1, float y0, float y1, float u, float uWide,
			float v0, float v1, float z) {
		if (x1 - x0 <= 0.001f || y1 <= y0) return;

		float uFrom = u / 64f;
		float uTo = (u + uWide) / 64f;
		float vFrom = v0 / 64f;
		float vTo = v1 / 64f;

		Matrix4f matrix = pose.pose();
		Vector3f normal = new Vector3f();
		pose.transformNormal(0, 0, -1, normal);

		npcStudio$corner(consumer, matrix, x0, y0, z, uFrom, vFrom, normal, light, overlay, colour);
		npcStudio$corner(consumer, matrix, x0, y1, z, uFrom, vTo, normal, light, overlay, colour);
		npcStudio$corner(consumer, matrix, x1, y1, z, uTo, vTo, normal, light, overlay, colour);
		npcStudio$corner(consumer, matrix, x1, y0, z, uTo, vFrom, normal, light, overlay, colour);
	}

	/**
	 * One eyelid: a slip of skin lowered over an eye, and only over the eye.
	 *
	 * <h2>Two things were wrong with this and they looked like one</h2>
	 *
	 * It covered the rectangle round the eye, so on any face whose eye is not a
	 * rectangle it stamped flat skin across the corners of somebody's drawing. And
	 * the lash below it was a whole model pixel tall — which on a face marked at
	 * thirty-two cells is four cells, on an eye three cells tall. The lash
	 * therefore covered the entire lid, in the colour of the iris, and every blink
	 * came down as a solid dark block. That is what "the blink uses the pupil's
	 * texture" was.
	 *
	 * Both are the same mistake at different scales: a length written when a cell
	 * and a pixel were the same thing. The lid is cut to the marking now and the
	 * lash is one cell, whatever a cell is on this face.
	 */
	@Unique
	private void npcStudio$eyelid(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour, boolean rightSide) {
		float shut = Math.clamp(npcStudio$eyes.shut(rightSide), 0f, 1f);
		if (shut <= 0) return;
		var face = npcStudio$eyes.face();
		var rows = npcStudio$rows(face, rightSide);
		if (rows == null) return;

		// A hair in front of the face, so the lid wins the depth test instead of
		// fighting the skin for the same pixels, and in front of the glance so that
		// a lid coming down covers whatever the eye was doing underneath.
		for (var patch : com.mopicmp.npcstudio.client.skin.EyePaint.lid(
				rows, face.eyeTop(), face.eyeBottom(), shut)) {
			npcStudio$quad(pose, consumer, light, overlay, colour, patch, -4.02f);
		}
		if (!com.mopicmp.npcstudio.client.NpcStudioConfig.get().eyeLash) return;
		for (var patch : com.mopicmp.npcstudio.client.skin.EyePaint.lash(
				rows, face.eyeTop(), face.eyeBottom(), shut)) {
			npcStudio$quad(pose, consumer, light, overlay, colour, patch, -4.025f);
		}
	}

	@Unique
	private void npcStudio$corner(VertexConsumer consumer, Matrix4f matrix,
			float x, float y, float z, float u, float v, Vector3f normal,
			int light, int overlay, int colour) {
		Vector3f at = matrix.transformPosition(x / 16f, y / 16f, z / 16f, new Vector3f());
		consumer.addVertex(at.x, at.y, at.z, colour, u, v, overlay, light,
			normal.x(), normal.y(), normal.z());
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At("HEAD"), cancellable = true)
	private void npcStudio$renderBent(PoseStack pose, VertexConsumer consumer,
			int light, int overlay, int colour, CallbackInfo info) {
		// Anything that has nothing to change is handed straight back to vanilla.
		// This is not a micro-optimisation: the slow path slices every face into
		// bands and emits it a vertex at a time, and it runs for every model part
		// of every entity in sight, players included.
		if ((npcStudio$bend == 0 && !npcStudio$built())
			|| npcStudio$failed || !visible || cubes.isEmpty()) return;

		try {
			npcStudio$draw(pose, consumer, light, overlay, colour);
			info.cancel();
		} catch (RuntimeException broken) {
			// Once, and then never again: a limb drawn straight is a small
			// disappointment, and an exception every frame is an unusable game.
			npcStudio$failed = true;
			NpcStudio.LOGGER.warn("Could not bend a model part, falling back: {}", broken.toString());
		}
	}

	@Unique
	private void npcStudio$draw(PoseStack pose, VertexConsumer consumer,
			int light, int overlay, int colour) {
		if (Float.isNaN(npcStudio$top)) npcStudio$measure();
		npcStudio$prepareJoint();

		pose.pushPose();
		translateAndRotate(pose);
		// A limb built out of segments first; anything else — the torso, and a limb
		// that only bends — the way it was.
		if (!skipDraw && !npcStudio$emitChain(pose.last(), consumer, light, overlay, colour)) {
			npcStudio$emit(pose.last(), consumer, light, overlay, colour);
		}

		// Children hang off the joint as they always did. A sleeve has a bend of
		// its own and curves the same way; a hand would not, and should not.
		for (ModelPart child : children.values()) {
			child.render(pose, consumer, light, overlay, colour);
		}
		pose.popPose();

		// Used up. The bend is set fresh every frame by whoever wants one, and
		// clearing it here is what stops it escaping to somebody else — the model
		// is shared, and the first-person hand is drawn without going through
		// `setupAnim` at all, so the tidy-up there never reached it. The player's
		// own arm came out bent by an NPC standing across the field.
		npcStudio$bend = 0;
		npcStudio$build = com.mopicmp.npcstudio.client.entity.PartBuild.NONE;
		npcStudio$round = 0;
		npcStudio$place = null;
		npcStudio$undo = null;
	}

	@Unique
	private void npcStudio$measure() {
		float lowest = Float.MAX_VALUE;
		float highest = -Float.MAX_VALUE;
		float leftmost = Float.MAX_VALUE;
		float rightmost = -Float.MAX_VALUE;
		float nearest = Float.MAX_VALUE;
		float furthest = -Float.MAX_VALUE;

		// The corners, not the cube's stated bounds. A Cube's minY and maxY are the
		// box that was asked for; its corners are that box grown by its inflation,
		// and every outer layer is inflated. Measuring the stated bounds gave a
		// sleeve the length of the arm inside it, so the quarter-pixel lip at each
		// end fell outside every band and was never drawn. Proved by SlicingTest.
		for (ModelPart.Cube cube : cubes) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				for (ModelPart.Vertex corner : polygon.vertices()) {
					lowest = Math.min(lowest, corner.worldY());
					highest = Math.max(highest, corner.worldY());
					leftmost = Math.min(leftmost, corner.worldX());
					rightmost = Math.max(rightmost, corner.worldX());
					nearest = Math.min(nearest, corner.worldZ());
					furthest = Math.max(furthest, corner.worldZ());
				}
			}
		}
		if (lowest > highest) return;

		npcStudio$top = lowest;
		npcStudio$bottom = highest;
		npcStudio$middleX = (leftmost + rightmost) / 2f;
		npcStudio$middleZ = (nearest + furthest) / 2f;
		npcStudio$halfX = (rightmost - leftmost) / 2f;
		npcStudio$halfZ = (furthest - nearest) / 2f;

		npcStudio$ownTop = npcStudio$top;
		npcStudio$ownBottom = npcStudio$bottom;
		npcStudio$ownMiddleX = npcStudio$middleX;
		npcStudio$ownHalfX = npcStudio$halfX;
		npcStudio$ownMiddleZ = npcStudio$middleZ;
		npcStudio$ownHalfZ = npcStudio$halfZ;
	}

	/**
	 * How wide the joint has to be before the inside of the fold eats itself.
	 *
	 * <h2>The hatching at the elbow</h2>
	 *
	 * A limb bends because every point on it is turned by an angle that depends on
	 * its own height. On the outside of the fold the surface stretches, which is
	 * fine. On the <b>inside</b> it has to compress — and when the fold is sharp
	 * enough it compresses past nothing and the surface passes through itself.
	 * A surface that has crossed itself argues with itself for every pixel, and
	 * what you see is a fan of fine hatching converging on the joint.
	 *
	 * That is why it only ever appeared in animations, only at the bends, and on
	 * every skin equally. It is not two parts fighting. It is one part folded
	 * tighter than it has room for.
	 *
	 * How much room a fold needs is arithmetic rather than taste. Turning through
	 * θ carries the inner surface, a distance {@code halfZ} from the axis, through
	 * an arc of about {@code θ · halfZ} — and that much material has to be
	 * absorbed by the length the joint is spread over. Spread it over less and the
	 * surface has nowhere to go but through itself.
	 *
	 * <h2>Widening the joint was the wrong cure</h2>
	 *
	 * The first fix spread the fold over more of the limb until the material fit.
	 * It worked and it cost the elbow: a quarter turn spread over half an arm is
	 * not a hinge, it is a hose, and it read as the arm squashing rather than
	 * bending.
	 *
	 * The complaint was never that the fold was too sharp. It was that a sharp
	 * fold has <b>more material on the inside than there is room for</b>. So take
	 * the surplus away instead of making room for it — which is what a real
	 * character does at a joint, and why elbows and knees lose volume when they
	 * close rather than ballooning.
	 *
	 * How much is surplus is not a matter of taste. Along the limb the fold turns
	 * at some rate; the reciprocal of that rate is the radius the surface is being
	 * curved to. Nothing on the compressed side may sit further from the axis than
	 * that radius — beyond it the surface has folded past itself. So points beyond
	 * it are drawn in to it, and nothing else is touched at all.
	 *
	 * The outside of the fold, which is the part anybody looks at, is untouched:
	 * it stretches, and stretching has no limit. Only the inside of the crook
	 * gives, and the inside of a closed crook is not visible.
	 */
	/**
	 * Which half of a mitred limb is being drawn at this moment.
	 *
	 * A field rather than an argument because it is constant for a whole piece and
	 * would otherwise have to be threaded through every level of the subdivision.
	 * Set once per piece in {@link #npcStudio$emit}, read where a point or a
	 * normal is turned.
	 */
	/**
	 * How far a surface at this height has been turned, for its shading.
	 *
	 * Nought on the shoulder half and the whole fold on the hand half. A lean does
	 * not turn a surface — it slides it along itself — so there is nothing in
	 * between to account for.
	 */
	@Unique
	private float npcStudio$angleAt(float y) {
		return com.mopicmp.npcstudio.client.emote.Folding.angleAt(
			y, npcStudio$top, npcStudio$bottom, npcStudio$fold());
	}

	/**
	 * Leans a point along the limb and, if it is on the hand half, swings it.
	 *
	 * The whole of the fold, in one function and with no geometry cut up: see
	 * {@code Folding}. Nothing is scaled and nothing is borrowed from a
	 * neighbouring half, so no surface can end up outside the box it came from.
	 */
	/**
	 * Shrinks a point towards the middle of its own part, by a sixteenth of a
	 * pixel on every face.
	 *
	 * Measured against the part's own box rather than the one it may have borrowed
	 * for folding, so a sleeve tucks about itself and stays the same fraction
	 * outside the arm it covers.
	 */
	@Unique
	private void npcStudio$tuck(org.joml.Vector3f at) {
		if (!com.mopicmp.npcstudio.client.NpcStudioConfig.get().tuckFaces) return;
		float by = com.mopicmp.npcstudio.client.emote.Tuck.DEPTH;
		float middleY = (npcStudio$ownTop + npcStudio$ownBottom) / 2f;
		float halfY = (npcStudio$ownBottom - npcStudio$ownTop) / 2f;

		at.x = com.mopicmp.npcstudio.client.emote.Tuck.inset(
			at.x, npcStudio$ownMiddleX, npcStudio$ownHalfX, by);
		at.y = com.mopicmp.npcstudio.client.emote.Tuck.inset(at.y, middleY, halfY, by);
		at.z = com.mopicmp.npcstudio.client.emote.Tuck.inset(
			at.z, npcStudio$ownMiddleZ, npcStudio$ownHalfZ, by);
	}

	@Unique
	private void npcStudio$bendPoint(org.joml.Vector3f at) {
		float fold = npcStudio$fold();
		if (fold == 0) return;
		float[] folded = com.mopicmp.npcstudio.client.emote.Folding.fold(
			at.y, at.z, npcStudio$top, npcStudio$bottom, fold, npcStudio$halfZ);
		at.y = folded[0];
		at.z = folded[1];
	}

	@Unique private static final int AXIS_X = 0;
	@Unique private static final int AXIS_Y = 1;
	@Unique private static final int AXIS_Z = 2;

	/**
	 * Cuts every face up and draws the pieces where the shape wants them.
	 *
	 * <h2>Two axes, chosen by which way the face looks</h2>
	 *
	 * A limb needs slicing along its length to bend and to carry a stomach, and
	 * around its girth to be round. Which of those a given face needs depends
	 * entirely on which way it faces: the front of an arm is cut across, the side
	 * of it is cut front to back, and the palm at the end is cut both ways. That is
	 * read off the face's own normal, so all six sides of a box go through one loop
	 * and none of them has to be recognised by name.
	 *
	 * <h2>Nothing is paid for that was not asked for</h2>
	 *
	 * With no roundness the second axis is a single piece, and what comes out is
	 * the same geometry this produced before rounding existed. That is the property
	 * worth having: it means rounding cannot have broken bending.
	 *
	 * With no bend and no stomach, nothing varies along the limb's length, so the
	 * first axis collapses to one piece as well. A plain rounded arm therefore
	 * costs four pieces a face rather than sixty-four.
	 */
	/**
	 * Draws a limb as the chain of boxes it is, or says it cannot.
	 *
	 * The other path in this class cuts the part's own faces into bands and moves
	 * every vertex. This one does not touch the vertices at all: it hands the part's
	 * faces to {@link com.mopicmp.npcstudio.entity.SegmentMesh}, which gives back the
	 * faces of a chain — a thigh and a calf, an upper arm and a forearm — each with
	 * the slice of skin that belongs to it.
	 *
	 * A chain of one segment gives the part straight back, so a character nobody has
	 * stretched or stepped comes out as the model Minecraft built. That is checked in
	 * a test rather than hoped for here.
	 *
	 * @return whether it drew anything; false means the caller does it the old way
	 */
	@Unique
	private boolean npcStudio$emitChain(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour) {
		var place = npcStudio$place;
		if (place == null || !(place.leg() || place.arm())) return false;

		java.util.List<com.mopicmp.npcstudio.entity.SegmentMesh.Quad> faces =
			new java.util.ArrayList<>();
		for (ModelPart.Cube cube : cubes) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				ModelPart.Vertex[] corners = polygon.vertices();
				if (corners.length < 4) continue;
				faces.add(new com.mopicmp.npcstudio.entity.SegmentMesh.Quad(
					npcStudio$corner(corners[0]), npcStudio$corner(corners[1]),
					npcStudio$corner(corners[2]), npcStudio$corner(corners[3])));
			}
		}
		if (faces.isEmpty()) return false;

		// The mesh speaks model pixels and a vertex arrives in blocks. Sixteen apart,
		// and the only place in the mod where the two have to meet.
		float top = npcStudio$top * 16f;
		float bottom = npcStudio$bottom * 16f;
		var source = new com.mopicmp.npcstudio.entity.SegmentMesh.Source(
			faces, top, bottom, npcStudio$middleX * 16f, npcStudio$middleZ * 16f);

		var shape = place.shape();
		var chain = place.leg()
			? com.mopicmp.npcstudio.entity.BodyChain.leg(shape, top, bottom,
				place.side(), place.pivotX(), npcStudio$middleZ * 16f, place.lip())
			: com.mopicmp.npcstudio.entity.BodyChain.arm(shape, top, bottom,
				place.side(), place.pivotX(), npcStudio$middleZ * 16f,
				place.base(), place.lip());

		Vector3f local = new Vector3f();
		Vector3f normal = new Vector3f();
		Vector3f at = new Vector3f();
		for (var quad : com.mopicmp.npcstudio.entity.SegmentMesh.build(source, chain)) {
			var corners = quad.corners();
			npcStudio$facing(corners, local);
			// Lit by the turn it is drawn with: a band held back at the joint faces
			// where its parent faces, not where the limb is swinging.
			npcStudio$hold(local, npcStudio$jointShare(corners[0].y() / 16f));
			pose.transformNormal(local, normal);

			for (var corner : corners) {
				at.set(corner.x() / 16f, corner.y() / 16f, corner.z() / 16f);
				npcStudio$holdJoint(at);
				npcStudio$tuck(at);
				npcStudio$bendPoint(at);
				pose.pose().transformPosition(at);
				consumer.addVertex(at.x, at.y, at.z, colour, corner.u(), corner.v(),
					overlay, light, normal.x(), normal.y(), normal.z());
			}
		}
		return true;
	}

	@Unique
	private com.mopicmp.npcstudio.entity.SegmentMesh.Corner npcStudio$corner(
			ModelPart.Vertex vertex) {
		return new com.mopicmp.npcstudio.entity.SegmentMesh.Corner(
			vertex.worldX() * 16f, vertex.worldY() * 16f, vertex.worldZ() * 16f,
			vertex.u(), vertex.v());
	}

	/** Which way a generated face looks, taken from the face itself. */
	@Unique
	private void npcStudio$facing(
			com.mopicmp.npcstudio.entity.SegmentMesh.Corner[] corners, Vector3f out) {
		float ux = corners[1].x() - corners[0].x();
		float uy = corners[1].y() - corners[0].y();
		float uz = corners[1].z() - corners[0].z();
		float vx = corners[3].x() - corners[0].x();
		float vy = corners[3].y() - corners[0].y();
		float vz = corners[3].z() - corners[0].z();
		out.set(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
		if (out.lengthSquared() > 1e-8f) out.normalize();
	}

	@Unique
	private void npcStudio$emit(PoseStack.Pose pose, VertexConsumer consumer,
			int light, int overlay, int colour) {
		Matrix4f matrix = pose.pose();
		Vector3f normal = new Vector3f();
		Vector3f local = new Vector3f();
		Vector3f middle = new Vector3f();
		if (npcStudio$bottom - npcStudio$top <= 0) return;

		boolean bulging = npcStudio$build.chest() != 0 || npcStudio$build.belly() != 0;
		// A part the field is drawing varies down its whole length — a waist, a knee,
		// a joint being held back — so it is always cut along. Across, it stays whole
		// unless there are corners to round or a chest to draw: nothing else here
		// varies from side to side, and columns nobody asked for are the expensive
		// kind of thorough.
		boolean placed = npcStudio$place != null;
		float softness = placed ? npcStudio$place.shape().softness() : 0f;
		int columns = softness > 0 ? COLUMNS * 2
			: npcStudio$round > 0 || bulging ? COLUMNS
			: 1;
		// Anything that varies down the part needs the part cut down its length: a
		// bend, a taper from shoulders to hips, or a bulge that has a height.
		//
		// A bend needs it for one reason only, and it is worth being precise about
		// which: within either half of a fold the transformation is affine, so a
		// quad stays flat and one band would do. It is the joint itself that has to
		// fall on a band boundary, so that no single quad has corners on both
		// halves and is stretched across the crease. BANDS is even, so the middle
		// of the part is a boundary.
		boolean alongLength = npcStudio$bend != 0 || npcStudio$build.tapers() || bulging || placed;
		int bands = alongLength ? BANDS : 1;

		for (ModelPart.Cube cube : cubes) {
			for (ModelPart.Polygon polygon : cube.polygons) {
				local.set(polygon.normal());
				boolean cap = Math.abs(local.y) > 0.5f;

				int firstAxis = cap ? AXIS_X : AXIS_Y;
				int firstCount = cap ? columns : bands;
				int secondAxis = cap ? AXIS_Z
					: Math.abs(local.z) >= Math.abs(local.x) ? AXIS_X : AXIS_Z;

				// The front and back of a torso carrying a chest are the one place
				// that needs real resolution across, because two lobes cannot be
				// drawn on four columns without coming out as two pyramids. Nowhere
				// else pays for it.
				int secondCount = secondAxis == AXIS_X && bulging
					? Math.max(columns, com.mopicmp.npcstudio.client.entity.BodyBuilder.LOBE_COLUMNS)
					: columns;

				for (int first = 0; first < firstCount; first++) {
					ModelPart.Vertex[] strip =
						npcStudio$piece(polygon.vertices(), firstAxis, first, firstCount);
					if (strip.length < 3) continue;

					for (int second = 0; second < secondCount; second++) {
						ModelPart.Vertex[] slice =
							npcStudio$piece(strip, secondAxis, second, secondCount);
						if (slice.length < 3) continue;
						npcStudio$facet(pose, consumer, matrix, slice, polygon,
							local, normal, middle, light, overlay, colour);
					}
				}
			}
		}
	}

	/**
	 * Draws one piece of a face, facing whichever way that piece has ended up.
	 *
	 * One normal for the whole piece, taken at its middle. The corners are moved
	 * one at a time, so only the shading is stepped — and with sixteen facets round
	 * a limb, those steps are what makes it read as round.
	 */
	@Unique
	private void npcStudio$facet(PoseStack.Pose pose, VertexConsumer consumer, Matrix4f matrix,
			ModelPart.Vertex[] slice, ModelPart.Polygon polygon, Vector3f local, Vector3f normal,
			Vector3f middle, int light, int overlay, int colour) {
		middle.zero();
		for (ModelPart.Vertex corner : slice) {
			middle.add(corner.worldX(), corner.worldY(), corner.worldZ());
		}
		middle.div(slice.length);
		float height = middle.y;
		npcStudio$thicken(middle);

		// Rounded and bent while still in the limb's own space, and only then put
		// into the world. Either one done the other way round lights a curved arm
		// as though it were still straight.
		local.set(polygon.normal());
		npcStudio$roundNormal(local, middle);
		npcStudio$turn(local, npcStudio$angleAt(height), false);
		// Lit by the turn it is actually drawn with. A band held back at the hip is
		// facing where the pelvis faces, not where the leg is swinging.
		npcStudio$hold(local, npcStudio$jointShare(height));
		pose.transformNormal(local, normal);

		for (int i = 1; i + 1 < slice.length; i++) {
			npcStudio$vertex(consumer, matrix, slice[0], normal, light, overlay, colour);
			npcStudio$vertex(consumer, matrix, slice[i], normal, light, overlay, colour);
			npcStudio$vertex(consumer, matrix, slice[i + 1], normal, light, overlay, colour);
			// The buffer wants quads, so the last corner is repeated to make the
			// triangle up to one.
			npcStudio$vertex(consumer, matrix, slice[i + 1], normal, light, overlay, colour);
		}
	}

	/**
	 * One of {@code count} equal slabs of a face, cut across the given axis.
	 *
	 * The arithmetic lives in {@link com.mopicmp.npcstudio.client.emote.Slicing},
	 * where it can be run against a face and asked whether the pieces still carry
	 * the right skin. All this adds is the part's own extent, which is the one
	 * thing Slicing cannot know.
	 */
	@Unique
	private ModelPart.Vertex[] npcStudio$piece(ModelPart.Vertex[] corners,
			int axis, int index, int count) {
		float low = switch (axis) {
			case AXIS_X -> npcStudio$ownMiddleX - npcStudio$ownHalfX;
			case AXIS_Z -> npcStudio$ownMiddleZ - npcStudio$ownHalfZ;
			default -> npcStudio$ownTop;
		};
		float high = switch (axis) {
			case AXIS_X -> npcStudio$ownMiddleX + npcStudio$ownHalfX;
			case AXIS_Z -> npcStudio$ownMiddleZ + npcStudio$ownHalfZ;
			default -> npcStudio$ownBottom;
		};
		return com.mopicmp.npcstudio.client.emote.Slicing.piece(
			corners, axis, index, count, low, high);
	}

	@Unique
	private static float lerp(float from, float to, float t) {
		return from + (to - from) * t;
	}

	/** Swings a point about the joint, in the limb's own space. */
	@Unique
	private void npcStudio$turn(Vector3f point, float angle, boolean position) {
		if (angle == 0) return;
		float middle = (npcStudio$top + npcStudio$bottom) / 2f;
		float sin = (float) Math.sin(angle);
		float cos = (float) Math.cos(angle);

		float y = position ? point.y - middle : point.y;
		float z = point.z;
		point.y = (position ? middle : 0) + y * cos - z * sin;
		point.z = y * sin + z * cos;
	}

	@Unique
	private void npcStudio$vertex(VertexConsumer consumer, Matrix4f matrix,
			ModelPart.Vertex corner, Vector3f normal,
			int light, int overlay, int colour) {
		Vector3f at = new Vector3f(corner.worldX(), corner.worldY(), corner.worldZ());
		// Thickened before it is folded, because the thickness belongs to the limb
		// and the fold happens to it. The other order would measure the bulge from
		// wherever the bend had already carried the point, so a bent arm would
		// swell in the wrong place.
		npcStudio$thicken(at);
		// And the top of the limb put back where the pelvis left it, before anything
		// measures against the part's own box: the tuck below is measured that way,
		// and the fold after it wants a limb already the shape it will be drawn in.
		npcStudio$holdJoint(at);
		// A hair inside its own surface, which is what stops a limb and the body it
		// touches arguing over the same depth. Towards the part's own middle rather
		// than along this face's normal: a corner belongs to three faces, and giving
		// it three answers is what tore every edge open. See Tuck.
		//
		// Before the fold, because it is measured against the part's own straight
		// box; afterwards there is no such box to measure against.
		npcStudio$tuck(at);
		// And folded last, so that the fold happens to a limb that is already the
		// shape it is going to be drawn in.
		npcStudio$bendPoint(at);
		matrix.transformPosition(at);
		consumer.addVertex(at.x, at.y, at.z, colour, corner.u(), corner.v(),
			overlay, light, normal.x(), normal.y(), normal.z());
	}
}
