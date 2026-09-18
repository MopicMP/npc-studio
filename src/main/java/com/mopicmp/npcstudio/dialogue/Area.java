package com.mopicmp.npcstudio.dialogue;

/**
 * A named box of the map, drawn by pointing at two opposite corners.
 *
 * <h2>One thing used two ways</h2>
 *
 * A stretch somebody walks into and a wall that seals a passage are the same
 * shape and are drawn with the same gesture, so they are the same thing here:
 * one is asked about — {@link Condition.Inside} — and the other is filled in —
 * {@link Effect.Wall}. "Wall" is not a kind of object; it is a box with
 * something put in it.
 *
 * That fell out of a decision about the walls rather than about the boxes. The
 * walls are drawn apart from the stretch that triggers them, so that a door three
 * blocks to the side can be sealed and so that one wall can be used where two
 * would be clumsy. But a wall drawn apart from its trigger has to be taken down
 * by a node that is not the node that put it up — so it needs a name, and once a
 * wall has a name there is no reason for the stretch not to have one too.
 *
 * <h2>Why the corners are the route's own points</h2>
 *
 * Because they meet the same fate. Builders build off to the side of the map and
 * paste the finished thing in, and every coordinate in it changes — which is the
 * reason a route is written as steps from where the character was placed, and it
 * is exactly as true of a corridor with a trap in it. So a corner is a
 * {@link Route.Point}: {@code go} four forward and two left, or {@code at} that
 * block of this world, and the two mix freely.
 *
 * A corner is a place and not an instruction, so it never waits: whatever
 * {@link Route.Point#stay()} a corner arrives carrying is dropped on the way in,
 * which is also why the file never has one to be puzzled by.
 *
 * <h2>What is not capped here</h2>
 *
 * The box. Asking whether somebody is inside one costs the same six comparisons
 * whether it is a doorway or a valley, and a trigger over a whole valley is a
 * perfectly reasonable thing to want. What has to be bounded is the <em>filling</em>
 * of one, which is a cost per block — so the ceiling belongs to the wall and is
 * stated there rather than being spent here, where it would forbid something that
 * costs nothing.
 */
public record Area(Route.Point first, Route.Point second, Route.From from) {

	/** A box nobody has drawn yet: the anchor's own square, which is honestly nowhere. */
	public static final Area NOWHERE = new Area(
		new Route.Point.Go(0, 0, 0), new Route.Point.Go(0, 0, 0), Route.From.CHARACTER);

	/** As long as a name may be. The same allowance a named place gets, for the same reason. */
	public static final int LONGEST = 24;

	/** As many boxes as one document may hold, which is the same bound its places get. */
	public static final int MOST = 256;

	public Area {
		first = first == null ? NOWHERE.first() : first.staying(0);
		second = second == null ? NOWHERE.second() : second.staying(0);
		from = from == null ? Route.From.CHARACTER : from;
	}

	/** The same box with one corner moved; the other stays where it was. */
	public Area withCorner(boolean which, Route.Point where) {
		return which ? new Area(first, where, from) : new Area(where, second, from);
	}

	public Area writtenFrom(Route.From now) {
		return new Area(first, second, now);
	}

	/** Whether either corner is a step, and therefore whether this needs an anchor at all. */
	public boolean needsAnchor() {
		return first instanceof Route.Point.Go || second instanceof Route.Point.Go;
	}

	/**
	 * The two corners as blocks of this world, smallest first.
	 *
	 * Sorted rather than taken as given, because the corners are two clicks and
	 * nobody clicks them in a fixed order. Everything downstream can then assume
	 * {@code low <= high} on all three axes, which is the assumption that a box
	 * test written the obvious way silently gets wrong for half the boxes anybody
	 * draws.
	 *
	 * @return {@code {{lowX, lowY, lowZ}, {highX, highY, highZ}}}, both inclusive
	 */
	public int[][] world(int anchorX, int anchorY, int anchorZ, Route.Facing facing) {
		int[] a = Route.world(first, anchorX, anchorY, anchorZ, facing);
		int[] b = Route.world(second, anchorX, anchorY, anchorZ, facing);
		return new int[][] {
			{ Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]) },
			{ Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]) } };
	}

	/**
	 * Whether a body standing at this position is in the box.
	 *
	 * Both corner blocks count, which is what anybody drawing two corners means:
	 * a box from a block to itself is one block and not none. So the far edge is
	 * the far side of the high corner rather than its near side — the difference
	 * is one block on each of three axes, and a trap one block short is a trap
	 * somebody walks through while watching it not fire.
	 *
	 * Told the anchor rather than asking for it, so that a test against a dozen
	 * boxes works it out once.
	 */
	public boolean holds(double x, double y, double z,
			int anchorX, int anchorY, int anchorZ, Route.Facing facing) {
		int[][] box = world(anchorX, anchorY, anchorZ, facing);
		return x >= box[0][0] && x < box[1][0] + 1
			&& y >= box[0][1] && y < box[1][1] + 1
			&& z >= box[0][2] && z < box[1][2] + 1;
	}

	/**
	 * How many blocks filling this would touch.
	 *
	 * A {@code long}, and that is not caution for its own sake. Three spans of a
	 * few thousand each overflow an {@code int} without any of them looking
	 * unreasonable, and the overflow lands as a negative count — which reads as
	 * "well under the limit" to every ceiling anybody would write against it. A
	 * box too big to fill would sail past the one check meant to stop it.
	 */
	public long cells(int anchorX, int anchorY, int anchorZ, Route.Facing facing) {
		int[][] box = world(anchorX, anchorY, anchorZ, facing);
		return (long) (box[1][0] - box[0][0] + 1)
			* (box[1][1] - box[0][1] + 1)
			* (box[1][2] - box[0][2] + 1);
	}

	/**
	 * Both corners rewritten in one form, over the same ground.
	 *
	 * The same requirement the route's own switch has and the same reason: a
	 * button that changes how a box is written and moves it at the same time is a
	 * button nobody would dare press twice.
	 */
	public Area allWrittenFrom(Route.From now, int anchorX, int anchorY, int anchorZ,
			Route.Facing facing) {
		return new Area(
			rewritten(first, now, anchorX, anchorY, anchorZ, facing),
			rewritten(second, now, anchorX, anchorY, anchorZ, facing),
			now);
	}

	private static Route.Point rewritten(Route.Point point, Route.From now,
			int anchorX, int anchorY, int anchorZ, Route.Facing facing) {
		int[] where = Route.world(point, anchorX, anchorY, anchorZ, facing);
		return switch (now) {
			case WORLD -> new Route.Point.At(where[0], where[1], where[2]);
			case CHARACTER -> Route.step(where[0], where[1], where[2],
				anchorX, anchorY, anchorZ, facing);
		};
	}

	/**
	 * The same box, that many blocks over.
	 *
	 * <h2>Why this exists rather than the boxes hanging off something that moves</h2>
	 *
	 * Because a scene gets built off to the side and pasted in, and everything in it
	 * has to arrive with it. The obvious answer is to write the boxes as steps from
	 * the character — and that answer was tried, and it is why a box could not be
	 * used by a place at all: a place has no character to hang off, so those steps
	 * were measured from the origin and the trap sat under the world spawn.
	 *
	 * So the boxes are plain coordinates and moving them is a thing somebody does on
	 * purpose, once, when they move the build. Nothing drifts on its own; the price
	 * is one deliberate act, and the act is two clicks.
	 *
	 * <h2>Why a corner written as a step is left alone</h2>
	 *
	 * It has already moved. A step is measured from the character, so a box written
	 * that way travelled with her when she was placed in the new build — shifting it
	 * as well would move it twice, and land it exactly as far past the target as the
	 * build was carried.
	 */
	public Area shifted(int byX, int byY, int byZ) {
		return new Area(moved(first, byX, byY, byZ), moved(second, byX, byY, byZ), from);
	}

	private static Route.Point moved(Route.Point corner, int byX, int byY, int byZ) {
		return corner instanceof Route.Point.At(int x, int y, int z, int stay)
			? new Route.Point.At(x + byX, y + byY, z + byZ, stay)
			: corner;
	}

	/**
	 * A name nothing else is using, tidied the way a named place is.
	 *
	 * One rule for both, because they are both names an author types into a small
	 * box and then reads back off a list.
	 */
	public static String tidy(String wanted) {
		if (wanted == null) return "";
		String trimmed = wanted.trim();
		return trimmed.length() <= LONGEST ? trimmed : trimmed.substring(0, LONGEST);
	}
}
