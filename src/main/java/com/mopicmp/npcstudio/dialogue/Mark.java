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

	/**
	 * Whatever this rule fired about: the block somebody just used.
	 *
	 * <h2>Why this had to exist before any rule could be written</h2>
	 *
	 * A rule fires because somebody clicked a block, and that block has no name. Every
	 * other way of naming a place in this language is a name somebody wrote down in
	 * advance — a marked point, her post, the player. None of them can say "the thing
	 * that was just touched", and without it the plainest rule there is cannot be
	 * written at all: if what they clicked is alight, put it out.
	 *
	 * The gap was invisible until the plan was checked against the one example it was
	 * built for. It is worth remembering how it hid: every piece of the mechanism was
	 * designed and costed, and the sentence they were for could not be spelled.
	 *
	 * Outside a rule it names nothing, the same as {@link #TARGET} outside a segment.
	 */
	public static final String IT = "it";

	public static final List<String> KNOWN =
		List.of(LEAD, PLAYER, KIN, POST, TARGET, IT, NOTHING);

	// -------------------------------------------------------- places in a world

	/**
	 * What a named point in a world is written as: {@code at:} and then its name.
	 *
	 * <h2>Why a prefix rather than simply allowing any word</h2>
	 *
	 * Because the vocabulary above is closed on purpose, and its being closed is
	 * what catches {@code palyer} before a character spends an evening staring at
	 * nothing. If any unrecognised word were read as a place name, every typo in
	 * every relative mark would become a place that happens not to exist yet, and
	 * the validator would have nothing left to say.
	 *
	 * So a place is a different <em>form</em>, not merely a different word. A graph
	 * saying {@code at:ворота} is unmistakably naming somewhere on this map; a graph
	 * saying {@code laed} is still a mistake, and still says so.
	 *
	 * <h2>Why the validator cannot check the name</h2>
	 *
	 * It can check the form and nothing further. Which points exist is a fact about
	 * a saved world, and this class is on the side of the fence that must not touch
	 * Minecraft — the same fence {@link Sense} sits on. Whether {@code at:ворота} is
	 * anywhere is answered where the world is, by {@code brain.Marks}, and answering
	 * it with nothing is not an error: a graph acting on a point that has been taken
	 * away behaves exactly as one acting on a lead that is not there.
	 */
	public static final String AT = "at:";

	/** How a point's name is written for a graph to use. */
	public static String at(String name) {
		return AT + name;
	}

	/** The point a mark names, or null when it names one of the relative ones. */
	public static String place(String mark) {
		if (mark == null || !mark.startsWith(AT)) return null;
		String name = mark.substring(AT.length());
		return name.isEmpty() ? null : name;
	}

	public static boolean known(String name) {
		return KNOWN.contains(name) || place(name) != null;
	}

	/**
	 * The place names among a set of marks, in the order they came.
	 *
	 * Separated out because two questions are asked of it from opposite ends and
	 * both are about a map being built. Taking a place away asks "which graphs were
	 * pointing here"; saving a graph asks "which of the places this points at does
	 * the world not have". The second is the one that catches a typo, which is the
	 * commonest way to end up with a character who simply does not walk.
	 *
	 * Order is kept because the answer is read by a person: a list that shuffles
	 * between two identical questions reads as two different answers.
	 */
	public static List<String> placesIn(java.util.Collection<String> marks) {
		List<String> found = new java.util.ArrayList<>();
		for (String mark : marks) {
			String place = place(mark);
			if (place != null && !found.contains(place)) found.add(place);
		}
		return List.copyOf(found);
	}
}
