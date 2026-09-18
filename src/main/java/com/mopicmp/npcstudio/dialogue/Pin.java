package com.mopicmp.npcstudio.dialogue;

/**
 * Where a node sits on the canvas.
 *
 * <h2>Why this is in the document at all</h2>
 *
 * It was not, and the report is what a missing field always looks like: the boxes
 * had been dragged into a shape, and after leaving and coming back they were in one
 * straight line. Nothing had gone wrong — nothing had ever been kept. The editor
 * laid the graph out afresh on every opening, in columns by distance from the
 * start, and a graph that is a chain is one column.
 *
 * That is the right thing to do for a document nobody has arranged, and exactly the
 * wrong thing to do to one somebody has. So the arrangement is a fact about the
 * document now, and the absence of it goes on meaning "lay it out for me" — which
 * is what every graph written before this, and every graph written by hand in a
 * datapack, is going to say for ever.
 *
 * <h2>Why in the document rather than beside it</h2>
 *
 * Because the shape somebody dragged their graph into is part of what they wrote.
 * A map handed to somebody else, or opened on another machine, should look like the
 * thing its author built and not like a column — and a layout kept in a file beside
 * the document is a layout that does not travel with it and quietly goes stale.
 *
 * <h2>Why it is not allowed to matter</h2>
 *
 * Nothing outside the editor may read this. A conversation must play identically
 * whether or not anybody ever opened it in a window, so a pin can be missing, can
 * name a node that no longer exists, and can hold any two numbers at all — none of
 * which is an error and none of which the runtime is ever told about.
 */
public record Pin(int x, int y) {

	/**
	 * As far from the middle as a node may be put.
	 *
	 * A bound because this arrives from a file, and a node at two billion is a node
	 * nobody can scroll to and a graph that looks empty for a reason its author
	 * cannot see. Generous enough that nobody laying out a real conversation will
	 * ever meet it: a canvas this wide holds some thousands of boxes across.
	 */
	public static final int FURTHEST = 100_000;

	public Pin {
		x = Math.clamp(x, -FURTHEST, FURTHEST);
		y = Math.clamp(y, -FURTHEST, FURTHEST);
	}
}
