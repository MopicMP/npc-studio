package com.mopicmp.npcstudio.client.workspace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * What the cursor is over.
 *
 * <h2>Why this is a thing rather than a chain of ifs</h2>
 *
 * Because the menu under the cursor is about the subject, not about the mod, and
 * that is the whole discipline holding it to eight entries. A ring built from
 * "what can this program do" grows for ever; one built from "what is this thing"
 * is bounded by what the thing is.
 *
 * So the question is answered once, in one place, and the answer is a value that
 * can be looked at. Two different pieces of code deciding separately what the
 * cursor is over is how a menu ends up offering to turn a bone that the click
 * actually landed beside.
 *
 * <h2>The order is the same one clicking already uses</h2>
 *
 * A limb of the selected character wins over the character, the character wins
 * over the ground behind it. That order is not new here — it is what the left
 * button has always done — and matching it is the point: the menu has to be
 * about the thing that a click would have taken, or the two disagree about what
 * you are pointing at.
 */
public record Under(Kind kind, Entity who, String limb, BlockPos block, Vec3 at) {

	public enum Kind {
		/** The sky, or nothing near enough to matter. */
		NOTHING,
		/** Somebody. */
		CHARACTER,
		/** One limb of the character already selected. */
		BONE,
		/** The scene's camera, which is an entity but is not a character. */
		CAMERA,
		/** A placed model — a ship, a building. */
		OBJECT,
		/** A block, and the exact point on it. */
		GROUND
	}

	public static final Under NOTHING = new Under(Kind.NOTHING, null, "", null, null);

	public static Under character(Entity who) {
		return new Under(Kind.CHARACTER, who, "", null, null);
	}

	public static Under bone(Entity who, String limb) {
		return new Under(Kind.BONE, who, limb, null, null);
	}

	public static Under camera(Entity who) {
		return new Under(Kind.CAMERA, who, "", null, null);
	}

	public static Under object(Entity who) {
		return new Under(Kind.OBJECT, who, "", null, null);
	}

	public static Under ground(BlockPos block, Vec3 at) {
		return new Under(Kind.GROUND, null, "", block, at);
	}

	public boolean nothing() {
		return kind == Kind.NOTHING;
	}

	/** Whether this is an entity of some sort, whichever sort. */
	public boolean anybody() {
		return who != null;
	}
}
