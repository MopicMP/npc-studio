package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * A path somebody drew on the map, and the manner of walking it.
 *
 * <h2>Why a step is the ordinary form and a coordinate is the reserve</h2>
 *
 * This was the other way round for a day, and the reason it turned over is worth
 * keeping. Builders build off to the side of the map — a whole keep, a village
 * square — and paste it in when it is finished. Every coordinate in it changes,
 * so a route written in coordinates has to be drawn again from nothing. Reported
 * exactly that way, and it is not a rare case: it is how large maps are made.
 *
 * A step — four forward, two left — survives that, because what an author means
 * by a patrol is never a set of coordinates. It is a shape with a relationship to
 * a doorway, and the doorway moves with the building.
 *
 * So a point is one of two things and the file says which:
 *
 * <pre>
 * {"go": [4, 0, 2]}      four forward, level, two to the left of the anchor
 * {"at": [214, 71, -88]} that block of this world, wherever the anchor is
 * </pre>
 *
 * Coordinates stay because sometimes a route really is about one piece of ground
 * that will never move, and because a route through terrain nobody built has no
 * anchor worth speaking of.
 *
 * <h2>What "forward" means, and why it never turns</h2>
 *
 * Forward is the way the anchor faces, snapped to one of four; left is a quarter
 * turn from it. Every step is measured in that one frame, so a step never
 * changes what "forward" means for the steps after it.
 *
 * The alternative was a turtle — each step relative to the way you now face —
 * which is more expressive and much worse to author. A mistake in the second of
 * twelve steps rotates the whole rest of the round, and the way to find it is to
 * walk all of it. Fixed axes make a mistake in one step a mistake in one step.
 *
 * <h2>Why the whole route is one value rather than three fields on the node</h2>
 *
 * Because the same thing is handed to the character when the walk is ordered, and
 * a route split across three arguments is three chances for the order to disagree
 * with the node it came from.
 */
public record Route(List<Point> points, Gait gait, float pace, From from) {

	/**
	 * One place along the way, and how long to stand there.
	 *
	 * <h2>Why a block and not a precise point</h2>
	 *
	 * The same answer {@link com.mopicmp.npcstudio.map.Spot} gives, for the same
	 * reason: whoever placed this clicked on a floor, and a point stored to six
	 * decimals would claim they chose those decimals.
	 *
	 * <h2>Why {@code stay} exists before anything shows it</h2>
	 *
	 * Because adding a field to a shape that is already in somebody's saved map is
	 * a migration, and adding one to a shape nobody has saved yet is a line. A
	 * patrol that pauses at each window is the second thing anybody will want from
	 * this, and the field costs nothing while it reads nought.
	 */
	public sealed interface Point {

		/** How many ticks to stand here before going on; nought walks straight through. */
		int stay();

		/** The same point, standing there for this long. */
		Point staying(int ticks);

		/**
		 * A place in the world, whoever is walking to it.
		 *
		 * The form a route had before steps existed, kept because sometimes a path
		 * really is about one piece of ground — and because terrain nobody built has
		 * no anchor that means anything.
		 */
		record At(int x, int y, int z, int stay) implements Point {

			public At(int x, int y, int z) {
				this(x, y, z, 0);
			}

			public At {
				stay = Math.max(0, stay);
			}

			@Override public Point staying(int ticks) {
				return new At(x, y, z, ticks);
			}
		}

		/**
		 * A step from the anchor, in the anchor's own frame.
		 *
		 * @param forward blocks along the way the anchor faces; negative is back
		 * @param up      blocks above it; negative is down
		 * @param left    blocks to the anchor's left; negative is right
		 */
		record Go(int forward, int up, int left, int stay) implements Point {

			public Go(int forward, int up, int left) {
				this(forward, up, left, 0);
			}

			public Go {
				stay = Math.max(0, stay);
			}

			@Override public Point staying(int ticks) {
				return new Go(forward, up, left, ticks);
			}
		}
	}

	/**
	 * What a fresh point is written as.
	 *
	 * A property of the route rather than of each point, because it is a decision
	 * about the route: either this path belongs to the character and travels with
	 * her, or it belongs to one piece of ground. Points already placed keep the
	 * form they were placed in — the two mix perfectly well, and a route with one
	 * fixed point and the rest hanging off the anchor is a reasonable thing to
	 * write.
	 */
	public enum From {
		/** From where the character was placed, in the direction she was placed facing. */
		CHARACTER,
		/** Coordinates of this world, which is what a point used to be and always can be. */
		WORLD
	}

	/**
	 * Which way the anchor faces, to a quarter turn.
	 *
	 * <h2>Why four and not three hundred and sixty</h2>
	 *
	 * Because "four forward" has to mean four blocks, and it only does if forward
	 * is along an axis. An anchor facing thirty-seven degrees would make every step
	 * a diagonal of some length nobody chose, and the numbers in the panel would
	 * stop describing what anybody could count on the ground.
	 *
	 * It also makes the frame stable. Nudging a character while building turns her
	 * a few degrees; snapping means the route only moves when she is turned enough
	 * to have meant it.
	 */
	public enum Facing {
		// The game's own yaw: nought faces south, and it goes clockwise from there.
		// Left is a quarter turn anticlockwise from forward, which for somebody
		// facing south is east — stand facing south and your left hand points east.
		SOUTH(0, 1, 1, 0),
		WEST(-1, 0, 0, 1),
		NORTH(0, -1, -1, 0),
		EAST(1, 0, 0, -1);

		private final int forwardX;
		private final int forwardZ;
		private final int leftX;
		private final int leftZ;

		Facing(int forwardX, int forwardZ, int leftX, int leftZ) {
			this.forwardX = forwardX;
			this.forwardZ = forwardZ;
			this.leftX = leftX;
			this.leftZ = leftZ;
		}

		public int forwardX() { return forwardX; }

		public int forwardZ() { return forwardZ; }

		public int leftX() { return leftX; }

		public int leftZ() { return leftZ; }

		/** The quarter turn a heading is nearest to. */
		public static Facing ofYaw(float yaw) {
			return values()[Math.floorMod(Math.round(yaw / 90f), 4)];
		}

		/** And back again, so a character sent home stands the way she was placed. */
		public float yaw() {
			return ordinal() * 90f;
		}
	}

	/**
	 * Walking or running, as the author said it rather than as the speed implies.
	 *
	 * <h2>Why this is not derived from the pace</h2>
	 *
	 * It was, and that was the whole of the problem. The animation is chosen from
	 * how fast the body is actually moving — above about half pace it is a run,
	 * below it a walk — so "walks or runs" and "how fast" were one dial with two
	 * knobs on it, and a node offering both would have had one of them lying the
	 * moment the other was moved.
	 *
	 * Said out loud, the two come apart honestly: this picks which animation plays
	 * and {@link Route#pace} picks how fast the body travels. A character can then
	 * cross a room in a slow run, which is a thing directors ask for and the
	 * derived version could not express at all.
	 */
	public enum Gait { WALK, RUN }

	/**
	 * As many points as one route may have.
	 *
	 * The same number the map's named places are capped at, for the same reason and
	 * with the same honesty about it: a document arriving from a client must be
	 * bounded, and nothing measured binds anywhere near here. A route is drawn as a
	 * chain of lines each frame, which is what the places already cost; the file is
	 * four numbers a point. A patrol is four points to a dozen.
	 */
	public static final int MOST = 256;

	/** An unhurried walk. The same number the reflexes use for going to look at something. */
	public static final float STROLL = 0.45f;

	/** And a run, for when whatever it is cannot wait. */
	public static final float DASH = 1f;

	/**
	 * Slower than this and she is not walking, she is sliding.
	 *
	 * Declared above the empty route on purpose. Static fields are made in the order
	 * they are written, the constructor below reads this one, and a floor read as
	 * nought while the class is still being built is the sort of fault that hides
	 * for a year because the value it lets through happens to be legal.
	 */
	public static final float CRAWL = 0.05f;

	/** A route that goes nowhere, which is the shape a fresh node has. */
	public static final Route NOWHERE =
		new Route(List.of(), Gait.WALK, STROLL, From.CHARACTER);

	public Route {
		points = points == null ? List.of() : List.copyOf(points);
		if (points.size() > MOST) points = points.subList(0, MOST);
		gait = gait == null ? Gait.WALK : gait;
		from = from == null ? From.CHARACTER : from;
		// Nought would be a character ordered to walk and told not to move, which is
		// a graph that stands still with nothing anywhere saying why. The floor is
		// the slowest a body reads as moving rather than as drifting.
		pace = Float.isFinite(pace) ? Math.clamp(pace, CRAWL, DASH) : STROLL;
	}

	public boolean isEmpty() {
		return points.isEmpty();
	}

	public int size() {
		return points.size();
	}

	/** The point at this position, or null when the route is shorter than that. */
	public Point at(int index) {
		return index < 0 || index >= points.size() ? null : points.get(index);
	}

	/** Whether any point of this depends on where the anchor is. */
	public boolean needsAnchor() {
		for (Point point : points) {
			if (point instanceof Point.Go) return true;
		}
		return false;
	}

	// ------------------------------------------------------- anchor and world

	/**
	 * Where a point actually is, given where the anchor stands and which way it faces.
	 *
	 * The one place the two forms of point meet. Everything downstream — the legs,
	 * the drawing, the panel — asks this and gets three numbers, so nothing else in
	 * the mod has to know that a route can be written two ways.
	 */
	public static int[] world(Point point, int anchorX, int anchorY, int anchorZ,
			Facing facing) {
		return switch (point) {
			case Point.At(int x, int y, int z, int _) -> new int[] { x, y, z };
			case Point.Go(int forward, int up, int left, int _) -> new int[] {
				anchorX + facing.forwardX() * forward + facing.leftX() * left,
				anchorY + up,
				anchorZ + facing.forwardZ() * forward + facing.leftZ() * left };
		};
	}

	/**
	 * And the other way: a block of the world written as a step from the anchor.
	 *
	 * Exact rather than approximate, because forward and left are whole axes: the
	 * two dot products recover the two counts with nothing left over. A route drawn
	 * by clicking and then read back as steps describes the same ground to the block.
	 */
	public static Point.Go step(int x, int y, int z, int anchorX, int anchorY, int anchorZ,
			Facing facing) {
		int dx = x - anchorX;
		int dz = z - anchorZ;
		return new Point.Go(
			dx * facing.forwardX() + dz * facing.forwardZ(),
			y - anchorY,
			dx * facing.leftX() + dz * facing.leftZ());
	}

	// ------------------------------------------------------------ the changing

	public Route withPoints(List<Point> now) {
		return new Route(now, gait, pace, from);
	}

	public Route withGait(Gait now) {
		return new Route(points, now, pace, from);
	}

	public Route withPace(float now) {
		return new Route(points, gait, now, from);
	}

	public Route writtenFrom(From now) {
		return new Route(points, gait, pace, now);
	}

	/**
	 * The same route with one more point on the end, or itself when it is full.
	 *
	 * Refusing quietly rather than throwing: this is reached from a click in the
	 * world, and the two hundred and fifty-seventh click on a route nobody will
	 * ever finish should not take the editor down with it.
	 */
	public Route and(Point point) {
		if (point == null || points.size() >= MOST) return this;
		List<Point> now = new java.util.ArrayList<>(points);
		now.add(point);
		return withPoints(now);
	}

	/** The same route without the point at this position. */
	public Route without(int index) {
		if (index < 0 || index >= points.size()) return this;
		List<Point> now = new java.util.ArrayList<>(points);
		now.remove(index);
		return withPoints(now);
	}

	/**
	 * Every point rewritten in one form, over the same ground.
	 *
	 * Used when the switch in the panel is thrown, and it has to leave the path
	 * exactly where it was — a button that changes how a route is written and moves
	 * it at the same time is a button nobody would dare press twice.
	 */
	public Route allWrittenFrom(From now, int anchorX, int anchorY, int anchorZ,
			Facing facing) {
		List<Point> rewritten = new java.util.ArrayList<>(points.size());
		for (Point point : points) {
			int[] where = world(point, anchorX, anchorY, anchorZ, facing);
			rewritten.add(switch (now) {
				case WORLD -> new Point.At(where[0], where[1], where[2], point.stay());
				case CHARACTER -> step(where[0], where[1], where[2],
					anchorX, anchorY, anchorZ, facing).staying(point.stay());
			});
		}
		return new Route(rewritten, gait, pace, now);
	}
}
