package com.mopicmp.npcstudio.client.emote;

import com.mopicmp.npcstudio.client.emote.Emote.Bone;
import com.mopicmp.npcstudio.client.emote.Emote.Channel;
import com.mopicmp.npcstudio.client.emote.Emote.Pose;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mopicmp.npcstudio.client.NpcStudioConfig;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

/**
 * Puts a sampled pose onto the vanilla player model.
 *
 * The rig an emote is animated against has the torso as the parent of
 * everything else, and the vanilla model does not: its head, arms and legs are
 * siblings. Composing that by hand would mean multiplying rotations and
 * converting back to Euler angles, which is where this sort of code usually
 * goes wrong.
 *
 * It is avoided entirely: all of those parts are children of the model's root,
 * so the torso track is applied to the root and the hierarchy the emote was
 * built for comes back for free.
 *
 * A limb the emote does not mention is left alone, so an emote that only moves
 * the arms lets vanilla keep animating the legs.
 */
public final class EmoteApplier {

	/**
	 * A limb is placed, and the torso is nudged. They are not the same kind of
	 * number and the first version of this treated them as though they were.
	 *
	 * Limb tracks hold an absolute position in model space, in the model's own
	 * pixels and on the model's own axes. This is not a guess: across the pack
	 * the right arm's x sits at exactly −5 and the left at +5, the legs at ∓1.9,
	 * and the legs' y at 12 — which are, to the decimal, where the vanilla parts
	 * already are. An animator moving a limb writes down where it ends up, not
	 * how far it travelled. Adding these to the rest pose, as this once did,
	 * doubled every one of them: arms at ∓10, legs sunk to 24. That is exactly
	 * what a character with its arms and legs flung off its body looks like.
	 *
	 * The torso is the exception, and has to be. Its numbers are tiny — a sit is
	 * −0.69, a crouch −0.13 — which is meaningless in pixels and right in blocks,
	 * because moving a torso means moving the whole character through the world.
	 * So it alone is an offset, scaled up, and flipped: an animator counts up
	 * and a model counts down.
	 */
	private static final float TORSO_SCALE = 16.0f;

	private EmoteApplier() { }

	/**
	 * Puts every fold back to nothing.
	 *
	 * Called for anybody who is not mid-emote, players included, and that is not
	 * belt and braces. The game keeps one model per kind of entity and lends it
	 * to each in turn, so a bent elbow left behind by an NPC would be worn by the
	 * next player drawn with the same model.
	 */
	public static void rest(PlayerModel model) {
		// Everything visible again. Hiding is decided fresh each frame, and a part
		// switched off for one character would otherwise stay off for the next one
		// drawn with the same model.
		model.body.visible = true;
		model.head.visible = true;
		model.rightArm.visible = true;
		model.leftArm.visible = true;
		model.rightLeg.visible = true;
		model.leftLeg.visible = true;

		bend(model.body, model.jacket, 0);
		bend(model.head, model.hat, 0);
		bend(model.rightArm, model.rightSleeve, 0);
		bend(model.leftArm, model.leftSleeve, 0);
		bend(model.rightLeg, model.rightPants, 0);
		bend(model.leftLeg, model.leftPants, 0);
	}

	/**
	 * Folds a limb and the sleeve over it by the same amount.
	 *
	 * The outer layer has to be told separately even though it hangs off the
	 * limb: it is a box of its own, and a box only knows how to cut itself.
	 */
	public static void bend(ModelPart part, ModelPart layer, float radians) {
		// Cast through Object because the class is final and knows nothing of this
		// interface at compile time — the mixin is what puts the two together, and
		// it does so after the compiler has stopped looking.
		BendablePart bendable = (BendablePart) (Object) part;
		bendable.npcStudio$setBend(radians);
		if (layer == null) return;

		// The sleeve deforms by the arm's numbers rather than working out its own.
		// It is an inflated copy, so every box it could measure is a fraction
		// bigger — and a fraction is enough, because nothing we do to a vertex is
		// linear. Measured apart, the two surfaces crease differently, cross, and
		// the overlap shimmers.
		if (!com.mopicmp.npcstudio.client.NpcStudioConfig.get().foldOuterLayer) return;

		BendablePart cover = (BendablePart) (Object) layer;
		cover.npcStudio$setBend(radians);
		cover.npcStudio$followExtent(bendable.npcStudio$extent());
	}

	/**
	 * Puts an emote on the model, optionally only part of the way.
	 *
	 * @param strength how far towards the emote to go, nought to one. One is the
	 *                 emote as authored. Below one every value sits between where
	 *                 the part rests and where the emote wants it, which is what
	 *                 lets a gesture with a set length hand the body back instead
	 *                 of dropping it — emotes end wherever the animator left them,
	 *                 and a bow that ends bowed cannot simply stop.
	 */
	public static void apply(PlayerModel model, Emote emote, float age, float strength) {
		apply(model, emote, age, strength, false);
	}

	/**
	 * Puts an emote on, over whatever is already there.
	 *
	 * <h2>Why there has to be a second way of doing this</h2>
	 *
	 * The ordinary one blends from the model's <em>resting</em> pose, which is right
	 * for one animation and wrong for two. Lay a second emote on with it and the
	 * first is not blended with, it is erased: every limb is worked out afresh from
	 * where a limb rests, as though nothing had been applied at all.
	 *
	 * That is why changing animation snapped. It was reported about a character who
	 * stands smoking and then notices you — and the transition did not look abrupt
	 * because it was fast, it looked abrupt because there was no transition
	 * anywhere. One frame of one pose, then one frame of another.
	 *
	 * So this way blends from wherever the limbs currently <em>are</em>. Apply the
	 * outgoing emote at full and then this one at a strength climbing from nought,
	 * and what comes out is a crossfade between the two. At strength one it is
	 * exactly the ordinary path, which is what keeps everything that already works
	 * untouched.
	 *
	 * @param over whether to blend from where the limbs are rather than from rest
	 */
	public static void apply(PlayerModel model, Emote emote, float age, float strength,
			boolean over) {
		Pose pose = emote.poseAt(emote.timeAt(age));
		float s = Math.clamp(strength, 0f, 1f);

		if (bodyPutAway(pose)) {
			walkingHead(model, pose, s);
			return;
		}

		// How folded the body was before this pass touched it. Nought for a single
		// animation; whatever the outgoing one left for a change.
		float hadFold = ((BendablePart) (Object) model.body).npcStudio$bend();

		torso(model, pose, s, over);
		limb(model.head, model.hat, pose, Bone.HEAD, s, over);
		limb(model.rightArm, model.rightSleeve, pose, Bone.RIGHT_ARM, s, over);
		limb(model.leftArm, model.leftSleeve, pose, Bone.LEFT_ARM, s, over);
		limb(model.rightLeg, model.rightPants, pose, Bone.RIGHT_LEG, s, over);
		limb(model.leftLeg, model.leftPants, pose, Bone.LEFT_LEG, s, over);

		// After the limbs, because it adds to where they were put. Given the fold
		// actually on the body rather than this pose's own, so that the legs follow
		// the waist that is being drawn — halfway through a change that is a fold
		// neither animation asked for.
		legsFollowTheWaist(model,
			((BendablePart) (Object) model.body).npcStudio$bend(), hadFold);

		// The second skin layer is deliberately not touched here. Those parts are
		// children of the limbs they cover — the model builds them as
		// `leftArm.getChild("left_sleeve")` — so they inherit the pose already.
		// Copying the arm's transform onto its own sleeve, which this used to do,
		// applied it twice and sent the sleeve flying off on its own.
	}

	/**
	 * How far down the body the waist is from the body box's own middle, in pixels.
	 *
	 * The body spans the neck at nought to the waist at twelve, so its middle is
	 * six, and the waist is six below that. A fold turns points about that middle,
	 * so six is the arm of the lever the waist swings on.
	 */
	private static final float WAIST_ARM = 6.0f;

	/**
	 * Moves the legs to wherever the folded torso has carried the waist.
	 *
	 * <h2>The legs that came away from the body</h2>
	 *
	 * A torso bend folds the body box at its middle: everything above the joint
	 * stays, everything below swings. The bottom of the body is the waist, and the
	 * waist is where the legs hang from — but the legs are not part of that box.
	 * They are their own parts, sitting where the emote's own tracks put them,
	 * and no fold of the torso ever moved them.
	 *
	 * So a character bending forward left its hips behind. It is not subtle: the
	 * fighting pose bends by 0.63 radians, which swings the waist three and a half
	 * pixels backwards on a torso only four pixels deep. Burpees and the delight
	 * bend further still. That is the gap.
	 *
	 * Added to where the tracks put the legs rather than replacing it, because the
	 * emote has its own opinion about where the legs are and this is only the
	 * correction for a fold it did not know we would draw.
	 */
	private static void legsFollowTheWaist(PlayerModel model, float fold, float had) {
		if (fold == had) return;

		// The difference rather than the whole thing, because this runs once per
		// animation being laid on and the legs must end up carrying one correction
		// rather than two. Undoing what the previous pass put there and putting the
		// new one leaves exactly the correction for the fold now on the body — and
		// for a single animation, where nothing was there before, it is unchanged.
		Vector3f moved = waistAfterFolding(fold);
		Vector3f before = waistAfterFolding(had);
		for (ModelPart leg : new ModelPart[] { model.rightLeg, model.leftLeg }) {
			leg.y += moved.y - before.y;
			leg.z += moved.z - before.z;
		}
		// Not the trousers: they are children of the legs and have moved already.
	}

	/**
	 * Where a fold of this much carries the waist, in model pixels.
	 *
	 * The same arithmetic the renderer performs on every vertex of the torso,
	 * applied once to the single point the legs care about — so the two cannot
	 * disagree without this being wrong first.
	 *
	 * Public because it is worth a test: it is the number that decides whether a
	 * bending character keeps its hips.
	 */
	public static Vector3f waistAfterFolding(float fold) {
		return new Vector3f(0,
			WAIST_ARM * ((float) Math.cos(fold) - 1f),
			WAIST_ARM * (float) Math.sin(fold));
	}

	/**
	 * Where the body bends: the waist, twelve pixels below the model's origin.
	 *
	 * A vanilla model part turns about its own origin, and the body's origin is
	 * at the neck. An animator's torso turns about the waist, and the difference
	 * is the whole character.
	 *
	 * The pack settles it without any need to guess. "Лежать" pitches the torso
	 * by exactly 90° and drops it ten pixels. Turned about the neck, the body
	 * ends up lying in the air a quarter of a block above the floor and those ten
	 * pixels do not save it. Turned about the waist it comes to rest on the
	 * ground, which is what lying down is. Press-ups say the same thing: 76° and
	 * barely a third of a block of drop, which only reaches the floor if the hips
	 * stay where they are.
	 */
	private static final float WAIST = 12.0f;

	/**
	 * The furthest a bone is allowed to be moved, in its own units.
	 *
	 * Some emotes park a limb thousands of units away — the "walking head" family
	 * puts its head and legs at z = −3200 and its torso a hundred and eighty
	 * blocks off, which is presumably how their author hides a body. Whatever it
	 * means, taken literally it throws the character clean out of the frame, and
	 * a character nobody can see reads as an animation that does not exist.
	 *
	 * So it is bounded. The pack's ordinary movements do not come close — limbs
	 * reach thirty pixels and torsos a couple of blocks — so nothing real is
	 * altered, and an emote we do not understand comes out looking odd instead of
	 * looking absent.
	 */
	private static final float LIMB_LIMIT = 64.0f;
	private static final float TORSO_LIMIT = 3.0f;

	private static float shift(float blocks) {
		return Math.clamp(blocks, -TORSO_LIMIT, TORSO_LIMIT);
	}

	/**
	 * Whether this emote is throwing the body away rather than moving it.
	 *
	 * A hundred and eighty blocks is not a lean. Nothing in the pack that means
	 * "shift the torso" goes past a couple of blocks, so anything at this scale is
	 * the author reaching for the only tool the format gives for hiding a bone:
	 * sending it somewhere nobody can see.
	 */
	private static boolean bodyPutAway(Pose pose) {
		return Math.abs(pose.or(Bone.TORSO, Channel.X, 0)) > TORSO_LIMIT
			|| Math.abs(pose.or(Bone.TORSO, Channel.Y, 0)) > TORSO_LIMIT
			|| Math.abs(pose.or(Bone.TORSO, Channel.Z, 0)) > TORSO_LIMIT;
	}

	/**
	 * The walking heads, and everything else built the same way.
	 *
	 * <h2>How the trick works</h2>
	 *
	 * The format has no way to say "do not draw this bone", so the author says it
	 * with distance: the torso goes a hundred and eighty blocks down, taking
	 * everything parented to it out of the world. Then the bones that <em>should</em>
	 * still be seen are given an equal and opposite fling — head and legs at
	 * z = −3200 — which, once the torso's ninety-degree pitch has turned that
	 * offset into a vertical one, brings them back where a character stands.
	 *
	 * What arrives is a head and a pair of legs with no body between them.
	 *
	 * <h2>Why it came out inside out</h2>
	 *
	 * We clamp both halves of that cancellation separately — the torso to three
	 * blocks, a limb to sixty-four pixels — and hide any limb that has been flung.
	 * So we hid exactly the bones that were compensating, and kept exactly the
	 * bones that were meant to leave. Head and legs gone, body and arms standing
	 * there: the precise inverse of the intent.
	 *
	 * <h2>What is done instead</h2>
	 *
	 * The cancellation is undone rather than clamped. A bone that was flung is put
	 * back where it rests and turned by its own angle <b>less the torso's</b>,
	 * which is the composition the author was writing. A bone that was not flung
	 * was going with the body, so it goes.
	 *
	 * The arithmetic checks itself: the legs of the walking run are authored at
	 * pitches of 1.20 to 1.95 against a torso pitch of 1.57. Subtract, and they
	 * swing between −0.37 and +0.38 radians — a walk cycle of twenty-two degrees
	 * either side. Numbers that fall out as a walk were a walk.
	 */
	private static void walkingHead(PlayerModel model, Pose pose, float s) {
		ModelPart root = model.root();
		PartPose rest = root.getInitialPose();
		root.x = rest.x();
		root.y = rest.y();
		root.z = rest.z();
		root.xRot = rest.xRot();
		root.yRot = rest.yRot();
		root.zRot = rest.zRot();

		// The body is what was being hidden. Its second layer is its child and goes
		// with it without being asked.
		model.body.visible = false;

		float turn = pose.or(Bone.TORSO, Channel.PITCH, 0) * s;
		kept(model.head, pose, Bone.HEAD, turn, s);

		// And the head comes down to sit on the hips. Putting every kept bone back
		// where it rests leaves a gap exactly the height of the body that is no
		// longer there — twelve pixels of nothing between a head and a pair of legs
		// walking along underneath it. The head belongs on the legs; that is the
		// whole idea of a walking head.
		model.head.y += WAIST * s;

		kept(model.rightArm, pose, Bone.RIGHT_ARM, turn, s);
		kept(model.leftArm, pose, Bone.LEFT_ARM, turn, s);
		kept(model.rightLeg, pose, Bone.RIGHT_LEG, turn, s);
		kept(model.leftLeg, pose, Bone.LEFT_LEG, turn, s);
	}

	/** One bone of a body-put-away emote: shown if it compensated, hidden if not. */
	private static void kept(ModelPart part, Pose pose, Bone bone, float turn, float s) {
		if (!pose.has(bone)) return;
		PartPose rest = part.getInitialPose();

		boolean compensated = Math.abs(pose.or(bone, Channel.X, rest.x()) - rest.x()) > LIMB_LIMIT
			|| Math.abs(pose.or(bone, Channel.Y, rest.y()) - rest.y()) > LIMB_LIMIT
			|| Math.abs(pose.or(bone, Channel.Z, rest.z()) - rest.z()) > LIMB_LIMIT;
		part.visible = compensated;
		if (!compensated) return;

		// Back where it belongs: the huge offset existed only to cancel a throw we
		// are not making.
		part.x = rest.x();
		part.y = rest.y();
		part.z = rest.z();

		// And turned by its own angle less the torso's, which is what the two were
		// composed to give.
		part.xRot = mix(rest.xRot(), pose.or(bone, Channel.PITCH, rest.xRot()) - turn, s);
		part.yRot = mix(rest.yRot(), pose.or(bone, Channel.YAW, rest.yRot()), s);
		part.zRot = mix(rest.zRot(), pose.or(bone, Channel.ROLL, rest.zRot()), s);
	}

	private static float place(float pixels) {
		return Math.clamp(pixels, -LIMB_LIMIT, LIMB_LIMIT);
	}

	/**
	 * The whole character, shifted and leaned.
	 *
	 * The torso is authored in world terms rather than model terms, and the game
	 * itself supplies the conversion: an entity model is drawn under a
	 * {@code scale(-1, -1, 1)}, so world and model differ by a half turn about Z.
	 * That flips x and y, and with them the rotations about those two axes, while
	 * leaving z and the roll alone.
	 *
	 * Each half of that was found separately before it was understood as one
	 * thing. Sitting emotes settle their torso negative, which only reads as
	 * downwards if y is flipped. Bows and apologies pitch negative, which only
	 * reads as forwards if the pitch is flipped — and until it was, a bow of a
	 * hundred degrees came out as a character standing on its head.
	 */
	private static void torso(PlayerModel model, Pose pose, float s, boolean over) {
		if (!pose.has(Bone.TORSO)) return;
		ModelPart root = model.root();
		PartPose rest = root.getInitialPose();

		// Where the blend starts, and this is the part that was missing.
		//
		// Every torso term is the rest pose plus an offset, so for one animation
		// scaling the offset is already the blend — which is what this used to do,
		// and it is right for one animation and wrong for two. Laid over another it
		// does not blend with the pose underneath, it overwrites it: at the first
		// tick of a change the strength is nought, so the whole character snapped
		// bolt upright and then leaned into the new pose.
		//
		// The torso turns the model's root, so everything hangs off it. That snap was
		// every limb moving at once, which is what "some limbs teleport" is.
		float fromPitch = over ? root.xRot : rest.xRot();
		float fromYaw = over ? root.yRot : rest.yRot();
		float fromRoll = over ? root.zRot : rest.zRot();
		// The correction below is a pure function of the rotations, so the one
		// already sitting in the root's position can be worked out from the
		// rotations still on it and taken back off. That leaves the position the
		// previous pose asked for, which is what there is to blend from.
		Vector3f already = over
			? waistCorrection(fromPitch, fromYaw, fromRoll)
			: new Vector3f();
		float fromX = (over ? root.x : rest.x()) - already.x;
		float fromY = (over ? root.y : rest.y()) - already.y;
		float fromZ = (over ? root.z : rest.z()) - already.z;
		float fromBend = over ? ((BendablePart) (Object) model.body).npcStudio$bend() : 0;

		// The bend is not part of this rotation, and folding it in was a mistake
		// worth naming: a torso bend curves the body at the waist, while the root
		// rotation swings the entire character. "Reading a book while sitting"
		// wants a lean of 38° and a fold of 61°, and adding them gave a hundred
		// degrees of lean — a character reading a book behind itself.
		root.xRot = mix(fromPitch, rest.xRot() - pose.or(Bone.TORSO, Channel.PITCH, 0), s);
		bend(model.body, model.jacket,
			mix(fromBend, -pose.or(Bone.TORSO, Channel.BEND, 0), s));
		root.yRot = mix(fromYaw, rest.yRot() - pose.or(Bone.TORSO, Channel.YAW, 0), s);
		root.zRot = mix(fromRoll, rest.zRot() + pose.or(Bone.TORSO, Channel.ROLL, 0), s);

		root.x = mix(fromX,
			rest.x() - shift(pose.or(Bone.TORSO, Channel.X, 0)) * TORSO_SCALE, s);
		root.y = mix(fromY,
			rest.y() - shift(pose.or(Bone.TORSO, Channel.Y, 0)) * TORSO_SCALE, s);
		root.z = mix(fromZ,
			rest.z() + shift(pose.or(Bone.TORSO, Channel.Z, 0)) * TORSO_SCALE, s);

		// Worked out from the rotations that ended up on the root rather than from
		// either pose's own, so it is always the correction for the lean actually
		// being drawn. Halfway through a change that is a lean neither animation
		// asked for, and it is the one the waist has to be held under.
		Vector3f correction = waistCorrection(root.xRot, root.yRot, root.zRot);
		root.x += correction.x;
		root.y += correction.y;
		root.z += correction.z;
	}

	/**
	 * How far to shift so that the waist stays put while the body turns.
	 *
	 * A model part turns about its own origin, so turning it sends the waist
	 * somewhere else; moving back by however far the waist travelled leaves it
	 * where it was and swings the body around it instead.
	 *
	 * The rotations are composed in the order the game applies them — z, then y,
	 * then x — because a correction built from a different order would be right
	 * only while two of the three were zero.
	 *
	 * With no rotation this comes out as zero, so a character standing still is
	 * untouched by any of it.
	 */
	public static Vector3f waistCorrection(float xRot, float yRot, float zRot) {
		Vector3f waist = new Vector3f(0, WAIST, 0);
		Vector3f turned = new Quaternionf()
			.rotateZ(zRot).rotateY(yRot).rotateX(xRot)
			.transform(new Vector3f(waist));
		return waist.sub(turned);
	}

	/**
	 * Poses one part, if the emote has anything to say about it.
	 *
	 * Each value is taken as given, and each one the emote is silent about falls
	 * back to where the part rests. That fallback matters more than it looks:
	 * every keyframe in this format carries a single channel of a single limb, so
	 * an emote that only rotates an arm genuinely has no opinion on where the arm
	 * is, and treating that silence as zero would drag it into the character's
	 * chest.
	 *
	 * The rotation replaces rather than adds. An emote is a whole performance
	 * rather than a flourish on top of one, and adding it to the walk cycle would
	 * give a character that waves while its arm is also swinging.
	 */
	private static void limb(ModelPart part, ModelPart layer, Pose pose, Bone bone, float s) {
		limb(part, layer, pose, bone, s, false);
	}

	private static void limb(ModelPart part, ModelPart layer, Pose pose, Bone bone, float s,
			boolean over) {
		if (!pose.has(bone)) return;
		PartPose rest = part.getInitialPose();
		// Where the blend starts from. Rest for a single animation, which is the
		// ordinary case and unchanged; wherever the limb currently is when this emote
		// is being laid over another, which is what makes a crossfade a crossfade
		// rather than a replacement.
		float fromX = over ? part.x : rest.x();
		float fromY = over ? part.y : rest.y();
		float fromZ = over ? part.z : rest.z();
		float fromPitch = over ? part.xRot : rest.xRot();
		float fromYaw = over ? part.yRot : rest.yRot();
		float fromRoll = over ? part.zRot : rest.zRot();
		// The elbow too, and it was the last thing in the pose that did not blend.
		//
		// A fold is a rotation like any other, but it was written as the value scaled
		// by the strength rather than as a mix from where the limb is. For one
		// animation those are the same thing, since a limb starts unfolded. Laid over
		// another they are not: at the first tick of a change the strength is nought,
		// so every elbow and knee went straight in one frame and then bent back into
		// the new pose.
		//
		// It reads as one limb teleporting, because it is: bare-handed fighting folds
		// an arm by a radian and a half, and a forearm snapping out of ninety degrees
		// throws the hand most of the length of the arm.
		float fromBend = over ? ((BendablePart) (Object) part).npcStudio$bend() : 0;

		float x = pose.or(bone, Channel.X, rest.x());
		float y = pose.or(bone, Channel.Y, rest.y());
		float z = pose.or(bone, Channel.Z, rest.z());

		// A limb parked a long way from the body is hidden rather than dragged
		// back. Some emotes fling parts thousands of units off — that is how the
		// "walking head" family gets rid of a body it does not want — and the
		// honest reading of a limb at z = −3200 is "not there". Clamping it made
		// it sit at the edge of the allowed box instead, which is a hand hanging
		// in mid-air rather than a hand that has gone.
		// Judged on where the emote wants the limb, not on where the blend has it
		// this frame. A part flung off the model is gone for the whole gesture; if
		// this looked at the blended position it would come back into view partway
		// through the ending and then vanish again with the rest of the pose.
		NpcStudioConfig config = NpcStudioConfig.get();
		boolean adrift = config.hideDistantParts
			&& (Math.abs(x - rest.x()) > config.distantPart
				|| Math.abs(y - rest.y()) > config.distantPart
				|| Math.abs(z - rest.z()) > config.distantPart);
		part.visible = !adrift;
		if (adrift) return;

		part.x = place(mix(fromX, x, s));
		part.y = place(mix(fromY, y, s));
		part.z = place(mix(fromZ, z, s));

		part.xRot = mix(fromPitch, pose.or(bone, Channel.PITCH, rest.xRot()), s);

		// The fold itself is done by the renderer, which cuts the box in two and
		// turns the far half. This only says by how much — and says it even when
		// the answer is nothing, because a model is shared between every character
		// wearing it and a bend left set would follow the next one out.
		bend(part, layer, mix(fromBend, pose.or(bone, Channel.BEND, 0), s));
		part.yRot = mix(fromYaw, pose.or(bone, Channel.YAW, rest.yRot()), s);
		part.zRot = mix(fromRoll, pose.or(bone, Channel.ROLL, rest.zRot()), s);
	}

	/**
	 * Part of the way from where a limb rests to where the emote wants it.
	 *
	 * Limb tracks are absolute positions, so unlike the torso there is nothing to
	 * scale — the blend has to be written out. At strength one this returns the
	 * emote's value exactly, which is what keeps the ordinary case untouched.
	 */
	public static float mix(float rest, float wanted, float strength) {
		return rest + (wanted - rest) * strength;
	}
}
