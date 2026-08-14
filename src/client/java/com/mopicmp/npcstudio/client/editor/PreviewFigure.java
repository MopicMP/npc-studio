package com.mopicmp.npcstudio.client.editor;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

/**
 * Draws a character in a panel, turned to any angle.
 *
 * The game already has a helper for this — the one the inventory uses — and it
 * was used here first. It cannot do the job: it works out which way to face
 * from the distance to the mouse pointer, through an arctangent, so the figure
 * turns as far as profile and then stops no matter how far the mouse goes. That
 * is fine for an inventory, where nobody is trying to look at the back of their
 * own head, and useless for judging an animation.
 *
 * So the same few steps are done here instead, with the angle simply given.
 * Everything this touches is public; the helper was never doing anything
 * privileged, only something opinionated.
 */
public final class PreviewFigure {

	/** Where the eye starts: slightly above, the angle a person is normally seen from. */
	public static final float RESTING_PITCH = -20f;

	private PreviewFigure() { }

	/**
	 * @param yaw   which way the character faces, in degrees, unbounded
	 * @param pitch how far above or below the character the eye is, in degrees
	 * @param scale roughly how many pixels tall a block of the character is
	 */
	@SuppressWarnings("unchecked")
	public static void draw(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom,
			float scale, float yOffset, float yaw, float pitch, LivingEntity entity) {

		EntityRenderer<LivingEntity, EntityRenderState> renderer =
			(EntityRenderer<LivingEntity, EntityRenderState>) (EntityRenderer<?, ?>)
				Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		EntityRenderState state = renderer.createRenderState(entity, 1.0f);

		if (state instanceof LivingEntityRenderState living) {
			// Only the body is turned. The head's field is measured against the body
			// rather than against the world — the game fills it in as
			// `headYaw - bodyRot` — so putting the same angle in both does not turn
			// the character round, it screws its head off: at 155° the figure faced
			// forward and looked backwards.
			living.bodyRot = 180f + yaw;
			living.yRot = 0;
			living.xRot = 0;
			// The character's own size is kept, not normalised away. The inventory's
			// helper divides it out so that every mob fits the same little box, which
			// is right there and wrong here: a size slider that shows nothing is a
			// slider nobody can use. The panel's own scaling is folded in instead.
			scale *= living.scale;
			living.boundingBoxWidth /= living.scale;
			living.boundingBoxHeight /= living.scale;
			living.scale = 1;
		}

		// Upside down, and deliberately: entity models are built the other way up
		// from the screen, so the half turn about Z is what puts a character's feet
		// at the bottom.
		// Turning the eye rather than sliding the figure. Dragging up and down used
		// to move the character within its frame, which is not what looking at
		// something from above means — this rolls the view over it the way any
		// model viewer does.
		Quaternionf facing = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(pitch));
		facing.mul(tilt);

		Vector3f middle = new Vector3f(0, state.boundingBoxHeight / 2 + yOffset, 0);
		graphics.entity(state, scale, middle, facing, tilt, left, top, right, bottom);
	}
}
