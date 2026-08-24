package com.mopicmp.npcstudio.client.entity;

import com.mopicmp.npcstudio.entity.SceneCamera;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Draws the camera as nothing at all.
 *
 * <h2>Why a renderer that renders nothing is not a mistake</h2>
 *
 * Two reasons, and the second is the real one.
 *
 * An entity with no renderer registered does not quietly go unseen — it throws
 * the first time the game tries to draw it. So something has to be here.
 *
 * And what is drawn instead is drawn <em>over</em> the world, by the viewport,
 * with the arrows and the rings. That is where every handle in this mod lives
 * and for the same reason: a marker made of geometry is behind whatever is in
 * front of it, so a camera inside a ship's hull would be a camera you cannot
 * see or click. Over the world it is always there, by construction.
 *
 * It also means the marker is absent from the film without anybody arranging it.
 * The overlay is interface; the shot is the world; a camera that appeared in its
 * own footage would be a thing to remember to hide.
 */
public class CameraRenderer extends EntityRenderer<SceneCamera, EntityRenderState> {

	public CameraRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public EntityRenderState createRenderState() {
		return new EntityRenderState();
	}
}
