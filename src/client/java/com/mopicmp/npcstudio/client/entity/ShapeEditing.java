package com.mopicmp.npcstudio.client.entity;

import com.mopicmp.npcstudio.entity.BodyShape;

/**
 * A build somebody is dragging a slider through, before anybody else has heard.
 *
 * A character's build lives on the server, which is right: everyone who can see
 * it has to draw it. But a slider is dragged sixty times a second, and a
 * character that only changed when the server answered would lag behind the
 * hand moving it — on a real server by a tenth of a second, which is exactly
 * long enough to make a fine adjustment impossible.
 *
 * So the screen puts its working copy here, the renderer prefers it, and the
 * server is told once at the end. Nothing else in the game reads this, so a
 * character being edited looks wrong to nobody but the person editing it —
 * which is the point.
 */
public final class ShapeEditing {

	private static int who = -1;
	private static BodyShape working;

	private ShapeEditing() { }

	/** What to draw this character as, or null to use whatever the server said. */
	public static BodyShape of(int entityId) {
		return entityId == who ? working : null;
	}

	public static void begin(int entityId, BodyShape shape) {
		who = entityId;
		working = shape;
	}

	/**
	 * Stops overriding.
	 *
	 * Called on closing the screen however it was closed, including by the world
	 * ending underneath it. A working copy left behind would keep a character
	 * looking edited for the rest of the session, and entity ids are handed out
	 * per world — so the next thing to hold that number would inherit somebody
	 * else's figure.
	 */
	public static void end() {
		who = -1;
		working = null;
	}
}
