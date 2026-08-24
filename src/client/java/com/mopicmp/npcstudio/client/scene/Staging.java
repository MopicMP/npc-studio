package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Putting a participant where the scene says it is.
 *
 * <h2>Drawn somewhere else, rather than moved</h2>
 *
 * The obvious way to animate a character's position is to set it, and it is the
 * wrong one on both sides. On the client the server sends the position back a
 * moment later and the character snaps home; on the server it means a scene
 * being scrubbed drags real entities around a world other people are standing
 * in, twenty times a second, for as long as somebody is editing.
 *
 * The render state is the right place. It is filled in from the entity every
 * frame and thrown away afterwards, it is what actually decides where the thing
 * appears, and writing to it changes nothing that outlives the frame. So a scene
 * is a lie told to the renderer — and because it is only a lie, closing the
 * scene puts everybody back where they really are with nothing to undo.
 *
 * <h2>Every number falls back to what it already was</h2>
 *
 * A scene that only turns a wheel must not also drag the wheel to the origin, so
 * nothing here reads a channel without handing over the value that is already
 * there. That is the difference between a scene that animates what it mentions
 * and one that takes over everything it touches.
 */
public final class Staging {

	private Staging() { }

	/**
	 * Moves and turns whatever is in the scene.
	 *
	 * Everything every participant has in common is done here; what a particular
	 * kind of thing keeps in its own fields is not, so the sample is handed back
	 * for the caller to carry on with. An object keeps its facing and its size in
	 * fields of its own rather than in the living-entity ones, and reaching across
	 * to write them from here would mean this class knowing about every renderer
	 * there will ever be.
	 *
	 * @param entity the thing being drawn
	 * @param state  its render state, already filled in by vanilla
	 * @return what the scene says about it, or null when it says nothing
	 */
	public static Playing.Sample place(Entity entity, EntityRenderState state, float partial) {
		Playing.Sample sample = Playing.sampleOf(entity, partial);
		if (sample == null) return null;

		state.x = sample.value(Channels.X, (float) state.x);
		state.y = sample.value(Channels.Y, (float) state.y);
		state.z = sample.value(Channels.Z, (float) state.z);

		if (!(state instanceof LivingEntityRenderState living)) return sample;

		// The body and the head are two numbers and the scene has one. Turning the
		// body without the head is a character walking one way while staring
		// straight ahead, which reads as a fault rather than as a performance; a
		// separate look-at channel is a thing to add when there is something for it
		// to look at.
		float was = living.bodyRot;
		float yaw = sample.value(Channels.YAW, was);
		living.bodyRot = yaw;
		living.yRot += yaw - was;
		living.xRot = sample.value(Channels.PITCH, living.xRot);
		living.scale = sample.value(Channels.SCALE, living.scale);
		return sample;
	}

	/**
	 * How far the scene turns each of a model's bones, in degrees.
	 *
	 * Only the bones it says something about. Nought is left out rather than
	 * written down as nought, because an empty map is what lets the drawing take
	 * the cached placement — an object in a scene that only moves as a whole
	 * should cost no more to draw than one that is not in a scene at all.
	 */
	public static java.util.Map<String, float[]> turnsOf(Playing.Sample sample,
			com.mopicmp.npcstudio.model.Model model) {
		if (sample == null || model == null) return java.util.Map.of();

		java.util.Map<String, float[]> turns = new java.util.HashMap<>();
		for (com.mopicmp.npcstudio.model.Bone bone : model.bones()) {
			float x = sample.value(Channels.of(bone.name(), Channels.TURN_X), 0);
			float y = sample.value(Channels.of(bone.name(), Channels.TURN_Y), 0);
			float z = sample.value(Channels.of(bone.name(), Channels.TURN_Z), 0);
			if (x != 0 || y != 0 || z != 0) turns.put(bone.name(), new float[] { x, y, z });
		}
		return turns.isEmpty() ? java.util.Map.of() : turns;
	}
}
