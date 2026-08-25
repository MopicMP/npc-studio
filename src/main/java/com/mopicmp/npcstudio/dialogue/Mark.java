package com.mopicmp.npcstudio.dialogue;

import java.util.List;

/**
 * The things a graph can point a character at.
 *
 * <h2>Why a name and not a place</h2>
 *
 * Because a graph that says "walk to 214, 71, -88" is a graph that only works
 * in the place it was written, and every character copied from it has to be
 * edited. What an author means is almost never a coordinate — it is "go and see
 * what that was", "face whoever is talking to me" — and those survive being
 * copied to another map, another scene, another character.
 *
 * It is also the same trick that worked for {@link Sense}, for the same reason:
 * adding one is a line in a table rather than a new type in the language. The
 * alternative was a value that could hold a position and a creature, which
 * would have meant teaching comparison, the editor and the file format about
 * two more kinds of thing to get one verb working.
 *
 * <h2>When coordinates arrive</h2>
 *
 * When somebody needs them. A named point placed in a scene — "the gate", "the
 * third window" — is the shape that will be wanted then, and it is still a name.
 */
public final class Mark {

	private Mark() { }

	/**
	 * Whatever has her attention: what she saw, or where she thinks a noise came
	 * from. Nothing at all when she is calm, which is not an error — a graph that
	 * acts on a lead should be waiting for one first.
	 */
	public static final String LEAD = "lead";

	/** The nearest living player. */
	public static final String PLAYER = "player";

	/** Where she was standing when this was last set, so she can go back. */
	public static final String POST = "post";

	/** Nothing: releases a gaze or an aim rather than pointing it somewhere. */
	public static final String NOTHING = "nothing";

	public static final List<String> KNOWN = List.of(LEAD, PLAYER, POST, NOTHING);

	public static boolean known(String name) {
		return KNOWN.contains(name);
	}
}
