package com.mopicmp.npcstudio.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes that are not boxes, built out of boxes.
 *
 * <h2>Why out of boxes</h2>
 *
 * Because a box is the only thing the format has, and that is not a limitation
 * to work around — it is what the whole thing looks like. A cubic sphere in
 * Minecraft reads as a sphere, the same way a cubic tree reads as a tree; the
 * steps are the style. Smoothing them away would need a mesh, and a mesh cannot
 * wear a block's texture on every face, which is the one idea this model format
 * is built on.
 *
 * <h2>The cell</h2>
 *
 * Everything here is laid out on a grid of cells, and the cell size is what
 * decides both how round it looks and how many boxes it costs. Two model pixels
 * — an eighth of a block — is the compromise: fine enough that a sphere of a
 * block across is recognisably round, coarse enough that it is a couple of
 * hundred boxes rather than a couple of thousand.
 *
 * <h2>Hollow, not solid</h2>
 *
 * Only the shell. The inside of a sphere is never seen and every box in it is a
 * box the renderer draws, the collision tests and somebody has to scroll past in
 * the parts list. A solid ball of radius eight is five hundred boxes; its shell
 * is under two hundred.
 */
public final class Primitives {

	/** Two model pixels: an eighth of a block. */
	public static final float CELL = 2;

	private Primitives() { }

	/**
	 * A circle of boxes lying flat, centred on the origin.
	 *
	 * Flat because that is what a ring is for — a rim, a hoop, the top of a barrel.
	 * Standing it on its side is one turn of the folder it lands in.
	 */
	public static List<Cube> ring(float radius, float tall, String block) {
		List<Cube> made = new ArrayList<>();
		int steps = Math.max(4, Math.round(radius / CELL));

		for (int x = -steps; x <= steps; x++) {
			for (int z = -steps; z <= steps; z++) {
				double away = Math.hypot(x + 0.5, z + 0.5) * CELL;
				if (away > radius || away < radius - CELL * 1.5) continue;
				made.add(cell(x * CELL, 0, z * CELL, CELL, tall, CELL, block));
			}
		}
		return made;
	}

	/**
	 * A hollow ball of boxes, centred on the origin.
	 *
	 * A cell is kept when its middle is inside the ball and outside a ball one cell
	 * and a half smaller. The half is what stops the shell developing holes where
	 * the surface runs diagonally through the grid — a plain "between two radii"
	 * test leaves gaps at the corners, and a sphere you can see through is a sphere
	 * that has to be done again by hand.
	 */
	public static List<Cube> sphere(float radius, String block) {
		List<Cube> made = new ArrayList<>();
		int steps = Math.max(2, Math.round(radius / CELL));

		for (int x = -steps; x <= steps; x++) {
			for (int y = -steps; y <= steps; y++) {
				for (int z = -steps; z <= steps; z++) {
					double away = Math.sqrt(sq(x + 0.5) + sq(y + 0.5) + sq(z + 0.5)) * CELL;
					if (away > radius || away < radius - CELL * 1.5) continue;
					made.add(cell(x * CELL, y * CELL, z * CELL, CELL, CELL, CELL, block));
				}
			}
		}
		return made;
	}

	/**
	 * A cone standing on the origin, point upwards.
	 *
	 * Solid rather than hollow, unlike the ball. A hollow cone is a shell that comes
	 * to a point, and near the point the shell is the whole of it anyway — so all
	 * hollowing saves is the wide end, and what it costs is a cone with an opening
	 * in its base that shows as a hole from underneath.
	 */
	public static List<Cube> cone(float radius, float tall, String block) {
		List<Cube> made = new ArrayList<>();
		int levels = Math.max(2, Math.round(tall / CELL));
		int steps = Math.max(1, Math.round(radius / CELL));

		for (int level = 0; level < levels; level++) {
			float here = radius * (1 - (float) level / levels);
			for (int x = -steps; x <= steps; x++) {
				for (int z = -steps; z <= steps; z++) {
					double away = Math.hypot(x + 0.5, z + 0.5) * CELL;
					if (away > here) continue;
					made.add(cell(x * CELL, level * CELL, z * CELL, CELL, CELL, CELL, block));
				}
			}
		}
		return made;
	}

	private static double sq(double of) {
		return of * of;
	}

	private static Cube cell(float x, float y, float z, float wide, float tall, float deep,
			String block) {
		return new Cube(x, y, z, x + wide, y + tall, z + deep,
			0, 0, 0, 0, 0, 0, 0, 0, 0, block);
	}
}
