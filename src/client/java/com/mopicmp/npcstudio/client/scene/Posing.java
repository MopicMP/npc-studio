package com.mopicmp.npcstudio.client.scene;

import java.util.List;

import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

/**
 * Putting a scene's pose onto a character.
 *
 * <h2>Which bones a character has</h2>
 *
 * The six the vanilla model has, under the names it gives them. Not a rig of our
 * own: an NPC <em>is</em> a player model, the emote packs are animated against
 * these same six, and inventing a seventh name for the same part would mean
 * translating between two vocabularies for ever with nothing gained at either
 * end.
 *
 * <h2>Absolute, and only where the scene speaks</h2>
 *
 * A keyed rotation replaces the part's rotation rather than being added to it.
 * That is what makes an authored pose <em>the</em> pose: added, a character that
 * is also idling would drift away from what somebody set, and the number in the
 * box would stop meaning where the arm is.
 *
 * Only where the scene speaks, though, and that is per number rather than per
 * bone. Keying the head's X leaves its Y still following whoever walks past — so
 * a scene can author a nod without also freezing the character's attention.
 * Every read hands over the value that is already there, and a channel with no
 * track hands it straight back.
 */
public final class Posing {

	/** The bones of a character, in the order anybody would look for them. */
	public static final List<String> BONES =
		List.of("head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg");

	private static final float RADIANS = (float) (Math.PI / 180);

	private Posing() { }

	public static ModelPart partOf(PlayerModel model, String bone) {
		return switch (bone) {
			case "head" -> model.head;
			case "body" -> model.body;
			case "rightArm" -> model.rightArm;
			case "leftArm" -> model.leftArm;
			case "rightLeg" -> model.rightLeg;
			case "leftLeg" -> model.leftLeg;
			default -> null;
		};
	}

	/**
	 * Poses whatever the scene has anything to say about.
	 *
	 * Runs after everything else the model does to itself — after the walk, after
	 * the gesture, after the build — because it is the authored answer and the
	 * others are what happens when nobody has authored one.
	 */
	public static void apply(PlayerModel model, Playing.Sample staged) {
		if (staged == null) return;

		ModelPart body = partOf(model, "body");
		float wasX = body == null ? 0 : body.xRot;
		float wasY = body == null ? 0 : body.yRot;
		float wasZ = body == null ? 0 : body.zRot;

		for (String bone : BONES) {
			ModelPart part = partOf(model, bone);
			if (part == null) continue;

			part.xRot = turn(staged, bone, Channels.TURN_X, part.xRot);
			part.yRot = turn(staged, bone, Channels.TURN_Y, part.yRot);
			part.zRot = turn(staged, bone, Channels.TURN_Z, part.zRot);

			part.x = staged.value(Channels.of(bone, Channels.SHIFT_X), part.x);
			part.y = staged.value(Channels.of(bone, Channels.SHIFT_Y), part.y);
			part.z = staged.value(Channels.of(bone, Channels.SHIFT_Z), part.z);

			fold(model, staged, bone, part);
		}

		lean(model, staged, wasX, wasY, wasZ);
	}

	/**
	 * Folds a limb, if the scene asks.
	 *
	 * <h2>Nothing new is drawn here</h2>
	 *
	 * The fold is the one the emotes have always used and the one the animations
	 * are already bent by: the limb is sliced into bands across its length and each
	 * band is turned by its share, so the surface curves and stays in one piece
	 * rather than opening into a wedge at the joint. See {@code ModelPartMixin}.
	 *
	 * All that was missing was a way for a scene to ask for it. So this is the same
	 * call the emote applier makes, through the emote applier — a second copy of
	 * those four lines would be a second place for the sleeve to be forgotten, and
	 * a sleeve that does not fold with the arm inside it shimmers where the two
	 * surfaces cross.
	 *
	 * <h2>And only when asked</h2>
	 *
	 * A bone the scene says nothing about keeps whatever the emote left on it. That
	 * is the same rule every other channel here follows, and it is what lets a scene
	 * straighten one arm of a character who is otherwise mid-gesture.
	 */
	private static void fold(PlayerModel model, Playing.Sample staged,
			String bone, ModelPart part) {
		String channel = Channels.of(bone, Channels.BEND);
		float degrees = staged.value(channel, Float.NaN);
		if (Float.isNaN(degrees)) return;
		com.mopicmp.npcstudio.client.emote.EmoteApplier.bend(
			part, layerOf(model, bone), degrees * RADIANS);
	}

	/**
	 * The outer layer over a bone, or null where there is none.
	 *
	 * A jacket is a box of its own and a box only knows how to fold itself, so it
	 * has to be told separately about everything that happens to the limb inside
	 * it.
	 */
	public static ModelPart layerOf(PlayerModel model, String bone) {
		return switch (bone) {
			case "head" -> model.hat;
			case "body" -> model.jacket;
			case "rightArm" -> model.rightSleeve;
			case "leftArm" -> model.leftSleeve;
			case "rightLeg" -> model.rightPants;
			case "leftLeg" -> model.leftPants;
			default -> null;
		};
	}

	/**
	 * Makes turning the torso a lean rather than a dismemberment.
	 *
	 * <h2>What the model does on its own, and why it is wrong here</h2>
	 *
	 * In the player model the torso is a <em>sibling</em> of the head, the arms and
	 * the legs — all six hang off one root. So turning the torso turns the torso and
	 * nothing else, about its own pivot, which sits at the neck. Lean a character
	 * back and the shoulders stay put while the waist swings out from over the hips:
	 * the body leaves the legs behind and the head and arms stay hanging in the air
	 * where the torso used to be. That is not a lean, and it is what was reported.
	 *
	 * Nobody leans from the neck. A lean happens at the waist, and everything above
	 * the waist goes with it.
	 *
	 * <h2>So two corrections, and only when a scene asked for one</h2>
	 *
	 * <b>The pivot moves to the hips.</b> A part turns about its own origin, so
	 * turning about a point {@code d} below it is the same turn with the offset
	 * {@code d - R·d} added to where the part sits — which is arithmetic rather than
	 * a new kind of part. How far below is read from the model: the drop from the
	 * torso's pivot to the legs'.
	 *
	 * <b>The head and the arms come along.</b> Each is turned by the same rotation
	 * about the same hip point, and has that rotation put in front of its own — so a
	 * character leaning back while looking down is doing both, in that order, which
	 * is what those two words mean.
	 *
	 * The legs are left alone, and that is the point of pivoting at the hips: the
	 * top of a leg is exactly the point everything else is turning about, so it
	 * cannot come apart from it.
	 *
	 * Only when the scene actually keyed one of the torso's turns. Doing it always
	 * would mean a character riding a boat — whose torso vanilla turns for its own
	 * reasons — suddenly carried its head and arms differently from every other
	 * character in the world.
	 */
	private static void lean(PlayerModel model, Playing.Sample staged,
			float wasX, float wasY, float wasZ) {
		ModelPart body = partOf(model, "body");
		if (body == null || !says(staged, "body")) return;

		var turned = Leaning.turned(body.zRot, body.yRot, body.xRot, wasZ, wasY, wasX);

		float[] hips = restOf("rightLeg");
		float[] chest = restOf("body");
		if (hips == null || chest == null) return;
		float drop = hips[1] - chest[1];

		// Where the turn happens: the hip line, in the frame the parts share.
		float[] hip = { body.x, body.y + drop, body.z };

		// The torso itself, turned about that point rather than about its own.
		float[] offset = Leaning.offset(turned, drop);
		body.x += offset[0];
		body.y += offset[1];
		body.z += offset[2];

		for (String bone : new String[] { "head", "rightArm", "leftArm" }) {
			ModelPart part = partOf(model, bone);
			if (part == null) continue;

			float[] at = Leaning.carried(turned, hip, new float[] { part.x, part.y, part.z });
			part.x = at[0];
			part.y = at[1];
			part.z = at[2];

			float[] both = Leaning.then(turned, part.zRot, part.yRot, part.xRot);
			part.xRot = both[0];
			part.yRot = both[1];
			part.zRot = both[2];
		}
	}

	/** Whether the scene has anything to say about how this bone is turned. */
	private static boolean says(Playing.Sample staged, String bone) {
		for (String field : new String[] { Channels.TURN_X, Channels.TURN_Y, Channels.TURN_Z }) {
			if (!Float.isNaN(staged.value(Channels.of(bone, field), Float.NaN))) return true;
		}
		return false;
	}

	/**
	 * One turn, in degrees on the way in and radians on the way out.
	 *
	 * Degrees in the document because that is what a person types and what every
	 * other tool shows; radians here because that is what a model part holds. The
	 * conversion has to happen somewhere and this is the only place that knows
	 * both.
	 */
	private static float turn(Playing.Sample staged, String bone, String field, float already) {
		float degrees = staged.value(Channels.of(bone, field), already / RADIANS);
		return degrees * RADIANS;
	}

	/** What a bone is turned to now, in degrees — the number a key starts from. */
	public static float[] turnsOf(ModelPart part) {
		return new float[] { part.xRot / RADIANS, part.yRot / RADIANS, part.zRot / RADIANS };
	}

	// ------------------------------------------------------- where a bone ended up

	/**
	 * Where each bone of each posed character actually sits, in model pixels.
	 *
	 * <h2>Why this is taken rather than worked out</h2>
	 *
	 * The handles need a point to draw a ring around, and until now that point was
	 * the bone's <em>rest</em> position — read from the game's own baked model,
	 * which was honest as far as it went and wrong as soon as anything moved. A
	 * character built broader, or mid-stride, or with an arm already raised has its
	 * shoulder somewhere else, and the ring sat where the shoulder would have been
	 * on a default character standing still.
	 *
	 * Working out where it went instead would mean re-deriving the walk, the
	 * gesture and the build outside the renderer — three calculations kept in step
	 * with three others, which is the arrangement that always drifts. But the
	 * numbers already exist: by the end of {@code setupAnim} the model holds
	 * exactly where every part is, because that is what it just spent the frame
	 * deciding. So they are copied out at that moment and read by the handles.
	 *
	 * Only for characters a scene is playing. That is the set that can have handles
	 * on it, and it keeps this from being a map of every player on a server.
	 *
	 * <h2>By part rather than by entity</h2>
	 *
	 * Because by the time a model poses itself the entity is gone — it is handed a
	 * render state and nothing else. The part's name is what both ends have: the
	 * renderer put it there, and the handles know it because they know who is
	 * selected and what they are playing.
	 */
	private static final java.util.Map<String, float[]> WHERE =
		java.util.Collections.synchronizedMap(new java.util.HashMap<>());

	/**
	 * A bone as something a click can land on: where it is, and how big it is.
	 *
	 * <h2>Why both, and why taken rather than worked out</h2>
	 *
	 * Choosing a bone used to be possible only from a list in a panel, which is
	 * why posing a character was reported as hard: the thing you want to move is
	 * right there on the screen and the only way to say so was to read six names
	 * and guess which one an elbow is. Clicking the limb needs the limb's actual
	 * place in the world — after the walk, after the gesture, after the pose — and
	 * that is a number the model has already worked out by the time it is drawn.
	 *
	 * @param place the part's own transform, which carries it from its own space
	 *              into the character's, in blocks
	 * @param box   the part's own extent in that space: low corner then high
	 * @param axes  which way, in the character's own space, each of the bone's three
	 *              turns actually turns it — nine numbers, X then Y then Z
	 * @param bend  how far the limb is folded at its middle, in radians
	 */
	public record Shape(org.joml.Matrix4f place, float[] box, float[] axes, float bend) {

		/**
		 * The bone as a run of boxes down its length, folded the way it is drawn.
		 *
		 * <h2>Why one box will not do once a limb bends</h2>
		 *
		 * Because a folded limb is a curve, and the box round a curve is the box
		 * round a straight limb plus a wedge of air. An arm folded double had its
		 * outline drawn straight through where the forearm no longer was, and a
		 * click on the hand landed on nothing.
		 *
		 * So it is cut the same way the drawing cuts it: bands across the length,
		 * each carried through {@link com.mopicmp.npcstudio.client.emote.Folding}
		 * exactly as the vertices are. The fold only moves a point along and through
		 * the limb — never across it — so a band stays a box, and enough bands
		 * follow the curve as closely as anybody can click.
		 *
		 * A limb nobody folded comes back as one box, which is what it was.
		 */
		public java.util.List<float[]> bands(int count) {
			if (Math.abs(bend) < 1e-4f || count < 2) return java.util.List.of(box);

			float halfDepth = (box[5] - box[2]) / 2f;
			java.util.List<float[]> found = new java.util.ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				float from = box[1] + (box[4] - box[1]) * i / count;
				float to = box[1] + (box[4] - box[1]) * (i + 1) / count;

				float lowY = Float.MAX_VALUE;
				float highY = -Float.MAX_VALUE;
				float lowZ = Float.MAX_VALUE;
				float highZ = -Float.MAX_VALUE;
				for (float y : new float[] { from, to }) {
					for (float z : new float[] { box[2], box[5] }) {
						float[] moved = com.mopicmp.npcstudio.client.emote.Folding.fold(
							y, z, box[1], box[4], bend, halfDepth);
						lowY = Math.min(lowY, moved[0]);
						highY = Math.max(highY, moved[0]);
						lowZ = Math.min(lowZ, moved[1]);
						highZ = Math.max(highZ, moved[1]);
					}
				}
				found.add(new float[] { box[0], lowY, lowZ, box[3], highY, highZ });
			}
			return found;
		}
	}

	private static final java.util.Map<String, Shape[]> SHAPES =
		java.util.Collections.synchronizedMap(new java.util.HashMap<>());

	/** Copies out where the parts ended up. Called once per posed character per frame. */
	public static void remember(String role, PlayerModel model) {
		if (role == null || role.isEmpty()) return;
		float[] at = WHERE.computeIfAbsent(role, any -> new float[BONES.size() * 3]);
		Shape[] shapes = SHAPES.computeIfAbsent(role, any -> new Shape[BONES.size()]);
		for (int i = 0; i < BONES.size(); i++) {
			ModelPart part = partOf(model, BONES.get(i));
			if (part == null) continue;
			at[i * 3] = part.x;
			at[i * 3 + 1] = part.y;
			at[i * 3 + 2] = part.z;
			shapes[i] = shapeOf(model, part);
		}
	}

	/**
	 * One part's transform and extent, asked of the part itself.
	 *
	 * The transform is the part's own {@code translateAndRotate}, run into a stack
	 * of our own — the same call the renderer is about to make, so there is nothing
	 * here that can disagree with where the limb is drawn.
	 *
	 * The extent comes from the mixin that already measures every part, for the
	 * fold. It is the boxes the model was built from rather than the surface a
	 * built-up character ends up with, so a character widened a long way has a
	 * catch box a little narrower than it looks. That is the right way round: too
	 * small means a click near the edge falls through to whatever is behind, and
	 * too big means a limb catching clicks off its own shoulder.
	 */
	private static Shape shapeOf(PlayerModel model, ModelPart part) {
		var stack = new com.mojang.blaze3d.vertex.PoseStack();
		// The root first. A limb is a child of it, so the renderer applies both — and
		// leaving it out is right only while the root happens to be the identity,
		// which it is not when a character is crouching or swimming. A handle that is
		// correct while standing and wrong while crouching is worse than one that is
		// wrong twice, because it is believed.
		model.root().translateAndRotate(stack);
		org.joml.Matrix4f afterRoot = new org.joml.Matrix4f(stack.last().pose());
		part.translateAndRotate(stack);
		org.joml.Matrix4f place = new org.joml.Matrix4f(stack.last().pose());

		// Through Object, because a model part only implements this at runtime — the
		// mixin puts it there and the compiler has never heard of it.
		var bendable = (com.mopicmp.npcstudio.client.emote.BendablePart) (Object) part;
		var extent = bendable.npcStudio$extent();
		if (extent == null || extent.bottom() <= extent.top()) return null;
		return new Shape(place, new float[] {
			extent.middleX() - extent.halfX(), extent.top(), extent.middleZ() - extent.halfZ(),
			extent.middleX() + extent.halfX(), extent.bottom(), extent.middleZ() + extent.halfZ() },
			axesOf(afterRoot, part), bendable.npcStudio$bend());
	}

	/**
	 * Which way each of a bone's three turns actually turns it.
	 *
	 * <h2>Why this is not simply X, Y and Z</h2>
	 *
	 * Because a part applies its rotation as {@code Rz · Ry · Rx} — verified in
	 * {@code ModelPart.translateAndRotate}, which builds it with
	 * {@code Quaternionf.rotationZYX} — so the three numbers are not three
	 * independent axes. Changing {@code zRot} turns the bone about the parent's own
	 * Z. Changing {@code yRot} turns it about Y <em>after</em> the Z turn has
	 * already happened, so its real axis is {@code Rz · Y}. And {@code xRot}'s is
	 * {@code Rz · Ry · X}.
	 *
	 * Drawn on the character's raw axes instead — which is what they were — a ring
	 * matches what dragging it does only while the bone is at rest. Turn an arm
	 * once and the Y ring is drawn round one axis and turns the arm about another,
	 * which is what "rotating arms about Y is not at all what it should be" was:
	 * not a wrong sign or a wrong channel, a ring drawn in the wrong plane.
	 */
	private static float[] axesOf(org.joml.Matrix4f afterRoot, ModelPart part) {
		org.joml.Vector3f z = new org.joml.Matrix4f(afterRoot)
			.transformDirection(new org.joml.Vector3f(0, 0, 1));
		org.joml.Matrix4f afterZ = new org.joml.Matrix4f(afterRoot).rotateZ(part.zRot);
		org.joml.Vector3f y = new org.joml.Matrix4f(afterZ)
			.transformDirection(new org.joml.Vector3f(0, 1, 0));
		org.joml.Vector3f x = new org.joml.Matrix4f(afterZ).rotateY(part.yRot)
			.transformDirection(new org.joml.Vector3f(1, 0, 0));
		return new float[] { x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z };
	}

	/** How a bone of a posed character sits this frame, or null before it is drawn. */
	public static Shape shapeOf(String role, String bone) {
		Shape[] shapes = SHAPES.get(role);
		int i = BONES.indexOf(bone);
		return shapes == null || i < 0 ? null : shapes[i];
	}

	/**
	 * Where a bone was last drawn, or null if this character has not been drawn yet.
	 *
	 * Null is ordinary rather than a failure: on the first frame after a scene is
	 * opened nothing has been through the renderer, and the caller falls back on
	 * the rest pose — which is where the bone is about to be anyway.
	 */
	public static float[] placeOf(String role, String bone) {
		float[] at = WHERE.get(role);
		int i = BONES.indexOf(bone);
		if (at == null || i < 0) return null;
		return new float[] { at[i * 3], at[i * 3 + 1], at[i * 3 + 2] };
	}

	/** Dropped with the scene; a part nobody is playing has nowhere to have been. */
	public static void forget() {
		WHERE.clear();
		SHAPES.clear();
	}

	// ------------------------------------------------------------- where a bone rests

	/**
	 * The rest pose, baked once from the game's own definition.
	 *
	 * Once, because baking builds every box of the model and this is asked for a
	 * pivot. Held for the session: a model layer does not change while the game is
	 * running, and the one thing that would change it — a resource reload — rebuilds
	 * everything else too.
	 *
	 * Read rather than remembered, and that is the point. The humanoid model was
	 * reworked in this version, so numbers written down from memory would put a
	 * shoulder somewhere near the shoulder and be impossible to argue with
	 * afterwards.
	 */
	private static PlayerModel resting;

	public static PlayerModel resting() {
		if (resting != null) return resting;
		try {
			resting = new PlayerModel(net.minecraft.client.Minecraft.getInstance()
				.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER),
				false);
		} catch (RuntimeException notReady) {
			// Asked before the models are loaded, which happens on the first frame of
			// a world. Nothing is drawn this frame and the next one will have it.
			return null;
		}
		return resting;
	}

	/**
	 * Where a bone sits when nobody has moved it, in the model's own pixels.
	 *
	 * What a shift channel means by "unset". It has to be this rather than nought,
	 * because a shift <em>replaces</em> a part's offset — nought is the middle of
	 * the chest, and a slider starting there would fling every bone into it the
	 * moment it was touched.
	 */
	public static float[] restOf(String bone) {
		PlayerModel model = resting();
		if (model == null) return null;
		ModelPart part = partOf(model, bone);
		return part == null ? null : new float[] { part.x, part.y, part.z };
	}
}
