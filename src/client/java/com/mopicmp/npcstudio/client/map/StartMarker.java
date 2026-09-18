package com.mopicmp.npcstudio.client.map;

import com.mopicmp.npcstudio.client.scene.CameraMarker;
import com.mopicmp.npcstudio.client.scene.Wireframe;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;
import com.mopicmp.npcstudio.net.MapPayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where the map starts, drawn in the world.
 *
 * <h2>Two marks, and the second one is the point</h2>
 *
 * The first is where the point was put: one block, with an arrow out of it along
 * the stored yaw and pitch. That much is only a picture of a number somebody
 * typed, and on its own it would be decoration.
 *
 * The second says where a player actually ends up, and it exists because those
 * are routinely not the same block. The game does not use the spawn point as
 * given: it moves down to the floor, up out of a wall, and — unless the world is
 * in adventure mode — it picks a random spot within the respawn radius instead.
 * A point placed carefully on a doorstep can put people in the river behind it,
 * and the way to discover that today is to leave the world and come back.
 *
 * So the second mark is not our idea of the rules. It comes from
 * {@code PlayerSpawnFinder.findSpawn} by way of {@link MapPayloads.Spawn} — the
 * method the server calls to place a real player.
 *
 * <h2>Why a square rather than a block when the radius is set</h2>
 *
 * Because with a radius there is no single answer, and drawing one would be a
 * lie told precisely. The finder returns one candidate out of
 * {@code (2r+1)²} and a different one next time; a square over the whole area is
 * the honest shape of "somewhere in here", and it is also the picture that makes
 * somebody go and set the radius to nought.
 */
public final class StartMarker {

	private static final int POINT = 0xFF4FC3F7;
	private static final int LOOK = 0xFFFFD54A;
	private static final int LANDS = 0xFF81C784;
	private static final int SCATTER = 0xFFE57373;

	/** How far the look arrow reaches, in blocks. Long enough to read across a room. */
	private static final double ARROW = 2.5;

	private StartMarker() { }

	/**
	 * The same mark over the ordinary game, drawn beside the map's places.
	 *
	 * The start has the same hole the places had and it is older: it can be moved by
	 * pointing at the ground, from the ring, without the workspace — and then it was
	 * only visible to somebody who opened the workspace to look. Whoever may move it
	 * should be able to see where they moved it to.
	 *
	 * The check for who and for whether the workspace is up is made once, next door,
	 * so the two marks cannot come to disagree about when they are shown.
	 */
	static void overWorld(GuiGraphicsExtractor graphics) {
		draw(graphics, 0, 0, false);
	}

	public static void draw(GuiGraphicsExtractor graphics, int originX, int originY) {
		draw(graphics, originX, originY, true);
	}

	private static void draw(GuiGraphicsExtractor graphics, int originX, int originY,
			boolean inPanel) {
		if (inPanel && !WorkspaceScreen.embedded()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;

		LevelData.RespawnData spawn = client.level.getLevelData().getRespawnData();
		// Drawn only from inside the dimension it is in. A point in the overworld has
		// no place on screen while standing in the nether, and drawing it at the same
		// coordinates there would put it somewhere it is not.
		if (!client.level.dimension().equals(spawn.dimension())) return;

		BlockPos pos = spawn.pos();
		box(graphics, originX, originY, new AABB(pos), POINT);
		arrow(graphics, originX, originY, pos, spawn.yaw(), spawn.pitch());

		MapPayloads.Spawn report = Started.spawn();
		// An answer about a different point is an answer to an older question. Better
		// no second mark than one in the wrong place.
		if (report == null || !report.pos().equals(pos)) return;

		if (report.radius() > 0 && !report.adventure()) {
			scatter(graphics, originX, originY, pos, report.radius());
			return;
		}

		BlockPos lands = BlockPos.containing(report.landing());
		if (!lands.equals(pos)) {
			box(graphics, originX, originY, new AABB(lands), LANDS);
		}
	}

	/** Which way somebody appearing here will be facing. */
	private static void arrow(GuiGraphicsExtractor graphics, int originX, int originY,
			BlockPos pos, float yaw, float pitch) {
		// From eye height rather than from the block's middle: the arrow is a line of
		// sight, and one drawn from the floor points at the floor.
		Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, 1.62, 0);
		CameraMarker.Aim aim = CameraMarker.aimOf(eye, yaw, pitch);
		Vec3 tip = eye.add(aim.forward().scale(ARROW));
		Wireframe.line(graphics, originX, originY, eye, tip, LOOK);

		// Two barbs, so that an arrow pointing away from the viewer still reads as an
		// arrow rather than as a line with nothing at either end.
		Vec3 back = aim.forward().scale(-0.4);
		for (double sign : new double[] { -1, 1 }) {
			Wireframe.line(graphics, originX, originY, tip,
				tip.add(back).add(aim.right().scale(0.22 * sign)), LOOK);
		}
	}

	/** The whole area a player may be put down in, when the radius is not nought. */
	private static void scatter(GuiGraphicsExtractor graphics, int originX, int originY,
			BlockPos pos, int radius) {
		double y = pos.getY();
		double minX = pos.getX() - radius;
		double maxX = pos.getX() + radius + 1;
		double minZ = pos.getZ() - radius;
		double maxZ = pos.getZ() + radius + 1;

		Vec3[] corners = {
			new Vec3(minX, y, minZ), new Vec3(maxX, y, minZ),
			new Vec3(maxX, y, maxZ), new Vec3(minX, y, maxZ) };
		for (int i = 0; i < corners.length; i++) {
			Wireframe.line(graphics, originX, originY,
				corners[i], corners[(i + 1) % corners.length], SCATTER);
		}
	}

	/** The twelve edges of a box, the same way the camera's is drawn. */
	private static void box(GuiGraphicsExtractor graphics, int originX, int originY,
			AABB around, int colour) {
		Vec3[] at = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			at[i] = new Vec3(
				(i & 1) == 0 ? around.minX : around.maxX,
				(i & 2) == 0 ? around.minY : around.maxY,
				(i & 4) == 0 ? around.minZ : around.maxZ);
		}
		for (int from = 0; from < 8; from++) {
			for (int bit = 1; bit <= 4; bit <<= 1) {
				int to = from | bit;
				if (to != from) Wireframe.line(graphics, originX, originY, at[from], at[to], colour);
			}
		}
	}
}
