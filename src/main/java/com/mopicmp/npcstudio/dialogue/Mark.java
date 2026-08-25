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

	/**
	 * The nearest other character running the same graph as she is, in sight.
	 *
	 * <h2>Why "the same graph" and not "the same side"</h2>
	 *
	 * Because sides do not exist yet and inventing them here would be inventing
	 * them badly. What does exist is the graph, and two characters running the
	 * same one are the same sort of character by construction - which is enough
	 * to find each other, and says nothing about whether they are friends.
	 *
	 * Which they are is the graph's to decide, and it can decide either. A
	 * patrol graph treats kin as somebody to keep formation with; the duel graph
	 * treats kin as somebody to hit. Nothing here takes a view.
	 *
	 * This is not perception. She has not noticed anybody in the sense that
	 * {@link #LEAD} means - there is no alarm, no gradual working-out, no being
	 * fooled. It is a direct question about who else is about, and it is honest
	 * about that by being a different name.
	 */
	public static final String KIN = "kin";

	/**
	 * Whoever this segment was told to act on.
	 *
	 * The one mark that means something different in every call, which is the
	 * whole point of it: "how to fight" written against the target fights whoever
	 * the caller named, and works unchanged for a duel, a brawl and a guard.
	 *
	 * Outside a segment it names nothing.
	 */
	public static final String TARGET = "target";

	/** Nothing: releases a gaze or an aim rather than pointing it somewhere. */
	public static final String NOTHING = "nothing";

	public static final List<String> KNOWN =
		List.of(LEAD, PLAYER, KIN, POST, TARGET, NOTHING);

	public static boolean known(String name) {
		return KNOWN.contains(name);
	}
}
