package com.mopicmp.npcstudio.model;

/**
 * One box of a model.
 *
 * <h2>The units</h2>
 *
 * Model pixels: sixteen to a block, which is what Blockbench shows and what
 * every Minecraft model is written in. Not blocks, and deliberately not — a
 * model whose numbers are sixteenths is a model whose numbers are all fractions,
 * and fractions typed by hand are fractions typed wrong.
 *
 * <h2>Corners rather than centre and size</h2>
 *
 * {@code from} and {@code to} are opposite corners, the way Blockbench and the
 * vanilla model files write them. Centre-and-size reads better in a properties
 * panel and is worse everywhere else: every operation that matters here —
 * snapping to the grid, growing a face, checking two boxes do not occupy one
 * place — is about the corners, and centre-and-size makes each of them convert
 * twice and round in the middle.
 *
 * The panel can still show a size. Showing is cheap; storing is what commits.
 */
public record Cube(
		float fromX, float fromY, float fromZ,
		float toX, float toY, float toZ,
		/** Where this box turns, in the same space as the corners. */
		float pivotX, float pivotY, float pivotZ,
		/** Degrees about each axis, applied at the pivot. */
		float rotX, float rotY, float rotZ,
		/**
		 * How far every face is pushed out along its own normal, in pixels.
		 *
		 * Kept because it is the only way to make two boxes in the same plane stop
		 * fighting over which is in front, and because the importer already uses it
		 * for exactly that. A negative value shrinks.
		 */
		float inflate,
		/** Top left of this box's patch on the texture. */
		int u, int v,
		/**
		 * The block whose texture this box wears, or empty for none.
		 *
		 * The base way to texture a model, and it earns that place by needing
		 * nothing: every block texture is already loaded, already in the atlas and
		 * already something people know the look of. A mast is a spruce log and a
		 * hull is dark oak planks, and nobody has drawn a PNG or laid out a UV
		 * map to say so.
		 *
		 * Per box rather than per model, because a ship is not made of one
		 * material. Per box rather than per face, because six materials on one box
		 * is a level of detail nobody has asked for and six times the fields.
		 */
		String block) {

	public Cube {
		block = block == null ? "" : block;
	}

	/** A one-pixel box at the origin: what a new cube is before it is anything. */
	public static Cube unit() {
		return new Cube(0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
	}

	/** Whether this box has been given a material to wear. */
	public boolean textured() {
		return !block.isEmpty();
	}

	public Cube wearing(String which) {
		return new Cube(fromX, fromY, fromZ, toX, toY, toZ, pivotX, pivotY, pivotZ,
			rotX, rotY, rotZ, inflate, u, v, which);
	}

	public float sizeX() {
		return toX - fromX;
	}

	public float sizeY() {
		return toY - fromY;
	}

	public float sizeZ() {
		return toZ - fromZ;
	}

	/** Whether this box turns at all, which decides how cheaply it can be drawn. */
	public boolean straight() {
		return rotX == 0 && rotY == 0 && rotZ == 0;
	}

	/**
	 * The same box with its corners moved, keeping the pivot where it was.
	 *
	 * The pivot stays because moving a box is not the same as moving what it
	 * turns around: a door dragged along the wall still swings on its hinge.
	 * Moving the pivot is its own operation and says so.
	 */
	public Cube movedTo(float x, float y, float z) {
		return new Cube(x, y, z, x + sizeX(), y + sizeY(), z + sizeZ(),
			pivotX, pivotY, pivotZ, rotX, rotY, rotZ, inflate, u, v, block);
	}

	/**
	 * The same box given new opposite corners, in either order.
	 *
	 * Sorted rather than rejected, because this is what a face dragged past the
	 * far side of its own box means: the box turns inside out and carries on. The
	 * alternative is a handle that stops dead at zero and has to be let go of and
	 * grabbed again from the other side, which nobody expects and everybody tries.
	 */
	public Cube corners(float x0, float y0, float z0, float x1, float y1, float z1) {
		return new Cube(
			Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1),
			Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1),
			pivotX, pivotY, pivotZ, rotX, rotY, rotZ, inflate, u, v, block);
	}

	/** The same box grown or shrunk from its own corner. */
	public Cube sized(float dx, float dy, float dz) {
		return new Cube(fromX, fromY, fromZ,
			fromX + Math.max(0, dx), fromY + Math.max(0, dy), fromZ + Math.max(0, dz),
			pivotX, pivotY, pivotZ, rotX, rotY, rotZ, inflate, u, v, block);
	}

	public Cube turned(float x, float y, float z) {
		return new Cube(fromX, fromY, fromZ, toX, toY, toZ,
			pivotX, pivotY, pivotZ, x, y, z, inflate, u, v, block);
	}

	public Cube pivotedAt(float x, float y, float z) {
		return new Cube(fromX, fromY, fromZ, toX, toY, toZ, x, y, z,
			rotX, rotY, rotZ, inflate, u, v, block);
	}

	public Cube textured(int atU, int atV) {
		return new Cube(fromX, fromY, fromZ, toX, toY, toZ,
			pivotX, pivotY, pivotZ, rotX, rotY, rotZ, inflate, atU, atV, block);
	}
}
