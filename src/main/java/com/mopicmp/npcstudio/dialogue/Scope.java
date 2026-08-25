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
	WORLD,

	/**
	 * CHARACTER is what one character knows about herself: how many rounds she has
	 * spent, whether she has already called for help, which door she came in by.
	 *
	 * It is what a brain needs and a conversation rarely does, which is why it was
	 * not here before. Without it a behaviour graph can decide but cannot
	 * remember, and a character who cannot remember is a character who calls for
	 * help every tick for as long as she is frightened.
	 */
	CHARACTER,

	/**
	 * SENSE is not stored anywhere and cannot be written: it is what she perceives
	 * right now, asked of the world at the moment the question comes up.
	 *
	 * <h2>Why a reading is a variable rather than a new kind of condition</h2>
	 *
	 * Because the language already knows how to compare a variable against a
	 * value, in six operators, with the types checked and the editor built. "Is
	 * she more than half alarmed" is that same sentence, and giving it its own
	 * node kind would have meant a second way to say one thing — and then a third
	 * when the next reading arrived.
	 *
	 * So the readings live in a scope of their own, and adding one costs a line
	 * in a table rather than a change to the language. The names are listed in
	 * {@code brain/Senses}, which is also what the validator checks against and
	 * what the editor will offer.
	 *
	 * Read-only is enforced rather than trusted: writing to a sense is refused by
	 * the validator before the graph runs and by the engine if it runs anyway.
	 * The alternative — letting a graph assign to {@code alarm} — produces a
	 * character whose fear is whatever was written last, which is not a feature
	 * anybody asked for and is impossible to debug.
	 */
	SENSE
}
