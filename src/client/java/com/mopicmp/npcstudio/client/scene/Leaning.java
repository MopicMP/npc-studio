package com.mopicmp.npcstudio.client.scene;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The arithmetic of leaning a torso, apart from the model it is done to.
 *
 * <h2>Why it is worth having on its own</h2>
 *
 * Because all of it is composition of rotations, and composition of rotations is
 * where the silent mistakes live. A part holds three numbers and applies them as
 * {@code Rz · Ry · Rx}; putting one rotation in front of another means composing
 * and then taking that same decomposition back out, and the two calls involved
 * take their arguments in opposite orders — {@code rotationZYX(z, y, x)} against
 * {@code getEulerAnglesZYX} handing back {@code (x, y, z)}. Getting that pair the
 * wrong way round produces a character who leans by turning inside out, which is
 * obvious in the game and impossible to reason about from the code.
 *
 * Here it is four functions with no Minecraft in them, and a test that pins the
 * convention rather than trusting it.
 */
public final class Leaning {

	private Leaning() { }

	/**
	 * What a scene did to the torso: where it is now, with where it was undone.
	 *
	 * The difference rather than the whole, because the model may already have had
	 * an opinion — a character in a boat has its torso turned by vanilla — and what
	 * has to be carried onto the head and the arms is only the part somebody
	 * authored.
	 */
	public static Quaternionf turned(float nowZ, float nowY, float nowX,
			float wasZ, float wasY, float wasX) {
		return new Quaternionf().rotationZYX(nowZ, nowY, nowX)
			.mul(new Quaternionf().rotationZYX(wasZ, wasY, wasX).conjugate());
	}

	/**
	 * How far a part has to be moved so that it turns about a point below itself.
	 *
	 * A part turns about its own origin and nothing else. Turning about a point
	 * {@code drop} below it puts the geometry at {@code h + R·(p − h)} rather than
	 * at {@code p + R·v}, and the difference between those is a translation of
	 * {@code d − R·d}. So the pivot need not move and no new kind of part is needed:
	 * the same rotation, with an offset added to where the part sits.
	 *
	 * @param drop how far below the part's origin the turn happens, in model pixels
	 * @return what to add to the part's own x, y and z
	 */
	public static float[] offset(Quaternionf turned, float drop) {
		Vector3f moved = turned.transform(new Vector3f(0, drop, 0));
		return new float[] { -moved.x, drop - moved.y, -moved.z };
	}

	/**
	 * Where something above the hips ends up once the torso has leaned.
	 *
	 * Rigid, about the hip point, which is what "connected" means: the head is
	 * exactly as far from the waist afterwards as it was before, so a lean cannot
	 * pull a neck apart however far it goes.
	 */
	public static float[] carried(Quaternionf turned, float[] hip, float[] at) {
		Vector3f away = turned.transform(
			new Vector3f(at[0] - hip[0], at[1] - hip[1], at[2] - hip[2]));
		return new float[] { hip[0] + away.x, hip[1] + away.y, hip[2] + away.z };
	}

	/**
	 * Two turns, one after the other, as the three numbers a part holds.
	 *
	 * The lean first and the part's own second, because that is what the words
	 * mean: a character leaning back while looking down is leaning, and then
	 * looking down from there.
	 *
	 * @return the part's new x, y and z rotations, in that order
	 */
	public static float[] then(Quaternionf turned, float ownZ, float ownY, float ownX) {
		Vector3f angles = new Quaternionf(turned)
			.mul(new Quaternionf().rotationZYX(ownZ, ownY, ownX))
			.getEulerAnglesZYX(new Vector3f());
		return new float[] { angles.x, angles.y, angles.z };
	}
}
