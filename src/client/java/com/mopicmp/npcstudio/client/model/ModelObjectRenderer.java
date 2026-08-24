package com.mopicmp.npcstudio.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mopicmp.npcstudio.entity.ModelObject;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mopicmp.npcstudio.model.Model;

/**
 * Draws a placed model.
 *
 * <h2>Why the document is fetched here and not held</h2>
 *
 * A renderer is made once and lives for the session; the model it is drawing
 * changes while somebody edits it. So it is asked for by name every frame. That
 * makes an edit visible on the next frame with nothing to invalidate by hand.
 *
 * The faces are worked out from the document each time rather than baked, which
 * is the price of every box wearing its own block: what would be baked is a
 * mesh per material, and the materials change as often as the boxes do.
 */
public class ModelObjectRenderer extends EntityRenderer<ModelObject, ModelObjectRenderer.State> {

	/** Sixteen model pixels to a block. */
	private static final float PIXEL = 1f / 16f;

	public static class State extends EntityRenderState {
		public String model = "";
		public float scale = 1f;
		public float yaw;

		/** The box being edited, outlined so it can be told from the ones around it. */
		public java.util.List<com.mopicmp.npcstudio.model.ModelPose.Solid> chosen =
			java.util.List.of();

		/** The solid parts as they really sit, for the outline. Empty when nothing is solid. */
		public java.util.List<com.mopicmp.npcstudio.model.ModelPose.Solid> collision =
			java.util.List.of();

		/**
		 * How far the scene has turned each bone past where it was drawn, in degrees.
		 *
		 * Empty for everything that is not in a scene, which is nearly everything —
		 * and empty is what takes the cached placement, so an object nobody is
		 * animating costs exactly what it always did.
		 */
		public java.util.Map<String, float[]> turns = java.util.Map.of();
	}

	public ModelObjectRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	/**
	 * How much room to keep the object drawn for.
	 *
	 * The entity's own box is one block, because that is what the type says, and
	 * what is drawn inside it can be twenty. The game stops drawing an entity as
	 * soon as its box leaves the frustum, so a large model vanished the moment its
	 * one-block heart went off the edge of the view — which from inside looks like
	 * everything disappearing at certain angles, and was reported as exactly that.
	 *
	 * Worked out from the model, and symmetric about the object horizontally so the
	 * answer does not have to be redone every time the thing is turned.
	 */
	@Override
	protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(ModelObject object) {
		Model model = ModelStore.get(object.model());
		if (model == null) return super.getBoundingBoxForCulling(object);

		float[] box = com.mopicmp.npcstudio.model.ModelPose.bounds(model);
		double scale = object.modelScale() / 16.0;
		double reach = Math.max(
			Math.max(Math.abs(box[0]), Math.abs(box[3])),
			Math.max(Math.abs(box[2]), Math.abs(box[5]))) * scale;

		// STANDS STILL: square about the object on purpose, so the answer does not
		// have to be worked out again every time it is turned. A turning object can
		// keep this — a moving one cannot, and wants the swept box instead.
		//
		// Never nothing. A model with no boxes gives every corner as zero, and a box
		// of no size is outside every frustum there is — so the thing stops being
		// drawn and, since nothing recomputes this for an object that never moves,
		// stays undrawn. A metre in each direction costs a frustum test.
		reach = Math.max(1, reach);
		double top = Math.max(box[4] * scale, box[1] * scale + 1);

		return new net.minecraft.world.phys.AABB(
			-reach, box[1] * scale, -reach,
			reach, top, reach).move(object.position());
	}

	@Override
	public void extractRenderState(ModelObject object, State state, float partial) {
		super.extractRenderState(object, state, partial);
		state.model = object.model();
		state.scale = object.modelScale();
		// Interpolated, because an object being dragged by a gizmo turns between
		// ticks and a rotation that steps twenty times a second reads as a stutter.
		state.yaw = net.minecraft.util.Mth.rotLerp(partial, object.yRotO, object.getYRot());

		// Where the scene has it, if it is in one. An object is a participant in
		// the same sense a character is — the ship's wheel has to turn on the same
		// clock as the hands on it — and the facing and the size live in this
		// renderer's own fields rather than in the ones a living thing keeps, so
		// they are written here.
		var staged = com.mopicmp.npcstudio.client.scene.Staging.place(object, state, partial);
		state.turns = java.util.Map.of();
		if (staged != null) {
			state.yaw = staged.value(com.mopicmp.npcstudio.scene.Channels.YAW, state.yaw);
			state.scale = staged.value(com.mopicmp.npcstudio.scene.Channels.SCALE, state.scale);
			state.turns = com.mopicmp.npcstudio.client.scene.Staging.turnsOf(
				staged, ModelStore.get(object.model()));
		}

		// The shape that is solid, drawn only while it is solid. Nothing to walk into
		// means nothing to draw: an outline round a thing that does not stop you is a
		// drawing of a rule that is not in force, and reading it as one is how three
		// blocks of clear air came to look like a place somebody was stuck in.
		Model theirs = ModelStore.get(object.model());
		state.collision = ModellingScreen.showing() && object.solid() && theirs != null
			? com.mopicmp.npcstudio.model.ModelPose.solids(theirs, object.getYRot())
			: java.util.List.of();

		// What is selected, lit up. The handles say where the middle of it is and
		// nothing says how big it is or which of forty planks it happens to be — and
		// on a model made of forty planks that is the only question being asked.
		//
		// One outline round the lot when several are picked, not one each. Two hundred
		// outlines round the two hundred bricks of a cone is a green haze that says
		// nothing about the cone; what somebody wants to see is where the thing they
		// have hold of begins and ends. It is also two hundred times less to draw.
		state.chosen = java.util.List.of();
		if (object == Gizmo.subject() && theirs != null && ModellingScreen.showing()) {
			var all = com.mopicmp.npcstudio.model.ModelPose.solids(theirs, object.getYRot());
			var placed = com.mopicmp.npcstudio.model.ModelPose.place(theirs);
			java.util.List<com.mopicmp.npcstudio.model.ModelPose.Solid> marked =
				new java.util.ArrayList<>();

			// The order the shapes come back in is the order the boxes are placed in,
			// which is the order the tree walks — so a place in the document is a place
			// in this list.
			for (int at = 0; at < placed.size() && at < all.size(); at++) {
				if (Modelling.isSelected(placed.get(at).bone(), placed.get(at).index())) {
					marked.add(all.get(at));
				}
			}
			state.chosen = marked.size() > 1 ? java.util.List.of(around(marked)) : marked;
		}
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera) {
		Model model = ModelStore.get(state.model);
		if (model == null) {
			complain("has no model called " + state.model + " on this client");
			return;
		}
		if (model.cubeCount() == 0) {
			complain("the model " + state.model + " has no boxes in it");
			return;
		}

		// Before the turn, because the shapes already carry it — they were worked out
		// in the same call the collision uses. Scaled here, in one number, rather than
		// by entering the model's frame: the outline is measured in model pixels and
		// the object may be drawn at any size.
		pose.pushPose();
		Arrows.outline(pose, collector, state.collision, state.scale * PIXEL, 0xAA66DD88);
		Arrows.outline(pose, collector, state.chosen, state.scale * PIXEL, 0xFF4FC3F7);
		pose.popPose();

		pose.pushPose();
		pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-state.yaw));
		pose.scale(state.scale, state.scale, state.scale);

		// Model space is sixteen units to a block, and its Y counts upwards from
		// the foot of the model — the same frame Blockbench shows, so a box drawn
		// resting on the floor there rests on the ground here.
		pose.scale(PIXEL, PIXEL, PIXEL);

		ModelFaces.submit(model, pose, collector, state.lightCoords, state.turns);

		pose.popPose();
	}

	/**
	 * One upright shape holding all of them.
	 *
	 * Upright, and that is a loss worth naming: a group of turned boxes gets a
	 * straight box round it rather than a turned one. There is no turned box that
	 * holds a set of differently turned boxes, so the choice is between a straight
	 * one and no answer at all.
	 */
	private static com.mopicmp.npcstudio.model.ModelPose.Solid around(
			java.util.List<com.mopicmp.npcstudio.model.ModelPose.Solid> solids) {
		float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
			-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
		for (var solid : solids) {
			float[] one = com.mopicmp.npcstudio.model.ModelPose.around(solid);
			for (int axis = 0; axis < 3; axis++) {
				box[axis] = Math.min(box[axis], one[axis]);
				box[axis + 3] = Math.max(box[axis + 3], one[axis + 3]);
			}
		}
		return new com.mopicmp.npcstudio.model.ModelPose.Solid(
			new float[] { (box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2 },
			new float[][] { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } },
			new float[] { (box[3] - box[0]) / 2, (box[4] - box[1]) / 2, (box[5] - box[2]) / 2 });
	}

	/** What was said last, so a reason is logged once rather than sixty times a second. */
	private String said = "";

	private void complain(String why) {
		if (why.equals(said)) return;
		said = why;
		com.mopicmp.npcstudio.NpcStudio.LOGGER.warn("A placed object draws nothing: {}", why);
	}
}
