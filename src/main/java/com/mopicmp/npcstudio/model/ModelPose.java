package com.mopicmp.npcstudio.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Where every box of a model ends up.
 *
 * <h2>Why this is worked out here rather than by the renderer</h2>
 *
 * Because it is arithmetic, and arithmetic can be checked. Composing a bone's
 * rotation with its parent's and then with a box's own is four lines that are
 * wrong in a way nobody sees — a mast a degree off looks like a mast — and the
 * only cheap moment to find that out is in a test.
 *
 * The renderer takes the answers and draws them. It has enough to be getting on
 * with.
 *
 * <h2>The order</h2>
 *
 * A bone is placed at its pivot, turned, and then its contents are written
 * relative to that pivot. A box inside is placed at its own pivot, turned, and
 * its corners written relative to that. Same rule twice, which is why one
 * method does both.
 */
public final class ModelPose {

	/**
	 * A box, the frame it sits in — sixteen numbers of a matrix, row major — and
	 * where in the document it came from.
	 *
	 * The name and the index are carried because the list is flat and something has
	 * to be able to get back. Clicking a box in the world means finding the nearest
	 * one a ray goes through and then selecting it in the tree, and a box with no
	 * return address can be drawn but never chosen.
	 */
	public record Placed(Cube cube, float[] matrix, String bone, int index) { }

	private ModelPose() { }

	/**
	 * Every box of the model, each with the transform that puts it in place.
	 *
	 * Flat rather than nested, because the thing that draws them wants a list and
	 * the thing that sorts them by material wants a list. The tree has already
	 * done its job by the time this returns.
	 */
	/**
	 * What has already been worked out, keyed by the document it was worked out from.
	 *
	 * <h2>Why a cache is safe here, and why it was needed</h2>
	 *
	 * Safe because a model is immutable: an edit makes a new one, so the key going
	 * away is exactly the answer going stale. There is nothing to invalidate by
	 * hand, which is the usual reason caches rot.
	 *
	 * Needed because this is walked far more often than anybody would guess from
	 * reading any one caller. Drawing walks it, the outline walks it, the handles
	 * walk it to find the frame of the selected box — three times a frame between
	 * them — the culling box walks it, and the collision walks it every tick. On a
	 * cone of two hundred boxes that is a couple of thousand matrix chains a frame
	 * to answer a question whose answer did not change.
	 *
	 * Weak on the key so a model nobody holds any more takes its answers with it.
	 * Synchronised because a server thread and a render thread ask about different
	 * models at the same time.
	 */
	private static final java.util.Map<Model, List<Placed>> PLACED =
		java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private static final java.util.Map<Model, java.util.Map<Float, List<Solid>>> SOLIDS =
		java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	public static List<Placed> place(Model model) {
		List<Placed> known = PLACED.get(model);
		if (known != null) return known;

		List<Placed> found = new ArrayList<>();
		for (Bone bone : model.childrenOf("")) walk(model, bone, identity(), found, java.util.Map.of());
		found = List.copyOf(found);
		PLACED.put(model, found);
		return found;
	}

	/**
	 * The same, with some bones turned further than the document turns them.
	 *
	 * <h2>What the numbers mean</h2>
	 *
	 * Degrees added to the bone's own rotation, so nought is the model exactly as
	 * it was drawn. That is the same rule the character bones follow — a channel
	 * says how far a bone is turned from where it rests — and the arithmetic
	 * differs only because a character's parts rest at nought while a model's rest
	 * wherever somebody drew them. Replacing instead would mean a scene that turns
	 * a wheel also throws away whatever tilt the wheel was drawn with.
	 *
	 * <h2>Why this one is not cached</h2>
	 *
	 * Because the key would be the pose, and the pose is different every frame —
	 * a cache whose every lookup misses is a memory leak with extra steps. The
	 * cost is one walk of the tree per posed object per frame, which is what the
	 * cache was there to avoid being done five times over for <em>every</em>
	 * object; here it is done once for the few that are actually in a scene.
	 */
	public static List<Placed> place(Model model, java.util.Map<String, float[]> turns) {
		if (turns == null || turns.isEmpty()) return place(model);

		List<Placed> found = new ArrayList<>();
		for (Bone bone : model.childrenOf("")) walk(model, bone, identity(), found, turns);
		return List.copyOf(found);
	}

	private static void walk(Model model, Bone bone, float[] parent, List<Placed> into,
			java.util.Map<String, float[]> turns) {
		float[] extra = turns.get(bone.name());
		float[] mine = times(parent, frame(bone.pivotX(), bone.pivotY(), bone.pivotZ(),
			bone.rotX() + (extra == null ? 0 : extra[0]),
			bone.rotY() + (extra == null ? 0 : extra[1]),
			bone.rotZ() + (extra == null ? 0 : extra[2])));

		for (int index = 0; index < bone.cubes().size(); index++) {
			Cube cube = bone.cubes().get(index);
			// The box turns about its own pivot, inside the bone's frame. A box with
			// no rotation still gets the frame, which is what makes it move when the
			// bone it belongs to does.
			float[] at = cube.straight() ? mine
				: times(mine, frame(cube.pivotX() - bone.pivotX(), cube.pivotY() - bone.pivotY(),
					cube.pivotZ() - bone.pivotZ(), cube.rotX(), cube.rotY(), cube.rotZ()));
			into.add(new Placed(cube, at, bone.name(), index));
		}
		for (Bone child : model.childrenOf(bone.name())) walk(model, child, mine, into, turns);
	}

	/**
	 * Move to a point, turn there, and come back.
	 *
	 * The coming back is the part that is easy to leave out and impossible to see
	 * afterwards: without it every rotation also drags whatever it turns towards
	 * the origin, which reads as a model that falls apart the moment anything is
	 * angled.
	 */
	private static float[] frame(float px, float py, float pz, float rx, float ry, float rz) {
		float[] out = translate(px, py, pz);
		out = times(out, rotateZ(rz));
		out = times(out, rotateY(ry));
		out = times(out, rotateX(rx));
		return times(out, translate(-px, -py, -pz));
	}

	/**
	 * The smallest upright box holding the whole model, in model pixels.
	 *
	 * Six numbers: the low corner then the high one. Empty models come back as all
	 * zeroes rather than as an inside-out box, because the two callers — how far a
	 * thing reaches for culling, and how big a thing is for walking into — both
	 * want "nothing" and neither wants to check.
	 *
	 * Every corner of every box is put through its frame, so a turned box counts
	 * for the room it actually takes rather than the room it would take unturned.
	 */
	public static float[] bounds(Model model) {
		return bounds(model, 0);
	}

	/**
	 * The same, with the whole model turned about the vertical first.
	 *
	 * The turn belongs in here rather than being applied to the answer, and the
	 * difference is not small. Enclosing the turned boxes gives the box the model
	 * actually needs; enclosing them first and turning the result afterwards
	 * encloses twice, and two enclosures at an angle to each other can be half as
	 * big again as the thing inside them. That is the collision reaching well past
	 * the model in width and length.
	 */
	public static float[] bounds(Model model, float yawDegrees) {
		float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
			-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
		boolean any = false;

		// From the shapes, not from the staircase they are approximated by. How much
		// room a model takes is a question about the shapes; going through the pieces
		// gives the same answer and cuts every turned box into sixty-four of them
		// first. That was being done every frame, for the culling box alone.
		for (Solid solid : solids(model, yawDegrees)) {
			float[] one = around(solid);
			for (int axis = 0; axis < 3; axis++) {
				box[axis] = Math.min(box[axis], one[axis]);
				box[axis + 3] = Math.max(box[axis + 3], one[axis + 3]);
			}
			any = true;
		}
		return any ? box : new float[6];
	}

	/** The upright box round a whole shape. */
	public static float[] around(Solid solid) {
		float[] reach = new float[3];
		for (int i = 0; i < 3; i++) {
			for (int axis = 0; axis < 3; axis++) {
				reach[i] += Math.abs(solid.axes()[axis][i]) * solid.half()[axis];
			}
		}
		return new float[] {
			solid.middle()[0] - reach[0], solid.middle()[1] - reach[1], solid.middle()[2] - reach[2],
			solid.middle()[0] + reach[0], solid.middle()[1] + reach[1], solid.middle()[2] + reach[2] };
	}

	/**
	 * One upright box per box of the model, turned about the vertical, in model pixels.
	 *
	 * A list rather than a single answer because one box for a whole model is a
	 * lie about the shape of it — a hull and its mast share a box that is mostly
	 * the air between them. Whoever wants the single answer can take the union,
	 * which is what {@link #bounds} does; whoever wants to know where the solid
	 * parts are gets to know.
	 */
	/**
	 * How finely a turned box is cut up, and how many pieces it may become.
	 *
	 * A piece about two pixels across is small enough that the upright box round it
	 * is close to the piece itself even at the worst angle. Sixty-four is what stops
	 * a large turned box from becoming five hundred of them.
	 */
	private static final float PIECE = 2;
	private static final int MOST_PIECES = 64;

	/**
	 * A box as it really sits: a middle, three unit directions, and a half size along each.
	 *
	 * <h2>Why this exists as a thing of its own</h2>
	 *
	 * Because it is the shape, and the list of upright boxes below it is only an
	 * approximation of the shape. The game's collision cannot take a turned box, so
	 * for now every caller wants the approximation — but the day the collision is
	 * ours rather than the game's, what it will want is exactly this: a centre,
	 * an orientation and three extents, which is what a separating-axis test is
	 * written against.
	 *
	 * So the turning happens once, here, and the cutting-up is a step that comes
	 * after it. Fold the two together and the real shape is never available; that is
	 * the door that would be quietly closed.
	 */
	public record Solid(float[] middle, float[][] axes, float[] half) { }

	/**
	 * Every box of the model as it really sits, in model pixels, with the object's
	 * turn worked in.
	 */
	public static List<Solid> solids(Model model, float yawDegrees) {
		java.util.Map<Float, List<Solid>> byYaw =
			SOLIDS.computeIfAbsent(model, any -> new java.util.concurrent.ConcurrentHashMap<>());
		List<Solid> known = byYaw.get(yawDegrees);
		if (known != null) return known;

		double yaw = Math.toRadians(yawDegrees);
		double cos = Math.cos(yaw);
		double sin = Math.sin(yaw);

		List<Solid> found = new ArrayList<>();
		for (Placed placed : place(model)) {
			Cube cube = placed.cube();
			float grow = cube.inflate();
			float[] middle = turn(apply(placed.matrix(),
				(cube.fromX() + cube.toX()) / 2,
				(cube.fromY() + cube.toY()) / 2,
				(cube.fromZ() + cube.toZ()) / 2), cos, sin);

			float[] origin = turn(apply(placed.matrix(), 0, 0, 0), cos, sin);
			float[][] axes = new float[3][];
			for (int axis = 0; axis < 3; axis++) {
				float[] end = turn(apply(placed.matrix(), axis == 0 ? 1 : 0, axis == 1 ? 1 : 0,
					axis == 2 ? 1 : 0), cos, sin);
				float x = end[0] - origin[0];
				float y = end[1] - origin[1];
				float z = end[2] - origin[2];
				float span = (float) Math.sqrt(x * x + y * y + z * z);
				if (span < 1e-6f) span = 1;
				axes[axis] = new float[] { x / span, y / span, z / span };
			}

			found.add(new Solid(middle, axes, new float[] {
				cube.sizeX() / 2 + grow, cube.sizeY() / 2 + grow, cube.sizeZ() / 2 + grow }));
		}
		found = List.copyOf(found);
		byYaw.put(yawDegrees, found);
		return found;
	}

	public static List<float[]> boxes(Model model, float yawDegrees) {
		List<float[]> found = new ArrayList<>();
		for (Solid solid : solids(model, yawDegrees)) cutUp(solid, found);
		return found;
	}

	/**
	 * A box turned into the upright boxes that stand in for it.
	 *
	 * One, when it ended up square to the world: then the upright box round it
	 * <em>is</em> it, exactly, and cutting it up would be work for nothing.
	 *
	 * Otherwise a staircase. This is the honest form of "make the collision follow
	 * the box when it turns": the game has no turned collision box and cannot be
	 * given one, so a turned box is approached by small upright ones. Coarse, that
	 * is the block round a plank — a lot of solid air, which is what shoves people
	 * about near a model. Fine, it is the plank.
	 */
	private static void cutUp(Solid solid, List<float[]> into) {
		int[] cuts = cutsFor(solid);
		for (int ix = 0; ix < cuts[0]; ix++) {
			for (int iy = 0; iy < cuts[1]; iy++) {
				for (int iz = 0; iz < cuts[2]; iz++) {
					into.add(piece(solid, cuts, ix, iy, iz));
				}
			}
		}
	}

	private static int[] cutsFor(Solid solid) {
		if (square(solid)) return new int[] { 1, 1, 1 };

		int[] cuts = new int[3];
		for (int axis = 0; axis < 3; axis++) {
			cuts[axis] = Math.max(1, Math.round(solid.half()[axis] * 2 / PIECE));
		}

		// Trimmed from the longest side down, so a plank keeps its length in slices
		// rather than losing it to a dimension two pixels thick.
		while (cuts[0] * cuts[1] * cuts[2] > MOST_PIECES) {
			int longest = cuts[0] >= cuts[1] && cuts[0] >= cuts[2] ? 0 : cuts[1] >= cuts[2] ? 1 : 2;
			cuts[longest] = Math.max(1, cuts[longest] - 1);
		}
		return cuts;
	}

	/** Whether the box's own sides point along the world's. */
	private static boolean square(Solid solid) {
		for (float[] axis : solid.axes()) {
			float most = 0;
			for (float part : axis) most = Math.max(most, Math.abs(part));
			if (most < 0.9999f) return false;
		}
		return true;
	}

	/**
	 * The upright box round one piece of a turned box.
	 *
	 * The reach along each world direction is the sum of how far each of the box's
	 * own directions goes that way, times how big it is that way — the standard way
	 * to put an upright box round a turned one, and cheaper and steadier than
	 * putting eight corners through the arithmetic and taking the extremes.
	 */
	private static float[] piece(Solid solid, int[] cuts, int ix, int iy, int iz) {
		float[] half = new float[3];
		float[] middle = solid.middle().clone();
		int[] step = { ix, iy, iz };

		for (int axis = 0; axis < 3; axis++) {
			half[axis] = solid.half()[axis] / cuts[axis];
			// From the near face, half a piece in, then a piece per step.
			float along = -solid.half()[axis] + half[axis] * (2 * step[axis] + 1);
			for (int i = 0; i < 3; i++) middle[i] += solid.axes()[axis][i] * along;
		}

		float[] reach = new float[3];
		for (int i = 0; i < 3; i++) {
			for (int axis = 0; axis < 3; axis++) {
				reach[i] += Math.abs(solid.axes()[axis][i]) * half[axis];
			}
		}

		return new float[] {
			middle[0] - reach[0], middle[1] - reach[1], middle[2] - reach[2],
			middle[0] + reach[0], middle[1] + reach[1], middle[2] + reach[2] };
	}

	/** The object's own turn, applied to a point rather than to a box it ended up in. */
	private static float[] turn(float[] at, double cos, double sin) {
		return new float[] {
			(float) (at[0] * cos - at[2] * sin), at[1], (float) (at[0] * sin + at[2] * cos) };
	}

	/** Where a point of the model lands, once its box's frame is applied. */
	public static float[] apply(float[] matrix, float x, float y, float z) {
		return new float[] {
			matrix[0] * x + matrix[1] * y + matrix[2] * z + matrix[3],
			matrix[4] * x + matrix[5] * y + matrix[6] * z + matrix[7],
			matrix[8] * x + matrix[9] * y + matrix[10] * z + matrix[11]
		};
	}

	// ------------------------------------------------------------ the matrices

	public static float[] identity() {
		return new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
	}

	private static float[] translate(float x, float y, float z) {
		return new float[] { 1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1 };
	}

	private static float[] rotateX(float degrees) {
		float c = (float) Math.cos(Math.toRadians(degrees));
		float s = (float) Math.sin(Math.toRadians(degrees));
		return new float[] { 1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1 };
	}

	private static float[] rotateY(float degrees) {
		float c = (float) Math.cos(Math.toRadians(degrees));
		float s = (float) Math.sin(Math.toRadians(degrees));
		return new float[] { c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1 };
	}

	private static float[] rotateZ(float degrees) {
		float c = (float) Math.cos(Math.toRadians(degrees));
		float s = (float) Math.sin(Math.toRadians(degrees));
		return new float[] { c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
	}

	private static float[] times(float[] a, float[] b) {
		float[] out = new float[16];
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				float sum = 0;
				for (int k = 0; k < 4; k++) sum += a[row * 4 + k] * b[k * 4 + column];
				out[row * 4 + column] = sum;
			}
		}
		return out;
	}
}
