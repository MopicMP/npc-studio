package com.mopicmp.npcstudio.client.map;

import com.mopicmp.npcstudio.client.scene.Wireframe;
import com.mopicmp.npcstudio.client.workspace.WorkspaceScreen;
import com.mopicmp.npcstudio.map.Spot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The map's named places, drawn where they are.
 *
 * <h2>Why the name is drawn and not only the box</h2>
 *
 * Because the name is the whole of what a place is. A graph refers to
 * {@code at:gate} and cannot see a box; the author writing that line has to be
 * able to stand in the world and read which of these is the gate. A row of
 * identical markers would be a row of places you have to guess between, which is
 * the state a coordinate was already in.
 *
 * <h2>Why a post rather than a box on the ground</h2>
 *
 * A place is stood on, so its box is at floor level and is hidden by the floor
 * from most angles anybody looks at it from. The upright above it is what makes
 * one visible across a courtyard, which is the distance these are chosen at.
 */
public final class SpotMarkers {

	private static final int PLACE = 0xFFFFB74D;
	private static final int LABEL = 0xFFFFE0B2;

	/** How far up the post goes. Head height and a little, so it clears a body. */
	private static final double POST = 2.0;

	/** Past this the name is not drawn: a courtyard of unreadable labels is noise. */
	private static final double NAMED_WITHIN = 48.0;

	private SpotMarkers() { }

	/**
	 * The same marks over the ordinary game, for whoever is building the map.
	 *
	 * <h2>The hole this fills</h2>
	 *
	 * Places are put down from the world — that is the whole point of them, and the
	 * ring reaches them without the workspace. But the drawing only ever happened
	 * inside the workspace's viewport, so putting one down while walking about
	 * produced nothing visible at all: the act worked, the mark existed, and the only
	 * way to see it was to open the editing window. A thing you cannot see is a thing
	 * you place twice.
	 *
	 * <h2>Who sees them</h2>
	 *
	 * Whoever may edit them, which the server already defines as creative mode. One
	 * rule rather than two: nobody should be able to see a mark they cannot move.
	 *
	 * It also means somebody testing their own map in survival sees it as a player
	 * does, with the scaffolding gone — which is what testing is for and is worth
	 * more than the convenience of leaving them up.
	 */
	public static void overWorld(GuiGraphicsExtractor graphics,
			net.minecraft.client.DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !client.player.isCreative()) return;
		// Not while the workspace is up: it draws these into its own viewport, and the
		// two together would be every mark drawn twice, one of them ignoring the panels
		// laid over the edges of the world.
		if (WorkspaceScreen.embedded()) return;
		draw(graphics, 0, 0);
		StartMarker.overWorld(graphics);
	}

	public static void draw(GuiGraphicsExtractor graphics, int originX, int originY) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;

		Vec3 eye = client.gameRenderer.mainCamera().position();

		// No further than the world itself goes.
		//
		// Every one of these is a handful of lines walked a pixel at a time, and over
		// a map's worth of places that is a cost paid every frame — which it now is,
		// because these are drawn over the ordinary game rather than only while
		// editing. The bound is taken from the player's own draw distance rather than
		// chosen: past it there is no ground under the mark and nothing to read it
		// against, so nothing is being hidden that could have been seen.
		double far = client.options.renderDistance().get() * 16.0;
		double farSquared = far * far;

		for (Spot spot : Spots.all()) {
			Vec3 foot = Vec3.atBottomCenterOf(spot.at());
			if (eye.distanceToSqr(foot) > farSquared) continue;
			Vec3 top = foot.add(0, POST, 0);
			Wireframe.line(graphics, originX, originY, foot, top, PLACE);

			// A small cross on the ground, so the exact block is readable from above as
			// well as the post being readable from across the room.
			for (double[] arm : new double[][] { { 0.4, 0 }, { -0.4, 0 }, { 0, 0.4 }, { 0, -0.4 } }) {
				Wireframe.line(graphics, originX, originY, foot,
					foot.add(arm[0], 0, arm[1]), PLACE);
			}

			if (eye.distanceToSqr(top) > NAMED_WITHIN * NAMED_WITHIN) continue;
			double[] on = Wireframe.at(top, originX, originY);
			if (on == null) continue;
			Component name = Component.literal(spot.name());
			int wide = client.font.width(name);
			graphics.text(client.font, name,
				(int) on[0] - wide / 2, (int) on[1] - 10, LABEL);
		}
	}
}
