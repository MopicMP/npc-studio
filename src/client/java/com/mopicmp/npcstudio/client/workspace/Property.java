package com.mopicmp.npcstudio.client.workspace;

/**
 * What kind of thing a panel edits, which is what decides where an edit can go.
 *
 * <h2>The question this settles</h2>
 *
 * Sixteen panels had quietly sorted themselves into "about a scene" and "not
 * about a scene", and the sorting was wrong. The lighting panel is not about a
 * scene at all — the sky exists whether or not anybody is filming, and it was
 * only put inside the scene editor because that was the only room with a door.
 * Then it grew a rule that made it useless everywhere else: no scene open, no
 * panel, only its six headings.
 *
 * So panels stop being sorted by which editor they were born in and start being
 * sorted by <em>what sort of thing they change</em>. That is a property of the
 * thing, not of the interface, and it is the same answer in every window.
 *
 * <h2>Why these four and not others</h2>
 *
 * They are the four different answers to "what does it mean to change this".
 * Two things in the same class behave the same in every receiver, and no two
 * classes behave the same in all of them — which is the test a classification
 * has to pass to be worth having rather than being a filing cabinet.
 */
public enum Property {

	/**
	 * Something the world is, at all times: the hour, the rain, the colour of the
	 * sky, whether a shader pack is on.
	 *
	 * Has a value even with nothing open and nobody filming, which is the whole
	 * point — "what is the sky doing" is answerable on a Tuesday afternoon in an
	 * empty world.
	 */
	WORLD,

	/**
	 * Something a character is: its skin, its costume, its build, its name.
	 *
	 * Belongs to the NPC rather than to a moment. A costume is not a keyframe: a
	 * character does not fade from one coat into another, it is wearing one or the
	 * other. So this never becomes a curve, in any receiver, ever.
	 */
	CHARACTER,

	/**
	 * A number that varies over time: a bone's angle, a camera's position, the
	 * field of view, how dark the picture is.
	 *
	 * Only means anything where there is a clock. Outside a scene there is no
	 * clock, so a curve edited there can be applied and looked at but not
	 * recorded — which is a real and useful state, and quite different from being
	 * refused.
	 */
	CURVE,

	/**
	 * A thing that happens at a moment: a sound starts, a caption appears, a
	 * structure is placed, a shader is switched on.
	 *
	 * The difference from {@link #CURVE} is not size, it is whether halfway
	 * exists. A shader is on or off and there is no half-on to interpolate
	 * towards, so it can never be a track however much one might want to fade it.
	 */
	MOMENT
}
