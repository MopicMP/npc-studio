package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * The few abilities the game has no word for, which this mod writes itself.
 *
 * <h2>Why there are so few, and why that is the good news</h2>
 *
 * Forty properties the game already keeps: how high you jump, how far you reach, how
 * fast you mine, how hard you land, how big you are, how much gravity there is. All
 * applied on the server, all synchronised by the game, none needing a line of client
 * code. So the thing that sounded like a piece of work per ability turned out to be one
 * verb for nearly all of it.
 *
 * What is left is this list. Each one is real work — client-side, because jumping
 * happens on the client and the server only sees the result — and each one is written
 * once. Making the list <em>short</em> was the whole point of finding the forty first.
 *
 * <h2>Why they sit in the same table as the game's own</h2>
 *
 * Because an author has no business knowing which of these the game happens to keep and
 * which we wrote. "Jump twice" and "jump higher" are the same wish said two ways, and a
 * table that split them by how they are implemented would be a table organised around
 * our problems instead of theirs.
 *
 * They are told apart by namespace, which the file format already has: anything under
 * {@code minecraft:} is the game's, anything under ours is one of these.
 *
 * <h2>Why "how much" means nothing here</h2>
 *
 * An attribute takes a number. An ability is on or off — half a double jump is not a
 * thing — so the amount on a standing rule naming one is ignored, and the editor does
 * not ask for it.
 */
public final class Knack {

	private Knack() { }

	/**
	 * A second jump, in mid-air, once per time off the ground.
	 *
	 * The one everybody asks for and the one the game has no attribute for. Note what it
	 * is <em>not</em>: raising {@code jump_strength} makes one jump higher, and lowering
	 * {@code gravity} makes the fall slower — both free, both often what somebody
	 * actually wanted. This is the case where they wanted the second jump itself.
	 */
	public static final String DOUBLE_JUMP = "npc_studio:double_jump";

	public static final List<String> KNOWN = List.of(DOUBLE_JUMP);

	/** Whether a name is one of ours rather than one of the game's properties. */
	public static boolean is(String name) {
		return KNOWN.contains(name);
	}
}
