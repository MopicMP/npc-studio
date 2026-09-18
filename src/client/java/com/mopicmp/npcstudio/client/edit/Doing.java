package com.mopicmp.npcstudio.client.edit;

import net.minecraft.network.chat.Component;

/**
 * Something that was done and can be taken back.
 *
 * <h2>Why an interface rather than a stack of positions</h2>
 *
 * Because the scope of what may be undone is going to widen, and the decision
 * was made knowing that. Today only things done <em>in the world</em> are
 * recorded — placing, turning, deleting — and panels and blocks are left alone.
 * The risk in that, said out loud when it was chosen, is that two different
 * rules for undoing in one window is the sort of thing that gets rebuilt later.
 *
 * So the shape here is the general one and only the entries are narrow. Widening
 * it means writing another {@link Doing}, not a second mechanism beside this one.
 *
 * <h2>Why undoing sends packets rather than setting fields</h2>
 *
 * Everything worth undoing happened on the server. Moving a character is a
 * {@code Place} payload and the server is what actually moved it; putting a
 * value back into a client field would produce a character that is in one place
 * here, another place there, and back where the server left it the moment
 * anything resyncs. So an entry remembers what to <em>send</em>, and undoing is
 * the same road the doing took.
 */
public interface Doing {

	/**
	 * Puts it back, or says why it cannot.
	 *
	 * @return null when it was taken back, otherwise what stopped it — said to the
	 *     person who pressed the key. A refusal is not a failure: a character
	 *     somebody else has moved since, or one that has been removed, are both
	 *     ordinary, and both are worse to do silently than to explain.
	 */
	Component undo();

	/** Does it again. Same contract, same reasons. */
	Component redo();

	/** What this was, for the line that says what just got taken back. */
	Component what();
}
