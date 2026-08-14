package com.mopicmp.npcstudio.dialogue;

/**
 * Who a variable belongs to.
 *
 * PLAYER is progress: this player has met the smith, paid the toll, chosen a
 * side. It travels with the player and is saved per player.
 *
 * WORLD is the state of the place: the gate is open, the festival has started.
 * One copy for everyone.
 *
 * The distinction has to be explicit in the data rather than guessed from the
 * name, because getting it wrong is silent. A quest flag stored in WORLD by
 * mistake works perfectly in single player and breaks the moment a second
 * player joins — the worst kind of bug to find, since it needs two people to
 * reproduce.
 */
public enum Scope {
	PLAYER,
	WORLD
}
