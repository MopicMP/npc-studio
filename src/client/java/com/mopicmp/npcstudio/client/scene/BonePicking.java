package com.mopicmp.npcstudio.client.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Choosing a bone by clicking the limb it belongs to.
 *
 * <h2>Why this had to exist</h2>
 *
 * Because until now the only way to say which bone you meant was a list of six
 * names in a panel. That is why posing was reported as hard: the arm you want to
 * lift is on the screen in front of you, and saying so meant reading
 * {@code rightArm}, {@code leftArm} and deciding which of them faces you. The
 * rings could turn a bone and could never choose one.
 *
 * <h2>The ray goes to the bone rather than the bone coming to the ray</h2>
 *
 * A limb is a box that has been turned — twice, in fact, once by the pose and
 * once by whichever way the character is facing — and the box round a turned box
 * is a poor answer, as a raised arm shows immediately: its enclosure is a slab of
 * mostly air reaching from the shoulder to wherever the hand got to.
 *
 * So nothing is enclosed. The two transforms are inverted and the <em>ray</em> is
 * carried into the bone's own space, where the bone is exactly the axis-aligned
 * box the model was built from. That is exact for any pose, costs one matrix
 * inversion per bone, and needs no case for an arm as opposed to a leg.
 *
 * <h2>Where the transforms come from</h2>
 *
 * Neither is worked out here. The part's own is copied out of the model at the
 * moment it finishes posing itself — see {@link Posing#remember} — so it is the
 * same number the renderer is about to draw with. The character's own is the
 * composition read out of {@code LivingEntityRenderer}, the same one the rings
 * are placed by: {@code world = position + rotY(180 - bodyRot) · (-x, 1.501 - y, z)}.
 */
public final class BonePicking {

	/** How far off the model a click may be and still count, in blocks. */
	private static final float GRACE = 0.06f;

	/**
	 * How many bands a folded limb is cut into.
	 *
	 * Enough to follow the curve at the size a character is clicked on, and few
	 * enough that a click costs six ray tests rather than sixty. A limb nobody
	 * folded is one band whatever this says.
	 */
	private static final int BANDS = 6;

	/** The character's own height offset, from {@code LivingEntityRenderer}. */
	private static final double STAND = 1.501;

	private BonePicking() { }

	/**
	 * Which bone of this character the ray meets first, or empty.
	 *
	 * @param who  the character, for where it stands and which way it faces
	 * @param role its name in the scene, which is what the shapes are filed under
	 */
	public static String at(Entity who, String role, Vec3 from, Vec3 to) {
		if (who == null || role == null || role.isEmpty()) return "";

		// Where the character is drawn, not where it stands. A scene moves the
		// picture and leaves the entity behind, so asking the entity put every catch
		// box at the old place — and none of them knew the character's size at all.
		Placed placed = Placed.drawn(who);
		Vec3 near = intoModel(placed, from);
		Vec3 far = intoModel(placed, to);

		String nearest = "";
		double best = Double.MAX_VALUE;
		for (String bone : Posing.BONES) {
			Posing.Shape shape = Posing.shapeOf(role, bone);
			if (shape == null) continue;

			Matrix4f back = new Matrix4f(shape.place()).invert();
			Vec3 a = through(back, near);
			Vec3 b = through(back, far);

			// Band by band, so that a folded limb is caught where it curves to rather
			// than where it would have been if it were straight.
			for (float[] box : shape.bands(BANDS)) {
				var hit = new AABB(box[0] - GRACE, box[1] - GRACE, box[2] - GRACE,
					box[3] + GRACE, box[4] + GRACE, box[5] + GRACE).clip(a, b);
				if (hit.isEmpty()) continue;

				// Measured along the ray rather than in the bone's own space, or a bone
				// whose space happens to be scaled would win by arithmetic.
				double away = a.distanceToSqr(hit.get()) / Math.max(1e-9, a.distanceToSqr(b));
				if (away >= best) continue;
				best = away;
				nearest = bone;
			}
		}
		return nearest;
	}

	/**
	 * The eight corners of a bone's box, in the world.
	 *
	 * For drawing an outline round whatever is about to be chosen. In the order a
	 * box's corners are usually walked: the low face first, then the high one, each
	 * going round rather than criss-cross, so that {@link #EDGES} can name them.
	 */
	public static java.util.List<Vec3[]> corners(Entity who, String role, String bone) {
		Posing.Shape shape = Posing.shapeOf(role, bone);
		if (who == null || shape == null) return java.util.List.of();
		Placed placed = Placed.drawn(who);

		java.util.List<Vec3[]> boxes = new java.util.ArrayList<>();
		for (float[] box : shape.bands(BANDS)) {
			Vec3[] found = new Vec3[8];
			int at = 0;
			for (int high = 0; high < 2; high++) {
				float y = high == 0 ? box[1] : box[4];
				float[][] round = {
					{ box[0], box[2] }, { box[3], box[2] }, { box[3], box[5] }, { box[0], box[5] } };
				for (float[] corner : round) {
					Vector3f point = shape.place().transformPosition(
						new Vector3f(corner[0], y, corner[1]));
					found[at++] = intoWorld(placed, new Vec3(point.x, point.y, point.z));
				}
			}
			boxes.add(found);
		}
		return boxes;
	}

	/** Which corners a box's twelve edges join. */
	public static final int[][] EDGES = {
		{ 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 },
		{ 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 },
		{ 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };

	// ------------------------------------------------------------------ the spaces

	public static Vec3 intoModel(Placed placed, Vec3 point) {
		return intoModel(placed.at(), placed.yaw(), placed.scale(), point);
	}

	public static Vec3 intoWorld(Placed placed, Vec3 point) {
		return intoWorld(placed.at(), placed.yaw(), placed.scale(), point);
	}

	/**
	 * A point of the world, in the character's own model space, in blocks.
	 *
	 * Taken apart from the entity so that it can be checked against the three
	 * heights it has to produce — the neck at 1.501, the shoulders at 1.376, the
	 * hips at 0.751 — without a world to stand a character in.
	 *
	 * <h2>Where the size comes in</h2>
	 *
	 * {@code LivingEntityRenderer} scales <em>after</em> lifting the model onto its
	 * feet and before turning it, so the whole offset from the character's own
	 * point is multiplied and nothing else is. That is one multiplication in each
	 * direction and it was simply missing: every handle placed a bone where it
	 * would have been on a character nobody had resized.
	 */
	public static Vec3 intoModel(Vec3 stands, float yaw, float scale, Vec3 point) {
		Vec3 away = point.subtract(stands);
		Vec3 turned = turned(away, -(180 - yaw)).scale(1.0 / scale);
		return new Vec3(-turned.x, STAND - turned.y, turned.z);
	}

	/** And back again, which is the composition the rings are placed by. */
	public static Vec3 intoWorld(Vec3 stands, float yaw, float scale, Vec3 point) {
		Vec3 lifted = new Vec3(-point.x, STAND - point.y, point.z).scale(scale);
		return stands.add(turned(lifted, 180 - yaw));
	}

	private static Vec3 through(Matrix4f matrix, Vec3 point) {
		Vector3f moved = matrix.transformPosition(
			new Vector3f((float) point.x, (float) point.y, (float) point.z));
		return new Vec3(moved.x, moved.y, moved.z);
	}

	private static Vec3 turned(Vec3 point, double degrees) {
		double angle = Math.toRadians(degrees);
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return new Vec3(point.x * cos + point.z * sin, point.y, -point.x * sin + point.z * cos);
	}

}
