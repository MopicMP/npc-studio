package com.mopicmp.npcstudio.client.wardrobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * A costume as a figure: solid, turned, one leg forward.
 *
 * <h2>Why this replaces the flat one</h2>
 *
 * {@link PaperDoll} cut the front faces out of the skin and laid them side by
 * side. It was cheap and it was right about one thing — what costumes differ by
 * is the body, not the face — but it is a paper cut-out, and a shelf of them
 * reads as a sheet of stickers rather than as a rail of clothes. Every site that
 * shows skins turns the figure and steps one leg forward, and it is not
 * decoration: the turn is what shows that a coat has a back and sides, and the
 * step is what stops the legs reading as one block.
 *
 * <h2>Nothing here is invented</h2>
 *
 * The game already renders a skin in three dimensions in a window — it is what
 * the skin settings show — and every number in it was read out of that widget
 * rather than guessed: the model comes from the baked player layer, the scale is
 * {@code 0.97 × height ÷ 2.125}, the figure hangs from {@code -1.0625}, and the
 * view sits at five degrees above and thirty degrees round. Those are vanilla's
 * own constants for exactly this picture, so the figure sits in its box the way
 * the game's does.
 *
 * The one thing added is the pose, because vanilla's widget stands to attention
 * and this is the part somebody asked for.
 */
public final class SkinFigure {

	/** How much of the box the figure fills, and how tall it is in blocks. */
	private static final float FILL = 0.97f;
	private static final float TALL = 2.125f;

	/** Where the figure hangs from, so its feet land at the bottom of the box. */
	private static final float PIVOT = -1.0625f;

	/** Where the eye stands: a little above, and round to one side. */
	public static final float ABOVE = -5f;
	public static final float ROUND = 30f;

	private static final float RADIANS = (float) (Math.PI / 180);

	private static Model.Simple wide;
	private static Model.Simple slim;

	private SkinFigure() { }

	/**
	 * The pose, in degrees, and why each number is what it is.
	 *
	 * A step rather than a stride: enough that the legs read as two and not so much
	 * that a hundred costumes all look like they are marching. The arms come out
	 * from the body by a few degrees for the same reason — at rest they are flush
	 * against the torso, and a coat's sleeve and a coat's side then share an edge
	 * and cannot be told apart at this size.
	 *
	 * The back leg is turned less than the front one. Legs do not swing evenly
	 * about a standing figure, and matching them exactly is what makes a pose look
	 * like a mannequin.
	 */
	private static void pose(ModelPart root) {
		turn(root, "right_leg", -14, 0, 0);
		turn(root, "left_leg", 9, 0, 0);
		turn(root, "right_arm", -4, 0, 5);
		turn(root, "left_arm", 5, 0, -5);
	}

	private static void turn(ModelPart root, String named, float x, float y, float z) {
		ModelPart part;
		try {
			part = root.getChild(named);
		} catch (RuntimeException noSuchPart) {
			// A layer that does not have this bone is a layer this pose does not
			// apply to, which is a thing to step over rather than to fail on.
			return;
		}
		part.xRot = x * RADIANS;
		part.yRot = y * RADIANS;
		part.zRot = z * RADIANS;
	}

	/**
	 * The two models, baked once and posed once.
	 *
	 * Once, because baking builds every box of a player and this is drawn for every
	 * tile on the shelf. The pose is set at the same time and left: a
	 * {@code Model.Simple} animates nothing of its own, so whatever the parts were
	 * put at is what every tile shows.
	 */
	private static boolean ready() {
		if (wide != null) return true;
		try {
			var models = Minecraft.getInstance().getEntityModels();
			ModelPart wideRoot = models.bakeLayer(ModelLayers.PLAYER);
			ModelPart slimRoot = models.bakeLayer(ModelLayers.PLAYER_SLIM);
			pose(wideRoot);
			pose(slimRoot);
			wide = new Model.Simple(wideRoot, RenderTypes::entityTranslucent);
			slim = new Model.Simple(slimRoot, RenderTypes::entityTranslucent);
		} catch (RuntimeException notYet) {
			// Asked before the models are loaded, which happens on the first frame.
			// Nothing is drawn this frame and the next one has it.
			return false;
		}
		return true;
	}

	/** How wide a box has to be for a figure of this height to fit in it turned. */
	public static int widthFor(int tall) {
		return Math.max(1, Math.round(tall * 0.6f));
	}

	/**
	 * Draws one costume into a box.
	 *
	 * @param texture the skin, or nothing when it has not arrived yet
	 * @param slimArms whether this skin is drawn on the narrow-armed model
	 */
	public static void draw(GuiGraphicsExtractor graphics, Identifier texture, boolean slimArms,
			int left, int top, int right, int bottom) {
		if (texture == null || !ready()) return;
		int tall = bottom - top;
		if (tall <= 0 || right <= left) return;

		// Where the screen has it, not where the panel thinks it is. A picture in a
		// picture is placed by four plain numbers with no matrix anywhere near them
		// — see Pip, which is where the whole of that lesson is written down.
		int[] box = com.mopicmp.npcstudio.client.workspace.Pip.box(
			graphics, left, top, right, bottom);
		graphics.skin(slimArms ? slim : wide, texture,
			FILL * tall / TALL, ABOVE, ROUND, PIVOT, box[0], box[1], box[2], box[3]);
	}
}
