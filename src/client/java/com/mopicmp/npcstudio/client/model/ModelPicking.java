package com.mopicmp.npcstudio.client.model;

import com.mopicmp.npcstudio.entity.ModelObject;
import com.mopicmp.npcstudio.model.Cube;
import com.mopicmp.npcstudio.model.Model;
import com.mopicmp.npcstudio.model.ModelPose;

import net.minecraft.world.phys.Vec3;

/**
 * What a click in the world lands on, box by box.
 *
 * <h2>Why not the entity's own box</h2>
 *
 * Because it is the wrong shape. A placed object is one entity whatever is inside
 * it, and the game gives that entity a bounding box out of its type — a fixed
 * size that has nothing to do with the model. A hull thirty pixels long lives
 * inside a box half a block wide, so clicking the hull misses and clicking empty
 * air next to its middle hits. That is the whole of "I can only choose a model
 * from the list": choosing it in the world was aiming at something invisible and
 * in the wrong place.
 *
 * So the ray is taken into the model's own space and tried against the boxes
 * themselves. It costs a handful of arithmetic per box and it answers the
 * question that was actually asked: which box is under the mouse.
 *
 * <h2>Turned boxes</h2>
 *
 * A box with a rotation is tried against the smallest upright box that contains
 * it. That is generous — a click in the corner of a box turned forty-five degrees
 * counts as a hit when strictly it is not — and generous is the right way to be
 * wrong here: a handle that is a little easier to hit than it looks costs a
 * misplaced selection, and one that is harder costs the belief that clicking
 * works at all.
 */
public final class ModelPicking {

	/** What the ray found: which object, which box in it, and how far along. */
	public record Hit(ModelObject object, String bone, int index, double away) { }

	private ModelPicking() { }

	/**
	 * The nearest box any placed object has under the given ray.
	 *
	 * The ray is the one the viewport casts, from the camera to as far as a click is
	 * allowed to mean, and the distance that comes back is a fraction of it — which
	 * is what makes objects at different scales comparable without converting
	 * anything back.
	 */
	public static Hit pick(Vec3 from, Vec3 to) {
		Hit best = null;
		for (ModelObject object : Modelling.placed()) {
			Model model = ModelStore.get(object.model());
			if (model == null) continue;

			Vec3 start = toModel(object, from);
			Vec3 end = toModel(object, to);
			Vec3 along = end.subtract(start);

			for (ModelPose.Placed placed : ModelPose.place(model)) {
				double[] box = bounds(placed);
				double away = through(start, along, box);
				if (away < 0) continue;
				if (best == null || away < best.away()) {
					best = new Hit(object, placed.bone(), placed.index(), away);
				}
			}
		}
		return best;
	}

	/**
	 * A point of the world in the model's own units: pixels, upright, unturned.
	 *
	 * The inverse of what the renderer does on the way out. Written out rather than
	 * inverted from a matrix because it is a scale and one rotation about the
	 * vertical, and the inverse of that is the same thing with two signs flipped.
	 */
	private static Vec3 toModel(ModelObject object, Vec3 point) {
		Vec3 local = point.subtract(object.position()).scale(16.0 / Math.max(0.01f, object.modelScale()));
		double yaw = Math.toRadians(object.getYRot());
		double cos = Math.cos(yaw);
		double sin = Math.sin(yaw);
		return new Vec3(local.x * cos + local.z * sin, local.y, -local.x * sin + local.z * cos);
	}

	/** The smallest upright box holding a placed box, in model pixels. */
	private static double[] bounds(ModelPose.Placed placed) {
		Cube cube = placed.cube();
		float grow = cube.inflate();
		float x0 = cube.fromX() - grow;
		float y0 = cube.fromY() - grow;
		float z0 = cube.fromZ() - grow;
		float x1 = cube.toX() + grow;
		float y1 = cube.toY() + grow;
		float z1 = cube.toZ() + grow;

		double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
			-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (int corner = 0; corner < 8; corner++) {
			float[] at = ModelPose.apply(placed.matrix(),
				(corner & 1) == 0 ? x0 : x1,
				(corner & 2) == 0 ? y0 : y1,
				(corner & 4) == 0 ? z0 : z1);
			for (int axis = 0; axis < 3; axis++) {
				box[axis] = Math.min(box[axis], at[axis]);
				box[axis + 3] = Math.max(box[axis + 3], at[axis]);
			}
		}
		return box;
	}

	/**
	 * How far along a ray a box is entered, or -1 when it is not.
	 *
	 * The slab method: each pair of parallel faces cuts the ray down to the stretch
	 * between them, and what survives all three pairs is the stretch inside the box.
	 * A direction of zero along an axis is the case worth naming — the ray runs
	 * parallel to that pair of faces, so it either misses forever or is unaffected,
	 * and dividing would answer with an infinity that happens to work out.
	 */
	private static double through(Vec3 from, Vec3 along, double[] box) {
		double enters = 0;
		double leaves = 1;

		double[] start = { from.x, from.y, from.z };
		double[] step = { along.x, along.y, along.z };
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(step[axis]) < 1e-9) {
				if (start[axis] < box[axis] || start[axis] > box[axis + 3]) return -1;
				continue;
			}
			double one = (box[axis] - start[axis]) / step[axis];
			double two = (box[axis + 3] - start[axis]) / step[axis];
			enters = Math.max(enters, Math.min(one, two));
			leaves = Math.min(leaves, Math.max(one, two));
			if (enters > leaves) return -1;
		}
		return enters;
	}
}
